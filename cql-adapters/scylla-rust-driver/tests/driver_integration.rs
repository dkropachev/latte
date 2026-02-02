// Allow unused helper functions in tests - they may be used in future tests
#![allow(dead_code)]

use anyhow::{anyhow, Context};
use bytes::{Buf, BufMut, BytesMut};
use driver_counterpart::config::DriverConfig;
use driver_counterpart::driver_integration::DriverClient;
use driver_counterpart::protocol::{self, Opcode};
use driver_counterpart::server;
use driver_counterpart::session::{DriverSession, SessionRegistry};
use scylla::frame::types::Consistency;
use std::collections::HashMap;
use std::env;
use std::path::PathBuf;
use std::sync::Arc;
use tempfile::tempdir;
use tokio::io::AsyncWriteExt;
use tokio::net::UnixListener;

const TYPE_INT: u16 = 0x0009;
const TYPE_TEXT: u16 = 0x000D;

fn write_short(value: u16, buf: &mut BytesMut) {
    buf.put_u16(value);
}

fn write_int(value: i32, buf: &mut BytesMut) {
    buf.put_i32(value);
}

fn write_int_length(len: usize, buf: &mut BytesMut) -> anyhow::Result<()> {
    let len: i32 = len
        .try_into()
        .map_err(|err| anyhow::anyhow!("length does not fit in i32: {err}"))?;
    write_int(len, buf);
    Ok(())
}

fn write_long_string(value: &str, buf: &mut BytesMut) -> anyhow::Result<()> {
    write_int_length(value.len(), buf)?;
    buf.extend_from_slice(value.as_bytes());
    Ok(())
}

fn write_string(value: &str, buf: &mut BytesMut) -> anyhow::Result<()> {
    let len: u16 = value
        .len()
        .try_into()
        .map_err(|err| anyhow!("string too long for CQL map: {err}"))?;
    buf.put_u16(len);
    buf.extend_from_slice(value.as_bytes());
    Ok(())
}

fn read_short(slice: &mut &[u8]) -> anyhow::Result<u16> {
    if slice.len() < 2 {
        anyhow::bail!("unexpected EOF reading short");
    }
    let (head, rest) = slice.split_at(2);
    *slice = rest;
    Ok(u16::from_be_bytes([head[0], head[1]]))
}

fn read_int(slice: &mut &[u8]) -> anyhow::Result<i32> {
    if slice.len() < 4 {
        anyhow::bail!("unexpected EOF reading int");
    }
    let (head, rest) = slice.split_at(4);
    *slice = rest;
    Ok(i32::from_be_bytes([head[0], head[1], head[2], head[3]]))
}

fn read_string(slice: &mut &[u8]) -> anyhow::Result<String> {
    let len = read_short(slice)? as usize;
    if slice.len() < len {
        anyhow::bail!("unexpected EOF reading string body");
    }
    let (head, rest) = slice.split_at(len);
    *slice = rest;
    Ok(String::from_utf8(head.to_vec())?)
}

fn build_config(socket_path: PathBuf) -> DriverConfig {
    DriverConfig {
        socket_path,
        inflight_limit: 8,
    }
}

fn build_connect_params(contact_points: Vec<String>) -> HashMap<String, String> {
    let mut params = HashMap::new();
    params.insert("contact_points".to_string(), contact_points.join(","));
    if let Ok(ks) = env::var("SCYLLA_KEYSPACE") {
        params.insert("keyspace".to_string(), ks);
    }
    params
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

fn decode_rows(frame: protocol::Frame) -> anyhow::Result<Vec<(i32, String)>> {
    let mut slice: &[u8] = &frame.body;

    let kind = read_int(&mut slice)?;
    if kind != 0x0002 {
        anyhow::bail!("expected ROWS kind, got {kind:#x}");
    }

    let flags = read_int(&mut slice)?;
    let global_tables = flags & 0x0001 != 0;
    let column_count = read_int(&mut slice)?;

    let (global_ks, global_table) = if global_tables {
        let ks = read_string(&mut slice)?;
        let table = read_string(&mut slice)?;
        (Some(ks), Some(table))
    } else {
        (None, None)
    };

    let mut columns = Vec::new();
    for _ in 0..column_count {
        let keyspace = if let Some(ks) = &global_ks {
            ks.clone()
        } else {
            read_string(&mut slice)?
        };
        let table = if let Some(tbl) = &global_table {
            tbl.clone()
        } else {
            read_string(&mut slice)?
        };
        let name = read_string(&mut slice)?;
        let typ = read_short(&mut slice)?;
        columns.push((keyspace, table, name, typ));
    }

    let rows_count = read_int(&mut slice)?;
    let mut rows = Vec::new();
    for _ in 0..rows_count {
        let mut id_val: Option<i32> = None;
        let mut text_val: Option<String> = None;
        for (_ks, _tbl, _col, typ) in &columns {
            let len = read_int(&mut slice)?;
            if len < 0 {
                continue;
            }
            let len = len as usize;
            if slice.len() < len {
                anyhow::bail!("truncated row payload");
            }
            let (value_slice, rest) = slice.split_at(len);
            slice = rest;

            match *typ {
                TYPE_INT => {
                    let mut bytes_slice: &[u8] = value_slice;
                    let v = read_int(&mut bytes_slice)?;
                    id_val = Some(v);
                }
                TYPE_TEXT => {
                    let text = String::from_utf8(value_slice.to_vec())?;
                    text_val = Some(text);
                }
                other => anyhow::bail!("unexpected column type {other:#x}"),
            }
        }

        let id = id_val.context("missing int column")?;
        let text = text_val.context("missing text column")?;
        rows.push((id, text));
    }

    Ok(rows)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rejects_unsupported_opcode_and_connects_to_scylla() -> anyhow::Result<()> {
    let dir = tempdir()?;
    let socket_path = dir.path().join("driver.sock");

    // Create a listener to accept the server's connection (server is now a client)
    let listener = UnixListener::bind(&socket_path)?;

    let config = build_config(socket_path.clone());
    let sessions = Arc::new(SessionRegistry::new());

    let server_config = config.clone();
    let server_task =
        tokio::spawn(async move { server::run(&server_config, Arc::clone(&sessions)).await });

    // Accept the server's connection
    let (mut client, _) = listener.accept().await?;

    let request = encode_request(Opcode::Startup, 5, &[]);
    client.write_all(&request).await?;

    let mut read_buf = BytesMut::with_capacity(1024);
    let response = protocol::read_frame(&mut client, &mut read_buf)
        .await?
        .expect("server closed connection");

    assert_eq!(response.header.stream, 5);
    assert_eq!(response.header.opcode, Opcode::Error);

    let mut body = response.body.clone();
    let error_code = body.get_u32();
    assert_eq!(error_code, protocol::ErrorCode::Protocol.as_u32());

    server_task.abort();
    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn connects_and_reads_release_version() -> anyhow::Result<()> {
    let contact_points = match env::var("SCYLLA_CONTACT_POINTS") {
        Ok(raw) if !raw.trim().is_empty() => raw
            .split(',')
            .map(|v| v.trim().to_string())
            .collect::<Vec<_>>(),
        _ => {
            eprintln!("SCYLLA_CONTACT_POINTS not set; skipping integration test");
            return Ok(());
        }
    };

    let params = build_connect_params(contact_points);
    let session = DriverSession::connect(&params).await?;

    let result = session
        .session()
        .query_unpaged("SELECT release_version FROM system.local", &[])
        .await?;
    let rows_result = result
        .into_rows_result()
        .context("system.local query returned no rows")?;
    let mut iter = rows_result.rows::<(String,)>()?;
    let maybe_row = iter
        .next()
        .transpose()
        .context("failed to decode release_version row")?;
    let version = maybe_row.context("no rows from system.local")?.0;

    assert!(
        !version.trim().is_empty(),
        "expected non-empty release_version"
    );

    Ok(())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn runs_queries_over_unix_socket() -> anyhow::Result<()> {
    let contact_points = match env::var("SCYLLA_CONTACT_POINTS") {
        Ok(raw) if !raw.trim().is_empty() => raw
            .split(',')
            .map(|v| v.trim().to_string())
            .collect::<Vec<_>>(),
        _ => {
            eprintln!("SCYLLA_CONTACT_POINTS not set; skipping integration test");
            return Ok(());
        }
    };

    let dir = tempdir()?;
    let socket_path = dir.path().join("driver.sock");

    // Create a listener to accept the server's connection (server is now a client)
    let listener = UnixListener::bind(&socket_path)?;

    let config = build_config(socket_path.clone());
    let sessions = Arc::new(SessionRegistry::new());

    let server_config = config.clone();
    let server_task =
        tokio::spawn(async move { server::run(&server_config, Arc::clone(&sessions)).await });

    // Accept the server's connection and create a client from it
    let (stream, _) = listener.accept().await?;
    let client = DriverClient::from_stream(stream);

    let mut builder = client.session_builder();
    for cp in &contact_points {
        builder = builder.known_node(cp);
    }
    if let Ok(ks) = env::var("SCYLLA_KEYSPACE") {
        builder = builder.use_keyspace(ks, false);
    }
    let session = builder
        .build()
        .await
        .context("failed to create session over driver socket")?;

    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let ks = format!("latte_test_{ts}");
    let table = format!("{}.roundtrip", ks);

    let create_ks = format!(
        "CREATE KEYSPACE IF NOT EXISTS {ks} WITH replication = {{'class': 'SimpleStrategy', 'replication_factor': 1}}"
    );
    let create_table =
        format!("CREATE TABLE IF NOT EXISTS {table} (id int PRIMARY KEY, value text)");

    for query in [create_ks.as_str(), create_table.as_str()] {
        let response = session
            .query_unpaged(query, Consistency::One)
            .await
            .context("failed to run schema statement over driver socket")?;
        assert_eq!(response.header.opcode, Opcode::Result);
        // Body is 4 bytes (VOID kind) + 8 bytes (driver latency) = 12 bytes
        assert_eq!(response.body.len(), 12);
    }

    let insert_one = format!("INSERT INTO {table} (id, value) VALUES (1, 'alpha')");
    let insert_two = format!("INSERT INTO {table} (id, value) VALUES (2, 'beta')");

    for query in [insert_one.as_str(), insert_two.as_str()] {
        let response = session
            .query_unpaged(query, Consistency::One)
            .await
            .context("failed to run insert over driver socket")?;
        assert_eq!(response.header.opcode, Opcode::Result);
    }

    let select = format!("SELECT id, value FROM {table}");
    let select_response = session
        .query_unpaged(&select, Consistency::One)
        .await
        .context("failed to select rows over driver socket")?;
    assert_eq!(select_response.header.opcode, Opcode::Result);
    let mut rows = decode_rows(select_response)?;
    rows.sort_by_key(|(id, _)| *id);
    assert_eq!(rows, vec![(1, "alpha".into()), (2, "beta".into())]);

    drop(client);
    server_task.abort();
    Ok(())
}
