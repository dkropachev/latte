//! IPC client for communicating with Alternator driver adapters over Unix sockets.

use super::alternator_protocol::{
    decode_error, encode_request, read_frame, Frame, RequestOpcode, ResponseOpcode,
};
use anyhow::{anyhow, bail, Result};
use aws_sdk_dynamodb::types::AttributeValue;
use bytes::{Buf, BufMut, BytesMut};
use dashmap::DashMap;
use futures::FutureExt;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI16, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio::net::unix::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::UnixStream;
use tokio::sync::{mpsc, oneshot};
use tracing::error;

/// Session ID type for Alternator adapter.
pub type AlternatorSessionId = u64;

/// Pending request waiting for a response.
struct PendingRequest {
    response_tx: oneshot::Sender<Frame>,
}

/// Message sent to the writer task.
struct WriteRequest {
    data: BytesMut,
}

/// Result of a single-item DynamoDB operation.
#[derive(Debug)]
pub struct ItemResult {
    pub item: Option<HashMap<String, AttributeValue>>,
    pub driver_latency_ns: i64,
}

/// Result of a query/scan operation.
#[derive(Debug)]
pub struct QueryScanResult {
    pub items: Vec<HashMap<String, AttributeValue>>,
    pub last_evaluated_key: Option<HashMap<String, AttributeValue>>,
    pub scanned_count: u32,
    pub driver_latency_ns: i64,
}

/// Result of a batch get operation.
#[derive(Debug)]
pub struct BatchGetResult {
    pub responses: HashMap<String, Vec<HashMap<String, AttributeValue>>>,
    pub unprocessed_keys: HashMap<String, Vec<HashMap<String, AttributeValue>>>,
    pub driver_latency_ns: i64,
}

/// Result of a batch write operation.
#[derive(Debug)]
pub struct BatchWriteResult {
    pub unprocessed_items_count: usize,
    pub driver_latency_ns: i64,
}

/// Request items for BatchGetItem operation (per table).
#[derive(Debug)]
pub struct BatchGetRequestItems {
    pub keys: Vec<HashMap<String, AttributeValue>>,
    pub consistent_read: bool,
    pub projection_expression: Option<String>,
    pub expression_attribute_names: Option<HashMap<String, String>>,
}

/// Write request for BatchWriteItem operation.
#[derive(Debug)]
pub enum BatchWriteRequest {
    Put(HashMap<String, AttributeValue>),
    Delete(HashMap<String, AttributeValue>),
}

/// IPC client for communicating with Alternator driver adapters.
#[derive(Clone)]
pub struct AlternatorIpcClient {
    write_tx: mpsc::Sender<WriteRequest>,
    pending: Arc<DashMap<i16, PendingRequest>>,
    next_stream: Arc<AtomicI16>,
    _tasks_alive: Arc<()>,
}

impl AlternatorIpcClient {
    /// Create an Alternator IPC client from an already-established Unix stream.
    pub fn from_stream(stream: UnixStream) -> Self {
        let (reader, writer) = stream.into_split();
        let pending: Arc<DashMap<i16, PendingRequest>> = Arc::new(DashMap::with_capacity(256));
        let tasks_alive = Arc::new(());

        let (write_tx, write_rx) = mpsc::channel::<WriteRequest>(1024);

        // Spawn background writer task
        let tasks_alive_writer = Arc::clone(&tasks_alive);
        tokio::spawn(async move {
            let _keep_alive = tasks_alive_writer;
            if let Err(e) = std::panic::AssertUnwindSafe(writer_task(writer, write_rx))
                .catch_unwind()
                .await
            {
                error!("Alternator IPC writer task panicked: {:?}", e);
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
                error!("Alternator IPC reader task panicked: {:?}", e);
            }
        });

        Self {
            write_tx,
            pending,
            next_stream: Arc::new(AtomicI16::new(0)),
            _tasks_alive: tasks_alive,
        }
    }

    /// Create a new session with the Alternator driver adapter.
    pub async fn create_session(
        &self,
        params: HashMap<String, String>,
    ) -> Result<AlternatorSessionId> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_session_params(&params);
        let request = encode_request(RequestOpcode::CreateSession, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;

        let opcode = ResponseOpcode::try_from(response.header.opcode)
            .map_err(|e| anyhow!("invalid response opcode: {}", e))?;

        if opcode == ResponseOpcode::Error {
            let (code, err_type, msg) = decode_error(&response.body);
            bail!(
                "failed to create session: {} (code={}, type={})",
                msg,
                code,
                err_type
            );
        }

        if opcode != ResponseOpcode::SessionCreated {
            bail!("expected SessionCreated opcode, got {:?}", opcode);
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

    /// Close a session.
    pub async fn close_session(&self, session_id: AlternatorSessionId) -> Result<()> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let mut body = BytesMut::with_capacity(8);
        body.put_u64(session_id);
        let request = encode_request(RequestOpcode::CloseSession, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        let opcode = ResponseOpcode::try_from(response.header.opcode)
            .map_err(|e| anyhow!("invalid response opcode: {}", e))?;

        if opcode == ResponseOpcode::Error {
            let (code, err_type, msg) = decode_error(&response.body);
            bail!(
                "failed to close session: {} (code={}, type={})",
                msg,
                code,
                err_type
            );
        }

        Ok(())
    }

    /// Execute a GetItem operation.
    pub async fn get_item(
        &self,
        session_id: AlternatorSessionId,
        table_name: &str,
        key: &HashMap<String, AttributeValue>,
        consistent_read: bool,
        projection_expression: Option<&str>,
        expression_attribute_names: Option<&HashMap<String, String>>,
    ) -> Result<ItemResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_get_item_request(
            session_id,
            table_name,
            key,
            consistent_read,
            projection_expression,
            expression_attribute_names,
        );
        let request = encode_request(RequestOpcode::GetItem, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_item_result(response)
    }

    /// Execute a PutItem operation.
    pub async fn put_item(
        &self,
        session_id: AlternatorSessionId,
        table_name: &str,
        item: &HashMap<String, AttributeValue>,
        condition_expression: Option<&str>,
        expression_attribute_names: Option<&HashMap<String, String>>,
        expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
        return_values: u8,
    ) -> Result<ItemResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_put_item_request(
            session_id,
            table_name,
            item,
            condition_expression,
            expression_attribute_names,
            expression_attribute_values,
            return_values,
        );
        let request = encode_request(RequestOpcode::PutItem, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_item_result(response)
    }

    /// Execute a DeleteItem operation.
    pub async fn delete_item(
        &self,
        session_id: AlternatorSessionId,
        table_name: &str,
        key: &HashMap<String, AttributeValue>,
        condition_expression: Option<&str>,
        expression_attribute_names: Option<&HashMap<String, String>>,
        expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
        return_values: u8,
    ) -> Result<ItemResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_delete_item_request(
            session_id,
            table_name,
            key,
            condition_expression,
            expression_attribute_names,
            expression_attribute_values,
            return_values,
        );
        let request = encode_request(RequestOpcode::DeleteItem, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_item_result(response)
    }

    /// Execute an UpdateItem operation.
    pub async fn update_item(
        &self,
        session_id: AlternatorSessionId,
        table_name: &str,
        key: &HashMap<String, AttributeValue>,
        update_expression: &str,
        condition_expression: Option<&str>,
        expression_attribute_names: Option<&HashMap<String, String>>,
        expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
        return_values: u8,
    ) -> Result<ItemResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_update_item_request(
            session_id,
            table_name,
            key,
            update_expression,
            condition_expression,
            expression_attribute_names,
            expression_attribute_values,
            return_values,
        );
        let request = encode_request(RequestOpcode::UpdateItem, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_item_result(response)
    }

    /// Execute a Query operation.
    #[allow(clippy::too_many_arguments)]
    pub async fn query(
        &self,
        session_id: AlternatorSessionId,
        table_name: &str,
        index_name: Option<&str>,
        key_condition_expression: &str,
        filter_expression: Option<&str>,
        projection_expression: Option<&str>,
        expression_attribute_names: Option<&HashMap<String, String>>,
        expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
        limit: Option<u32>,
        consistent_read: bool,
        scan_index_forward: bool,
        exclusive_start_key: Option<&HashMap<String, AttributeValue>>,
    ) -> Result<QueryScanResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_query_request(
            session_id,
            table_name,
            index_name,
            key_condition_expression,
            filter_expression,
            projection_expression,
            expression_attribute_names,
            expression_attribute_values,
            limit,
            consistent_read,
            scan_index_forward,
            exclusive_start_key,
        );
        let request = encode_request(RequestOpcode::Query, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_query_result(response)
    }

    /// Execute a Scan operation.
    #[allow(clippy::too_many_arguments)]
    pub async fn scan(
        &self,
        session_id: AlternatorSessionId,
        table_name: &str,
        index_name: Option<&str>,
        filter_expression: Option<&str>,
        projection_expression: Option<&str>,
        expression_attribute_names: Option<&HashMap<String, String>>,
        expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
        limit: Option<u32>,
        consistent_read: bool,
        segment: Option<u32>,
        total_segments: Option<u32>,
        exclusive_start_key: Option<&HashMap<String, AttributeValue>>,
    ) -> Result<QueryScanResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_scan_request(
            session_id,
            table_name,
            index_name,
            filter_expression,
            projection_expression,
            expression_attribute_names,
            expression_attribute_values,
            limit,
            consistent_read,
            segment,
            total_segments,
            exclusive_start_key,
        );
        let request = encode_request(RequestOpcode::Scan, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_query_result(response)
    }

    /// Execute a BatchGetItem operation.
    pub async fn batch_get_item(
        &self,
        session_id: AlternatorSessionId,
        request_items: &HashMap<String, BatchGetRequestItems>,
    ) -> Result<BatchGetResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_batch_get_request(session_id, request_items);
        let request = encode_request(RequestOpcode::BatchGetItem, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_batch_get_result(response)
    }

    /// Execute a BatchWriteItem operation.
    pub async fn batch_write_item(
        &self,
        session_id: AlternatorSessionId,
        request_items: &HashMap<String, Vec<BatchWriteRequest>>,
    ) -> Result<BatchWriteResult> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_batch_write_request(session_id, request_items);
        let request = encode_request(RequestOpcode::BatchWriteItem, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;
        self.decode_batch_write_result(response)
    }

    async fn send_request(&self, stream_id: i16, request: BytesMut) -> Result<Frame> {
        let (response_tx, response_rx) = oneshot::channel();

        self.pending
            .insert(stream_id, PendingRequest { response_tx });

        self.write_tx
            .send(WriteRequest { data: request })
            .await
            .map_err(|_| anyhow!("writer task closed"))?;

        response_rx
            .await
            .map_err(|_| anyhow!("reader task closed before response received"))
    }

    fn decode_item_result(&self, frame: Frame) -> Result<ItemResult> {
        let opcode = ResponseOpcode::try_from(frame.header.opcode)
            .map_err(|e| anyhow!("invalid response opcode: {}", e))?;

        if opcode == ResponseOpcode::Error {
            let (code, err_type, msg) = decode_error(&frame.body);
            bail!(
                "operation failed: {} (code={}, type={})",
                msg,
                code,
                err_type
            );
        }

        if opcode != ResponseOpcode::ItemResult {
            bail!("expected ItemResult opcode, got {:?}", opcode);
        }

        let mut slice = &frame.body[..];

        // Read has_item
        let has_item = read_bool(&mut slice)?;
        let item = if has_item {
            Some(read_item(&mut slice)?)
        } else {
            None
        };

        // Skip consumed_capacity (optional)
        let _has_consumed_capacity = read_bool(&mut slice)?;

        // Read driver_latency_ns
        let driver_latency_ns = read_i64(&mut slice)?;

        Ok(ItemResult {
            item,
            driver_latency_ns,
        })
    }

    fn decode_query_result(&self, frame: Frame) -> Result<QueryScanResult> {
        let opcode = ResponseOpcode::try_from(frame.header.opcode)
            .map_err(|e| anyhow!("invalid response opcode: {}", e))?;

        if opcode == ResponseOpcode::Error {
            let (code, err_type, msg) = decode_error(&frame.body);
            bail!(
                "operation failed: {} (code={}, type={})",
                msg,
                code,
                err_type
            );
        }

        if opcode != ResponseOpcode::QueryResult {
            bail!("expected QueryResult opcode, got {:?}", opcode);
        }

        let mut slice = &frame.body[..];

        // Read item_count and items
        let item_count = read_u32(&mut slice)?;
        let mut items = Vec::with_capacity(item_count as usize);
        for _ in 0..item_count {
            items.push(read_item(&mut slice)?);
        }

        // Read optional last_evaluated_key
        let has_last_key = read_bool(&mut slice)?;
        let last_evaluated_key = if has_last_key {
            Some(read_key(&mut slice)?)
        } else {
            None
        };

        // Read scanned_count
        let scanned_count = read_u32(&mut slice)?;

        // Skip consumed_capacity (optional)
        let _has_consumed_capacity = read_bool(&mut slice)?;

        // Read driver_latency_ns
        let driver_latency_ns = read_i64(&mut slice)?;

        Ok(QueryScanResult {
            items,
            last_evaluated_key,
            scanned_count,
            driver_latency_ns,
        })
    }

    fn decode_batch_get_result(&self, frame: Frame) -> Result<BatchGetResult> {
        let opcode = ResponseOpcode::try_from(frame.header.opcode)
            .map_err(|e| anyhow!("invalid response opcode: {}", e))?;

        if opcode == ResponseOpcode::Error {
            let (code, err_type, msg) = decode_error(&frame.body);
            bail!(
                "operation failed: {} (code={}, type={})",
                msg,
                code,
                err_type
            );
        }

        if opcode != ResponseOpcode::BatchResult {
            bail!("expected BatchResult opcode, got {:?}", opcode);
        }

        let mut slice = &frame.body[..];

        // Read responses (table_count, then for each: table_name, item_count, items)
        let response_count = read_u16(&mut slice)?;
        let mut responses: HashMap<String, Vec<HashMap<String, AttributeValue>>> = HashMap::new();
        for _ in 0..response_count {
            let table_name = read_string(&mut slice)?;
            let item_count = read_u32(&mut slice)?;
            let mut items = Vec::with_capacity(item_count as usize);
            for _ in 0..item_count {
                items.push(read_item(&mut slice)?);
            }
            responses.insert(table_name, items);
        }

        // Read unprocessed keys
        let unprocessed_count = read_u16(&mut slice)?;
        let mut unprocessed_keys: HashMap<String, Vec<HashMap<String, AttributeValue>>> =
            HashMap::new();
        for _ in 0..unprocessed_count {
            let table_name = read_string(&mut slice)?;
            let key_count = read_u32(&mut slice)?;
            let mut keys = Vec::with_capacity(key_count as usize);
            for _ in 0..key_count {
                keys.push(read_key(&mut slice)?);
            }
            unprocessed_keys.insert(table_name, keys);
        }

        // Skip consumed_capacity (optional)
        let _has_consumed_capacity = read_bool(&mut slice)?;

        // Read driver_latency_ns
        let driver_latency_ns = read_i64(&mut slice)?;

        Ok(BatchGetResult {
            responses,
            unprocessed_keys,
            driver_latency_ns,
        })
    }

    fn decode_batch_write_result(&self, frame: Frame) -> Result<BatchWriteResult> {
        let opcode = ResponseOpcode::try_from(frame.header.opcode)
            .map_err(|e| anyhow!("invalid response opcode: {}", e))?;

        if opcode == ResponseOpcode::Error {
            let (code, err_type, msg) = decode_error(&frame.body);
            bail!(
                "operation failed: {} (code={}, type={})",
                msg,
                code,
                err_type
            );
        }

        if opcode != ResponseOpcode::BatchResult {
            bail!("expected BatchResult opcode, got {:?}", opcode);
        }

        let mut slice = &frame.body[..];

        // Skip responses (empty for BatchWriteItem)
        let response_count = read_u16(&mut slice)?;
        for _ in 0..response_count {
            let _table_name = read_string(&mut slice)?;
            let item_count = read_u32(&mut slice)?;
            for _ in 0..item_count {
                let _ = read_item(&mut slice)?;
            }
        }

        // Skip unprocessed keys (empty for BatchWriteItem)
        let unprocessed_keys_count = read_u16(&mut slice)?;
        for _ in 0..unprocessed_keys_count {
            let _table_name = read_string(&mut slice)?;
            let key_count = read_u32(&mut slice)?;
            for _ in 0..key_count {
                let _ = read_key(&mut slice)?;
            }
        }

        // Read unprocessed items count (for BatchWriteItem)
        let unprocessed_items_count = read_u16(&mut slice)? as usize;
        // Skip the actual unprocessed items - just count for now
        for _ in 0..unprocessed_items_count {
            let _table_name = read_string(&mut slice)?;
            let req_count = read_u32(&mut slice)?;
            for _ in 0..req_count {
                let req_type = read_u8(&mut slice)?;
                match req_type {
                    0x01 => {
                        let _ = read_item(&mut slice)?;
                    }
                    0x02 => {
                        let _ = read_key(&mut slice)?;
                    }
                    _ => bail!("unknown write request type: {}", req_type),
                }
            }
        }

        // Skip consumed_capacity (optional)
        let _has_consumed_capacity = read_bool(&mut slice)?;

        // Read driver_latency_ns
        let driver_latency_ns = read_i64(&mut slice)?;

        Ok(BatchWriteResult {
            unprocessed_items_count,
            driver_latency_ns,
        })
    }
}

/// Background task that batches and writes requests to the socket.
/// Flushes when buffer is large (4KB) or after max-latency timeout (50us).
async fn writer_task(writer: OwnedWriteHalf, mut write_rx: mpsc::Receiver<WriteRequest>) {
    use tokio::time::{timeout, Duration, Instant};

    let mut writer = BufWriter::with_capacity(16 * 1024, writer);
    let flush_timeout = Duration::from_micros(50);

    loop {
        let Some(req) = write_rx.recv().await else {
            break;
        };

        if let Err(err) = writer.write_all(&req.data).await {
            tracing::warn!(?err, "alternator writer task: failed to write request");
            break;
        }

        // Batch more requests with a max-latency timeout
        let deadline = Instant::now() + flush_timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                if let Err(err) = writer.flush().await {
                    tracing::warn!(?err, "alternator writer task: failed to flush");
                    return;
                }
                break;
            }
            match timeout(remaining, write_rx.recv()).await {
                Ok(Some(req)) => {
                    if let Err(err) = writer.write_all(&req.data).await {
                        tracing::warn!(?err, "alternator writer task: failed to write request");
                        return;
                    }
                    if writer.buffer().len() >= 4 * 1024 {
                        if let Err(err) = writer.flush().await {
                            tracing::warn!(?err, "alternator writer task: failed to flush");
                            return;
                        }
                        break; // Reset timeout after flush
                    }
                }
                Ok(None) => {
                    let _ = writer.flush().await;
                    return;
                }
                Err(_) => {
                    if let Err(err) = writer.flush().await {
                        tracing::warn!(?err, "alternator writer task: failed to flush");
                        return;
                    }
                    break;
                }
            }
        }
    }

    let _ = writer.flush().await;
}

/// Background task that reads responses and routes them to waiting callers.
async fn reader_task(mut reader: OwnedReadHalf, pending: Arc<DashMap<i16, PendingRequest>>) {
    let mut buffer = BytesMut::with_capacity(16 * 1024);

    loop {
        let frame = match read_frame(&mut reader, &mut buffer).await {
            Ok(Some(frame)) => frame,
            Ok(None) => break,
            Err(err) => {
                tracing::warn!(?err, "alternator reader task: failed to read frame");
                break;
            }
        };

        let stream_id = frame.header.stream;

        if let Some((_, req)) = pending.remove(&stream_id) {
            let _ = req.response_tx.send(frame);
        } else {
            tracing::warn!(stream_id, "received response for unknown stream");
        }
    }

    pending.clear();
}

// Encoding helpers

fn encode_session_params(params: &HashMap<String, String>) -> BytesMut {
    let mut buf = BytesMut::with_capacity(256);
    buf.put_u16(params.len() as u16);
    for (key, value) in params {
        write_string(&mut buf, key);
        write_string(&mut buf, value);
    }
    buf
}

fn encode_get_item_request(
    session_id: AlternatorSessionId,
    table_name: &str,
    key: &HashMap<String, AttributeValue>,
    consistent_read: bool,
    projection_expression: Option<&str>,
    expression_attribute_names: Option<&HashMap<String, String>>,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(256);
    buf.put_u64(session_id);
    write_string(&mut buf, table_name);
    write_key(&mut buf, key);
    buf.put_u8(if consistent_read { 1 } else { 0 });
    write_optional_string(&mut buf, projection_expression);
    write_expr_attr_names(&mut buf, expression_attribute_names);
    buf
}

fn encode_put_item_request(
    session_id: AlternatorSessionId,
    table_name: &str,
    item: &HashMap<String, AttributeValue>,
    condition_expression: Option<&str>,
    expression_attribute_names: Option<&HashMap<String, String>>,
    expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
    return_values: u8,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(512);
    buf.put_u64(session_id);
    write_string(&mut buf, table_name);
    write_item(&mut buf, item);
    write_optional_condition_expression(
        &mut buf,
        condition_expression,
        expression_attribute_names,
        expression_attribute_values,
    );
    buf.put_u8(return_values);
    buf
}

fn encode_delete_item_request(
    session_id: AlternatorSessionId,
    table_name: &str,
    key: &HashMap<String, AttributeValue>,
    condition_expression: Option<&str>,
    expression_attribute_names: Option<&HashMap<String, String>>,
    expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
    return_values: u8,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(256);
    buf.put_u64(session_id);
    write_string(&mut buf, table_name);
    write_key(&mut buf, key);
    write_optional_condition_expression(
        &mut buf,
        condition_expression,
        expression_attribute_names,
        expression_attribute_values,
    );
    buf.put_u8(return_values);
    buf
}

fn encode_update_item_request(
    session_id: AlternatorSessionId,
    table_name: &str,
    key: &HashMap<String, AttributeValue>,
    update_expression: &str,
    condition_expression: Option<&str>,
    expression_attribute_names: Option<&HashMap<String, String>>,
    expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
    return_values: u8,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(512);
    buf.put_u64(session_id);
    write_string(&mut buf, table_name);
    write_key(&mut buf, key);
    write_string(&mut buf, update_expression);
    write_optional_condition_expression(
        &mut buf,
        condition_expression,
        expression_attribute_names,
        expression_attribute_values,
    );
    write_expr_attr_names(&mut buf, expression_attribute_names);
    write_expr_attr_values(&mut buf, expression_attribute_values);
    buf.put_u8(return_values);
    buf
}

#[allow(clippy::too_many_arguments)]
fn encode_query_request(
    session_id: AlternatorSessionId,
    table_name: &str,
    index_name: Option<&str>,
    key_condition_expression: &str,
    filter_expression: Option<&str>,
    projection_expression: Option<&str>,
    expression_attribute_names: Option<&HashMap<String, String>>,
    expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
    limit: Option<u32>,
    consistent_read: bool,
    scan_index_forward: bool,
    exclusive_start_key: Option<&HashMap<String, AttributeValue>>,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(512);
    buf.put_u64(session_id);
    write_string(&mut buf, table_name);
    write_optional_string(&mut buf, index_name);
    write_string(&mut buf, key_condition_expression);
    write_optional_string(&mut buf, filter_expression);
    write_optional_string(&mut buf, projection_expression);
    write_expr_attr_names(&mut buf, expression_attribute_names);
    write_expr_attr_values(&mut buf, expression_attribute_values);
    write_optional_u32(&mut buf, limit);
    buf.put_u8(if consistent_read { 1 } else { 0 });
    buf.put_u8(if scan_index_forward { 1 } else { 0 });
    write_optional_key(&mut buf, exclusive_start_key);
    buf
}

#[allow(clippy::too_many_arguments)]
fn encode_scan_request(
    session_id: AlternatorSessionId,
    table_name: &str,
    index_name: Option<&str>,
    filter_expression: Option<&str>,
    projection_expression: Option<&str>,
    expression_attribute_names: Option<&HashMap<String, String>>,
    expression_attribute_values: Option<&HashMap<String, AttributeValue>>,
    limit: Option<u32>,
    consistent_read: bool,
    segment: Option<u32>,
    total_segments: Option<u32>,
    exclusive_start_key: Option<&HashMap<String, AttributeValue>>,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(512);
    buf.put_u64(session_id);
    write_string(&mut buf, table_name);
    write_optional_string(&mut buf, index_name);
    write_optional_string(&mut buf, filter_expression);
    write_optional_string(&mut buf, projection_expression);
    write_expr_attr_names(&mut buf, expression_attribute_names);
    write_expr_attr_values(&mut buf, expression_attribute_values);
    write_optional_u32(&mut buf, limit);
    buf.put_u8(if consistent_read { 1 } else { 0 });
    write_optional_u32(&mut buf, segment);
    write_optional_u32(&mut buf, total_segments);
    write_optional_key(&mut buf, exclusive_start_key);
    buf
}

fn encode_batch_get_request(
    session_id: AlternatorSessionId,
    request_items: &HashMap<String, BatchGetRequestItems>,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(1024);
    buf.put_u64(session_id);
    buf.put_u16(request_items.len() as u16);

    for (table_name, items) in request_items {
        write_string(&mut buf, table_name);
        buf.put_u32(items.keys.len() as u32);
        for key in &items.keys {
            write_key(&mut buf, key);
        }
        buf.put_u8(if items.consistent_read { 1 } else { 0 });
        write_optional_string(&mut buf, items.projection_expression.as_deref());
        write_expr_attr_names(&mut buf, items.expression_attribute_names.as_ref());
    }

    buf
}

fn encode_batch_write_request(
    session_id: AlternatorSessionId,
    request_items: &HashMap<String, Vec<BatchWriteRequest>>,
) -> BytesMut {
    let mut buf = BytesMut::with_capacity(2048);
    buf.put_u64(session_id);
    buf.put_u16(request_items.len() as u16);

    for (table_name, requests) in request_items {
        write_string(&mut buf, table_name);
        buf.put_u32(requests.len() as u32);
        for req in requests {
            match req {
                BatchWriteRequest::Put(item) => {
                    buf.put_u8(0x01);
                    write_item(&mut buf, item);
                }
                BatchWriteRequest::Delete(key) => {
                    buf.put_u8(0x02);
                    write_key(&mut buf, key);
                }
            }
        }
    }

    buf
}

// Write helpers

fn write_string(buf: &mut BytesMut, s: &str) {
    buf.put_u32(s.len() as u32);
    buf.extend_from_slice(s.as_bytes());
}

fn write_optional_string(buf: &mut BytesMut, s: Option<&str>) {
    match s {
        Some(s) => {
            buf.put_u8(1);
            write_string(buf, s);
        }
        None => buf.put_u8(0),
    }
}

fn write_optional_u32(buf: &mut BytesMut, v: Option<u32>) {
    match v {
        Some(v) => {
            buf.put_u8(1);
            buf.put_u32(v);
        }
        None => buf.put_u8(0),
    }
}

fn write_key(buf: &mut BytesMut, key: &HashMap<String, AttributeValue>) {
    buf.put_u16(key.len() as u16);
    for (name, value) in key {
        write_string(buf, name);
        write_attribute_value(buf, value);
    }
}

fn write_optional_key(buf: &mut BytesMut, key: Option<&HashMap<String, AttributeValue>>) {
    match key {
        Some(k) => {
            buf.put_u8(1);
            write_key(buf, k);
        }
        None => buf.put_u8(0),
    }
}

fn write_item(buf: &mut BytesMut, item: &HashMap<String, AttributeValue>) {
    buf.put_u32(item.len() as u32);
    for (name, value) in item {
        write_string(buf, name);
        write_attribute_value(buf, value);
    }
}

fn write_expr_attr_names(buf: &mut BytesMut, names: Option<&HashMap<String, String>>) {
    match names {
        Some(n) => {
            buf.put_u16(n.len() as u16);
            for (key, value) in n {
                write_string(buf, key);
                write_string(buf, value);
            }
        }
        None => buf.put_u16(0),
    }
}

fn write_expr_attr_values(buf: &mut BytesMut, values: Option<&HashMap<String, AttributeValue>>) {
    match values {
        Some(v) => {
            buf.put_u16(v.len() as u16);
            for (key, value) in v {
                write_string(buf, key);
                write_attribute_value(buf, value);
            }
        }
        None => buf.put_u16(0),
    }
}

fn write_optional_condition_expression(
    buf: &mut BytesMut,
    expression: Option<&str>,
    names: Option<&HashMap<String, String>>,
    values: Option<&HashMap<String, AttributeValue>>,
) {
    match expression {
        Some(expr) => {
            buf.put_u8(1);
            write_string(buf, expr);
            write_expr_attr_names(buf, names);
            write_expr_attr_values(buf, values);
        }
        None => buf.put_u8(0),
    }
}

fn write_attribute_value(buf: &mut BytesMut, value: &AttributeValue) {
    match value {
        AttributeValue::Null(_) => buf.put_u8(0x00),
        AttributeValue::Bool(b) => {
            buf.put_u8(0x01);
            buf.put_u8(if *b { 1 } else { 0 });
        }
        AttributeValue::N(n) => {
            buf.put_u8(0x02);
            write_string(buf, n);
        }
        AttributeValue::S(s) => {
            buf.put_u8(0x03);
            write_string(buf, s);
        }
        AttributeValue::B(b) => {
            buf.put_u8(0x04);
            buf.put_u32(b.as_ref().len() as u32);
            buf.extend_from_slice(b.as_ref());
        }
        AttributeValue::Ss(ss) => {
            buf.put_u8(0x05);
            buf.put_u32(ss.len() as u32);
            for s in ss {
                write_string(buf, s);
            }
        }
        AttributeValue::Ns(ns) => {
            buf.put_u8(0x06);
            buf.put_u32(ns.len() as u32);
            for n in ns {
                write_string(buf, n);
            }
        }
        AttributeValue::Bs(bs) => {
            buf.put_u8(0x07);
            buf.put_u32(bs.len() as u32);
            for b in bs {
                buf.put_u32(b.as_ref().len() as u32);
                buf.extend_from_slice(b.as_ref());
            }
        }
        AttributeValue::L(list) => {
            buf.put_u8(0x08);
            buf.put_u32(list.len() as u32);
            for item in list {
                write_attribute_value(buf, item);
            }
        }
        AttributeValue::M(map) => {
            buf.put_u8(0x09);
            buf.put_u32(map.len() as u32);
            for (key, value) in map {
                write_string(buf, key);
                write_attribute_value(buf, value);
            }
        }
        _ => buf.put_u8(0x00), // Unknown types as NULL
    }
}

// Read helpers

fn read_bool(slice: &mut &[u8]) -> Result<bool> {
    if slice.is_empty() {
        bail!("unexpected EOF reading bool");
    }
    Ok(slice.get_u8() != 0)
}

fn read_u8(slice: &mut &[u8]) -> Result<u8> {
    if slice.is_empty() {
        bail!("unexpected EOF reading u8");
    }
    Ok(slice.get_u8())
}

fn read_u16(slice: &mut &[u8]) -> Result<u16> {
    if slice.len() < 2 {
        bail!("unexpected EOF reading u16");
    }
    Ok(slice.get_u16())
}

fn read_u32(slice: &mut &[u8]) -> Result<u32> {
    if slice.len() < 4 {
        bail!("unexpected EOF reading u32");
    }
    Ok(slice.get_u32())
}

fn read_i64(slice: &mut &[u8]) -> Result<i64> {
    if slice.len() < 8 {
        bail!("unexpected EOF reading i64");
    }
    Ok(slice.get_i64())
}

fn read_string(slice: &mut &[u8]) -> Result<String> {
    let len = read_u32(slice)? as usize;
    if slice.len() < len {
        bail!("unexpected EOF reading string body");
    }
    let s = String::from_utf8_lossy(&slice[..len]).to_string();
    slice.advance(len);
    Ok(s)
}

fn read_bytes(slice: &mut &[u8]) -> Result<Vec<u8>> {
    let len = read_u32(slice)? as usize;
    if slice.len() < len {
        bail!("unexpected EOF reading bytes body");
    }
    let b = slice[..len].to_vec();
    slice.advance(len);
    Ok(b)
}

fn read_key(slice: &mut &[u8]) -> Result<HashMap<String, AttributeValue>> {
    let count = read_u16(slice)?;
    let mut key = HashMap::with_capacity(count as usize);
    for _ in 0..count {
        let name = read_string(slice)?;
        let value = read_attribute_value(slice)?;
        key.insert(name, value);
    }
    Ok(key)
}

fn read_item(slice: &mut &[u8]) -> Result<HashMap<String, AttributeValue>> {
    let count = read_u32(slice)?;
    let mut item = HashMap::with_capacity(count as usize);
    for _ in 0..count {
        let name = read_string(slice)?;
        let value = read_attribute_value(slice)?;
        item.insert(name, value);
    }
    Ok(item)
}

fn read_attribute_value(slice: &mut &[u8]) -> Result<AttributeValue> {
    if slice.is_empty() {
        bail!("unexpected EOF reading attribute value type");
    }
    let type_tag = slice.get_u8();

    Ok(match type_tag {
        0x00 => AttributeValue::Null(true),
        0x01 => {
            let b = read_bool(slice)?;
            AttributeValue::Bool(b)
        }
        0x02 => {
            let n = read_string(slice)?;
            AttributeValue::N(n)
        }
        0x03 => {
            let s = read_string(slice)?;
            AttributeValue::S(s)
        }
        0x04 => {
            let b = read_bytes(slice)?;
            AttributeValue::B(aws_sdk_dynamodb::primitives::Blob::new(b))
        }
        0x05 => {
            let count = read_u32(slice)?;
            let mut ss = Vec::with_capacity(count as usize);
            for _ in 0..count {
                ss.push(read_string(slice)?);
            }
            AttributeValue::Ss(ss)
        }
        0x06 => {
            let count = read_u32(slice)?;
            let mut ns = Vec::with_capacity(count as usize);
            for _ in 0..count {
                ns.push(read_string(slice)?);
            }
            AttributeValue::Ns(ns)
        }
        0x07 => {
            let count = read_u32(slice)?;
            let mut bs = Vec::with_capacity(count as usize);
            for _ in 0..count {
                let b = read_bytes(slice)?;
                bs.push(aws_sdk_dynamodb::primitives::Blob::new(b));
            }
            AttributeValue::Bs(bs)
        }
        0x08 => {
            let count = read_u32(slice)?;
            let mut list = Vec::with_capacity(count as usize);
            for _ in 0..count {
                list.push(read_attribute_value(slice)?);
            }
            AttributeValue::L(list)
        }
        0x09 => {
            let count = read_u32(slice)?;
            let mut map = HashMap::with_capacity(count as usize);
            for _ in 0..count {
                let key = read_string(slice)?;
                let value = read_attribute_value(slice)?;
                map.insert(key, value);
            }
            AttributeValue::M(map)
        }
        _ => AttributeValue::Null(true),
    })
}
