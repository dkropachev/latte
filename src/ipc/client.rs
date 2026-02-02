//! IPC client for communicating with the driver counterpart over Unix sockets.

use super::protocol::{self, Frame, Opcode};
use super::types::{QueryResult, SessionConfig, SessionId};
use anyhow::{anyhow, bail, Result};
use bytes::{Buf, BufMut, BytesMut};
use dashmap::DashMap;
use futures::FutureExt;
use scylla::frame::types::Consistency;
use scylla::value::CqlValue;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI16, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio::net::unix::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::UnixStream;
use tokio::sync::{mpsc, oneshot};
use tracing::error;

/// Pending request waiting for a response
struct PendingRequest {
    response_tx: oneshot::Sender<Frame>,
}

/// Message sent to the writer task
struct WriteRequest {
    data: BytesMut,
}

/// IPC client for communicating with the driver counterpart.
///
/// This client uses multiplexed request/response handling:
/// - Requests are sent via a channel to a background writer task
/// - A background reader task reads responses and routes them by stream ID
/// - Multiple requests can be in flight simultaneously
/// - Writes are batched for better throughput
/// - Uses lock-free DashMap for pending request tracking
#[derive(Clone)]
pub struct IpcClient {
    /// Channel for sending requests to the writer task
    write_tx: mpsc::Sender<WriteRequest>,
    /// Pending requests awaiting responses, keyed by stream ID (lock-free)
    pending: Arc<DashMap<i16, PendingRequest>>,
    /// Next stream ID to use
    next_stream: Arc<AtomicI16>,
    /// Kept to detect when background tasks die
    _tasks_alive: Arc<()>,
}

impl IpcClient {
    /// Create an IPC client from an already-established Unix stream.
    pub fn from_stream(stream: UnixStream) -> Self {
        let (reader, writer) = stream.into_split();
        let pending: Arc<DashMap<i16, PendingRequest>> = Arc::new(DashMap::with_capacity(256));
        let tasks_alive = Arc::new(());

        // Channel for write requests - sized to allow concurrent senders without blocking
        // 1024 provides good buffering without excessive memory use
        let (write_tx, write_rx) = mpsc::channel::<WriteRequest>(1024);

        // Spawn background writer task
        let tasks_alive_writer = Arc::clone(&tasks_alive);
        tokio::spawn(async move {
            let _keep_alive = tasks_alive_writer;
            if let Err(e) = std::panic::AssertUnwindSafe(writer_task(writer, write_rx))
                .catch_unwind()
                .await
            {
                error!("IPC writer task panicked: {:?}", e);
            }
        });

        // Spawn background reader task
        let pending_clone = Arc::clone(&pending);
        let tasks_alive_reader = Arc::clone(&tasks_alive);
        tokio::spawn(async move {
            let _keep_alive = tasks_alive_reader;
            if let Err(e) = std::panic::AssertUnwindSafe(reader_task(reader, pending_clone))
                .catch_unwind()
                .await
            {
                error!("IPC reader task panicked: {:?}", e);
            }
        });

        Self {
            write_tx,
            pending,
            next_stream: Arc::new(AtomicI16::new(0)),
            _tasks_alive: tasks_alive,
        }
    }

    /// Create a new session with the driver counterpart.
    pub async fn create_session(&self, config: SessionConfig) -> Result<SessionId> {
        let params = config.into_params();
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_string_map(&params)?;
        let request = protocol::encode_request(Opcode::CreateSession, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;

        if response.header.opcode == Opcode::Error {
            let msg = protocol::decode_error_message(&response.body);
            bail!("failed to create session: {}", msg);
        }

        if response.header.opcode != Opcode::SessionCreated {
            bail!(
                "expected SessionCreated opcode, got {:?}",
                response.header.opcode
            );
        }

        if response.body.len() < 8 {
            bail!(
                "unexpected SessionCreated body length: {}",
                response.body.len()
            );
        }

        let mut slice = response.body.clone();
        let session_id = slice.get_u64();
        Ok(session_id)
    }

    /// Execute a simple query on the given session.
    /// Returns the query result and the driver-side latency.
    pub async fn query(
        &self,
        session_id: SessionId,
        query: &str,
        consistency: Consistency,
    ) -> Result<(QueryResult, Option<Duration>)> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_query_body(session_id, query, consistency);
        let request = protocol::encode_request(Opcode::Query, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_result(response)
    }

    /// Prepare a statement on the given session.
    pub async fn prepare(&self, session_id: SessionId, key: &str, query: &str) -> Result<()> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_prepare_body(session_id, query, key);
        let request = protocol::encode_request(Opcode::Prepare, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;

        if response.header.opcode == Opcode::Error {
            let msg = protocol::decode_error_message(&response.body);
            bail!("prepare failed: {}", msg);
        }

        // We don't need to decode the full PREPARED result; the driver caches it
        Ok(())
    }

    /// Execute a prepared statement on the given session.
    /// Returns the query result and the driver-side latency.
    pub async fn execute(
        &self,
        session_id: SessionId,
        key: &str,
        values: &[CqlValue],
        consistency: Consistency,
    ) -> Result<(QueryResult, Option<Duration>)> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_execute_body(session_id, key, consistency, values)?;
        let request = protocol::encode_request(Opcode::Execute, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_result(response)
    }

    /// Execute a batch of prepared statements on the given session.
    ///
    /// Each element of `statements` is a tuple of (statement_key, values).
    /// Returns the driver-side latency.
    pub async fn batch(
        &self,
        session_id: SessionId,
        statements: &[(&str, &[CqlValue])],
        consistency: Consistency,
    ) -> Result<Option<Duration>> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_batch_body(session_id, statements, consistency)?;
        let request = protocol::encode_request(Opcode::Batch, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;

        if response.header.opcode == Opcode::Error {
            let msg = protocol::decode_error_message(&response.body);
            bail!("batch failed: {}", msg);
        }

        // Extract driver latency from the last 8 bytes of the body (if present)
        let driver_latency = if response.body.len() >= 12 {
            // At minimum we need 4 bytes for VOID kind + 8 bytes for latency
            let body_len = response.body.len();
            let latency_bytes = &response.body[body_len - 8..];
            // unwrap is safe: we just sliced exactly 8 bytes above
            let latency_ns = u64::from_be_bytes(latency_bytes.try_into().unwrap());
            // DEBUG: Log received batch latency (guarded to avoid division when disabled)
            if tracing::enabled!(tracing::Level::DEBUG) {
                tracing::debug!(
                    body_len = body_len,
                    latency_ns = latency_ns,
                    latency_ms = latency_ns as f64 / 1_000_000.0,
                    "batch: extracted driver latency"
                );
            }
            Some(Duration::from_nanos(latency_ns))
        } else {
            None
        };

        Ok(driver_latency)
    }

    async fn send_request(&self, stream_id: i16, request: BytesMut) -> Result<Frame> {
        // Create response channel
        let (response_tx, response_rx) = oneshot::channel();

        // Register pending request before sending (lock-free insert)
        self.pending
            .insert(stream_id, PendingRequest { response_tx });

        // Send request to writer task via channel - this is non-blocking
        self.write_tx
            .send(WriteRequest { data: request })
            .await
            .map_err(|_| anyhow!("writer task closed"))?;

        // Wait for response
        response_rx
            .await
            .map_err(|_| anyhow!("reader task closed before response received"))
    }

    fn decode_result(&self, frame: Frame) -> Result<(QueryResult, Option<Duration>)> {
        if frame.header.opcode == Opcode::Error {
            let msg = protocol::decode_error_message(&frame.body);
            bail!("query failed: {}", msg);
        }

        if frame.header.opcode != Opcode::Result {
            bail!("expected Result opcode, got {:?}", frame.header.opcode);
        }

        if frame.body.len() < 4 {
            bail!("result body too short");
        }

        // Extract driver latency from the last 8 bytes of the body (if present)
        let (result_body, driver_latency) = if frame.body.len() >= 12 {
            // At minimum we need 4 bytes for kind + 8 bytes for latency
            let body_len = frame.body.len();
            let latency_bytes = &frame.body[body_len - 8..];
            // unwrap is safe: we just sliced exactly 8 bytes above
            let latency_ns = u64::from_be_bytes(latency_bytes.try_into().unwrap());
            // DEBUG: Log received latency (guarded to avoid division when disabled)
            if tracing::enabled!(tracing::Level::DEBUG) {
                tracing::debug!(
                    body_len = body_len,
                    latency_ns = latency_ns,
                    latency_ms = latency_ns as f64 / 1_000_000.0,
                    "decode_result: extracted driver latency"
                );
            }
            (
                &frame.body[..body_len - 8],
                Some(Duration::from_nanos(latency_ns)),
            )
        } else {
            (&frame.body[..], None)
        };

        let mut slice = result_body;
        let kind = read_int(&mut slice)?;

        let result = match kind {
            0x0001 => QueryResult::Void,               // VOID
            0x0002 => decode_rows_result(&mut slice)?, // ROWS
            0x0003 => {
                // SET_KEYSPACE
                let _ks = read_string(&mut slice)?;
                QueryResult::Void
            }
            0x0004 => {
                // PREPARED - treat as void for our purposes
                QueryResult::Void
            }
            0x0005 => {
                // SCHEMA_CHANGE
                let change_type = read_string(&mut slice)?;
                QueryResult::SchemaChange { change_type }
            }
            _ => bail!("unknown result kind: {:#x}", kind),
        };

        Ok((result, driver_latency))
    }
}

/// Background task that batches and writes requests to the socket.
///
/// This task:
/// - Receives requests from the channel
/// - Writes them to a buffered writer
/// - Flushes when buffer is large (4KB) or after max-latency timeout (50us)
async fn writer_task(writer: OwnedWriteHalf, mut write_rx: mpsc::Receiver<WriteRequest>) {
    use tokio::time::{timeout, Duration, Instant};

    let mut writer = BufWriter::with_capacity(16 * 1024, writer);
    let flush_timeout = Duration::from_micros(50);

    loop {
        // Wait for the first request
        let Some(req) = write_rx.recv().await else {
            break;
        };

        if let Err(err) = writer.write_all(&req.data).await {
            tracing::warn!(?err, "writer task: failed to write request");
            break;
        }

        // Batch more requests with a max-latency timeout
        let deadline = Instant::now() + flush_timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                // Timeout reached - flush to bound latency
                if let Err(err) = writer.flush().await {
                    tracing::warn!(?err, "writer task: failed to flush");
                    return;
                }
                break;
            }
            match timeout(remaining, write_rx.recv()).await {
                Ok(Some(req)) => {
                    if let Err(err) = writer.write_all(&req.data).await {
                        tracing::warn!(?err, "writer task: failed to write request");
                        return;
                    }
                    // Flush if buffer is getting large
                    if writer.buffer().len() >= 4 * 1024 {
                        if let Err(err) = writer.flush().await {
                            tracing::warn!(?err, "writer task: failed to flush");
                            return;
                        }
                        break; // Reset timeout after flush
                    }
                }
                Ok(None) => {
                    // Channel closed, flush and exit
                    let _ = writer.flush().await;
                    return;
                }
                Err(_) => {
                    // Timeout - flush to bound latency
                    if let Err(err) = writer.flush().await {
                        tracing::warn!(?err, "writer task: failed to flush");
                        return;
                    }
                    break;
                }
            }
        }
    }

    // Final flush
    let _ = writer.flush().await;
}

/// Background task that reads responses and routes them to waiting callers
async fn reader_task(mut reader: OwnedReadHalf, pending: Arc<DashMap<i16, PendingRequest>>) {
    let mut buffer = BytesMut::with_capacity(16 * 1024);

    loop {
        let frame = match protocol::read_frame(&mut reader, &mut buffer).await {
            Ok(Some(frame)) => frame,
            Ok(None) => {
                // Clean EOF
                break;
            }
            Err(err) => {
                tracing::warn!(?err, "reader task: failed to read frame");
                break;
            }
        };

        let stream_id = frame.header.stream;

        // Find and remove pending request (lock-free remove)
        if let Some((_, req)) = pending.remove(&stream_id) {
            // Send response to waiting caller (ignore if receiver dropped)
            let _ = req.response_tx.send(frame);
        } else {
            tracing::warn!(stream_id, "received response for unknown stream");
        }
    }

    // Clean up any remaining pending requests
    pending.clear();
}

fn decode_rows_result(slice: &mut &[u8]) -> Result<QueryResult> {
    let flags = read_int(slice)?;
    let columns_count = read_int(slice)? as usize;

    // Skip paging state if present
    let _has_more_pages = (flags & 0x02) != 0;
    // Skip no_metadata flag handling for now

    let mut columns = Vec::with_capacity(columns_count);
    for _ in 0..columns_count {
        let ks = read_string(slice)?;
        let table = read_string(slice)?;
        let name = read_string(slice)?;
        let type_code = read_short(slice)?;
        columns.push(super::types::ColumnInfo {
            keyspace: ks,
            table,
            name,
            type_code,
        });
    }

    let row_count = read_int(slice)? as usize;
    let mut rows = Vec::with_capacity(row_count);

    for _ in 0..row_count {
        let mut row = Vec::with_capacity(columns_count);
        for col in &columns {
            let value = decode_column_value(slice, col.type_code)?;
            row.push(value);
        }
        rows.push(row);
    }

    Ok(QueryResult::Rows { columns, rows })
}

fn decode_column_value(slice: &mut &[u8], type_code: u16) -> Result<Option<CqlValue>> {
    let len = read_int(slice)?;
    if len < 0 {
        return Ok(None);
    }
    let len = len as usize;
    if slice.len() < len {
        bail!("unexpected EOF reading column value");
    }
    let (data, rest) = slice.split_at(len);
    *slice = rest;

    let value = match type_code {
        0x0001 => {
            // ascii - use from_utf8_lossy directly on slice to avoid intermediate Vec allocation
            CqlValue::Ascii(String::from_utf8_lossy(data).into_owned())
        }
        0x0002 => {
            // bigint
            if data.len() != 8 {
                bail!("invalid bigint length");
            }
            CqlValue::BigInt(i64::from_be_bytes(data.try_into().unwrap()))
        }
        0x0003 => {
            // blob
            CqlValue::Blob(data.to_vec())
        }
        0x0004 => {
            // boolean
            CqlValue::Boolean(data.first().copied().unwrap_or(0) != 0)
        }
        0x0005 => {
            // counter
            if data.len() != 8 {
                bail!("invalid counter length");
            }
            CqlValue::Counter(scylla::value::Counter(i64::from_be_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x0006 => {
            // decimal - 4-byte scale + varint mantissa
            if data.len() < 4 {
                bail!("invalid decimal length");
            }
            let scale = i32::from_be_bytes(data[0..4].try_into().unwrap());
            let mantissa_bytes = &data[4..];
            CqlValue::Decimal(
                scylla::value::CqlDecimal::from_signed_be_bytes_slice_and_exponent(
                    mantissa_bytes,
                    scale,
                ),
            )
        }
        0x0007 => {
            // double
            if data.len() != 8 {
                bail!("invalid double length");
            }
            CqlValue::Double(f64::from_be_bytes(data.try_into().unwrap()))
        }
        0x0008 => {
            // float
            if data.len() != 4 {
                bail!("invalid float length");
            }
            CqlValue::Float(f32::from_be_bytes(data.try_into().unwrap()))
        }
        0x0009 => {
            // int
            if data.len() != 4 {
                bail!("invalid int length");
            }
            CqlValue::Int(i32::from_be_bytes(data.try_into().unwrap()))
        }
        0x000B => {
            // timestamp
            if data.len() != 8 {
                bail!("invalid timestamp length");
            }
            CqlValue::Timestamp(scylla::value::CqlTimestamp(i64::from_be_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x000C => {
            // uuid
            if data.len() != 16 {
                bail!("invalid uuid length");
            }
            CqlValue::Uuid(uuid::Uuid::from_bytes(data.try_into().unwrap()))
        }
        0x000D => {
            // text/varchar - use from_utf8_lossy directly on slice to avoid intermediate Vec allocation
            CqlValue::Text(String::from_utf8_lossy(data).into_owned())
        }
        0x000E => {
            // varint - variable-length signed integer
            CqlValue::Varint(scylla::value::CqlVarint::from_signed_bytes_be_slice(data))
        }
        0x000F => {
            // timeuuid
            if data.len() != 16 {
                bail!("invalid timeuuid length");
            }
            CqlValue::Timeuuid(scylla::value::CqlTimeuuid::from_bytes(
                data.try_into().unwrap(),
            ))
        }
        0x0010 => {
            // inet
            match data.len() {
                4 => {
                    let bytes: [u8; 4] = data.try_into().unwrap();
                    CqlValue::Inet(std::net::IpAddr::V4(std::net::Ipv4Addr::from(bytes)))
                }
                16 => {
                    let bytes: [u8; 16] = data.try_into().unwrap();
                    CqlValue::Inet(std::net::IpAddr::V6(std::net::Ipv6Addr::from(bytes)))
                }
                _ => bail!("invalid inet length: {}", data.len()),
            }
        }
        0x0011 => {
            // date - 4-byte unsigned (days since epoch)
            if data.len() != 4 {
                bail!("invalid date length");
            }
            CqlValue::Date(scylla::value::CqlDate(u32::from_be_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x0012 => {
            // time - 8-byte signed (nanoseconds since midnight)
            if data.len() != 8 {
                bail!("invalid time length");
            }
            CqlValue::Time(scylla::value::CqlTime(i64::from_be_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x0013 => {
            // smallint
            if data.len() != 2 {
                bail!("invalid smallint length");
            }
            CqlValue::SmallInt(i16::from_be_bytes(data.try_into().unwrap()))
        }
        0x0014 => {
            // tinyint
            if data.len() != 1 {
                bail!("invalid tinyint length");
            }
            CqlValue::TinyInt(data[0] as i8)
        }
        0x0015 => {
            // duration - 3 varints: months, days, nanoseconds
            let mut cursor = data;
            let months = decode_vint(&mut cursor)?;
            let days = decode_vint(&mut cursor)?;
            let nanos = decode_vint(&mut cursor)?;
            CqlValue::Duration(scylla::value::CqlDuration {
                months: months as i32,
                days: days as i32,
                nanoseconds: nanos,
            })
        }
        0x0020 => {
            // list - format: [int] n_elements, ([int] length, [bytes] data)*
            let mut cursor: &[u8] = data;
            let n_elements = read_int(&mut cursor)? as usize;
            let mut elements = Vec::with_capacity(n_elements);
            for _ in 0..n_elements {
                let len = read_int(&mut cursor)?;
                if len < 0 {
                    // null element - skip
                    continue;
                }
                let len = len as usize;
                if cursor.len() < len {
                    bail!("unexpected EOF reading list element");
                }
                let (elem_data, rest) = cursor.split_at(len);
                cursor = rest;
                // Decode as blob since we don't have element type info
                elements.push(CqlValue::Blob(elem_data.to_vec()));
            }
            CqlValue::List(elements)
        }
        0x0021 => {
            // map - format: [int] n_entries, ([int] key_len, [bytes] key, [int] val_len, [bytes] val)*
            let mut cursor: &[u8] = data;
            let n_entries = read_int(&mut cursor)? as usize;
            let mut entries = Vec::with_capacity(n_entries);
            for _ in 0..n_entries {
                let key_len = read_int(&mut cursor)?;
                if key_len < 0 {
                    // null key - skip entire entry
                    let _ = read_int(&mut cursor)?; // skip value length
                    continue;
                }
                let key_len = key_len as usize;
                if cursor.len() < key_len {
                    bail!("unexpected EOF reading map key");
                }
                let (key_data, rest) = cursor.split_at(key_len);
                cursor = rest;
                let key = CqlValue::Blob(key_data.to_vec());

                let val_len = read_int(&mut cursor)?;
                if val_len < 0 {
                    // null value - still include entry with null value
                    continue;
                }
                let val_len = val_len as usize;
                if cursor.len() < val_len {
                    bail!("unexpected EOF reading map value");
                }
                let (val_data, rest) = cursor.split_at(val_len);
                cursor = rest;
                let val = CqlValue::Blob(val_data.to_vec());

                entries.push((key, val));
            }
            CqlValue::Map(entries)
        }
        0x0022 => {
            // set - format: [int] n_elements, ([int] length, [bytes] data)*
            let mut cursor: &[u8] = data;
            let n_elements = read_int(&mut cursor)? as usize;
            let mut elements = Vec::with_capacity(n_elements);
            for _ in 0..n_elements {
                let len = read_int(&mut cursor)?;
                if len < 0 {
                    continue; // skip null elements in sets
                }
                let len = len as usize;
                if cursor.len() < len {
                    bail!("unexpected EOF reading set element");
                }
                let (elem_data, rest) = cursor.split_at(len);
                cursor = rest;
                // Decode as blob since we don't have element type info
                elements.push(CqlValue::Blob(elem_data.to_vec()));
            }
            CqlValue::Set(elements)
        }
        0x0030 => {
            // vector<float, N> - data is N contiguous big-endian floats
            // The dimension is inferred from data length: len / 4 = dimension
            if data.len() % 4 != 0 {
                bail!(
                    "invalid vector data length: {} (not a multiple of 4)",
                    data.len()
                );
            }
            let dimension = data.len() / 4;
            let mut elements = Vec::with_capacity(dimension);
            for i in 0..dimension {
                let offset = i * 4;
                let f = f32::from_be_bytes(data[offset..offset + 4].try_into().unwrap());
                elements.push(CqlValue::Float(f));
            }
            CqlValue::Vector(elements)
        }
        _ => CqlValue::Blob(data.to_vec()),
    };

    Ok(Some(value))
}

/// Decode a value from raw bytes (without length prefix)
#[allow(dead_code)]
fn decode_column_value_from_bytes(data: &[u8], type_code: u16) -> Result<Option<CqlValue>> {
    if data.is_empty() {
        return Ok(None);
    }
    // Reuse the main decoder logic by wrapping data with a fake length prefix
    let mut fake_slice = Vec::with_capacity(4 + data.len());
    fake_slice.extend_from_slice(&(data.len() as i32).to_be_bytes());
    fake_slice.extend_from_slice(data);
    let mut cursor: &[u8] = &fake_slice;
    decode_column_value(&mut cursor, type_code)
}

/// Decode a variable-length signed integer (zigzag encoded)
fn decode_vint(slice: &mut &[u8]) -> Result<i64> {
    if slice.is_empty() {
        bail!("unexpected EOF reading vint");
    }

    let mut result: u64 = 0;
    let mut shift = 0;

    loop {
        if slice.is_empty() {
            bail!("unexpected EOF reading vint");
        }
        let byte = slice[0];
        *slice = &slice[1..];

        result |= ((byte & 0x7F) as u64) << shift;

        if byte & 0x80 == 0 {
            break;
        }
        shift += 7;

        if shift > 63 {
            bail!("vint overflow");
        }
    }

    // Zigzag decode
    Ok(((result >> 1) as i64) ^ -((result & 1) as i64))
}

fn encode_string_map(params: &HashMap<String, String>) -> Result<BytesMut> {
    // Pre-allocate: 2 (count) + sum of (2 + key.len() + 2 + value.len()) for each entry
    let estimated_size: usize = 2 + params
        .iter()
        .map(|(k, v)| 4 + k.len() + v.len())
        .sum::<usize>();
    let mut body = BytesMut::with_capacity(estimated_size);
    let count: u16 = params
        .len()
        .try_into()
        .map_err(|err| anyhow!("too many map entries: {err}"))?;
    body.put_u16(count);
    for (key, value) in params {
        write_string(key, &mut body)?;
        write_string(value, &mut body)?;
    }
    Ok(body)
}

fn encode_query_body(session_id: SessionId, query: &str, consistency: Consistency) -> BytesMut {
    // Pre-allocate: 8 (session_id) + 4 (query len) + query + 2 (consistency) + 1 (flags)
    let mut body = BytesMut::with_capacity(15 + query.len());
    body.put_u64(session_id);
    write_long_string(query, &mut body);
    body.put_u16(consistency as u16);
    body.put_u8(0); // flags
    body
}

fn encode_prepare_body(session_id: SessionId, query: &str, key: &str) -> BytesMut {
    // Pre-allocate: 8 (session_id) + 4 (query len) + query + 2 (key len) + key
    let mut body = BytesMut::with_capacity(14 + query.len() + key.len());
    body.put_u64(session_id);
    write_long_string(query, &mut body);
    write_string(key, &mut body).expect("key fits");
    body
}

fn encode_execute_body(
    session_id: SessionId,
    key: &str,
    consistency: Consistency,
    values: &[CqlValue],
) -> Result<BytesMut> {
    // Pre-allocate: 8 (session_id) + 2 (key len) + key + 2 (consistency) + 1 (flags) + 2 (value count) + ~32 per value
    let estimated_values_size = values.len() * 36;
    let mut body = BytesMut::with_capacity(15 + key.len() + estimated_values_size);
    body.put_u64(session_id);
    write_string(key, &mut body)?;
    body.put_u16(consistency as u16);

    // Flags: bit 0 = values present
    let flags: u8 = if values.is_empty() { 0 } else { 0x01 };
    body.put_u8(flags);

    if !values.is_empty() {
        encode_values(values, &mut body)?;
    }

    Ok(body)
}

fn encode_batch_body(
    session_id: SessionId,
    statements: &[(&str, &[CqlValue])],
    consistency: Consistency,
) -> Result<BytesMut> {
    // Estimate size: 8 (session_id) + 1 (batch type) + 2 (n statements) + 2 (consistency) + 1 (flags)
    // + for each statement: 1 (kind) + 2 (key len) + key + 2 (n values) + values
    let estimated_size: usize = 14
        + statements
            .iter()
            .map(|(k, v)| 5 + k.len() + v.len() * 36)
            .sum::<usize>();
    let mut body = BytesMut::with_capacity(estimated_size);

    body.put_u64(session_id);
    body.put_u8(0); // batch type: LOGGED

    let n_statements: u16 = statements
        .len()
        .try_into()
        .map_err(|err| anyhow!("too many statements in batch: {err}"))?;
    body.put_u16(n_statements);

    for (key, values) in statements {
        // kind = 1 means prepared statement (identified by key)
        body.put_u8(1);
        write_string(key, &mut body)?;
        encode_values(values, &mut body)?;
    }

    body.put_u16(consistency as u16);
    body.put_u8(0); // flags

    Ok(body)
}

#[inline]
fn encode_values(values: &[CqlValue], buf: &mut BytesMut) -> Result<()> {
    let count: u16 = values
        .len()
        .try_into()
        .map_err(|err| anyhow!("too many values: {err}"))?;
    buf.put_u16(count);

    for value in values {
        encode_value(value, buf)?;
    }

    Ok(())
}

/// CQL type codes (matching the CQL binary protocol)
mod type_codes {
    pub const ASCII: u16 = 0x0001;
    pub const BIGINT: u16 = 0x0002;
    pub const BLOB: u16 = 0x0003;
    pub const BOOLEAN: u16 = 0x0004;
    pub const COUNTER: u16 = 0x0005;
    pub const DOUBLE: u16 = 0x0007;
    pub const FLOAT: u16 = 0x0008;
    pub const INT: u16 = 0x0009;
    pub const TIMESTAMP: u16 = 0x000B;
    pub const UUID: u16 = 0x000C;
    pub const TEXT: u16 = 0x000D;
    pub const TIMEUUID: u16 = 0x000F;
    pub const INET: u16 = 0x0010;
    pub const DATE: u16 = 0x0011;
    pub const TIME: u16 = 0x0012;
    pub const SMALLINT: u16 = 0x0013;
    pub const TINYINT: u16 = 0x0014;
    pub const LIST: u16 = 0x0020;
    pub const MAP: u16 = 0x0021;
    pub const SET: u16 = 0x0022;
    pub const VECTOR: u16 = 0x0030;
    pub const TUPLE: u16 = 0x0031;
    /// Packed format for list<vector<float, N>> - eliminates per-element length prefixes
    /// Format: [type_code: u16] [length: i32] [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
    pub const PACKED_FLOAT_VECTOR_LIST: u16 = 0x0032;
    pub const UDT: u16 = 0x0040;
}

#[inline]
pub fn encode_value(value: &CqlValue, buf: &mut BytesMut) -> Result<()> {
    // Format: [type_code: u16] [length: i32] [data: bytes]
    // For null values: [type_code: u16] [length: -1]
    match value {
        CqlValue::TinyInt(v) => {
            buf.put_u16(type_codes::TINYINT);
            buf.put_i32(1);
            buf.put_i8(*v);
        }
        CqlValue::SmallInt(v) => {
            buf.put_u16(type_codes::SMALLINT);
            buf.put_i32(2);
            buf.put_i16(*v);
        }
        CqlValue::Int(v) => {
            buf.put_u16(type_codes::INT);
            buf.put_i32(4);
            buf.put_i32(*v);
        }
        CqlValue::BigInt(v) => {
            buf.put_u16(type_codes::BIGINT);
            buf.put_i32(8);
            buf.put_i64(*v);
        }
        CqlValue::Text(v) => {
            buf.put_u16(type_codes::TEXT);
            let bytes = v.as_bytes();
            let len: i32 = bytes
                .len()
                .try_into()
                .map_err(|err| anyhow!("text value too long: {err}"))?;
            buf.put_i32(len);
            buf.extend_from_slice(bytes);
        }
        CqlValue::Ascii(v) => {
            buf.put_u16(type_codes::ASCII);
            let bytes = v.as_bytes();
            let len: i32 = bytes
                .len()
                .try_into()
                .map_err(|err| anyhow!("ascii value too long: {err}"))?;
            buf.put_i32(len);
            buf.extend_from_slice(bytes);
        }
        CqlValue::Boolean(v) => {
            buf.put_u16(type_codes::BOOLEAN);
            buf.put_i32(1);
            buf.put_u8(if *v { 1 } else { 0 });
        }
        CqlValue::Float(v) => {
            buf.put_u16(type_codes::FLOAT);
            buf.put_i32(4);
            buf.extend_from_slice(&v.to_be_bytes());
        }
        CqlValue::Double(v) => {
            buf.put_u16(type_codes::DOUBLE);
            buf.put_i32(8);
            buf.extend_from_slice(&v.to_be_bytes());
        }
        CqlValue::Uuid(v) => {
            buf.put_u16(type_codes::UUID);
            buf.put_i32(16);
            buf.extend_from_slice(v.as_bytes());
        }
        CqlValue::Timeuuid(v) => {
            buf.put_u16(type_codes::TIMEUUID);
            buf.put_i32(16);
            buf.extend_from_slice(v.as_bytes());
        }
        CqlValue::Blob(v) => {
            buf.put_u16(type_codes::BLOB);
            let len: i32 = v
                .len()
                .try_into()
                .map_err(|err| anyhow!("blob value too long: {err}"))?;
            buf.put_i32(len);
            buf.extend_from_slice(v);
        }
        CqlValue::Timestamp(v) => {
            buf.put_u16(type_codes::TIMESTAMP);
            buf.put_i32(8);
            buf.put_i64(v.0);
        }
        CqlValue::Date(v) => {
            buf.put_u16(type_codes::DATE);
            buf.put_i32(4);
            buf.put_u32(v.0);
        }
        CqlValue::Time(v) => {
            buf.put_u16(type_codes::TIME);
            buf.put_i32(8);
            buf.put_i64(v.0);
        }
        CqlValue::Inet(v) => {
            buf.put_u16(type_codes::INET);
            match v {
                std::net::IpAddr::V4(ip) => {
                    buf.put_i32(4);
                    buf.extend_from_slice(&ip.octets());
                }
                std::net::IpAddr::V6(ip) => {
                    buf.put_i32(16);
                    buf.extend_from_slice(&ip.octets());
                }
            }
        }
        CqlValue::Counter(v) => {
            buf.put_u16(type_codes::COUNTER);
            buf.put_i32(8);
            buf.put_i64(v.0);
        }
        CqlValue::Empty => {
            buf.put_u16(type_codes::BLOB); // type doesn't matter for null
            buf.put_i32(-1); // null
        }
        CqlValue::Vector(elements) => {
            // Vector is encoded as: [type_code: u16=0x0030] [length: i32] [subtype: u16] [dimension: u16] [data: bytes]
            // For vector<float, N>, subtype is FLOAT (0x0008)
            // Data is contiguous float bytes (no per-element length prefix)
            buf.put_u16(type_codes::VECTOR);
            let dimension: u16 = elements
                .len()
                .try_into()
                .map_err(|err| anyhow!("vector dimension too large: {err}"))?;
            // Total data length: 2 (subtype) + 2 (dimension) + N*4 (floats)
            let data_len: i32 = (4 + elements.len() * 4)
                .try_into()
                .map_err(|err| anyhow!("vector data too long: {err}"))?;
            buf.put_i32(data_len);
            buf.put_u16(type_codes::FLOAT); // subtype
            buf.put_u16(dimension);
            for element in elements {
                match element {
                    CqlValue::Float(f) => buf.extend_from_slice(&f.to_be_bytes()),
                    CqlValue::Double(d) => buf.extend_from_slice(&(*d as f32).to_be_bytes()),
                    _ => bail!("vector element must be float, got {:?}", element),
                }
            }
        }
        CqlValue::List(elements) => {
            // Check if this is a list<vector<float>> - use packed format for efficiency
            if let Some(CqlValue::Vector(first_vec)) = elements.first() {
                // Verify first element contains floats
                let is_float_vector = first_vec
                    .first()
                    .map(|e| matches!(e, CqlValue::Float(_) | CqlValue::Double(_)))
                    .unwrap_or(true);

                if is_float_vector {
                    // Use packed format: eliminates per-element length prefixes
                    // Format: [type_code: u16] [length: i32] [n_elements: i32] [dimension: u16] [packed_floats]
                    let n_elements = elements.len();
                    let dimension = first_vec.len();

                    // Pre-calculate exact buffer size: 4 (n_elements) + 2 (dimension) + n * dim * 4 (floats)
                    let data_len: i32 = (6 + n_elements * dimension * 4)
                        .try_into()
                        .map_err(|err| anyhow!("packed vector list too long: {err}"))?;

                    buf.put_u16(type_codes::PACKED_FLOAT_VECTOR_LIST);
                    buf.put_i32(data_len);
                    buf.put_i32(n_elements as i32);
                    buf.put_u16(dimension as u16);

                    // Reserve space and write floats directly
                    buf.reserve(n_elements * dimension * 4);
                    for element in elements {
                        if let CqlValue::Vector(vec_elements) = element {
                            for ve in vec_elements {
                                match ve {
                                    CqlValue::Float(f) => buf.extend_from_slice(&f.to_be_bytes()),
                                    CqlValue::Double(d) => {
                                        buf.extend_from_slice(&(*d as f32).to_be_bytes())
                                    }
                                    _ => bail!("vector element must be float"),
                                }
                            }
                        } else {
                            bail!("list<vector> contains non-vector element");
                        }
                    }
                    return Ok(());
                }
            }

            // Standard list encoding for non-vector lists
            buf.put_u16(type_codes::LIST);

            // Determine subtype from first element (or use BLOB as fallback)
            let subtype = if let Some(first) = elements.first() {
                match first {
                    CqlValue::Vector(_) => type_codes::VECTOR,
                    CqlValue::Float(_) => type_codes::FLOAT,
                    CqlValue::Double(_) => type_codes::DOUBLE,
                    CqlValue::Int(_) => type_codes::INT,
                    CqlValue::BigInt(_) => type_codes::BIGINT,
                    CqlValue::Text(_) => type_codes::TEXT,
                    CqlValue::List(_) => type_codes::LIST,
                    CqlValue::Set(_) => type_codes::SET,
                    CqlValue::Map(_) => type_codes::MAP,
                    CqlValue::UserDefinedType { .. } => type_codes::UDT,
                    _ => type_codes::BLOB,
                }
            } else {
                type_codes::BLOB
            };

            // Write directly to output buffer using length placeholder pattern
            // Reserve placeholder for length (we'll fill it in later)
            let len_pos = buf.len();
            buf.put_i32(0); // placeholder

            buf.put_u16(subtype);

            // For list<vector<float, N>>, we need to also encode vector subtype and dimension
            if subtype == type_codes::VECTOR {
                buf.put_u16(type_codes::FLOAT); // vector element type
                                                // Get dimension from first vector
                let dimension: u16 = if let Some(CqlValue::Vector(v)) = elements.first() {
                    v.len()
                        .try_into()
                        .map_err(|err| anyhow!("vector dimension too large: {err}"))?
                } else {
                    0
                };
                buf.put_u16(dimension);
            }

            let n_elements: i32 = elements
                .len()
                .try_into()
                .map_err(|err| anyhow!("too many list elements: {err}"))?;
            buf.put_i32(n_elements);

            for element in elements {
                match element {
                    CqlValue::Vector(vec_elements) => {
                        // Vector data is contiguous floats without per-element length
                        let vec_len: i32 = (vec_elements.len() * 4)
                            .try_into()
                            .map_err(|err| anyhow!("vector too long: {err}"))?;
                        buf.put_i32(vec_len);
                        for ve in vec_elements {
                            match ve {
                                CqlValue::Float(f) => buf.extend_from_slice(&f.to_be_bytes()),
                                CqlValue::Double(d) => {
                                    buf.extend_from_slice(&(*d as f32).to_be_bytes())
                                }
                                _ => bail!("vector element must be float"),
                            }
                        }
                    }
                    _ => {
                        // For other types, encode recursively - write directly to output buffer
                        // Save position before type code
                        let elem_start = buf.len();
                        encode_value(element, buf)?;
                        // Remove the type code (first 2 bytes) by shifting data
                        let elem_data_start = elem_start + 2;
                        let elem_end = buf.len();
                        buf.copy_within(elem_data_start..elem_end, elem_start);
                        buf.truncate(elem_end - 2);
                    }
                }
            }

            // Fill in the length placeholder
            let data_len: i32 = (buf.len() - len_pos - 4)
                .try_into()
                .map_err(|err| anyhow!("list data too long: {err}"))?;
            buf[len_pos..len_pos + 4].copy_from_slice(&data_len.to_be_bytes());
        }
        CqlValue::Set(elements) => {
            // Set is encoded similarly to List: [type_code: u16=0x0022] [length: i32] [subtype: u16] [n_elements: i32] [elements...]
            buf.put_u16(type_codes::SET);

            let subtype = if let Some(first) = elements.first() {
                match first {
                    CqlValue::Int(_) => type_codes::INT,
                    CqlValue::BigInt(_) => type_codes::BIGINT,
                    CqlValue::Text(_) => type_codes::TEXT,
                    CqlValue::Float(_) => type_codes::FLOAT,
                    CqlValue::Double(_) => type_codes::DOUBLE,
                    CqlValue::List(_) => type_codes::LIST,
                    CqlValue::Set(_) => type_codes::SET,
                    CqlValue::Map(_) => type_codes::MAP,
                    CqlValue::UserDefinedType { .. } => type_codes::UDT,
                    _ => type_codes::BLOB,
                }
            } else {
                type_codes::BLOB
            };

            // Write directly to output buffer using length placeholder pattern
            let len_pos = buf.len();
            buf.put_i32(0); // placeholder for length

            buf.put_u16(subtype);

            let n_elements: i32 = elements
                .len()
                .try_into()
                .map_err(|err| anyhow!("too many set elements: {err}"))?;
            buf.put_i32(n_elements);

            for element in elements {
                // Write directly to output buffer, then strip type code
                let elem_start = buf.len();
                encode_value(element, buf)?;
                // Remove the type code (first 2 bytes) by shifting data
                let elem_data_start = elem_start + 2;
                let elem_end = buf.len();
                buf.copy_within(elem_data_start..elem_end, elem_start);
                buf.truncate(elem_end - 2);
            }

            // Fill in the length placeholder
            let set_len: i32 = (buf.len() - len_pos - 4)
                .try_into()
                .map_err(|err| anyhow!("set data too long: {err}"))?;
            buf[len_pos..len_pos + 4].copy_from_slice(&set_len.to_be_bytes());
        }
        CqlValue::Map(entries) => {
            // Map is encoded as: [type_code: u16=0x0021] [length: i32] [key_type: u16] [value_type: u16] [n_entries: i32] [entries...]
            // Each entry is [key_length: i32] [key_data] [value_length: i32] [value_data]
            buf.put_u16(type_codes::MAP);

            let (key_type, value_type) = if let Some((first_key, first_value)) = entries.first() {
                let kt = match first_key {
                    CqlValue::Int(_) => type_codes::INT,
                    CqlValue::BigInt(_) => type_codes::BIGINT,
                    CqlValue::Text(_) => type_codes::TEXT,
                    _ => type_codes::BLOB,
                };
                let vt = match first_value {
                    CqlValue::Int(_) => type_codes::INT,
                    CqlValue::BigInt(_) => type_codes::BIGINT,
                    CqlValue::Text(_) => type_codes::TEXT,
                    CqlValue::Float(_) => type_codes::FLOAT,
                    CqlValue::Double(_) => type_codes::DOUBLE,
                    CqlValue::List(_) => type_codes::LIST,
                    CqlValue::Set(_) => type_codes::SET,
                    CqlValue::Map(_) => type_codes::MAP,
                    CqlValue::UserDefinedType { .. } => type_codes::UDT,
                    _ => type_codes::BLOB,
                };
                (kt, vt)
            } else {
                (type_codes::BLOB, type_codes::BLOB)
            };

            // Write directly to output buffer using length placeholder pattern
            let len_pos = buf.len();
            buf.put_i32(0); // placeholder for length

            buf.put_u16(key_type);
            buf.put_u16(value_type);

            let n_entries: i32 = entries
                .len()
                .try_into()
                .map_err(|err| anyhow!("too many map entries: {err}"))?;
            buf.put_i32(n_entries);

            for (key, value) in entries {
                // Encode key directly to buffer, then strip type code
                let key_start = buf.len();
                encode_value(key, buf)?;
                let key_data_start = key_start + 2;
                let key_end = buf.len();
                buf.copy_within(key_data_start..key_end, key_start);
                buf.truncate(key_end - 2);

                // Encode value directly to buffer, then strip type code
                let val_start = buf.len();
                encode_value(value, buf)?;
                let val_data_start = val_start + 2;
                let val_end = buf.len();
                buf.copy_within(val_data_start..val_end, val_start);
                buf.truncate(val_end - 2);
            }

            // Fill in the length placeholder
            let map_len: i32 = (buf.len() - len_pos - 4)
                .try_into()
                .map_err(|err| anyhow!("map data too long: {err}"))?;
            buf[len_pos..len_pos + 4].copy_from_slice(&map_len.to_be_bytes());
        }
        CqlValue::Tuple(elements) => {
            // Tuple is encoded as: [type_code: u16=0x0031] [length: i32] [n_elements: u16] [element_types...] [elements...]
            // Each element is [length: i32] [data: bytes] (or length=-1 for null)
            buf.put_u16(type_codes::TUPLE);

            // Write directly to output buffer using length placeholder pattern
            let len_pos = buf.len();
            buf.put_i32(0); // placeholder for length

            let n_elements: u16 = elements
                .len()
                .try_into()
                .map_err(|err| anyhow!("too many tuple elements: {err}"))?;
            buf.put_u16(n_elements);

            // Write element types
            for elem in elements {
                let elem_type = match elem {
                    Some(CqlValue::Int(_)) => type_codes::INT,
                    Some(CqlValue::BigInt(_)) => type_codes::BIGINT,
                    Some(CqlValue::Text(_)) => type_codes::TEXT,
                    Some(CqlValue::Boolean(_)) => type_codes::BOOLEAN,
                    Some(CqlValue::Float(_)) => type_codes::FLOAT,
                    Some(CqlValue::Double(_)) => type_codes::DOUBLE,
                    Some(CqlValue::List(_)) => type_codes::LIST,
                    Some(CqlValue::Map(_)) => type_codes::MAP,
                    Some(CqlValue::Set(_)) => type_codes::SET,
                    _ => type_codes::BLOB,
                };
                buf.put_u16(elem_type);
            }

            // Write element data
            for elem in elements {
                match elem {
                    Some(val) => {
                        // Write directly to buffer, then strip type code
                        let elem_start = buf.len();
                        encode_value(val, buf)?;
                        let elem_data_start = elem_start + 2;
                        let elem_end = buf.len();
                        buf.copy_within(elem_data_start..elem_end, elem_start);
                        buf.truncate(elem_end - 2);
                    }
                    None => {
                        buf.put_i32(-1); // null
                    }
                }
            }

            // Fill in the length placeholder
            let tuple_len: i32 = (buf.len() - len_pos - 4)
                .try_into()
                .map_err(|err| anyhow!("tuple data too long: {err}"))?;
            buf[len_pos..len_pos + 4].copy_from_slice(&tuple_len.to_be_bytes());
        }
        CqlValue::UserDefinedType { fields, .. } => {
            // UDT is encoded as: [type_code: u16=0x0040] [length: i32] [n_fields: u16] [field_name_len: u16] [field_name] [field_type: u16] ... [field_values...]
            buf.put_u16(type_codes::UDT);

            // Write directly to output buffer using length placeholder pattern
            let len_pos = buf.len();
            buf.put_i32(0); // placeholder for length

            let n_fields: u16 = fields
                .len()
                .try_into()
                .map_err(|err| anyhow!("too many UDT fields: {err}"))?;
            buf.put_u16(n_fields);

            // Write field names and types
            for (field_name, field_value) in fields {
                // Write field name
                let name_bytes = field_name.as_bytes();
                let name_len: u16 = name_bytes
                    .len()
                    .try_into()
                    .map_err(|err| anyhow!("field name too long: {err}"))?;
                buf.put_u16(name_len);
                buf.extend_from_slice(name_bytes);

                // Write field type
                let field_type = match field_value {
                    Some(CqlValue::Int(_)) => type_codes::INT,
                    Some(CqlValue::BigInt(_)) => type_codes::BIGINT,
                    Some(CqlValue::Text(_)) => type_codes::TEXT,
                    Some(CqlValue::Boolean(_)) => type_codes::BOOLEAN,
                    Some(CqlValue::Float(_)) => type_codes::FLOAT,
                    Some(CqlValue::Double(_)) => type_codes::DOUBLE,
                    Some(CqlValue::UserDefinedType { .. }) => type_codes::UDT,
                    _ => type_codes::BLOB,
                };
                buf.put_u16(field_type);
            }

            // Write field values
            for (_, field_value) in fields {
                match field_value {
                    Some(val) => {
                        // Write directly to buffer, then strip type code
                        let field_start = buf.len();
                        encode_value(val, buf)?;
                        let field_data_start = field_start + 2;
                        let field_end = buf.len();
                        buf.copy_within(field_data_start..field_end, field_start);
                        buf.truncate(field_end - 2);
                    }
                    None => {
                        buf.put_i32(-1); // null
                    }
                }
            }

            // Fill in the length placeholder
            let udt_len: i32 = (buf.len() - len_pos - 4)
                .try_into()
                .map_err(|err| anyhow!("UDT data too long: {err}"))?;
            buf[len_pos..len_pos + 4].copy_from_slice(&udt_len.to_be_bytes());
        }
        other => {
            bail!("unsupported CqlValue type for encoding: {:?}", other);
        }
    }
    Ok(())
}

fn read_short(slice: &mut &[u8]) -> Result<u16> {
    if slice.len() < 2 {
        bail!("unexpected EOF reading short");
    }
    let (head, rest) = slice.split_at(2);
    *slice = rest;
    Ok(u16::from_be_bytes([head[0], head[1]]))
}

fn read_int(slice: &mut &[u8]) -> Result<i32> {
    if slice.len() < 4 {
        bail!("unexpected EOF reading int");
    }
    let (head, rest) = slice.split_at(4);
    *slice = rest;
    Ok(i32::from_be_bytes([head[0], head[1], head[2], head[3]]))
}

fn read_string(slice: &mut &[u8]) -> Result<String> {
    let len = read_short(slice)? as usize;
    if slice.len() < len {
        bail!("unexpected EOF reading string body");
    }
    let (head, rest) = slice.split_at(len);
    *slice = rest;
    // Use from_utf8_lossy directly on slice (Cow avoids allocation for valid UTF-8 until into_owned)
    Ok(String::from_utf8_lossy(head).into_owned())
}

fn write_string(value: &str, buf: &mut BytesMut) -> Result<()> {
    let len: u16 = value
        .len()
        .try_into()
        .map_err(|err| anyhow!("string too long: {err}"))?;
    buf.put_u16(len);
    buf.extend_from_slice(value.as_bytes());
    Ok(())
}

fn write_long_string(value: &str, buf: &mut BytesMut) {
    buf.put_i32(value.len() as i32);
    buf.extend_from_slice(value.as_bytes());
}
