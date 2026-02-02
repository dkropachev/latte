use crate::protocol::{self, Opcode};
use anyhow::{anyhow, bail, Context, Result};
use bytes::{Buf, BufMut, BytesMut};
use dashmap::DashMap;
use scylla::frame::types::Consistency;
use scylla::value::CqlValue;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI16, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio::net::unix::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::UnixStream;
use tokio::sync::{mpsc, oneshot};

pub type Session = DriverSession;

/// Pending request waiting for a response
struct PendingRequest {
    response_tx: oneshot::Sender<protocol::Frame>,
}

/// Minimal driver handle that manages socket lifecycles and spawns sessions.
pub struct UniversalDriver {
    read_socket_path: PathBuf,
    write_socket_path: PathBuf,
    client: Option<DriverClient>,
}

impl UniversalDriver {
    pub fn new(
        read_socket_path: impl Into<PathBuf>,
        write_socket_path: impl Into<PathBuf>,
    ) -> Self {
        Self {
            read_socket_path: read_socket_path.into(),
            write_socket_path: write_socket_path.into(),
            client: None,
        }
    }

    /// Connect to the configured sockets and prepare the driver client.
    pub async fn start(&mut self) -> Result<()> {
        let read_socket_path = self.read_socket_path.clone();
        let write_socket_path = self.write_socket_path.clone();

        let (reader, writer) = if read_socket_path == write_socket_path {
            let stream = UnixStream::connect(&write_socket_path)
                .await
                .with_context(|| {
                    format!(
                        "failed to connect to driver socket at {}",
                        write_socket_path.display()
                    )
                })?;
            stream.into_split()
        } else {
            let (reader, _) = UnixStream::connect(&read_socket_path)
                .await
                .with_context(|| {
                    format!(
                        "failed to connect to driver read socket at {}",
                        read_socket_path.display()
                    )
                })?
                .into_split();

            let (_, writer) = UnixStream::connect(&write_socket_path)
                .await
                .with_context(|| {
                    format!(
                        "failed to connect to driver write socket at {}",
                        write_socket_path.display()
                    )
                })?
                .into_split();

            (reader, writer)
        };

        self.client = Some(DriverClient::from_split(reader, writer));
        Ok(())
    }

    /// Drop the client connection, closing any open sockets.
    pub fn stop(&mut self) {
        self.client = None;
    }

    /// Create a new remote session using the underlying client.
    pub async fn create_session(&self, params: HashMap<String, String>) -> Result<Session> {
        let client = self
            .client
            .as_ref()
            .context("driver not started; call start() before creating sessions")?;
        client.create_session(params).await
    }
}

/// Client-side helper for speaking the Latte driver counterpart protocol over a Unix socket.
///
/// This client uses multiplexed request/response handling:
/// - Requests are sent via a dedicated writer task (no lock contention)
/// - A background task reads responses and routes them by stream ID
/// - Multiple requests can be in flight simultaneously
#[derive(Clone)]
pub struct DriverClient {
    /// Channel to send requests to the dedicated writer task (lock-free)
    write_tx: mpsc::Sender<BytesMut>,
    /// Lock-free pending request map for high-concurrency response routing
    pending: Arc<DashMap<i16, PendingRequest>>,
    next_stream: Arc<AtomicI16>,
    /// Keep background tasks alive
    _tasks_alive: Arc<()>,
}

impl DriverClient {
    /// Build a client from a connected `UnixStream`, splitting it into owned read/write halves.
    pub fn from_stream(stream: UnixStream) -> Self {
        let (reader, writer) = stream.into_split();
        Self::from_split(reader, writer)
    }

    /// Build a client from separately owned read/write halves. This makes it easy to integrate with
    /// fd-passing setups where the halves are provided individually.
    pub fn from_split(reader: OwnedReadHalf, writer: OwnedWriteHalf) -> Self {
        // Pre-allocate DashMap with expected concurrent request capacity
        let pending: Arc<DashMap<i16, PendingRequest>> = Arc::new(DashMap::with_capacity(256));
        let tasks_alive = Arc::new(());

        // Channel for sending requests to the writer task (bounded to provide backpressure)
        let (write_tx, write_rx) = mpsc::channel::<BytesMut>(256);

        // Spawn background reader task
        let pending_clone = Arc::clone(&pending);
        let tasks_alive_reader = Arc::clone(&tasks_alive);
        tokio::spawn(async move {
            let _keep_alive = tasks_alive_reader;
            reader_task(reader, pending_clone).await;
        });

        // Spawn background writer task (eliminates lock contention on writes)
        let tasks_alive_writer = Arc::clone(&tasks_alive);
        tokio::spawn(async move {
            let _keep_alive = tasks_alive_writer;
            writer_task(writer, write_rx).await;
        });

        Self {
            write_tx,
            pending,
            next_stream: Arc::new(AtomicI16::new(0)),
            _tasks_alive: tasks_alive,
        }
    }

    /// Start a new session builder that mirrors the Scylla driver's `SessionBuilder` API.
    pub fn session_builder(&self) -> DriverSessionBuilder {
        DriverSessionBuilder::new(self.clone())
    }

    async fn create_session(&self, params: HashMap<String, String>) -> Result<Session> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_string_map(&params)?;
        let request = encode_request(Opcode::CreateSession, stream_id, &body);

        let response = self.send_request(stream_id, request).await?;

        if response.header.opcode != Opcode::SessionCreated {
            bail!(
                "expected SessionCreated opcode, got {:?}",
                response.header.opcode
            );
        }

        if response.body.len() != 8 {
            bail!(
                "unexpected SessionCreated body length: {}",
                response.body.len()
            );
        }

        let mut slice = response.body.clone();
        let session_id = slice.get_u64();
        Ok(DriverSession {
            client: self.clone(),
            session_id,
        })
    }

    async fn send_request(&self, stream_id: i16, request: BytesMut) -> Result<protocol::Frame> {
        // Create response channel
        let (response_tx, response_rx) = oneshot::channel();

        // Register pending request before sending (lock-free insert)
        self.pending.insert(stream_id, PendingRequest { response_tx });

        // Send request to writer task (lock-free channel send)
        self.write_tx
            .send(request)
            .await
            .map_err(|_| anyhow!("writer task closed"))?;

        // Wait for response
        response_rx
            .await
            .map_err(|_| anyhow!("reader task closed before response received"))
    }

    async fn send_query(
        &self,
        session_id: u64,
        query: &str,
        consistency: Consistency,
    ) -> Result<protocol::Frame> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_query_body(session_id, query, consistency);
        let request = encode_request(Opcode::Query, stream_id, &body);
        self.send_request(stream_id, request).await
    }

    async fn send_prepare(
        &self,
        session_id: u64,
        query: &str,
        statement_key: &str,
    ) -> Result<protocol::Frame> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_prepare_body(session_id, query, statement_key);
        let request = encode_request(Opcode::Prepare, stream_id, &body);
        self.send_request(stream_id, request).await
    }

    async fn send_execute(
        &self,
        session_id: u64,
        statement_key: &str,
        consistency: Consistency,
        values: &[CqlValue],
    ) -> Result<protocol::Frame> {
        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let body = encode_execute_body(session_id, statement_key, consistency, values)?;
        let request = encode_request(Opcode::Execute, stream_id, &body);
        self.send_request(stream_id, request).await
    }
}

/// Background task that reads responses and routes them to waiting callers
async fn reader_task(mut reader: OwnedReadHalf, pending: Arc<DashMap<i16, PendingRequest>>) {
    let mut buffer = BytesMut::with_capacity(64 * 1024);

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
        let pending_req = pending.remove(&stream_id);

        if let Some((_, req)) = pending_req {
            // Send response to waiting caller (ignore if receiver dropped)
            let _ = req.response_tx.send(frame);
        } else {
            tracing::warn!(stream_id, "received response for unknown stream");
        }
    }

    // Clean up any remaining pending requests (lock-free clear)
    pending.clear();
}

/// Background task that batches and writes requests to the socket
async fn writer_task(writer: OwnedWriteHalf, mut write_rx: mpsc::Receiver<BytesMut>) {
    let mut writer = BufWriter::with_capacity(64 * 1024, writer);

    loop {
        // Wait for the first request
        let Some(request) = write_rx.recv().await else {
            break;
        };

        if let Err(err) = writer.write_all(&request).await {
            tracing::warn!(?err, "writer task: failed to write request");
            break;
        }

        // Try to batch more requests that are immediately available
        loop {
            match write_rx.try_recv() {
                Ok(request) => {
                    if let Err(err) = writer.write_all(&request).await {
                        tracing::warn!(?err, "writer task: failed to write request");
                        let _ = writer.flush().await;
                        return;
                    }
                    // Flush if buffer is getting large
                    if writer.buffer().len() >= 32 * 1024 {
                        if let Err(err) = writer.flush().await {
                            tracing::warn!(?err, "writer task: failed to flush");
                            return;
                        }
                    }
                }
                Err(mpsc::error::TryRecvError::Empty) => {
                    // No more immediately available - flush what we have for low latency
                    if let Err(err) = writer.flush().await {
                        tracing::warn!(?err, "writer task: failed to flush");
                        return;
                    }
                    break;
                }
                Err(mpsc::error::TryRecvError::Disconnected) => {
                    // Channel closed, flush and exit
                    let _ = writer.flush().await;
                    return;
                }
            }
        }
    }

    // Flush any remaining buffered data
    let _ = writer.flush().await;
}

/// Builder that closely mirrors the Scylla driver's `SessionBuilder`, but serializes parameters
/// into a string map for the driver counterpart to hydrate.
pub struct DriverSessionBuilder {
    client: DriverClient,
    contact_points: Vec<String>,
    keyspace: Option<String>,
    username: Option<String>,
    password: Option<String>,
    /// Catch-all for additional tuning parameters to forward as strings.
    extra: HashMap<String, String>,
}

impl DriverSessionBuilder {
    fn new(client: DriverClient) -> Self {
        Self {
            client,
            contact_points: Vec::new(),
            keyspace: None,
            username: None,
            password: None,
            extra: HashMap::new(),
        }
    }

    /// Add a single contact point, mirroring `SessionBuilder::known_node`.
    pub fn known_node(mut self, node: impl Into<String>) -> Self {
        self.contact_points.push(node.into());
        self
    }

    /// Add a list of contact points, mirroring `SessionBuilder::known_nodes`.
    pub fn known_nodes<I, S>(mut self, nodes: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        for node in nodes {
            self.contact_points.push(node.into());
        }
        self
    }

    /// Set the keyspace, mirroring `SessionBuilder::use_keyspace`.
    pub fn use_keyspace(mut self, keyspace: impl Into<String>, _case_sensitive: bool) -> Self {
        self.keyspace = Some(keyspace.into());
        self
    }

    /// Set authentication credentials, mirroring `SessionBuilder::user`.
    pub fn user(mut self, username: impl Into<String>, password: impl Into<String>) -> Self {
        self.username = Some(username.into());
        self.password = Some(password.into());
        self
    }

    /// Attach an arbitrary parameter by key/value to match additional `SessionBuilder` setters.
    pub fn with_param(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.extra.insert(key.into(), value.into());
        self
    }

    fn into_params(self) -> HashMap<String, String> {
        let mut map = self.extra;
        if !self.contact_points.is_empty() {
            map.insert("contact_points".to_string(), self.contact_points.join(","));
        }
        if let Some(keyspace) = self.keyspace {
            map.insert("keyspace".to_string(), keyspace);
        }
        if let (Some(user), Some(pass)) = (self.username, self.password) {
            map.insert("username".to_string(), user);
            map.insert("password".to_string(), pass);
        }
        map
    }

    /// Build a remote session by sending a `CreateSession` request to the counterpart.
    pub async fn build(self) -> Result<Session> {
        let client = self.client.clone();
        let params = self.into_params();
        client.create_session(params).await
    }
}

/// Client-side session wrapper. It carries the remote session id and exposes a familiar query API.
#[derive(Clone)]
pub struct DriverSession {
    client: DriverClient,
    session_id: u64,
}

impl DriverSession {
    /// Returns the underlying remote session id assigned by the counterpart.
    pub fn id(&self) -> u64 {
        self.session_id
    }

    /// Execute a simple, unpaged query with the provided consistency level.
    ///
    /// This mirrors the ergonomics of `Session::query_unpaged` while returning the raw CQL frame so
    /// callers can decode it as needed.
    pub async fn query_unpaged(
        &self,
        query: impl AsRef<str>,
        consistency: Consistency,
    ) -> Result<protocol::Frame> {
        self.client
            .send_query(self.session_id, query.as_ref(), consistency)
            .await
    }

    /// Prepare a CQL statement and cache it under the given key.
    ///
    /// Returns the raw RESULT frame with kind=PREPARED.
    pub async fn prepare(
        &self,
        statement_key: &str,
        query: impl AsRef<str>,
    ) -> Result<protocol::Frame> {
        let frame = self
            .client
            .send_prepare(self.session_id, query.as_ref(), statement_key)
            .await?;

        if frame.header.opcode == Opcode::Error {
            let error_msg = decode_error_message(&frame.body);
            bail!("prepare failed: {}", error_msg);
        }

        Ok(frame)
    }

    /// Execute a previously prepared statement by its key with the given values.
    ///
    /// Returns the raw RESULT frame.
    pub async fn execute(
        &self,
        statement_key: &str,
        values: &[CqlValue],
        consistency: Consistency,
    ) -> Result<protocol::Frame> {
        let frame = self
            .client
            .send_execute(self.session_id, statement_key, consistency, values)
            .await?;

        if frame.header.opcode == Opcode::Error {
            let error_msg = decode_error_message(&frame.body);
            bail!("execute failed: {}", error_msg);
        }

        Ok(frame)
    }
}

fn encode_request(opcode: Opcode, stream: i16, body: &[u8]) -> BytesMut {
    let mut buf = BytesMut::with_capacity(protocol::HEADER_LENGTH + body.len());
    buf.put_u8(protocol::VERSION_REQUEST);
    buf.put_u8(0);
    buf.put_i16(stream);
    buf.put_u8(opcode as u8);
    buf.put_u32(body.len() as u32);
    buf.extend_from_slice(body);
    buf
}

fn encode_query_body(session_id: u64, query: &str, consistency: Consistency) -> BytesMut {
    // Pre-allocate: 8 (session_id) + 4 (query len) + query + 2 (consistency) + 1 (flags)
    let mut body = BytesMut::with_capacity(15 + query.len());
    body.put_u64(session_id);
    write_long_string(query, &mut body).expect("query length fits in frame");
    body.put_u16(consistency as u16);
    body.put_u8(0); // flags
    body
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

fn write_string(value: &str, buf: &mut BytesMut) -> Result<()> {
    let len: u16 = value
        .len()
        .try_into()
        .map_err(|err| anyhow!("string too long for CQL map: {err}"))?;
    buf.put_u16(len);
    buf.extend_from_slice(value.as_bytes());
    Ok(())
}

fn write_long_string(value: &str, buf: &mut BytesMut) -> Result<()> {
    let len: i32 = value
        .len()
        .try_into()
        .map_err(|err| anyhow!("length does not fit in i32: {err}"))?;
    buf.put_i32(len);
    buf.extend_from_slice(value.as_bytes());
    Ok(())
}

fn encode_prepare_body(session_id: u64, query: &str, statement_key: &str) -> BytesMut {
    // Pre-allocate: 8 (session_id) + 4 (query len) + query + 2 (key len) + key
    let mut body = BytesMut::with_capacity(14 + query.len() + statement_key.len());
    body.put_u64(session_id);
    write_long_string(query, &mut body).expect("query length fits in frame");
    write_string(statement_key, &mut body).expect("statement key fits in frame");
    body
}

fn encode_execute_body(
    session_id: u64,
    statement_key: &str,
    consistency: Consistency,
    values: &[CqlValue],
) -> Result<BytesMut> {
    // Pre-allocate: 8 (session_id) + 2 (key len) + key + 2 (consistency) + 1 (flags) + 2 (value count) + ~32 per value
    let estimated_values_size = values.len() * 36;
    let mut body = BytesMut::with_capacity(15 + statement_key.len() + estimated_values_size);
    body.put_u64(session_id);
    write_string(statement_key, &mut body)?;
    body.put_u16(consistency as u16);

    // Flags: bit 0 = values present
    let flags: u8 = if values.is_empty() { 0 } else { 0x01 };
    body.put_u8(flags);

    if !values.is_empty() {
        encode_values(values, &mut body)?;
    }

    Ok(body)
}

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

fn encode_value(value: &CqlValue, buf: &mut BytesMut) -> Result<()> {
    match value {
        CqlValue::TinyInt(v) => {
            buf.put_i32(1);
            buf.put_i8(*v);
        }
        CqlValue::SmallInt(v) => {
            buf.put_i32(2);
            buf.put_i16(*v);
        }
        CqlValue::Int(v) => {
            buf.put_i32(4);
            buf.put_i32(*v);
        }
        CqlValue::BigInt(v) => {
            buf.put_i32(8);
            buf.put_i64(*v);
        }
        CqlValue::Text(v) | CqlValue::Ascii(v) => {
            let bytes = v.as_bytes();
            let len: i32 = bytes
                .len()
                .try_into()
                .map_err(|err| anyhow!("text value too long: {err}"))?;
            buf.put_i32(len);
            buf.extend_from_slice(bytes);
        }
        CqlValue::Boolean(v) => {
            buf.put_i32(1);
            buf.put_u8(if *v { 1 } else { 0 });
        }
        CqlValue::Float(v) => {
            buf.put_i32(4);
            buf.extend_from_slice(&v.to_be_bytes());
        }
        CqlValue::Double(v) => {
            buf.put_i32(8);
            buf.extend_from_slice(&v.to_be_bytes());
        }
        CqlValue::Uuid(v) => {
            buf.put_i32(16);
            buf.extend_from_slice(v.as_bytes());
        }
        CqlValue::Timeuuid(v) => {
            buf.put_i32(16);
            buf.extend_from_slice(v.as_bytes());
        }
        CqlValue::Blob(v) => {
            let len: i32 = v
                .len()
                .try_into()
                .map_err(|err| anyhow!("blob value too long: {err}"))?;
            buf.put_i32(len);
            buf.extend_from_slice(v);
        }
        CqlValue::Timestamp(v) => {
            buf.put_i32(8);
            buf.put_i64(v.0);
        }
        CqlValue::Date(v) => {
            buf.put_i32(4);
            buf.put_u32(v.0);
        }
        CqlValue::Time(v) => {
            buf.put_i32(8);
            buf.put_i64(v.0);
        }
        CqlValue::Inet(v) => match v {
            std::net::IpAddr::V4(ip) => {
                buf.put_i32(4);
                buf.extend_from_slice(&ip.octets());
            }
            std::net::IpAddr::V6(ip) => {
                buf.put_i32(16);
                buf.extend_from_slice(&ip.octets());
            }
        },
        CqlValue::Counter(v) => {
            buf.put_i32(8);
            buf.put_i64(v.0);
        }
        CqlValue::Empty => {
            buf.put_i32(-1); // null
        }
        other => {
            bail!("unsupported CqlValue type for encoding: {:?}", other);
        }
    }
    Ok(())
}

fn decode_error_message(body: &bytes::Bytes) -> String {
    if body.len() < 6 {
        return "unknown error (body too short)".to_string();
    }
    let mut slice = body.clone();
    let _error_code = slice.get_u32();
    let msg_len = slice.get_u16() as usize;
    if slice.len() < msg_len {
        return "unknown error (message truncated)".to_string();
    }
    String::from_utf8_lossy(&slice[..msg_len]).to_string()
}
