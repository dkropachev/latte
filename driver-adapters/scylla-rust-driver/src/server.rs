use crate::config::DriverConfig;
use crate::protocol::{self, ErrorCode, Frame, FrameHeader, Opcode};
use crate::session::{
    parse_batch_frame, parse_execute_frame, parse_prepare_frame, parse_query_frame,
    read_string_map, SessionRegistry,
};
use anyhow::{Context, Result};
use bytes::{BufMut, BytesMut};
use std::sync::Arc;
use std::time::Duration;
use tokio::fs;
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio::net::unix::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{mpsc, Semaphore};

pub async fn run(config: &DriverConfig, sessions: Arc<SessionRegistry>) -> Result<()> {
    if let Some(parent) = config.socket_parent() {
        fs::create_dir_all(parent)
            .await
            .with_context(|| format!("failed to create socket directory at {parent:?}"))?;
    }

    if config.socket_path.exists() {
        fs::remove_file(&config.socket_path)
            .await
            .with_context(|| {
                format!("failed to remove stale socket at {:?}", config.socket_path)
            })?;
    }

    let listener = UnixListener::bind(&config.socket_path)
        .with_context(|| format!("failed to bind unix socket at {:?}", config.socket_path))?;

    // Set socket permissions to allow all users to connect (0666)
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let perms = std::fs::Permissions::from_mode(0o666);
        std::fs::set_permissions(&config.socket_path, perms).with_context(|| {
            format!(
                "failed to set socket permissions at {:?}",
                config.socket_path
            )
        })?;
    }

    tracing::info!(
        path = %config.socket_path.display(),
        inflight_limit = config.inflight_limit,
        contact_points = ?config.contact_points,
        "driver counterpart listening for host"
    );

    loop {
        let (stream, _) = listener
            .accept()
            .await
            .context("failed to accept unix stream")?;
        let sessions = Arc::clone(&sessions);
        let inflight_limit = config.inflight_limit;
        tokio::spawn(async move {
            if let Err(error) = handle_connection(stream, sessions, inflight_limit).await {
                tracing::warn!(?error, "driver connection closed with error");
            }
        });
    }
}

async fn handle_connection(
    stream: UnixStream,
    sessions: Arc<SessionRegistry>,
    inflight_limit: usize,
) -> Result<()> {
    let (reader, writer) = stream.into_split();

    // Channel for sending responses from dispatch tasks to the writer
    // Buffer size matches inflight limit to avoid backpressure on dispatch
    let (response_tx, response_rx) = mpsc::channel::<Frame>(inflight_limit.max(64));

    // Semaphore to limit concurrent in-flight requests
    let semaphore = Arc::new(Semaphore::new(inflight_limit));

    // Spawn writer task
    let writer_handle = tokio::spawn(writer_task(writer, response_rx));

    // Run reader loop (this is the main task)
    let reader_result = reader_loop(reader, sessions, semaphore, response_tx).await;

    // Wait for writer to finish (it will exit when response_tx is dropped)
    let _ = writer_handle.await;

    reader_result
}

async fn reader_loop(
    mut reader: OwnedReadHalf,
    sessions: Arc<SessionRegistry>,
    semaphore: Arc<Semaphore>,
    response_tx: mpsc::Sender<Frame>,
) -> Result<()> {
    let mut buffer = BytesMut::with_capacity(64 * 1024);

    loop {
        let Some(frame) = protocol::read_frame(&mut reader, &mut buffer)
            .await
            .context("failed to read frame")?
        else {
            tracing::info!("latte host disconnected");
            break;
        };

        // Acquire semaphore permit before spawning dispatch task
        // This provides backpressure when too many requests are in flight
        let permit = semaphore
            .clone()
            .acquire_owned()
            .await
            .expect("semaphore closed unexpectedly");

        let sessions = Arc::clone(&sessions);
        let tx = response_tx.clone();

        tokio::spawn(async move {
            let stream_id = frame.header.stream;
            let response = match dispatch(frame, &sessions).await {
                Ok(resp) => resp,
                Err(err) => {
                    tracing::warn!(?err, "dispatch error");
                    protocol::error_frame(stream_id, ErrorCode::Server, &err.to_string())
                }
            };

            // Send response to writer task
            // If the channel is closed, the connection is shutting down
            let _ = tx.send(response).await;

            // Permit is dropped here, releasing the semaphore slot
            drop(permit);
        });
    }

    Ok(())
}

async fn writer_task(writer: OwnedWriteHalf, mut response_rx: mpsc::Receiver<Frame>) {
    let mut writer = BufWriter::with_capacity(64 * 1024, writer);

    loop {
        // Wait for the first response
        let Some(frame) = response_rx.recv().await else {
            break;
        };

        if let Err(err) = write_frame_buffered(&mut writer, &frame).await {
            tracing::warn!(?err, "failed to write response frame");
            break;
        }

        // Try to batch more responses that are immediately available
        loop {
            match response_rx.try_recv() {
                Ok(frame) => {
                    if let Err(err) = write_frame_buffered(&mut writer, &frame).await {
                        tracing::warn!(?err, "failed to write response frame");
                        // Final flush
                        let _ = writer.flush().await;
                        return;
                    }
                    // Flush if buffer is getting large
                    if writer.buffer().len() >= 32 * 1024 {
                        if let Err(err) = writer.flush().await {
                            tracing::warn!(?err, "failed to flush");
                            return;
                        }
                    }
                }
                Err(tokio::sync::mpsc::error::TryRecvError::Empty) => {
                    // No more immediately available - flush what we have for low latency
                    if let Err(err) = writer.flush().await {
                        tracing::warn!(?err, "failed to flush");
                        return;
                    }
                    break;
                }
                Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
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

async fn write_frame_buffered(writer: &mut BufWriter<OwnedWriteHalf>, frame: &Frame) -> Result<()> {
    let mut encoded = BytesMut::with_capacity(protocol::HEADER_LENGTH + frame.body.len());
    encoded.put_u8(protocol::VERSION_RESPONSE);
    encoded.put_u8(frame.header.flags);
    encoded.put_i16(frame.header.stream);
    encoded.put_u8(frame.header.opcode as u8);
    encoded.put_u32(frame.body.len() as u32);
    encoded.extend_from_slice(&frame.body);

    writer.write_all(&encoded).await.context("write failed")?;

    // Flush periodically when buffer is getting full
    // BufWriter will auto-flush when buffer is full, but explicit flush
    // helps maintain low latency for smaller batches
    if writer.buffer().len() > 32 * 1024 {
        writer.flush().await.context("flush failed")?;
    }

    Ok(())
}

async fn dispatch(frame: Frame, sessions: &SessionRegistry) -> Result<Frame> {
    let (mut response, latency) = match frame.header.opcode {
        Opcode::CreateSession => {
            let resp = handle_create_session(frame, sessions).await?;
            (resp, None)
        }
        Opcode::Query => {
            let parsed = parse_query_frame(&frame)?;
            // Lock-free session lookup
            let Some(session) = sessions.get(parsed.session_id) else {
                return Ok(protocol::error_frame(
                    frame.header.stream,
                    ErrorCode::Protocol,
                    &format!("unknown session id {}", parsed.session_id),
                ));
            };

            let (resp, lat) = session.execute_query(frame.header.stream, parsed).await?;
            (resp, Some(lat))
        }
        Opcode::Prepare => {
            let parsed = parse_prepare_frame(&frame)?;
            // Lock-free session lookup
            let Some(session) = sessions.get(parsed.session_id) else {
                return Ok(protocol::error_frame(
                    frame.header.stream,
                    ErrorCode::Protocol,
                    &format!("unknown session id {}", parsed.session_id),
                ));
            };

            let resp = session
                .prepare_statement(frame.header.stream, parsed)
                .await?;
            (resp, None)
        }
        Opcode::Execute => {
            let parsed = parse_execute_frame(&frame)?;
            // Lock-free session lookup
            let Some(session) = sessions.get(parsed.session_id) else {
                return Ok(protocol::error_frame(
                    frame.header.stream,
                    ErrorCode::Protocol,
                    &format!("unknown session id {}", parsed.session_id),
                ));
            };

            let (resp, lat) = session
                .execute_prepared(frame.header.stream, parsed)
                .await?;
            (resp, Some(lat))
        }
        Opcode::Batch => {
            let parsed = parse_batch_frame(&frame)?;
            // Lock-free session lookup
            let Some(session) = sessions.get(parsed.session_id) else {
                return Ok(protocol::error_frame(
                    frame.header.stream,
                    ErrorCode::Protocol,
                    &format!("unknown session id {}", parsed.session_id),
                ));
            };

            let (resp, lat) = session.execute_batch(frame.header.stream, parsed).await?;
            (resp, Some(lat))
        }
        other => {
            return Ok(protocol::error_frame(
                frame.header.stream,
                ErrorCode::Protocol,
                &format!("unsupported opcode {:?}", other),
            ));
        }
    };

    // Append driver latency to response frame body (8 bytes, u64 nanoseconds)
    if let Some(lat) = latency {
        response = append_latency_to_frame(response, lat);
    }

    Ok(response)
}

/// Appends driver latency (8 bytes, u64 nanoseconds) to the end of a frame's body.
fn append_latency_to_frame(frame: Frame, latency: Duration) -> Frame {
    // Saturate at u64::MAX to prevent overflow (would require ~584 years of latency)
    let latency_ns = latency.as_nanos().min(u64::MAX as u128) as u64;
    let mut new_body = BytesMut::with_capacity(frame.body.len() + 8);
    new_body.extend_from_slice(&frame.body);
    new_body.put_u64(latency_ns);

    Frame {
        header: FrameHeader {
            version: frame.header.version,
            flags: frame.header.flags,
            stream: frame.header.stream,
            opcode: frame.header.opcode,
            body_length: new_body.len() as u32,
        },
        body: new_body.freeze(),
    }
}

async fn handle_create_session(frame: Frame, sessions: &SessionRegistry) -> Result<Frame> {
    let mut slice: &[u8] = &frame.body;
    let params = read_string_map(&mut slice)?;
    if !slice.is_empty() {
        anyhow::bail!("unexpected trailing bytes after session params");
    }

    let session_id = sessions.create_session(params).await?;
    Ok(session_created_frame(frame.header.stream, session_id))
}

fn session_created_frame(stream: i16, session_id: u64) -> Frame {
    let mut body = BytesMut::with_capacity(8);
    body.put_u64(session_id);

    Frame {
        header: FrameHeader {
            version: protocol::VERSION_RESPONSE,
            flags: 0,
            stream,
            opcode: Opcode::SessionCreated,
            body_length: body.len() as u32,
        },
        body: body.freeze(),
    }
}
