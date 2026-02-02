use crate::config::DriverConfig;
use crate::protocol::{self, ErrorCode, Frame, FrameHeader, Opcode, VERSION_RESPONSE};
use anyhow::{anyhow, Context, Result};
use bytes::{BufMut, Bytes, BytesMut};
use chrono::Timelike;
use dashmap::DashMap;
use scylla::client::execution_profile::ExecutionProfile;
use scylla::client::session::Session;
use scylla::client::session_builder::SessionBuilder;
use scylla::client::PoolSize;
use scylla::frame::response::result::{ColumnSpec, ColumnType, NativeType};
use scylla::frame::types::Consistency;
use scylla::policies::load_balancing::DefaultPolicy;
use scylla::response::query_result::IntoRowsResultError;
use scylla::statement::prepared::PreparedStatement;
use scylla::statement::unprepared::Statement;
use scylla::value::{CqlValue, Row};
use std::collections::HashMap;
use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tracing::info;

#[derive(Clone)]
pub struct DriverSession {
    inner: Arc<Session>,
    /// Lock-free prepared statement cache for high-concurrency execute paths.
    prepared_cache: Arc<DashMap<String, CachedPrepared>>,
}

#[derive(Clone)]
pub struct CachedPrepared {
    pub statement: Arc<PreparedStatement>,
    pub bind_types: Arc<[ColumnType<'static>]>,
}

impl DriverSession {
    pub async fn connect(config: &DriverConfig) -> Result<Self> {
        Self::connect_with_params(config, &Default::default()).await
    }

    pub async fn connect_with_params(
        config: &DriverConfig,
        params: &HashMap<String, String>,
    ) -> Result<Self> {
        let mut builder = SessionBuilder::new();

        // === Contact points ===
        let contact_points = params
            .get("contact_points")
            .map(|raw| parse_contact_points(raw))
            .transpose()?
            .unwrap_or_else(|| config.contact_points.clone());

        if contact_points.is_empty() {
            anyhow::bail!("contact points are required to create a session");
        }

        for contact_point in &contact_points {
            builder = builder.known_node(contact_point);
        }

        // === Keyspace ===
        let keyspace = params
            .get("keyspace")
            .cloned()
            .or_else(|| config.keyspace.clone());
        if let Some(keyspace) = &keyspace {
            builder = builder.use_keyspace(keyspace, false);
        }

        // === Authentication ===
        let username = params.get("username");
        let password = params.get("password");
        match (username, password) {
            (Some(user), Some(pass)) => {
                builder = builder.user(user, pass);
            }
            (Some(_), None) => {
                tracing::warn!("username provided without password, authentication disabled");
            }
            (None, Some(_)) => {
                tracing::warn!("password provided without username, authentication disabled");
            }
            (None, None) => {}
        }

        // === Connection pool size ===
        if let Some(count_str) = params.get("connections_per_shard") {
            match count_str.parse::<usize>() {
                Ok(0) => {
                    tracing::warn!(
                        connections_per_shard = %count_str,
                        "connections_per_shard must be positive, ignoring"
                    );
                }
                Ok(count) => {
                    if let Some(count) = NonZeroUsize::new(count) {
                        builder = builder.pool_size(PoolSize::PerShard(count));
                    }
                }
                Err(e) => {
                    tracing::warn!(
                        connections_per_shard = %count_str,
                        error = %e,
                        "failed to parse connections_per_shard, ignoring"
                    );
                }
            }
        }

        // === Build execution profile for DC/rack awareness, timeouts, and consistency ===
        let datacenter = params.get("datacenter").cloned();
        let rack = params.get("rack").cloned();
        let request_timeout_ms = params
            .get("request_timeout_ms")
            .and_then(|v| v.parse::<u64>().ok());
        let consistency = params.get("consistency").and_then(|v| parse_consistency(v));
        let serial_consistency = params
            .get("serial_consistency")
            .and_then(|v| parse_serial_consistency(v));

        // Validate rack requires datacenter
        if rack.is_some() && datacenter.is_none() {
            anyhow::bail!("rack parameter requires datacenter to be specified");
        }

        // Only create a custom execution profile if we have custom settings
        if datacenter.is_some()
            || request_timeout_ms.is_some()
            || consistency.is_some()
            || serial_consistency.is_some()
        {
            let mut profile_builder = ExecutionProfile::builder();

            // DC/rack-aware load balancing
            if let Some(dc) = &datacenter {
                let lb_builder = if let Some(r) = &rack {
                    // Both datacenter and rack specified
                    DefaultPolicy::builder()
                        .prefer_datacenter_and_rack(dc.clone(), r.clone())
                        .permit_dc_failover(true)
                } else {
                    // Only datacenter specified
                    DefaultPolicy::builder()
                        .prefer_datacenter(dc.clone())
                        .permit_dc_failover(true)
                };
                profile_builder = profile_builder.load_balancing_policy(lb_builder.build());
            }

            // Request timeout
            if let Some(timeout_ms) = request_timeout_ms {
                profile_builder =
                    profile_builder.request_timeout(Some(Duration::from_millis(timeout_ms)));
            }

            // Default consistency
            if let Some(c) = consistency {
                profile_builder = profile_builder.consistency(c);
            }

            // Serial consistency for LWT
            if let Some(sc) = serial_consistency {
                profile_builder = profile_builder.serial_consistency(Some(sc));
            }

            builder =
                builder.default_execution_profile_handle(profile_builder.build().into_handle());
        }

        let inner = builder
            .build()
            .await
            .context("failed to establish scylla session")?;

        info!(
            contact_points = ?contact_points,
            keyspace = ?keyspace,
            datacenter = ?datacenter,
            rack = ?rack,
            request_timeout_ms = ?request_timeout_ms,
            consistency = ?params.get("consistency"),
            "connected to scylla cluster"
        );

        Ok(Self {
            inner: Arc::new(inner),
            prepared_cache: Arc::new(DashMap::new()),
        })
    }

    pub async fn execute_query(
        &self,
        stream: i16,
        parsed: ParsedQuery,
    ) -> Result<(Frame, Duration)> {
        let mut statement = Statement::new(parsed.query);
        statement.set_consistency(parsed.consistency);

        let start = Instant::now();
        let result = self
            .inner
            .query_unpaged(statement, &[])
            .await
            .context("query execution failed")?;
        let latency = start.elapsed();

        match result.into_rows_result() {
            Ok(rows_result) => Ok((build_rows_frame(stream, &rows_result)?, latency)),
            Err(IntoRowsResultError::ResultNotRows(_non_rows)) => Ok((void_frame(stream), latency)),
            Err(other) => Err(anyhow!(other).context("failed to decode rows result")),
        }
    }

    pub async fn prepare_statement(&self, stream: i16, parsed: ParsedPrepare) -> Result<Frame> {
        let prepared = self
            .inner
            .prepare(parsed.query.clone())
            .await
            .context("failed to prepare statement")?;

        // Extract bind variable types from prepared statement metadata
        let bind_types: Arc<[ColumnType<'static>]> = prepared
            .get_variable_col_specs()
            .iter()
            .map(|spec| spec.typ().clone().into_owned())
            .collect();

        let prepared = Arc::new(prepared);

        // Cache the prepared statement by key along with bind types (lock-free insert)
        self.prepared_cache.insert(
            parsed.statement_key.clone(),
            CachedPrepared {
                statement: Arc::clone(&prepared),
                bind_types,
            },
        );

        build_prepared_frame(stream, &parsed.statement_key, &prepared)
    }

    pub async fn execute_prepared(
        &self,
        stream: i16,
        parsed: ParsedExecute,
    ) -> Result<(Frame, Duration)> {
        // Lock-free lookup from DashMap - clone only the Arc pointers, not the underlying data
        let cached = match self.prepared_cache.get(&parsed.statement_key) {
            Some(ref_guard) => CachedPrepared {
                statement: Arc::clone(&ref_guard.statement),
                bind_types: Arc::clone(&ref_guard.bind_types),
            },
            None => {
                return Ok((
                    protocol::error_frame(
                        stream,
                        ErrorCode::Unprepared,
                        &format!("statement '{}' not prepared", parsed.statement_key),
                    ),
                    Duration::ZERO,
                ));
            }
        };

        let mut stmt = cached.statement.as_ref().clone();
        stmt.set_consistency(parsed.consistency);

        // Convert raw values to typed values using the bind_types from the cached prepared statement
        let typed_values = convert_to_typed_values(&parsed.raw_values, &cached.bind_types)?;

        let start = Instant::now();
        let result = self
            .inner
            .execute_unpaged(&stmt, &typed_values)
            .await
            .map_err(|e| {
                tracing::error!(
                    statement_key = %parsed.statement_key,
                    num_values = typed_values.len(),
                    error = %e,
                    "execute failed"
                );
                e
            })
            .with_context(|| {
                format!(
                    "execute failed for statement '{}' with {} values",
                    parsed.statement_key,
                    typed_values.len()
                )
            })?;
        let latency = start.elapsed();

        match result.into_rows_result() {
            Ok(rows_result) => Ok((build_rows_frame(stream, &rows_result)?, latency)),
            Err(IntoRowsResultError::ResultNotRows(_non_rows)) => Ok((void_frame(stream), latency)),
            Err(other) => Err(anyhow!(other).context("failed to decode rows result")),
        }
    }

    pub async fn execute_batch(
        &self,
        stream: i16,
        parsed: ParsedBatch,
    ) -> Result<(Frame, Duration)> {
        use scylla::statement::batch::{Batch, BatchType};

        // Determine batch type
        let batch_type = match parsed.batch_type {
            0 => BatchType::Logged,
            1 => BatchType::Unlogged,
            2 => BatchType::Counter,
            _ => BatchType::Logged,
        };

        let mut batch = Batch::new(batch_type);
        batch.set_consistency(parsed.consistency);

        let mut all_values: Vec<Vec<Option<CqlValue>>> =
            Vec::with_capacity(parsed.statements.len());

        // Collect prepared statements and convert values (lock-free lookups)
        for stmt in &parsed.statements {
            let Some(cached) = self.prepared_cache.get(&stmt.statement_key) else {
                return Ok((
                    protocol::error_frame(
                        stream,
                        ErrorCode::Unprepared,
                        &format!("statement '{}' not prepared", stmt.statement_key),
                    ),
                    Duration::ZERO,
                ));
            };

            batch.append_statement(cached.statement.as_ref().clone());
            let typed_values = convert_to_typed_values(&stmt.raw_values, &cached.bind_types)?;
            all_values.push(typed_values);
        }

        // Execute the batch
        let start = Instant::now();
        self.inner
            .batch(&batch, &all_values)
            .await
            .context("batch execution failed")?;
        let latency = start.elapsed();

        Ok((void_frame(stream), latency))
    }

    #[allow(dead_code)]
    pub fn session(&self) -> &Session {
        self.inner.as_ref()
    }
}

#[derive(Debug, Clone)]
pub struct ParsedQuery {
    pub session_id: u64,
    pub query: String,
    pub consistency: Consistency,
}

#[derive(Debug, Clone)]
pub struct ParsedPrepare {
    pub session_id: u64,
    pub query: String,
    pub statement_key: String,
}

#[derive(Debug, Clone)]
pub struct ParsedExecute {
    pub session_id: u64,
    pub statement_key: String,
    pub consistency: Consistency,
    pub raw_values: Vec<RawValue>,
}

/// A single statement in a batch
#[derive(Debug, Clone)]
pub struct BatchStatement {
    pub statement_key: String,
    pub raw_values: Vec<RawValue>,
}

#[derive(Debug, Clone)]
pub struct ParsedBatch {
    pub session_id: u64,
    pub batch_type: u8,
    pub statements: Vec<BatchStatement>,
    pub consistency: Consistency,
}

/// Raw value bytes from the wire - includes type code for proper decoding.
/// Uses zero-copy slicing from the original frame body.
#[derive(Debug, Clone)]
pub enum RawValue {
    Null { type_code: u16 },
    Bytes { type_code: u16, data: Bytes },
}

/// Zero-copy reader that tracks position in a Bytes buffer.
/// Enables extracting sub-slices without copying data.
struct BytesReader {
    data: Bytes,
    pos: usize,
}

impl BytesReader {
    fn new(data: Bytes) -> Self {
        Self { data, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    /// Take a zero-copy slice of n bytes from the current position.
    fn take_bytes(&mut self, n: usize) -> Result<Bytes> {
        if self.remaining() < n {
            anyhow::bail!("unexpected EOF: need {} bytes, have {}", n, self.remaining());
        }
        let slice = self.data.slice(self.pos..self.pos + n);
        self.pos += n;
        Ok(slice)
    }

    fn read_u8(&mut self) -> Result<u8> {
        if self.remaining() < 1 {
            anyhow::bail!("unexpected EOF reading u8");
        }
        let v = self.data[self.pos];
        self.pos += 1;
        Ok(v)
    }

    fn read_u16(&mut self) -> Result<u16> {
        if self.remaining() < 2 {
            anyhow::bail!("unexpected EOF reading u16");
        }
        let v = u16::from_be_bytes([self.data[self.pos], self.data[self.pos + 1]]);
        self.pos += 2;
        Ok(v)
    }

    fn read_i32(&mut self) -> Result<i32> {
        if self.remaining() < 4 {
            anyhow::bail!("unexpected EOF reading i32");
        }
        let v = i32::from_be_bytes([
            self.data[self.pos],
            self.data[self.pos + 1],
            self.data[self.pos + 2],
            self.data[self.pos + 3],
        ]);
        self.pos += 4;
        Ok(v)
    }

    fn read_i64(&mut self) -> Result<i64> {
        if self.remaining() < 8 {
            anyhow::bail!("unexpected EOF reading i64");
        }
        let v = i64::from_be_bytes([
            self.data[self.pos],
            self.data[self.pos + 1],
            self.data[self.pos + 2],
            self.data[self.pos + 3],
            self.data[self.pos + 4],
            self.data[self.pos + 5],
            self.data[self.pos + 6],
            self.data[self.pos + 7],
        ]);
        self.pos += 8;
        Ok(v)
    }

    fn read_string(&mut self) -> Result<String> {
        let len = self.read_u16()? as usize;
        let bytes = self.take_bytes(len)?;
        String::from_utf8(bytes.to_vec()).context("invalid UTF-8 in string")
    }
}

pub fn parse_query_frame(frame: &Frame) -> Result<ParsedQuery> {
    let mut slice: &[u8] = &frame.body;
    let session_id =
        read_long(&mut slice).context("failed to decode session id from QUERY body")?;

    let query = read_long_string(&mut slice)
        .context("failed to decode query string from QUERY body")?
        .to_owned();

    let raw_consistency =
        read_short(&mut slice).context("failed to read consistency from QUERY body")?;
    let consistency = Consistency::try_from(raw_consistency).unwrap_or(Consistency::One);

    let flags = slice
        .first()
        .copied()
        .ok_or_else(|| anyhow!("missing flags in QUERY body"))?;
    if flags != 0 {
        anyhow::bail!("unsupported QUERY flags: {flags:#x}");
    }

    Ok(ParsedQuery {
        session_id,
        query,
        consistency,
    })
}

pub fn parse_prepare_frame(frame: &Frame) -> Result<ParsedPrepare> {
    let mut slice: &[u8] = &frame.body;

    let session_id =
        read_long(&mut slice).context("failed to decode session id from PREPARE body")?;

    let query = read_long_string(&mut slice)
        .context("failed to decode query string from PREPARE body")?
        .to_owned();

    let statement_key =
        read_string(&mut slice).context("failed to decode statement key from PREPARE body")?;

    Ok(ParsedPrepare {
        session_id,
        query,
        statement_key,
    })
}

pub fn parse_execute_frame(frame: &Frame) -> Result<ParsedExecute> {
    // Use zero-copy BytesReader for parsing values
    let mut reader = BytesReader::new(frame.body.clone());

    let session_id = reader
        .read_i64()
        .context("failed to decode session id from EXECUTE body")? as u64;

    let statement_key = reader
        .read_string()
        .context("failed to decode statement key from EXECUTE body")?;

    let raw_consistency = reader
        .read_u16()
        .context("failed to read consistency from EXECUTE body")?;
    let consistency = Consistency::try_from(raw_consistency).unwrap_or(Consistency::One);

    let flags = reader.read_u8().context("missing flags in EXECUTE body")?;

    // Zero-copy value parsing - values reference the original frame body
    let raw_values = if flags & 0x01 != 0 {
        decode_raw_values_zerocopy(&mut reader)?
    } else {
        Vec::new()
    };

    Ok(ParsedExecute {
        session_id,
        statement_key,
        consistency,
        raw_values,
    })
}

pub fn parse_batch_frame(frame: &Frame) -> Result<ParsedBatch> {
    // Use zero-copy BytesReader for parsing values
    let mut reader = BytesReader::new(frame.body.clone());

    let session_id = reader
        .read_i64()
        .context("failed to decode session id from BATCH body")? as u64;

    let batch_type = reader
        .read_u8()
        .context("missing batch type in BATCH body")?;

    let n_statements = reader
        .read_u16()
        .context("failed to read statement count from BATCH body")? as usize;

    let mut statements = Vec::with_capacity(n_statements);

    for _ in 0..n_statements {
        let kind = reader
            .read_u8()
            .context("missing statement kind in BATCH body")?;

        if kind != 1 {
            anyhow::bail!(
                "only prepared statements (kind=1) are supported in batch, got kind={}",
                kind
            );
        }

        let statement_key = reader
            .read_string()
            .context("failed to decode statement key from BATCH body")?;

        // Zero-copy value parsing
        let raw_values = decode_raw_values_zerocopy(&mut reader)?;

        statements.push(BatchStatement {
            statement_key,
            raw_values,
        });
    }

    let raw_consistency = reader
        .read_u16()
        .context("failed to read consistency from BATCH body")?;
    let consistency = Consistency::try_from(raw_consistency).unwrap_or(Consistency::One);

    // Flags byte follows but we don't use batch flags - no need to read it
    // since we're done parsing and returning immediately

    Ok(ParsedBatch {
        session_id,
        batch_type,
        statements,
        consistency,
    })
}

/// Decode raw values from BytesReader using zero-copy slicing.
/// Values reference the original frame body without copying.
fn decode_raw_values_zerocopy(reader: &mut BytesReader) -> Result<Vec<RawValue>> {
    let count = reader.read_u16()? as usize;
    let mut values = Vec::with_capacity(count);

    for _ in 0..count {
        let value = decode_raw_value_zerocopy(reader)?;
        values.push(value);
    }

    Ok(values)
}

/// Zero-copy version of decode_raw_value - uses Bytes::slice() instead of copy.
fn decode_raw_value_zerocopy(reader: &mut BytesReader) -> Result<RawValue> {
    let type_code = reader.read_u16()?;
    let len = reader.read_i32()?;

    if len < 0 {
        return Ok(RawValue::Null { type_code });
    }

    let data = reader.take_bytes(len as usize)?;
    Ok(RawValue::Bytes { type_code, data })
}

/// Convert raw wire values to typed CqlValue using the type codes sent with each value.
/// Uses bind_types to handle type mismatches (e.g., when latte sends Double but column is Float,
/// or when latte sends BigInt but column is Varint/Decimal).
fn convert_to_typed_values(
    raw_values: &[RawValue],
    bind_types: &Arc<[ColumnType<'static>]>,
) -> Result<Vec<Option<CqlValue>>> {
    let mut typed_values = Vec::with_capacity(raw_values.len());

    for (i, raw) in raw_values.iter().enumerate() {
        let typed = match raw {
            RawValue::Null { .. } => None,
            RawValue::Bytes { type_code, data } => {
                // Check if we need to convert based on actual column type
                let bind_type = bind_types.get(i);
                let value = match (type_code, bind_type) {
                    // Double from wire but column expects Float - convert
                    (0x0007, Some(ColumnType::Native(NativeType::Float))) if data.len() == 8 => {
                        let d = f64::from_be_bytes(data.as_ref().try_into().unwrap());
                        CqlValue::Float(d as f32)
                    }
                    // BigInt from wire but column expects Varint - convert
                    (0x0002, Some(ColumnType::Native(NativeType::Varint))) => {
                        let v = read_int_flexible(data)?;
                        CqlValue::Varint(scylla::value::CqlVarint::from_signed_bytes_be(
                            v.to_be_bytes().to_vec(),
                        ))
                    }
                    // BigInt from wire but column expects Decimal - convert (scale=0)
                    (0x0002, Some(ColumnType::Native(NativeType::Decimal))) => {
                        let v = read_int_flexible(data)?;
                        CqlValue::Decimal(
                            scylla::value::CqlDecimal::from_signed_be_bytes_and_exponent(
                                v.to_be_bytes().to_vec(),
                                0,
                            ),
                        )
                    }
                    // Text from wire but column expects Decimal - parse decimal string
                    (0x000D, Some(ColumnType::Native(NativeType::Decimal))) => {
                        let s =
                            std::str::from_utf8(data).context("invalid UTF-8 in decimal string")?;
                        let decimal = rust_decimal::Decimal::from_str_exact(s).map_err(|e| {
                            anyhow::anyhow!("invalid decimal string '{}': {}", s, e)
                        })?;
                        CqlValue::Decimal(
                            scylla::value::CqlDecimal::from_signed_be_bytes_and_exponent(
                                decimal.mantissa().to_be_bytes().to_vec(),
                                decimal.scale().try_into().unwrap(),
                            ),
                        )
                    }
                    // Text from wire but column expects Date - parse date string "YYYY-MM-DD"
                    (0x000D, Some(ColumnType::Native(NativeType::Date))) => {
                        let s =
                            std::str::from_utf8(data).context("invalid UTF-8 in date string")?;
                        let date = chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d")
                            .map_err(|e| anyhow::anyhow!("invalid date string '{}': {}", s, e))?;
                        let epoch = chrono::NaiveDate::from_ymd_opt(1970, 1, 1).unwrap();
                        let days = (date - epoch).num_days();
                        let cql_date = (days + (1i64 << 31)) as u32;
                        CqlValue::Date(scylla::value::CqlDate(cql_date))
                    }
                    // Text from wire but column expects Time - parse time string "HH:MM:SS"
                    (0x000D, Some(ColumnType::Native(NativeType::Time))) => {
                        let s =
                            std::str::from_utf8(data).context("invalid UTF-8 in time string")?;
                        let time = chrono::NaiveTime::parse_from_str(s, "%H:%M:%S")
                            .or_else(|_| chrono::NaiveTime::parse_from_str(s, "%-H:%-M:%-S"))
                            .map_err(|e| anyhow::anyhow!("invalid time string '{}': {}", s, e))?;
                        let nanos = time.num_seconds_from_midnight() as i64 * 1_000_000_000
                            + time.nanosecond() as i64;
                        CqlValue::Time(scylla::value::CqlTime(nanos))
                    }
                    // Text from wire but column expects Duration - parse duration string
                    (0x000D, Some(ColumnType::Native(NativeType::Duration))) => {
                        let s = std::str::from_utf8(data)
                            .context("invalid UTF-8 in duration string")?;
                        let duration = parse_duration_string(s)?;
                        CqlValue::Duration(duration)
                    }
                    // Text from wire but column expects Inet - parse IP address string
                    (0x000D, Some(ColumnType::Native(NativeType::Inet))) => {
                        let s =
                            std::str::from_utf8(data).context("invalid UTF-8 in inet string")?;
                        let addr: std::net::IpAddr = s
                            .parse()
                            .map_err(|e| anyhow::anyhow!("invalid inet string '{}': {}", s, e))?;
                        CqlValue::Inet(addr)
                    }
                    // Text from wire but column expects Timeuuid - parse UUID string
                    (0x000D, Some(ColumnType::Native(NativeType::Timeuuid))) => {
                        let s = std::str::from_utf8(data)
                            .context("invalid UTF-8 in timeuuid string")?;
                        let uuid = uuid::Uuid::parse_str(s).map_err(|e| {
                            anyhow::anyhow!("invalid timeuuid string '{}': {}", s, e)
                        })?;
                        CqlValue::Timeuuid(scylla::value::CqlTimeuuid::from_bytes(
                            uuid.into_bytes(),
                        ))
                    }
                    // BigInt from wire but column expects Timestamp - convert
                    (0x0002, Some(ColumnType::Native(NativeType::Timestamp))) => {
                        let v = read_int_flexible(data)?;
                        CqlValue::Timestamp(scylla::value::CqlTimestamp(v))
                    }
                    // BigInt from wire but column expects Time - convert
                    (0x0002, Some(ColumnType::Native(NativeType::Time))) => {
                        let v = read_int_flexible(data)?;
                        CqlValue::Time(scylla::value::CqlTime(v))
                    }
                    // BigInt from wire but column expects Counter - convert
                    (0x0002, Some(ColumnType::Native(NativeType::Counter))) => {
                        let v = read_int_flexible(data)?;
                        CqlValue::Counter(scylla::value::Counter(v))
                    }
                    // BigInt from wire but column expects smaller integer types - convert with bounds check
                    (0x0002, Some(ColumnType::Native(NativeType::Int))) => {
                        let v = read_int_flexible(data)?;
                        let v_i32: i32 = v.try_into().map_err(|_| {
                            anyhow::anyhow!("BigInt value {} exceeds Int range", v)
                        })?;
                        CqlValue::Int(v_i32)
                    }
                    (0x0002, Some(ColumnType::Native(NativeType::SmallInt))) => {
                        let v = read_int_flexible(data)?;
                        let v_i16: i16 = v.try_into().map_err(|_| {
                            anyhow::anyhow!("BigInt value {} exceeds SmallInt range", v)
                        })?;
                        CqlValue::SmallInt(v_i16)
                    }
                    (0x0002, Some(ColumnType::Native(NativeType::TinyInt))) => {
                        let v = read_int_flexible(data)?;
                        let v_i8: i8 = v.try_into().map_err(|_| {
                            anyhow::anyhow!("BigInt value {} exceeds TinyInt range", v)
                        })?;
                        CqlValue::TinyInt(v_i8)
                    }
                    // List from wire - coerce elements to match expected type
                    (0x0020, Some(ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::List(elem_type), .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::List(elements) = decoded {
                            let coerced = elements.into_iter()
                                .map(|e| coerce_value(e, elem_type))
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::List(coerced)
                        } else {
                            decoded
                        }
                    }
                    // List from wire but column expects Set - convert List to Set and coerce elements
                    (0x0020, Some(ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(elem_type), .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::List(elements) = decoded {
                            let coerced = elements.into_iter()
                                .map(|e| coerce_value(e, elem_type))
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::Set(coerced)
                        } else {
                            decoded
                        }
                    }
                    // Packed float vector list from wire - coerce elements to match expected type
                    (0x0032, Some(ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::List(elem_type), .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::List(elements) = decoded {
                            let coerced = elements.into_iter()
                                .map(|e| coerce_value(e, elem_type))
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::List(coerced)
                        } else {
                            decoded
                        }
                    }
                    // Packed float vector list from wire but column expects Set
                    (0x0032, Some(ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(elem_type), .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::List(elements) = decoded {
                            let coerced = elements.into_iter()
                                .map(|e| coerce_value(e, elem_type))
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::Set(coerced)
                        } else {
                            decoded
                        }
                    }
                    // Set from wire - coerce elements to match expected type
                    (0x0022, Some(ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(elem_type), .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::Set(elements) = decoded {
                            let coerced = elements.into_iter()
                                .map(|e| coerce_value(e, elem_type))
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::Set(coerced)
                        } else {
                            decoded
                        }
                    }
                    // Map from wire - coerce keys and values to match expected types
                    (0x0021, Some(ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Map(key_type, val_type), .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::Map(entries) = decoded {
                            let coerced = entries.into_iter()
                                .map(|(k, v)| {
                                    let ck = coerce_value(k, key_type)?;
                                    let cv = coerce_value(v, val_type)?;
                                    Ok((ck, cv))
                                })
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::Map(coerced)
                        } else {
                            decoded
                        }
                    }
                    // Tuple from wire - coerce elements to match expected types
                    (0x0031, Some(ColumnType::Tuple(elem_types))) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::Tuple(elements) = decoded {
                            let coerced: Vec<Option<CqlValue>> = elements.into_iter()
                                .zip(elem_types.iter())
                                .map(|(elem, expected_type)| {
                                    match elem {
                                        Some(v) => Ok(Some(coerce_value(v, expected_type)?)),
                                        None => Ok(None),
                                    }
                                })
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::Tuple(coerced)
                        } else {
                            decoded
                        }
                    }
                    // UDT from wire - coerce to match expected UDT with correct name/keyspace
                    (0x0040, Some(ColumnType::UserDefinedType { definition, .. })) => {
                        let decoded = decode_typed_value_by_code(data, *type_code)?;
                        if let CqlValue::UserDefinedType { fields, .. } = decoded {
                            // Build a map of field_name -> field_value from the decoded UDT
                            let field_map: std::collections::HashMap<String, Option<CqlValue>> =
                                fields.into_iter().collect();

                            // Coerce each field value to match expected field type, matching by name
                            let coerced_fields: Vec<(String, Option<CqlValue>)> = definition.field_types.iter()
                                .map(|(expected_name, expected_type)| {
                                    let expected_name_str = expected_name.clone().into_owned();
                                    let field_value = field_map.get(&expected_name_str).cloned().flatten();
                                    let coerced_value = match field_value {
                                        Some(v) => Some(coerce_value(v, expected_type)?),
                                        None => None,
                                    };
                                    Ok((expected_name_str, coerced_value))
                                })
                                .collect::<Result<Vec<_>>>()?;
                            CqlValue::UserDefinedType {
                                keyspace: definition.keyspace.clone().into_owned(),
                                name: definition.name.clone().into_owned(),
                                fields: coerced_fields,
                            }
                        } else {
                            decoded
                        }
                    }
                    // Default: use the type code from wire
                    _ => decode_typed_value_by_code(data, *type_code)?,
                };
                Some(value)
            }
        };
        typed_values.push(typed);
    }

    Ok(typed_values)
}

/// Coerce a CqlValue to match an expected column type
fn coerce_value(value: CqlValue, expected: &ColumnType<'_>) -> Result<CqlValue> {
    match (value, expected) {
        // BigInt to smaller int types
        (CqlValue::BigInt(v), ColumnType::Native(NativeType::Int)) => {
            let v_i32: i32 = v.try_into().map_err(|_| anyhow!("BigInt {} exceeds Int range", v))?;
            Ok(CqlValue::Int(v_i32))
        }
        (CqlValue::BigInt(v), ColumnType::Native(NativeType::SmallInt)) => {
            let v_i16: i16 = v.try_into().map_err(|_| anyhow!("BigInt {} exceeds SmallInt range", v))?;
            Ok(CqlValue::SmallInt(v_i16))
        }
        (CqlValue::BigInt(v), ColumnType::Native(NativeType::TinyInt)) => {
            let v_i8: i8 = v.try_into().map_err(|_| anyhow!("BigInt {} exceeds TinyInt range", v))?;
            Ok(CqlValue::TinyInt(v_i8))
        }
        // Int to smaller int types
        (CqlValue::Int(v), ColumnType::Native(NativeType::SmallInt)) => {
            let v_i16: i16 = v.try_into().map_err(|_| anyhow!("Int {} exceeds SmallInt range", v))?;
            Ok(CqlValue::SmallInt(v_i16))
        }
        (CqlValue::Int(v), ColumnType::Native(NativeType::TinyInt)) => {
            let v_i8: i8 = v.try_into().map_err(|_| anyhow!("Int {} exceeds TinyInt range", v))?;
            Ok(CqlValue::TinyInt(v_i8))
        }
        // Nested List
        (CqlValue::List(elements), ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::List(inner), .. }) => {
            let coerced = elements.into_iter()
                .map(|e| coerce_value(e, inner))
                .collect::<Result<Vec<_>>>()?;
            Ok(CqlValue::List(coerced))
        }
        // Nested Set
        (CqlValue::Set(elements), ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(inner), .. }) => {
            let coerced = elements.into_iter()
                .map(|e| coerce_value(e, inner))
                .collect::<Result<Vec<_>>>()?;
            Ok(CqlValue::Set(coerced))
        }
        // Nested Map
        (CqlValue::Map(entries), ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Map(kt, vt), .. }) => {
            let coerced = entries.into_iter()
                .map(|(k, v)| {
                    let ck = coerce_value(k, kt)?;
                    let cv = coerce_value(v, vt)?;
                    Ok((ck, cv))
                })
                .collect::<Result<Vec<_>>>()?;
            Ok(CqlValue::Map(coerced))
        }
        // Tuple coercion - coerce each element to match expected type
        (CqlValue::Tuple(elements), ColumnType::Tuple(elem_types)) => {
            let coerced: Vec<Option<CqlValue>> = elements.into_iter()
                .zip(elem_types.iter())
                .map(|(elem, expected_type)| {
                    match elem {
                        Some(v) => Ok(Some(coerce_value(v, expected_type)?)),
                        None => Ok(None),
                    }
                })
                .collect::<Result<Vec<_>>>()?;
            Ok(CqlValue::Tuple(coerced))
        }
        // UDT coercion - update name/keyspace to match expected type and coerce fields
        (CqlValue::UserDefinedType { fields, .. }, ColumnType::UserDefinedType { definition, .. }) => {
            // Build a map of field_name -> field_value from the incoming UDT
            let field_map: std::collections::HashMap<String, Option<CqlValue>> =
                fields.into_iter().collect();

            // Coerce each field value to match expected field type, matching by name
            let coerced_fields: Vec<(String, Option<CqlValue>)> = definition.field_types.iter()
                .map(|(expected_name, expected_type)| {
                    let expected_name_str = expected_name.clone().into_owned();
                    let field_value = field_map.get(&expected_name_str).cloned().flatten();
                    let coerced_value = match field_value {
                        Some(v) => Some(coerce_value(v, expected_type)?),
                        None => None,
                    };
                    Ok((expected_name_str, coerced_value))
                })
                .collect::<Result<Vec<_>>>()?;
            Ok(CqlValue::UserDefinedType {
                keyspace: definition.keyspace.clone().into_owned(),
                name: definition.name.clone().into_owned(),
                fields: coerced_fields,
            })
        }
        // No coercion needed - return as is
        (v, _) => Ok(v),
    }
}

/// Decode a value using the CQL type code
fn decode_typed_value_by_code(data: &[u8], type_code: u16) -> Result<CqlValue> {
    match type_code {
        0x0001 => {
            // ASCII
            Ok(CqlValue::Ascii(
                std::str::from_utf8(data)
                    .context("invalid ASCII string")?
                    .to_owned(),
            ))
        }
        0x0002 => {
            // BigInt
            let val = read_int_flexible(data)?;
            Ok(CqlValue::BigInt(val))
        }
        0x0003 => {
            // Blob
            Ok(CqlValue::Blob(data.to_vec()))
        }
        0x0004 => {
            // Boolean
            if data.is_empty() {
                anyhow::bail!("empty boolean value");
            }
            Ok(CqlValue::Boolean(data[0] != 0))
        }
        0x0005 => {
            // Counter
            let val = read_int_flexible(data)?;
            Ok(CqlValue::Counter(scylla::value::Counter(val)))
        }
        0x0007 => {
            // Double
            if data.len() != 8 {
                anyhow::bail!("invalid double length: {}", data.len());
            }
            Ok(CqlValue::Double(f64::from_be_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x0008 => {
            // Float
            if data.len() != 4 {
                anyhow::bail!("invalid float length: {}", data.len());
            }
            Ok(CqlValue::Float(f32::from_be_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x0009 => {
            // Int
            let val = read_int_flexible(data)?;
            Ok(CqlValue::Int(val as i32))
        }
        0x000B => {
            // Timestamp
            let val = read_int_flexible(data)?;
            Ok(CqlValue::Timestamp(scylla::value::CqlTimestamp(val)))
        }
        0x000C => {
            // UUID
            if data.len() != 16 {
                anyhow::bail!("invalid uuid length: {}", data.len());
            }
            Ok(CqlValue::Uuid(uuid::Uuid::from_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x000D => {
            // Text/Varchar
            Ok(CqlValue::Text(
                std::str::from_utf8(data)
                    .context("invalid UTF-8 string")?
                    .to_owned(),
            ))
        }
        0x000F => {
            // Timeuuid
            if data.len() != 16 {
                anyhow::bail!("invalid timeuuid length: {}", data.len());
            }
            Ok(CqlValue::Timeuuid(scylla::value::CqlTimeuuid::from_bytes(
                data.try_into().unwrap(),
            )))
        }
        0x0010 => {
            // Inet
            match data.len() {
                4 => Ok(CqlValue::Inet(std::net::IpAddr::V4(
                    std::net::Ipv4Addr::from(<[u8; 4]>::try_from(data).unwrap()),
                ))),
                16 => Ok(CqlValue::Inet(std::net::IpAddr::V6(
                    std::net::Ipv6Addr::from(<[u8; 16]>::try_from(data).unwrap()),
                ))),
                _ => anyhow::bail!("invalid inet length: {}", data.len()),
            }
        }
        0x0011 => {
            // Date
            if data.len() != 4 {
                anyhow::bail!("invalid date length: {}", data.len());
            }
            Ok(CqlValue::Date(scylla::value::CqlDate(u32::from_be_bytes(
                data.try_into().unwrap(),
            ))))
        }
        0x0012 => {
            // Time
            if data.len() != 8 {
                anyhow::bail!("invalid time length: {}", data.len());
            }
            Ok(CqlValue::Time(scylla::value::CqlTime(i64::from_be_bytes(
                data.try_into().unwrap(),
            ))))
        }
        0x0013 => {
            // SmallInt
            let val = read_int_flexible(data)?;
            Ok(CqlValue::SmallInt(val as i16))
        }
        0x0014 => {
            // TinyInt
            let val = read_int_flexible(data)?;
            Ok(CqlValue::TinyInt(val as i8))
        }
        0x0020 => {
            // List - data format: [subtype: u16] [optional: vector_subtype: u16, dimension: u16] [n_elements: i32] [elements...]
            // Each element is [length: i32] [data: bytes]
            let mut slice: &[u8] = data;
            let subtype = read_short(&mut slice)?;

            // For list<vector<...>>, we need to read vector metadata
            let (vector_subtype, vector_dim) = if subtype == 0x0030 {
                let vs = read_short(&mut slice)?;
                let vd = read_short(&mut slice)?;
                (Some(vs), Some(vd))
            } else {
                (None, None)
            };

            let n_elements = read_int(&mut slice)? as usize;
            let mut elements = Vec::with_capacity(n_elements);

            for _ in 0..n_elements {
                let elem_len = read_int(&mut slice)?;
                if elem_len < 0 {
                    // null element in list - skip
                    continue;
                }
                let elem_len = elem_len as usize;
                if slice.len() < elem_len {
                    anyhow::bail!("unexpected EOF reading list element");
                }
                let (elem_data, rest) = slice.split_at(elem_len);
                slice = rest;

                // Decode element based on subtype
                let elem = if subtype == 0x0030 && vector_subtype == Some(0x0008) {
                    // Vector<float> element - data is contiguous floats
                    let dim = vector_dim.unwrap_or(0) as usize;
                    let expected_len = dim * 4;
                    if elem_data.len() != expected_len {
                        anyhow::bail!("vector element size mismatch: expected {}, got {}", expected_len, elem_data.len());
                    }
                    let mut vec_elements = Vec::with_capacity(dim);
                    for i in 0..dim {
                        let f = f32::from_be_bytes(elem_data[i*4..(i+1)*4].try_into().unwrap());
                        vec_elements.push(CqlValue::Float(f));
                    }
                    CqlValue::Vector(vec_elements)
                } else {
                    decode_typed_value_by_code(elem_data, subtype)?
                };
                elements.push(elem);
            }
            Ok(CqlValue::List(elements))
        }
        0x0030 => {
            // Vector - data format: [subtype: u16] [dimension: u16] [data: contiguous element bytes]
            // For vector<float, N>, data is N * 4 bytes of big-endian floats
            let mut slice: &[u8] = data;
            let subtype = read_short(&mut slice)?;
            let dimension = read_short(&mut slice)? as usize;

            if subtype != 0x0008 {
                anyhow::bail!("only vector<float> is currently supported, got subtype {:#x}", subtype);
            }

            let expected_data_len = dimension * 4;
            if slice.len() != expected_data_len {
                anyhow::bail!("vector data size mismatch: expected {}, got {}", expected_data_len, slice.len());
            }

            let mut elements = Vec::with_capacity(dimension);
            for i in 0..dimension {
                let f = f32::from_be_bytes(slice[i*4..(i+1)*4].try_into().unwrap());
                elements.push(CqlValue::Float(f));
            }
            Ok(CqlValue::Vector(elements))
        }
        0x0032 => {
            // PACKED_FLOAT_VECTOR_LIST - optimized format for list<vector<float, N>>
            // Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
            // No per-element length prefixes - all vectors have the same dimension
            let mut slice: &[u8] = data;
            let n_elements = read_int(&mut slice)? as usize;
            let dimension = read_short(&mut slice)? as usize;

            let expected_data_len = n_elements * dimension * 4;
            if slice.len() != expected_data_len {
                anyhow::bail!(
                    "packed vector list data size mismatch: expected {} ({}*{}*4), got {}",
                    expected_data_len, n_elements, dimension, slice.len()
                );
            }

            let mut vectors = Vec::with_capacity(n_elements);
            for vec_idx in 0..n_elements {
                let vec_start = vec_idx * dimension * 4;
                let mut vec_elements = Vec::with_capacity(dimension);
                for i in 0..dimension {
                    let offset = vec_start + i * 4;
                    let f = f32::from_be_bytes(slice[offset..offset + 4].try_into().unwrap());
                    vec_elements.push(CqlValue::Float(f));
                }
                vectors.push(CqlValue::Vector(vec_elements));
            }
            Ok(CqlValue::List(vectors))
        }
        0x0021 => {
            // Map - data format: [key_type: u16] [value_type: u16] [n_entries: i32] [entries...]
            // Each entry is [key_length: i32] [key_data] [value_length: i32] [value_data]
            let mut slice: &[u8] = data;
            let key_type = read_short(&mut slice)?;
            let value_type = read_short(&mut slice)?;
            let n_entries = read_int(&mut slice)? as usize;
            let mut entries = Vec::with_capacity(n_entries);

            for _ in 0..n_entries {
                // Read key
                let key_len = read_int(&mut slice)?;
                if key_len < 0 {
                    anyhow::bail!("null key in map");
                }
                let key_len = key_len as usize;
                if slice.len() < key_len {
                    anyhow::bail!("unexpected EOF reading map key");
                }
                let (key_data, rest) = slice.split_at(key_len);
                slice = rest;
                let key = decode_typed_value_by_code(key_data, key_type)?;

                // Read value
                let val_len = read_int(&mut slice)?;
                let value = if val_len < 0 {
                    CqlValue::Empty
                } else {
                    let val_len = val_len as usize;
                    if slice.len() < val_len {
                        anyhow::bail!("unexpected EOF reading map value");
                    }
                    let (val_data, rest) = slice.split_at(val_len);
                    slice = rest;
                    decode_typed_value_by_code(val_data, value_type)?
                };

                entries.push((key, value));
            }
            Ok(CqlValue::Map(entries))
        }
        0x0022 => {
            // Set - data format: [subtype: u16] [n_elements: i32] [elements...]
            // Each element is [length: i32] [data: bytes]
            let mut slice: &[u8] = data;
            let subtype = read_short(&mut slice)?;
            let n_elements = read_int(&mut slice)? as usize;
            let mut elements = Vec::with_capacity(n_elements);

            for _ in 0..n_elements {
                let elem_len = read_int(&mut slice)?;
                if elem_len < 0 {
                    continue; // null element in set - skip
                }
                let elem_len = elem_len as usize;
                if slice.len() < elem_len {
                    anyhow::bail!("unexpected EOF reading set element");
                }
                let (elem_data, rest) = slice.split_at(elem_len);
                slice = rest;
                let elem = decode_typed_value_by_code(elem_data, subtype)?;
                elements.push(elem);
            }
            Ok(CqlValue::Set(elements))
        }
        0x0031 => {
            // Tuple - data format: [n_elements: u16] [element_types: u16 * n] [elements...]
            // Each element is [length: i32] [data: bytes] (or length=-1 for null)
            let mut slice: &[u8] = data;
            let n_elements = read_short(&mut slice)? as usize;

            // Read element types
            let mut elem_types = Vec::with_capacity(n_elements);
            for _ in 0..n_elements {
                elem_types.push(read_short(&mut slice)?);
            }

            // Read element data
            let mut elements = Vec::with_capacity(n_elements);
            for elem_type in elem_types {
                let elem_len = read_int(&mut slice)?;
                if elem_len < 0 {
                    elements.push(None);
                } else {
                    let elem_len = elem_len as usize;
                    if slice.len() < elem_len {
                        anyhow::bail!("unexpected EOF reading tuple element");
                    }
                    let (elem_data, rest) = slice.split_at(elem_len);
                    slice = rest;
                    let elem = decode_typed_value_by_code(elem_data, elem_type)?;
                    elements.push(Some(elem));
                }
            }
            Ok(CqlValue::Tuple(elements))
        }
        0x0040 => {
            // UDT - data format: [n_fields: u16] [field_name_len: u16] [field_name] [field_type: u16] ... [field_values...]
            let mut slice: &[u8] = data;
            let n_fields = read_short(&mut slice)? as usize;

            // Read field names and types
            let mut field_info: Vec<(String, u16)> = Vec::with_capacity(n_fields);
            for _ in 0..n_fields {
                let name_len = read_short(&mut slice)? as usize;
                if slice.len() < name_len {
                    anyhow::bail!("unexpected EOF reading UDT field name");
                }
                let (name_data, rest) = slice.split_at(name_len);
                slice = rest;
                let field_name = String::from_utf8_lossy(name_data).to_string();
                let field_type = read_short(&mut slice)?;
                field_info.push((field_name, field_type));
            }

            // Read field values
            let mut fields = Vec::with_capacity(n_fields);
            for (field_name, field_type) in field_info {
                let field_len = read_int(&mut slice)?;
                let field_value = if field_len < 0 {
                    None
                } else {
                    let field_len = field_len as usize;
                    if slice.len() < field_len {
                        anyhow::bail!("unexpected EOF reading UDT field value");
                    }
                    let (field_data, rest) = slice.split_at(field_len);
                    slice = rest;
                    Some(decode_typed_value_by_code(field_data, field_type)?)
                };
                fields.push((field_name, field_value));
            }

            Ok(CqlValue::UserDefinedType {
                name: "udt".to_string(),
                keyspace: "".to_string(),
                fields,
            })
        }
        _ => {
            // Unknown type - treat as blob
            Ok(CqlValue::Blob(data.to_vec()))
        }
    }
}

/// Read an integer value from bytes of any standard size (1, 2, 4, or 8 bytes).
/// This allows flexible handling when latte sends BigInt for smaller integer types.
fn read_int_flexible(data: &[u8]) -> Result<i64> {
    match data.len() {
        1 => Ok(data[0] as i8 as i64),
        2 => Ok(i16::from_be_bytes(data.try_into().unwrap()) as i64),
        4 => Ok(i32::from_be_bytes(data.try_into().unwrap()) as i64),
        8 => Ok(i64::from_be_bytes(data.try_into().unwrap())),
        _ => anyhow::bail!("invalid integer value length: {}", data.len()),
    }
}

/// Parse a CQL duration string like "1mo2d3h4m5s" or "1y2mo3d4h5m6s"
fn parse_duration_string(s: &str) -> Result<scylla::value::CqlDuration> {
    let mut months: i32 = 0;
    let mut days: i32 = 0;
    let mut nanos: i64 = 0;

    let mut num_str = String::new();
    let mut chars = s.chars().peekable();

    while let Some(c) = chars.next() {
        if c.is_ascii_digit() || c == '-' {
            num_str.push(c);
        } else {
            let num: i64 = if num_str.is_empty() {
                0
            } else {
                num_str
                    .parse()
                    .map_err(|_| anyhow::anyhow!("invalid duration number: {}", num_str))?
            };
            num_str.clear();

            match c {
                'y' => months += (num * 12) as i32,
                'm' => {
                    // Could be 'mo' for months, 'm' for minutes, or 'ms' for milliseconds
                    if chars.peek() == Some(&'o') {
                        chars.next();
                        months += num as i32;
                    } else if chars.peek() == Some(&'s') {
                        chars.next();
                        nanos += num * 1_000_000; // milliseconds to nanos
                    } else {
                        nanos += num * 60 * 1_000_000_000; // minutes to nanos
                    }
                }
                'd' => days += num as i32,
                'h' => nanos += num * 3600 * 1_000_000_000,
                's' => nanos += num * 1_000_000_000,
                'n' => {
                    // 'ns' for nanoseconds
                    if chars.peek() == Some(&'s') {
                        chars.next();
                    }
                    nanos += num;
                }
                'u' | 'µ' => {
                    // 'us' or 'µs' for microseconds
                    if chars.peek() == Some(&'s') {
                        chars.next();
                    }
                    nanos += num * 1_000;
                }
                _ => anyhow::bail!("invalid duration unit: {}", c),
            }
        }
    }

    Ok(scylla::value::CqlDuration {
        months,
        days,
        nanoseconds: nanos,
    })
}

/// Encode a variable-length integer (vint) for CQL duration encoding.
/// Uses zig-zag encoding for signed values.
fn encode_vint(value: i64, out: &mut Vec<u8>) {
    // Zig-zag encode
    let zigzag = ((value << 1) ^ (value >> 63)) as u64;
    let mut n = zigzag;

    // Variable-length encoding: 7 bits per byte, high bit indicates continuation
    if n < 0x80 {
        out.push(n as u8);
    } else if n < 0x4000 {
        out.push((0x80 | (n >> 7)) as u8);
        out.push((n & 0x7F) as u8);
    } else if n < 0x200000 {
        out.push((0x80 | (n >> 14)) as u8);
        out.push((0x80 | ((n >> 7) & 0x7F)) as u8);
        out.push((n & 0x7F) as u8);
    } else if n < 0x10000000 {
        out.push((0x80 | (n >> 21)) as u8);
        out.push((0x80 | ((n >> 14) & 0x7F)) as u8);
        out.push((0x80 | ((n >> 7) & 0x7F)) as u8);
        out.push((n & 0x7F) as u8);
    } else {
        // For larger values, encode with more bytes
        let mut temp = Vec::new();
        while n >= 0x80 {
            temp.push((0x80 | (n & 0x7F)) as u8);
            n >>= 7;
        }
        temp.push(n as u8);
        temp.reverse();
        out.extend_from_slice(&temp);
    }
}

fn build_prepared_frame(
    stream: i16,
    statement_key: &str,
    _prepared: &PreparedStatement,
) -> Result<Frame> {
    // Pre-allocate: 4 (kind) + 2+key_len (string) + 2+key_len (short bytes) + 16 (metadata)
    let mut body = BytesMut::with_capacity(24 + statement_key.len() * 2);

    // RESULT kind = PREPARED (0x0004)
    body.put_i32(0x0004);

    // Echo the statement key as a [string]
    write_string(statement_key, &mut body)?;

    // Prepared statement ID as [short bytes] - we use the key as the ID for simplicity
    let id_bytes = statement_key.as_bytes();
    write_short(id_bytes.len() as u16, &mut body);
    body.extend_from_slice(id_bytes);

    // For now, minimal metadata - flags=0, columns_count=0 for both bind and result metadata
    // Bind metadata
    body.put_i32(0); // flags (no global_table_spec)
    body.put_i32(0); // columns_count

    // Result metadata
    body.put_i32(0); // flags
    body.put_i32(0); // columns_count

    Ok(Frame {
        header: FrameHeader {
            version: VERSION_RESPONSE,
            flags: 0,
            stream,
            opcode: Opcode::Result,
            body_length: body.len() as u32,
        },
        body: body.freeze(),
    })
}

fn void_frame(stream: i16) -> Frame {
    let mut body = BytesMut::with_capacity(4);
    body.put_i32(0x0001); // RESULT kind = VOID

    Frame {
        header: FrameHeader {
            version: VERSION_RESPONSE,
            flags: 0,
            stream,
            opcode: Opcode::Result,
            body_length: body.len() as u32,
        },
        body: body.freeze(),
    }
}

fn type_code(column_type: &ColumnType<'_>) -> Option<u16> {
    match column_type {
        ColumnType::Native(NativeType::Ascii) => Some(0x0001),
        ColumnType::Native(NativeType::BigInt) => Some(0x0002),
        ColumnType::Native(NativeType::Blob) => Some(0x0003),
        ColumnType::Native(NativeType::Boolean) => Some(0x0004),
        ColumnType::Native(NativeType::Counter) => Some(0x0005),
        ColumnType::Native(NativeType::Decimal) => Some(0x0006),
        ColumnType::Native(NativeType::Double) => Some(0x0007),
        ColumnType::Native(NativeType::Float) => Some(0x0008),
        ColumnType::Native(NativeType::Int) => Some(0x0009),
        ColumnType::Native(NativeType::Timestamp) => Some(0x000B),
        ColumnType::Native(NativeType::Uuid) => Some(0x000C),
        ColumnType::Native(NativeType::Text) => Some(0x000D),
        ColumnType::Native(NativeType::Varint) => Some(0x000E),
        ColumnType::Native(NativeType::Timeuuid) => Some(0x000F),
        ColumnType::Native(NativeType::Inet) => Some(0x0010),
        ColumnType::Native(NativeType::Date) => Some(0x0011),
        ColumnType::Native(NativeType::Time) => Some(0x0012),
        ColumnType::Native(NativeType::SmallInt) => Some(0x0013),
        ColumnType::Native(NativeType::TinyInt) => Some(0x0014),
        ColumnType::Native(NativeType::Duration) => Some(0x0015),
        ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::List(_), .. } => Some(0x0020),
        ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Map(_, _), .. } => Some(0x0021),
        ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(_), .. } => Some(0x0022),
        ColumnType::Vector { .. } => Some(0x0030),
        ColumnType::Tuple(_) => Some(0x0031),
        ColumnType::UserDefinedType { .. } => Some(0x0040),
        _ => None,
    }
}

fn encode_value(column_type: &ColumnType<'_>, value: &CqlValue, out: &mut BytesMut) -> Result<()> {
    match (column_type, value) {
        (ColumnType::Native(NativeType::Int), CqlValue::Int(v)) => {
            write_int(4, out);
            out.put_i32(*v);
        }
        (ColumnType::Native(NativeType::Text), CqlValue::Text(v)) => {
            let bytes = v.as_bytes();
            write_int_length(bytes.len(), out)?;
            out.extend_from_slice(bytes);
        }
        (ColumnType::Native(NativeType::Ascii), CqlValue::Ascii(v)) => {
            let bytes = v.as_bytes();
            write_int_length(bytes.len(), out)?;
            out.extend_from_slice(bytes);
        }
        (ColumnType::Native(NativeType::BigInt), CqlValue::BigInt(v)) => {
            write_int(8, out);
            out.put_i64(*v);
        }
        (ColumnType::Native(NativeType::Boolean), CqlValue::Boolean(v)) => {
            write_int(1, out);
            out.put_u8(if *v { 1 } else { 0 });
        }
        (ColumnType::Native(NativeType::Float), CqlValue::Float(v)) => {
            write_int(4, out);
            out.extend_from_slice(&v.to_be_bytes());
        }
        (ColumnType::Native(NativeType::Double), CqlValue::Double(v)) => {
            write_int(8, out);
            out.extend_from_slice(&v.to_be_bytes());
        }
        (ColumnType::Native(NativeType::Uuid), CqlValue::Uuid(v)) => {
            write_int(16, out);
            out.extend_from_slice(v.as_bytes());
        }
        (ColumnType::Native(NativeType::Timeuuid), CqlValue::Timeuuid(v)) => {
            write_int(16, out);
            out.extend_from_slice(v.as_bytes());
        }
        (ColumnType::Native(NativeType::TinyInt), CqlValue::TinyInt(v)) => {
            write_int(1, out);
            out.put_i8(*v);
        }
        (ColumnType::Native(NativeType::SmallInt), CqlValue::SmallInt(v)) => {
            write_int(2, out);
            out.put_i16(*v);
        }
        (ColumnType::Native(NativeType::Blob), CqlValue::Blob(v)) => {
            write_int_length(v.len(), out)?;
            out.extend_from_slice(v);
        }
        (ColumnType::Native(NativeType::Date), CqlValue::Date(v)) => {
            write_int(4, out);
            out.put_u32(v.0);
        }
        (ColumnType::Native(NativeType::Time), CqlValue::Time(v)) => {
            write_int(8, out);
            out.put_i64(v.0);
        }
        (ColumnType::Native(NativeType::Timestamp), CqlValue::Timestamp(v)) => {
            write_int(8, out);
            out.put_i64(v.0);
        }
        (ColumnType::Native(NativeType::Counter), CqlValue::Counter(v)) => {
            write_int(8, out);
            out.put_i64(v.0);
        }
        (ColumnType::Native(NativeType::Inet), CqlValue::Inet(v)) => match v {
            std::net::IpAddr::V4(addr) => {
                write_int(4, out);
                out.extend_from_slice(&addr.octets());
            }
            std::net::IpAddr::V6(addr) => {
                write_int(16, out);
                out.extend_from_slice(&addr.octets());
            }
        },
        (ColumnType::Native(NativeType::Varint), CqlValue::Varint(v)) => {
            let bytes = v.as_signed_bytes_be_slice();
            write_int_length(bytes.len(), out)?;
            out.extend_from_slice(bytes);
        }
        (ColumnType::Native(NativeType::Decimal), CqlValue::Decimal(v)) => {
            let (bytes, scale) = v.as_signed_be_bytes_slice_and_exponent();
            let total_len = 4 + bytes.len();
            write_int_length(total_len, out)?;
            out.put_i32(scale);
            out.extend_from_slice(bytes);
        }
        (ColumnType::Native(NativeType::Duration), CqlValue::Duration(v)) => {
            // Duration is encoded as 3 varints: months, days, nanoseconds
            let mut buf = Vec::with_capacity(24);
            encode_vint(v.months as i64, &mut buf);
            encode_vint(v.days as i64, &mut buf);
            encode_vint(v.nanoseconds, &mut buf);
            write_int_length(buf.len(), out)?;
            out.extend_from_slice(&buf);
        }
        (ColumnType::Vector { typ: _, dimensions }, CqlValue::Vector(elements)) => {
            // Vector is encoded as contiguous float bytes
            // For result rows, we don't include subtype/dimension metadata (they're in column spec)
            let expected_dim = *dimensions as usize;
            if elements.len() != expected_dim {
                anyhow::bail!("vector dimension mismatch: expected {}, got {}", expected_dim, elements.len());
            }
            let float_bytes = expected_dim * 4;
            write_int_length(float_bytes, out)?;
            for elem in elements {
                match elem {
                    CqlValue::Float(f) => out.extend_from_slice(&f.to_be_bytes()),
                    _ => anyhow::bail!("vector element must be Float, got {:?}", elem),
                }
            }
        }
        (ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::List(elem_type), .. }, CqlValue::List(elements)) => {
            // List is encoded as: [n_elements: i32] [elements...]
            // Each element is [length: i32] [data: bytes]
            // Pre-allocate: 4 (count) + ~12 bytes per element (4 len + ~8 data)
            let mut list_buf = BytesMut::with_capacity(4 + elements.len() * 12);
            let n_elements: i32 = elements.len().try_into().map_err(|_| anyhow!("too many list elements"))?;
            list_buf.put_i32(n_elements);

            for elem in elements {
                match (elem_type.as_ref(), elem) {
                    (ColumnType::Vector { dimensions, .. }, CqlValue::Vector(vec_elements)) => {
                        // Vector element
                        let dim = *dimensions as usize;
                        let vec_len: i32 = (dim * 4) as i32;
                        list_buf.put_i32(vec_len);
                        for ve in vec_elements {
                            match ve {
                                CqlValue::Float(f) => list_buf.extend_from_slice(&f.to_be_bytes()),
                                _ => anyhow::bail!("vector element must be Float"),
                            }
                        }
                    }
                    (ColumnType::Native(NativeType::Float), CqlValue::Float(f)) => {
                        list_buf.put_i32(4);
                        list_buf.extend_from_slice(&f.to_be_bytes());
                    }
                    (ColumnType::Native(NativeType::Int), CqlValue::Int(v)) => {
                        list_buf.put_i32(4);
                        list_buf.put_i32(*v);
                    }
                    (ColumnType::Native(NativeType::BigInt), CqlValue::BigInt(v)) => {
                        list_buf.put_i32(8);
                        list_buf.put_i64(*v);
                    }
                    (ColumnType::Native(NativeType::Text), CqlValue::Text(v)) => {
                        let bytes = v.as_bytes();
                        list_buf.put_i32(bytes.len() as i32);
                        list_buf.extend_from_slice(bytes);
                    }
                    _ => {
                        // For other types, encode null as fallback
                        list_buf.put_i32(-1);
                    }
                }
            }

            write_int_length(list_buf.len(), out)?;
            out.extend_from_slice(&list_buf);
        }
        (ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(elem_type), .. }, CqlValue::Set(elements)) => {
            // Set is encoded as: [n_elements: i32] [elements...]
            // Pre-allocate: 4 (count) + ~12 bytes per element (4 len + ~8 data)
            let mut set_buf = BytesMut::with_capacity(4 + elements.len() * 12);
            let n_elements: i32 = elements.len().try_into().map_err(|_| anyhow!("too many set elements"))?;
            set_buf.put_i32(n_elements);

            for elem in elements {
                encode_element(elem_type.as_ref(), elem, &mut set_buf)?;
            }

            write_int_length(set_buf.len(), out)?;
            out.extend_from_slice(&set_buf);
        }
        (ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Map(key_type, value_type), .. }, CqlValue::Map(entries)) => {
            // Map is encoded as: [n_entries: i32] [entries...]
            // Each entry is [key_length: i32] [key_data] [value_length: i32] [value_data]
            // Pre-allocate: 4 (count) + ~24 bytes per entry (2x (4 len + ~8 data))
            let mut map_buf = BytesMut::with_capacity(4 + entries.len() * 24);
            let n_entries: i32 = entries.len().try_into().map_err(|_| anyhow!("too many map entries"))?;
            map_buf.put_i32(n_entries);

            for (key, value) in entries {
                encode_element(key_type.as_ref(), key, &mut map_buf)?;
                encode_element(value_type.as_ref(), value, &mut map_buf)?;
            }

            write_int_length(map_buf.len(), out)?;
            out.extend_from_slice(&map_buf);
        }
        (ColumnType::Tuple(elem_types), CqlValue::Tuple(elements)) => {
            // Tuple is encoded as: [elements...]
            // Each element is [length: i32] [data: bytes] (or length=-1 for null)
            // Pre-allocate: ~12 bytes per element (4 len + ~8 data)
            let mut tuple_buf = BytesMut::with_capacity(elements.len() * 12);

            for (elem_type, elem) in elem_types.iter().zip(elements.iter()) {
                match elem {
                    Some(val) => encode_element(elem_type, val, &mut tuple_buf)?,
                    None => tuple_buf.put_i32(-1),
                }
            }

            write_int_length(tuple_buf.len(), out)?;
            out.extend_from_slice(&tuple_buf);
        }
        (ColumnType::UserDefinedType { definition, .. }, CqlValue::UserDefinedType { fields, .. }) => {
            // UDT is encoded as: [field_values...]
            // Each field is [length: i32] [data: bytes] (or length=-1 for null)
            // Pre-allocate: ~12 bytes per field (4 len + ~8 data)
            let mut udt_buf = BytesMut::with_capacity(fields.len() * 12);

            // Match fields by position (UDT fields must be in schema order)
            for ((_, field_type), (_, field_value)) in definition.field_types.iter().zip(fields.iter()) {
                match field_value {
                    Some(val) => encode_element(field_type, val, &mut udt_buf)?,
                    None => udt_buf.put_i32(-1),
                }
            }

            write_int_length(udt_buf.len(), out)?;
            out.extend_from_slice(&udt_buf);
        }
        (col_type, val) => {
            anyhow::bail!("unsupported value {:?} for column type {:?}", val, col_type);
        }
    }

    Ok(())
}

/// Encode a single element (for collections)
fn encode_element(elem_type: &ColumnType<'_>, elem: &CqlValue, buf: &mut BytesMut) -> Result<()> {
    match (elem_type, elem) {
        (ColumnType::Native(NativeType::Int), CqlValue::Int(v)) => {
            buf.put_i32(4);
            buf.put_i32(*v);
        }
        (ColumnType::Native(NativeType::BigInt), CqlValue::BigInt(v)) => {
            buf.put_i32(8);
            buf.put_i64(*v);
        }
        (ColumnType::Native(NativeType::Text), CqlValue::Text(v)) => {
            let bytes = v.as_bytes();
            buf.put_i32(bytes.len() as i32);
            buf.extend_from_slice(bytes);
        }
        (ColumnType::Native(NativeType::Float), CqlValue::Float(f)) => {
            buf.put_i32(4);
            buf.extend_from_slice(&f.to_be_bytes());
        }
        (ColumnType::Native(NativeType::Double), CqlValue::Double(d)) => {
            buf.put_i32(8);
            buf.extend_from_slice(&d.to_be_bytes());
        }
        (ColumnType::Native(NativeType::Boolean), CqlValue::Boolean(b)) => {
            buf.put_i32(1);
            buf.put_u8(if *b { 1 } else { 0 });
        }
        (ColumnType::Vector { dimensions, .. }, CqlValue::Vector(vec_elements)) => {
            let dim = *dimensions as usize;
            let vec_len: i32 = (dim * 4) as i32;
            buf.put_i32(vec_len);
            for ve in vec_elements {
                match ve {
                    CqlValue::Float(f) => buf.extend_from_slice(&f.to_be_bytes()),
                    _ => anyhow::bail!("vector element must be Float"),
                }
            }
        }
        (ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::List(inner_type), .. }, CqlValue::List(elements)) => {
            // Pre-allocate: 4 (count) + ~12 bytes per element
            let mut inner_buf = BytesMut::with_capacity(4 + elements.len() * 12);
            let n: i32 = elements.len().try_into().map_err(|_| anyhow!("too many elements"))?;
            inner_buf.put_i32(n);
            for e in elements {
                encode_element(inner_type.as_ref(), e, &mut inner_buf)?;
            }
            buf.put_i32(inner_buf.len() as i32);
            buf.extend_from_slice(&inner_buf);
        }
        (ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Set(inner_type), .. }, CqlValue::Set(elements)) => {
            // Pre-allocate: 4 (count) + ~12 bytes per element
            let mut inner_buf = BytesMut::with_capacity(4 + elements.len() * 12);
            let n: i32 = elements.len().try_into().map_err(|_| anyhow!("too many elements"))?;
            inner_buf.put_i32(n);
            for e in elements {
                encode_element(inner_type.as_ref(), e, &mut inner_buf)?;
            }
            buf.put_i32(inner_buf.len() as i32);
            buf.extend_from_slice(&inner_buf);
        }
        (ColumnType::Collection { typ: scylla::frame::response::result::CollectionType::Map(kt, vt), .. }, CqlValue::Map(entries)) => {
            // Pre-allocate: 4 (count) + ~24 bytes per entry
            let mut inner_buf = BytesMut::with_capacity(4 + entries.len() * 24);
            let n: i32 = entries.len().try_into().map_err(|_| anyhow!("too many entries"))?;
            inner_buf.put_i32(n);
            for (k, v) in entries {
                encode_element(kt.as_ref(), k, &mut inner_buf)?;
                encode_element(vt.as_ref(), v, &mut inner_buf)?;
            }
            buf.put_i32(inner_buf.len() as i32);
            buf.extend_from_slice(&inner_buf);
        }
        (ColumnType::UserDefinedType { definition, .. }, CqlValue::UserDefinedType { fields, .. }) => {
            // Pre-allocate: ~12 bytes per field
            let mut inner_buf = BytesMut::with_capacity(fields.len() * 12);
            for ((_, field_type), (_, field_value)) in definition.field_types.iter().zip(fields.iter()) {
                match field_value {
                    Some(val) => encode_element(field_type, val, &mut inner_buf)?,
                    None => inner_buf.put_i32(-1),
                }
            }
            buf.put_i32(inner_buf.len() as i32);
            buf.extend_from_slice(&inner_buf);
        }
        _ => {
            // Encode as null
            buf.put_i32(-1);
        }
    }
    Ok(())
}

fn build_rows_frame(
    stream: i16,
    rows_result: &scylla::response::query_result::QueryRowsResult,
) -> Result<Frame> {
    let column_specs: Vec<&ColumnSpec<'_>> = rows_result.column_specs().iter().collect();

    for spec in &column_specs {
        if type_code(spec.typ()).is_none() {
            anyhow::bail!("unsupported column type in result: {:?}", spec.typ());
        }
    }

    let mut rows = Vec::new();
    let mut iter = rows_result.rows::<Row>()?;
    while let Some(row) = iter.next().transpose()? {
        rows.push(row);
    }

    // Estimate body size: header (12 bytes) + metadata (~50 bytes per column) + rows (~32 bytes per cell)
    let estimated_size = 12 + column_specs.len() * 50 + rows.len() * column_specs.len() * 32;
    let mut body = BytesMut::with_capacity(estimated_size);
    body.put_i32(0x0002); // RESULT kind = ROWS

    let flags: i32 = 0; // no metadata id, no paging
    body.put_i32(flags);
    write_int(column_specs.len() as i32, &mut body);

    for spec in &column_specs {
        write_string(spec.table_spec().ks_name(), &mut body)?;
        write_string(spec.table_spec().table_name(), &mut body)?;
        write_string(spec.name(), &mut body)?;

        let col_type = spec.typ();
        let code = type_code(col_type)
            .ok_or_else(|| anyhow!("unsupported column type in metadata: {col_type:?}"))?;
        write_short(code, &mut body);
    }

    write_int(rows.len() as i32, &mut body);
    for row in rows {
        for (value, spec) in row.columns.iter().zip(&column_specs) {
            match value {
                Some(value) => encode_value(spec.typ(), value, &mut body)?,
                None => write_int(-1, &mut body),
            }
        }
    }

    Ok(Frame {
        header: FrameHeader {
            version: VERSION_RESPONSE,
            flags: 0,
            stream,
            opcode: Opcode::Result,
            body_length: body.len() as u32,
        },
        body: body.freeze(),
    })
}

fn read_short(slice: &mut &[u8]) -> Result<u16> {
    if slice.len() < 2 {
        anyhow::bail!("unexpected EOF reading short");
    }
    let (head, rest) = slice.split_at(2);
    *slice = rest;
    Ok(u16::from_be_bytes([head[0], head[1]]))
}

fn read_int(slice: &mut &[u8]) -> Result<i32> {
    if slice.len() < 4 {
        anyhow::bail!("unexpected EOF reading int");
    }
    let (head, rest) = slice.split_at(4);
    *slice = rest;
    Ok(i32::from_be_bytes([head[0], head[1], head[2], head[3]]))
}

fn read_long_string<'a>(slice: &mut &'a [u8]) -> Result<&'a str> {
    let len = read_int(slice)? as usize;
    if slice.len() < len {
        anyhow::bail!("unexpected EOF reading string body");
    }
    let (head, rest) = slice.split_at(len);
    *slice = rest;
    Ok(std::str::from_utf8(head)?)
}

fn read_long(slice: &mut &[u8]) -> Result<u64> {
    if slice.len() < 8 {
        anyhow::bail!("unexpected EOF reading long");
    }
    let (head, rest) = slice.split_at(8);
    *slice = rest;
    Ok(u64::from_be_bytes([
        head[0], head[1], head[2], head[3], head[4], head[5], head[6], head[7],
    ]))
}

pub fn read_string(slice: &mut &[u8]) -> Result<String> {
    let len = read_short(slice)? as usize;
    if slice.len() < len {
        anyhow::bail!("unexpected EOF reading string body");
    }
    let (head, rest) = slice.split_at(len);
    *slice = rest;
    Ok(std::str::from_utf8(head)?.to_owned())
}

pub fn read_string_map(slice: &mut &[u8]) -> Result<HashMap<String, String>> {
    let count = read_short(slice)? as usize;
    let mut map = HashMap::with_capacity(count);
    for _ in 0..count {
        let key = read_string(slice)?;
        let value = read_string(slice)?;
        map.insert(key, value);
    }
    Ok(map)
}

fn parse_contact_points(raw: &str) -> Result<Vec<String>> {
    let points = raw
        .split(',')
        .map(|v| v.trim())
        .filter(|v| !v.is_empty())
        .map(|v| v.to_string())
        .collect::<Vec<_>>();

    if points.is_empty() {
        anyhow::bail!("contact_points override produced an empty list");
    }

    Ok(points)
}

/// Parse a consistency level string into a Consistency enum.
/// Returns None for unrecognized values (forward compatibility).
/// Uses case-insensitive comparison to avoid string allocation.
fn parse_consistency(s: &str) -> Option<Consistency> {
    if s.eq_ignore_ascii_case("ANY") {
        Some(Consistency::Any)
    } else if s.eq_ignore_ascii_case("ONE") || s == "1" {
        Some(Consistency::One)
    } else if s.eq_ignore_ascii_case("TWO") || s == "2" {
        Some(Consistency::Two)
    } else if s.eq_ignore_ascii_case("THREE") || s == "3" {
        Some(Consistency::Three)
    } else if s.eq_ignore_ascii_case("QUORUM") {
        Some(Consistency::Quorum)
    } else if s.eq_ignore_ascii_case("ALL") {
        Some(Consistency::All)
    } else if s.eq_ignore_ascii_case("LOCAL_ONE") || s.eq_ignore_ascii_case("LOCALONE") {
        Some(Consistency::LocalOne)
    } else if s.eq_ignore_ascii_case("LOCAL_QUORUM") || s.eq_ignore_ascii_case("LOCALQUORUM") {
        Some(Consistency::LocalQuorum)
    } else if s.eq_ignore_ascii_case("EACH_QUORUM") || s.eq_ignore_ascii_case("EACHQUORUM") {
        Some(Consistency::EachQuorum)
    } else if s.eq_ignore_ascii_case("SERIAL") {
        Some(Consistency::Serial)
    } else if s.eq_ignore_ascii_case("LOCAL_SERIAL") || s.eq_ignore_ascii_case("LOCALSERIAL") {
        Some(Consistency::LocalSerial)
    } else {
        None
    }
}

/// Parse a serial consistency level string.
/// Returns None for unrecognized values (forward compatibility).
/// Uses case-insensitive comparison to avoid string allocation.
fn parse_serial_consistency(s: &str) -> Option<scylla::frame::types::SerialConsistency> {
    if s.eq_ignore_ascii_case("SERIAL") {
        Some(scylla::frame::types::SerialConsistency::Serial)
    } else if s.eq_ignore_ascii_case("LOCAL_SERIAL") || s.eq_ignore_ascii_case("LOCALSERIAL") {
        Some(scylla::frame::types::SerialConsistency::LocalSerial)
    } else {
        None
    }
}

/// Registry for managing driver sessions.
/// Uses DashMap for lock-free concurrent access on the hot path (get).
pub struct SessionRegistry {
    config: DriverConfig,
    next_id: AtomicU64,
    sessions: DashMap<u64, Arc<DriverSession>>,
}

impl SessionRegistry {
    pub fn new(config: DriverConfig) -> Self {
        Self {
            config,
            next_id: AtomicU64::new(1),
            sessions: DashMap::new(),
        }
    }

    pub async fn create_session(&self, params: HashMap<String, String>) -> Result<u64> {
        let session = DriverSession::connect_with_params(&self.config, &params).await?;
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);

        // Lock-free insert
        self.sessions.insert(id, Arc::new(session));
        Ok(id)
    }

    /// Get a session by ID (lock-free).
    pub fn get(&self, session_id: u64) -> Option<Arc<DriverSession>> {
        self.sessions.get(&session_id).map(|r| r.value().clone())
    }

    /// Remove a session by ID (lock-free).
    /// Returns true if the session was found and removed.
    pub fn remove(&self, session_id: u64) -> bool {
        self.sessions.remove(&session_id).is_some()
    }

    /// Get the number of active sessions.
    pub fn len(&self) -> usize {
        self.sessions.len()
    }

    /// Check if there are no active sessions.
    pub fn is_empty(&self) -> bool {
        self.sessions.is_empty()
    }
}

fn write_int(value: i32, out: &mut BytesMut) {
    out.put_i32(value);
}

fn write_int_length(len: usize, out: &mut BytesMut) -> Result<()> {
    let value: i32 = len
        .try_into()
        .map_err(|err| anyhow!("length does not fit in i32: {err}"))?;
    write_int(value, out);
    Ok(())
}

fn write_short(value: u16, out: &mut BytesMut) {
    out.put_u16(value);
}

fn write_string(value: &str, out: &mut BytesMut) -> Result<()> {
    let len: u16 = value
        .len()
        .try_into()
        .map_err(|err| anyhow!("string too long for short length: {err}"))?;
    write_short(len, out);
    out.extend_from_slice(value.as_bytes());
    Ok(())
}
