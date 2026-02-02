//! DynamoDB context type for workload scripts.

use super::client::{DynamoClient, SessionConfig};
use super::error::DynamoError;
use super::types::{attribute_value_to_rune, rune_to_attribute_value};
use crate::config::DynamoDbConf;
use crate::ipc::{AlternatorIpcClient, AlternatorSessionId, DockerConfig, DockerManager};
use crate::stats::session::SessionStats;
use aws_sdk_dynamodb::types::AttributeValue;
use parking_lot::Mutex;
use rune::alloc::clone::TryClone;
use rune::runtime::{Object, Ref, Shared, Vec as RuneVec, VmError};
use rune::Any;
use rune::Value;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::time::Instant;

/// The main context object that a DynamoDB workload script uses to interface with the database.
/// Similar to the CQL Context, but for DynamoDB operations.
#[derive(Any)]
#[rune(item = ::dynamodb)]
pub struct DynamoContext {
    start_time: Mutex<Instant>,

    /// Default client (AWS SDK direct)
    default_client: Arc<DynamoClient>,

    /// Named clients for multi-endpoint testing
    named_clients: HashMap<String, Arc<DynamoClient>>,

    /// Configuration used to create the default client
    config: DynamoDbConf,

    /// Statistics tracking
    stats: Mutex<SessionStats>,

    /// Number of load cycles (settable by workload scripts)
    #[rune(get, set, add_assign, copy)]
    pub load_cycle_count: u64,

    /// Custom user data object
    #[rune(get)]
    pub data: Value,

    /// Prepared operation templates (for client-side caching)
    prepared_operations: HashMap<String, PreparedOperation>,

    /// Optional IPC client for external Alternator driver
    ipc_client: Option<Arc<AlternatorIpcClient>>,

    /// Session ID for IPC client
    ipc_session_id: Option<AlternatorSessionId>,
}

/// A prepared operation template for client-side caching.
#[derive(Clone)]
pub struct PreparedOperation {
    pub operation_type: OperationType,
    pub table_name: String,
    /// Template keys using Arc<str> to avoid cloning in hot path
    pub template_keys: Vec<Arc<str>>,
    pub key_condition_expression: Option<String>,
    pub projection_expression: Option<String>,
    pub limit: Option<i32>,
}

#[derive(Clone, Debug)]
pub enum OperationType {
    PutItem,
    GetItem,
    DeleteItem,
    UpdateItem,
    Query,
    Scan,
}

// Safety: Same reasoning as the CQL Context - we ensure no concurrent access
// and serialize data properly when cloning.
unsafe impl Send for DynamoContext {}
unsafe impl Sync for DynamoContext {}

impl DynamoContext {
    /// Create a new DynamoDB context with the given configuration.
    /// Returns the context and an optional DockerManager that must be kept alive
    /// for the duration of the benchmark (dropping it stops the container).
    pub async fn new(config: DynamoDbConf) -> Result<(Self, Option<DockerManager>), DynamoError> {
        let client = DynamoClient::from_config(&config).await?;

        // Connect to IPC client if external driver mode is enabled
        let (ipc_client, ipc_session_id, docker_manager) = if config.is_external_driver() {
            let socket_path = config.alternator_socket_path();
            if let Some(socket_dir) = socket_path.parent() {
                tokio::fs::create_dir_all(socket_dir)
                    .await
                    .map_err(|e| DynamoError::connection(format!("failed to create socket directory: {}", e)))?;
            }
            let _ = tokio::fs::remove_file(&socket_path).await;

            let listener = tokio::net::UnixListener::bind(&socket_path)
                .map_err(|e| DynamoError::connection(format!("failed to bind socket at {}: {}", socket_path.display(), e)))?;

            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(&socket_path, std::fs::Permissions::from_mode(0o666))
                    .map_err(|e| DynamoError::connection(format!("failed to set socket permissions: {}", e)))?;
            }

            eprintln!("info: Listening for alternator adapter on {}...", socket_path.display());

            // Start Docker container if --alternator-adapter-image was specified
            let docker_manager = if let Some(ref image) = config.alternator_adapter_image {
                eprintln!("info: Starting alternator adapter container ({})...", image);
                let mut extra_envs = Vec::new();
                if let Some(ref endpoint) = config.endpoint {
                    extra_envs.push(("LATTE_ALTERNATOR_ENDPOINT".to_string(), endpoint.clone()));
                }
                let docker_config = DockerConfig {
                    image: image.clone(),
                    socket_path: socket_path.clone(),
                    container_name: None,
                    socket_env_name: "LATTE_ALTERNATOR_SOCKET".to_string(),
                    extra_envs,
                };
                let mut manager = DockerManager::new(docker_config);
                manager.start().await.map_err(|e| {
                    DynamoError::connection(format!("failed to start alternator adapter container: {}", e))
                })?;
                eprintln!(
                    "info: Alternator adapter container started (id={})",
                    manager.container_id().unwrap_or("unknown")
                );
                Some(manager)
            } else {
                None
            };

            let (stream, _) = listener.accept()
                .await
                .map_err(|e| DynamoError::connection(format!("failed to accept adapter connection: {}", e)))?;

            eprintln!("info: Alternator adapter connected");

            let ipc = AlternatorIpcClient::from_stream(stream);

            // Create session with IPC adapter
            let mut session_params = HashMap::new();
            if let Some(ref endpoint) = config.endpoint {
                session_params.insert("endpoint".to_string(), endpoint.clone());
            }
            session_params.insert("region".to_string(), config.region.clone());
            if let Some(ref access_key) = config.access_key_id {
                session_params.insert("access_key_id".to_string(), access_key.clone());
            }
            if let Some(ref secret_key) = config.secret_access_key {
                session_params.insert("secret_access_key".to_string(), secret_key.clone());
            }
            session_params.insert("max_connections".to_string(), config.max_connections.to_string());
            session_params.insert("connect_timeout_ms".to_string(), config.connect_timeout_ms.to_string());
            session_params.insert("request_timeout_ms".to_string(), config.read_timeout_ms.to_string());
            session_params.insert("max_retries".to_string(), config.max_retries.to_string());

            let session_id = ipc.create_session(session_params)
                .await
                .map_err(|e| DynamoError::connection(format!("failed to create IPC session: {}", e)))?;

            (Some(Arc::new(ipc)), Some(session_id), docker_manager)
        } else {
            (None, None, None)
        };

        Ok((Self {
            start_time: Mutex::new(Instant::now()),
            default_client: Arc::new(client),
            named_clients: HashMap::new(),
            config,
            stats: Mutex::new(SessionStats::new()),
            load_cycle_count: 0,
            data: Value::Object(Shared::new(Object::new()).unwrap()),
            prepared_operations: HashMap::new(),
            ipc_client,
            ipc_session_id,
        }, docker_manager))
    }

    /// Returns true if this context is using an external IPC driver.
    pub fn is_using_ipc(&self) -> bool {
        self.ipc_client.is_some()
    }

    /// Get the default client.
    pub fn client(&self) -> Arc<DynamoClient> {
        Arc::clone(&self.default_client)
    }

    /// Get a named client.
    pub fn client_named(&self, name: &str) -> Result<Arc<DynamoClient>, DynamoError> {
        self.named_clients
            .get(name)
            .cloned()
            .ok_or_else(|| DynamoError::client_not_found(name))
    }

    /// Create a new client with custom configuration (Object-based, legacy).
    pub async fn create_client_from_object(
        &mut self,
        config: &Object,
    ) -> Result<Arc<DynamoClient>, DynamoError> {
        let client = DynamoClient::from_rune_config(config).await?;
        Ok(Arc::new(client))
    }

    /// Create a named client with custom configuration (Object-based, legacy).
    pub async fn create_client_named_from_object(
        &mut self,
        name: &str,
        config: &Object,
    ) -> Result<(), DynamoError> {
        let client = DynamoClient::from_rune_config(config).await?;
        self.named_clients
            .insert(name.to_string(), Arc::new(client));
        Ok(())
    }

    /// Create a new client with SessionConfig.
    pub async fn create_client(
        &mut self,
        config: &SessionConfig,
    ) -> Result<Arc<DynamoClient>, DynamoError> {
        let client = DynamoClient::from_session_config(config).await?;
        Ok(Arc::new(client))
    }

    /// Create a named client with SessionConfig.
    pub async fn create_client_named(
        &mut self,
        name: &str,
        config: &SessionConfig,
    ) -> Result<(), DynamoError> {
        let client = DynamoClient::from_session_config(config).await?;
        self.named_clients
            .insert(name.to_string(), Arc::new(client));
        Ok(())
    }

    /// Get elapsed time since start in seconds.
    pub fn elapsed_secs(&self) -> f64 {
        self.start_time.lock().elapsed().as_secs_f64()
    }

    /// Reset statistics and start time.
    /// Preserves queue_length to avoid underflow from in-flight requests.
    pub fn reset(&self) {
        self.stats.lock().reset();
        *self.start_time.lock() = Instant::now();
    }

    /// Take the current session statistics.
    /// Preserves queue_length in the new stats to avoid underflow from in-flight requests.
    pub fn take_session_stats(&self) -> SessionStats {
        let mut stats = self.stats.lock();
        let queue_length = stats.queue_length;
        let taken = std::mem::take(&mut *stats);
        stats.queue_length = queue_length;
        taken
    }

    /// Record a request start.
    /// Returns the start time and increments the queue length for stats tracking.
    /// Note: This acquires the stats lock briefly.
    pub fn start_request(&self) -> Instant {
        self.stats.lock().start_request()
    }

    /// Record a completed request.
    /// Note: This acquires the stats lock once for all updates.
    pub fn complete_request(&self, start: Instant, item_count: u64) {
        let duration = Instant::now() - start;
        self.stats
            .lock()
            .complete_request_simple(duration, None, Some(item_count));
    }

    /// Record a completed request with driver-side latency (for IPC mode).
    /// Note: This acquires the stats lock once for all updates.
    pub fn complete_request_with_driver_latency(
        &self,
        start: Instant,
        driver_latency_ns: i64,
        item_count: u64,
    ) {
        let duration = Instant::now() - start;
        let driver_latency = if driver_latency_ns > 0 {
            Some(Duration::from_nanos(driver_latency_ns as u64))
        } else {
            None
        };
        self.stats
            .lock()
            .complete_request_simple(duration, driver_latency, Some(item_count));
    }

    /// Combined start and complete for simple timing without queue tracking.
    /// Use this when you don't need accurate queue length tracking.
    /// Records the request in a single lock acquisition.
    #[inline]
    pub fn record_request(&self, duration: std::time::Duration, item_count: u64) {
        let mut stats = self.stats.lock();
        stats.resp_times_ns.record(duration);
        stats.req_count += 1;
        stats.row_count += item_count;
    }

    /// Record an error.
    pub fn record_error(&self, error: &str) {
        self.stats.lock().record_ipc_error(error);
    }

    /// Check if the default client is connected to Alternator.
    pub fn is_alternator(&self) -> bool {
        self.default_client.is_alternator()
    }

    /// Store a prepared operation template.
    pub fn store_prepared(&mut self, name: &str, operation: PreparedOperation) {
        self.prepared_operations.insert(name.to_string(), operation);
    }

    /// Get a prepared operation template.
    pub fn get_prepared(&self, name: &str) -> Option<&PreparedOperation> {
        self.prepared_operations.get(name)
    }

    /// Prepare a PutItem operation template.
    /// The template_keys define which attributes will be filled in at execution time.
    pub fn prepare_put_item(&mut self, name: &str, table_name: &str, template_keys: Vec<String>) {
        let op = PreparedOperation {
            operation_type: OperationType::PutItem,
            table_name: table_name.to_string(),
            template_keys: template_keys.into_iter().map(Arc::from).collect(),
            key_condition_expression: None,
            projection_expression: None,
            limit: None,
        };
        self.store_prepared(name, op);
    }

    /// Prepare a GetItem operation template.
    /// The template_keys define which key attributes will be filled in at execution time.
    pub fn prepare_get_item(
        &mut self,
        name: &str,
        table_name: &str,
        template_keys: Vec<String>,
        projection_expression: Option<String>,
    ) {
        let op = PreparedOperation {
            operation_type: OperationType::GetItem,
            table_name: table_name.to_string(),
            template_keys: template_keys.into_iter().map(Arc::from).collect(),
            key_condition_expression: None,
            projection_expression,
            limit: None,
        };
        self.store_prepared(name, op);
    }

    /// Prepare a Query operation template.
    /// The template_keys define which expression attribute values will be filled in.
    pub fn prepare_query(
        &mut self,
        name: &str,
        table_name: &str,
        key_condition_expression: &str,
        template_keys: Vec<String>,
        projection_expression: Option<String>,
        limit: Option<i32>,
    ) {
        let op = PreparedOperation {
            operation_type: OperationType::Query,
            table_name: table_name.to_string(),
            template_keys: template_keys.into_iter().map(Arc::from).collect(),
            key_condition_expression: Some(key_condition_expression.to_string()),
            projection_expression,
            limit,
        };
        self.store_prepared(name, op);
    }

    /// Execute a prepared operation with the given values.
    pub async fn execute_prepared(
        &self,
        name: &str,
        values: &RuneVec,
    ) -> Result<Value, DynamoError> {
        // Get reference to prepared operation (avoid cloning)
        let op = self
            .get_prepared(name)
            .ok_or_else(|| DynamoError::prepared_operation_not_found(name))?;

        // Convert Rune values to AttributeValues
        let mut attr_values: Vec<AttributeValue> = Vec::new();
        for item in values.iter() {
            let attr = rune_to_attribute_value(item).map_err(|e| {
                DynamoError::invalid_parameter(format!("Failed to convert value: {}", e))
            })?;
            attr_values.push(attr);
        }

        // Verify we have the right number of values
        if attr_values.len() != op.template_keys.len() {
            return Err(DynamoError::invalid_parameter(format!(
                "Expected {} values but got {}",
                op.template_keys.len(),
                attr_values.len()
            )));
        }

        let start = self.start_request();
        let client = self.client();

        let result = match op.operation_type {
            OperationType::PutItem => {
                // Build item from template keys and values (pre-allocate for efficiency)
                // Using Arc<str> keys - convert to String for HashMap (avoids clone overhead)
                let mut item: HashMap<String, AttributeValue> =
                    HashMap::with_capacity(op.template_keys.len());
                for (key, value) in op.template_keys.iter().zip(attr_values.into_iter()) {
                    item.insert(key.to_string(), value);
                }

                client
                    .put_item(&op.table_name, item, None, None, None)
                    .await?;

                self.complete_request(start, 1);
                Value::EmptyTuple
            }

            OperationType::GetItem => {
                // Build key from template keys and values (pre-allocate for efficiency)
                // Using Arc<str> keys - convert to String for HashMap (avoids clone overhead)
                let mut key: HashMap<String, AttributeValue> =
                    HashMap::with_capacity(op.template_keys.len());
                for (k, value) in op.template_keys.iter().zip(attr_values.into_iter()) {
                    key.insert(k.to_string(), value);
                }

                let result = client
                    .get_item(
                        &op.table_name,
                        key,
                        op.projection_expression.as_deref(),
                        None,
                        None,
                    )
                    .await?;

                self.complete_request(start, if result.is_some() { 1 } else { 0 });

                // Convert result to Rune value
                match result {
                    Some(item) => {
                        let mut obj = Object::new();
                        for (k, v) in item {
                            let rune_val = attribute_value_to_rune(&v).map_err(|e| {
                                DynamoError::invalid_parameter(format!(
                                    "Failed to convert result: {}",
                                    e
                                ))
                            })?;
                            let _ = obj.insert(
                                rune::alloc::String::try_from(k).map_err(|e| {
                                    DynamoError::invalid_parameter(format!(
                                        "Key conversion error: {}",
                                        e
                                    ))
                                })?,
                                rune_val,
                            );
                        }
                        Value::Object(Shared::new(obj).map_err(|e| {
                            DynamoError::invalid_parameter(format!("Shared error: {}", e))
                        })?)
                    }
                    None => Value::EmptyTuple,
                }
            }

            OperationType::Query => {
                // Build expression attribute values from template keys and values (pre-allocate)
                // Using Arc<str> keys - convert to String for HashMap (avoids clone overhead)
                let mut expr_values: HashMap<String, AttributeValue> =
                    HashMap::with_capacity(op.template_keys.len());
                for (k, value) in op.template_keys.iter().zip(attr_values.into_iter()) {
                    expr_values.insert(k.to_string(), value);
                }

                // Get the key condition expression as a reference
                let key_cond = op.key_condition_expression.as_deref().unwrap_or_default();

                let items = client
                    .query(
                        &op.table_name,
                        None, // index_name
                        key_cond,
                        None, // filter_expression
                        op.projection_expression.as_deref(),
                        None, // expression_attribute_names
                        Some(expr_values),
                        op.limit,
                        None, // scan_index_forward
                        None, // consistent_read
                    )
                    .await?;

                self.complete_request(start, items.len() as u64);

                // Convert items to Rune Vec (pre-allocate for efficiency)
                let mut result_vec =
                    rune::runtime::Vec::with_capacity(items.len()).map_err(|e| {
                        DynamoError::invalid_parameter(format!("Failed to allocate Vec: {}", e))
                    })?;
                for item in items {
                    // Object doesn't have with_capacity, so we use new()
                    let mut obj = Object::new();
                    for (k, v) in item {
                        let rune_val = attribute_value_to_rune(&v).map_err(|e| {
                            DynamoError::invalid_parameter(format!(
                                "Failed to convert result: {}",
                                e
                            ))
                        })?;
                        let _ = obj.insert(
                            rune::alloc::String::try_from(k).map_err(|e| {
                                DynamoError::invalid_parameter(format!(
                                    "Key conversion error: {}",
                                    e
                                ))
                            })?,
                            rune_val,
                        );
                    }
                    result_vec
                        .push(Value::Object(Shared::new(obj).map_err(|e| {
                            DynamoError::invalid_parameter(format!("Shared error: {}", e))
                        })?))
                        .map_err(|e| {
                            DynamoError::invalid_parameter(format!("Vec push error: {}", e))
                        })?;
                }
                Value::Vec(Shared::new(result_vec).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Shared vec error: {}", e))
                })?)
            }

            OperationType::DeleteItem => {
                // Build key from template keys and values (pre-allocate for efficiency)
                // Using Arc<str> keys - convert to String for HashMap (avoids clone overhead)
                let mut key: HashMap<String, AttributeValue> =
                    HashMap::with_capacity(op.template_keys.len());
                for (k, value) in op.template_keys.iter().zip(attr_values.into_iter()) {
                    key.insert(k.to_string(), value);
                }

                client
                    .delete_item(&op.table_name, key, None, None, None)
                    .await?;

                self.complete_request(start, 1);
                Value::EmptyTuple
            }

            OperationType::UpdateItem => {
                // For UpdateItem, the first values are the key, the rest are expression values
                // This is more complex and would need a different template structure
                return Err(DynamoError::invalid_parameter(
                    "UpdateItem prepared operations not yet supported",
                ));
            }

            OperationType::Scan => {
                // Scan doesn't typically need prepared operations
                return Err(DynamoError::invalid_parameter(
                    "Scan prepared operations not supported",
                ));
            }
        };

        Ok(result)
    }

    /// Clone the context for use by another thread.
    pub fn clone_for_thread(&self) -> Result<Self, DynamoError> {
        // Use Rune's TryClone for deep copying the data field.
        // This is more efficient than MessagePack serialization round-trip.
        let cloned_data = self
            .data
            .try_clone()
            .map_err(|e| DynamoError::invalid_parameter(format!("Failed to clone data: {}", e)))?;

        Ok(Self {
            start_time: Mutex::new(*self.start_time.lock()),
            default_client: Arc::clone(&self.default_client),
            named_clients: self.named_clients.clone(),
            config: self.config.clone(),
            stats: Mutex::new(SessionStats::new()),
            load_cycle_count: self.load_cycle_count,
            data: cloned_data,
            prepared_operations: self.prepared_operations.clone(),
            ipc_client: self.ipc_client.clone(),
            ipc_session_id: self.ipc_session_id,
        })
    }

    /// Helper to convert a Rune Vec to a Vec<String>.
    pub fn vec_to_string_vec(vec: &RuneVec) -> Result<Vec<String>, VmError> {
        let mut result = Vec::with_capacity(vec.len());
        for item in vec.iter() {
            if let Value::String(s) = item {
                let s_ref = s
                    .borrow_ref()
                    .map_err(|e| VmError::panic(format!("{}", e)))?;
                result.push(s_ref.to_string());
            } else {
                return Err(VmError::panic("Expected string values in template"));
            }
        }
        Ok(result)
    }
}

// ==================== Rune-Exposed Standalone Functions ====================

use rune::runtime::Mut;

/// Prepare a PutItem operation (Rune-exposed).
/// Usage: ctx.prepare_put_item("insert", "table_name", ["pk", "sk", "data"])
#[rune::function(instance)]
pub fn dynamo_prepare_put_item(
    mut ctx: Mut<DynamoContext>,
    name: Ref<str>,
    table_name: Ref<str>,
    template_keys: Vec<Ref<str>>,
) {
    let keys: Vec<String> = template_keys.iter().map(|s| s.to_string()).collect();
    ctx.prepare_put_item(&name, &table_name, keys);
}

/// Prepare a GetItem operation (Rune-exposed).
/// Usage: ctx.prepare_get_item("read", "table_name", ["pk", "sk"])
#[rune::function(instance)]
pub fn dynamo_prepare_get_item(
    mut ctx: Mut<DynamoContext>,
    name: Ref<str>,
    table_name: Ref<str>,
    key_template: Vec<Ref<str>>,
) {
    let keys: Vec<String> = key_template.iter().map(|s| s.to_string()).collect();
    ctx.prepare_get_item(&name, &table_name, keys, None);
}

/// Prepare a Query operation (Rune-exposed).
/// Usage: ctx.prepare_query("query_by_pk", "table_name", "pk = :pk", [":pk"])
#[rune::function(instance)]
pub fn dynamo_prepare_query(
    mut ctx: Mut<DynamoContext>,
    name: Ref<str>,
    table_name: Ref<str>,
    key_condition: Ref<str>,
    value_template: Vec<Ref<str>>,
) {
    let keys: Vec<String> = value_template.iter().map(|s| s.to_string()).collect();
    ctx.prepare_query(&name, &table_name, &key_condition, keys, None, None);
}

/// Execute a prepared operation (Rune-exposed).
/// Usage: let result = ctx.execute_prepared("read", [pk_value, sk_value]).await
#[rune::function(instance)]
pub async fn dynamo_execute_prepared(
    ctx: Ref<DynamoContext>,
    name: Ref<str>,
    values: Vec<Value>,
) -> Result<Value, DynamoError> {
    // Convert Vec<Value> to RuneVec
    let mut rune_vec = RuneVec::new();
    for v in values {
        rune_vec
            .push(v)
            .map_err(|e| DynamoError::invalid_parameter(format!("Vec push error: {}", e)))?;
    }
    ctx.execute_prepared(&name, &rune_vec).await
}

// ==================== Rune-Exposed DynamoDB Operations ====================

use super::operations::{AttributeDefWrapper, KeySchemaWrapper, ThroughputWrapper};

/// Helper to convert a Rune Object to HashMap<String, AttributeValue>
fn object_to_item(obj: &Object) -> Result<HashMap<String, AttributeValue>, DynamoError> {
    let mut map = HashMap::with_capacity(obj.len());
    for (key, val) in obj.iter() {
        let attr = rune_to_attribute_value(val).map_err(|e| {
            DynamoError::invalid_parameter(format!("Failed to convert '{}': {}", key, e))
        })?;
        map.insert(key.to_string(), attr);
    }
    Ok(map)
}

/// Helper to convert a Rune Object to HashMap<String, String> (for expression attribute names)
fn object_to_string_map(obj: &Object) -> Result<HashMap<String, String>, DynamoError> {
    let mut map = HashMap::with_capacity(obj.len());
    for (key, val) in obj.iter() {
        if let Value::String(s) = val {
            let s_ref = s
                .borrow_ref()
                .map_err(|e| DynamoError::invalid_parameter(format!("Borrow error: {}", e)))?;
            map.insert(key.to_string(), s_ref.to_string());
        } else {
            return Err(DynamoError::invalid_parameter(format!(
                "Expected string value for key '{}'",
                key
            )));
        }
    }
    Ok(map)
}

/// Helper to extract optional string from options object
fn get_opt_string(obj: &Object, key: &str) -> Option<String> {
    obj.get(key).and_then(|v| {
        if let Value::String(s) = v {
            s.borrow_ref().ok().map(|r| r.to_string())
        } else {
            None
        }
    })
}

/// Helper to extract optional bool from options object
fn get_opt_bool(obj: &Object, key: &str) -> Option<bool> {
    obj.get(key).and_then(|v| {
        if let Value::Bool(b) = v {
            Some(*b)
        } else {
            None
        }
    })
}

/// Helper to extract optional i64 from options object
fn get_opt_i64(obj: &Object, key: &str) -> Option<i64> {
    obj.get(key).and_then(|v| {
        if let Value::Integer(i) = v {
            Some(*i)
        } else {
            None
        }
    })
}

/// Helper to extract optional Object from options object
fn get_opt_object(obj: &Object, key: &str) -> Option<Object> {
    obj.get(key).and_then(|v| {
        if let Value::Object(o) = v {
            o.borrow_ref().ok().and_then(|r| r.try_clone().ok())
        } else {
            None
        }
    })
}

/// Helper to convert a single item HashMap to Rune Object
fn item_to_object(item: HashMap<String, AttributeValue>) -> Result<Object, DynamoError> {
    let mut obj = Object::new();
    for (key, val) in item {
        let rune_val = attribute_value_to_rune(&val).map_err(|e| {
            DynamoError::invalid_parameter(format!("Convert error for '{}': {}", key, e))
        })?;
        let rune_key = rune::alloc::String::try_from(key.as_str())
            .map_err(|e| DynamoError::invalid_parameter(format!("Key conversion error: {}", e)))?;
        let _ = obj.insert(rune_key, rune_val);
    }
    Ok(obj)
}

/// Helper to convert items to Rune Vec of Objects
fn items_to_rune_vec(items: Vec<HashMap<String, AttributeValue>>) -> Result<Value, DynamoError> {
    let mut vec = RuneVec::with_capacity(items.len()).map_err(|e| {
        DynamoError::invalid_parameter(format!("Failed to allocate RuneVec: {}", e))
    })?;
    for item in items {
        let obj = item_to_object(item)?;
        vec.push(Value::Object(Shared::new(obj).map_err(|e| {
            DynamoError::invalid_parameter(format!("Shared creation error: {}", e))
        })?))
        .map_err(|e| DynamoError::invalid_parameter(format!("Vec push error: {}", e)))?;
    }
    Ok(Value::Vec(Shared::new(vec).map_err(|e| {
        DynamoError::invalid_parameter(format!("Shared creation error: {}", e))
    })?))
}

/// Create a new client with SessionConfig (Rune-exposed).
///
/// Usage:
/// ```rune
/// let config = session_config()
///     .endpoint("http://localhost:8000")
///     .region("us-east-1");
/// let client = ctx.create_client(config).await;
/// ```
#[rune::function(instance, path = create_client)]
pub async fn dynamo_create_client(
    mut ctx: rune::runtime::Mut<DynamoContext>,
    config: SessionConfig,
) -> Result<(), DynamoError> {
    let client = super::client::DynamoClient::from_session_config(&config).await?;
    ctx.named_clients
        .insert("_temp_client".to_string(), Arc::new(client));
    Ok(())
}

/// Create a named client with SessionConfig (Rune-exposed).
///
/// Usage:
/// ```rune
/// let config = session_config()
///     .endpoint("http://localhost:8000")
///     .region("us-east-1");
/// ctx.create_client_named("secondary", config).await;
/// ```
#[rune::function(instance, path = create_client_named)]
pub async fn dynamo_create_client_named(
    mut ctx: rune::runtime::Mut<DynamoContext>,
    name: Ref<str>,
    config: SessionConfig,
) -> Result<(), DynamoError> {
    let client = super::client::DynamoClient::from_session_config(&config).await?;
    ctx.named_clients.insert(name.to_string(), Arc::new(client));
    Ok(())
}

/// Create a table (Rune-exposed).
/// Usage: ctx.create_table(table_name, key_schema, attribute_defs, throughput).await
/// Where throughput is optional (use Some(dynamodb::dynamo_throughput(r, w)) or None)
#[rune::function(instance, path = create_table)]
pub async fn dynamo_create_table(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    key_schema: RuneVec,
    attribute_definitions: RuneVec,
    provisioned_throughput: Option<Value>,
) -> Result<(), DynamoError> {
    use aws_sdk_dynamodb::types::{AttributeDefinition, KeySchemaElement};

    // Convert key schema
    let mut ks_vec: Vec<KeySchemaElement> = Vec::new();
    for v in key_schema.iter() {
        if let Value::Any(any) = v {
            let borrowed = any.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("KeySchema borrow error: {}", e))
            })?;
            if let Some(wrapper) = borrowed.downcast_borrow_ref::<KeySchemaWrapper>() {
                ks_vec.push(wrapper.inner.clone());
            } else {
                return Err(DynamoError::invalid_parameter("Expected KeySchemaWrapper"));
            }
        } else {
            return Err(DynamoError::invalid_parameter("Expected KeySchemaWrapper"));
        }
    }

    // Convert attribute definitions
    let mut ad_vec: Vec<AttributeDefinition> = Vec::new();
    for v in attribute_definitions.iter() {
        if let Value::Any(any) = v {
            let borrowed = any.borrow_ref().map_err(|e| {
                DynamoError::invalid_parameter(format!("AttributeDef borrow error: {}", e))
            })?;
            if let Some(wrapper) = borrowed.downcast_borrow_ref::<AttributeDefWrapper>() {
                ad_vec.push(wrapper.inner.clone());
            } else {
                return Err(DynamoError::invalid_parameter(
                    "Expected AttributeDefWrapper",
                ));
            }
        } else {
            return Err(DynamoError::invalid_parameter(
                "Expected AttributeDefWrapper",
            ));
        }
    }

    // Convert provisioned throughput
    let pt = if let Some(Value::Any(any)) = provisioned_throughput {
        let borrowed = any.borrow_ref().map_err(|e| {
            DynamoError::invalid_parameter(format!("Throughput borrow error: {}", e))
        })?;
        borrowed
            .downcast_borrow_ref::<ThroughputWrapper>()
            .map(|wrapper| wrapper.inner.clone())
    } else {
        None
    };

    ctx.default_client
        .create_table(
            table_name.as_ref(),
            ks_vec,
            ad_vec,
            None, // billing_mode
            pt,
            None, // gsi
            None, // lsi
        )
        .await
}

/// Delete a table (Rune-exposed).
#[rune::function(instance, path = delete_table)]
pub async fn dynamo_delete_table(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
) -> Result<(), DynamoError> {
    ctx.default_client.delete_table(table_name.as_ref()).await
}

/// Wait for table to become active (Rune-exposed).
#[rune::function(instance, path = wait_table_active)]
pub async fn dynamo_wait_table_active(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
) -> Result<(), DynamoError> {
    ctx.default_client
        .wait_table_active(table_name.as_ref())
        .await
}

/// Put an item (Rune-exposed).
/// Options object can contain: condition_expression, expression_attribute_names, expression_attribute_values
#[rune::function(instance, path = put_item)]
pub async fn dynamo_put_item(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    item: Ref<Object>,
    options: Option<Ref<Object>>,
) -> Result<(), DynamoError> {
    let item_map = object_to_item(&item)?;

    let (cond, names, values) = if let Some(opts) = options {
        let cond = get_opt_string(&opts, "condition_expression");
        let names = get_opt_object(&opts, "expression_attribute_names")
            .map(|o| object_to_string_map(&o))
            .transpose()?;
        let values = get_opt_object(&opts, "expression_attribute_values")
            .map(|o| object_to_item(&o))
            .transpose()?;
        (cond, names, values)
    } else {
        (None, None, None)
    };

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let start = ctx.start_request();
        let result = ipc
            .put_item(
                session_id,
                table_name.as_ref(),
                &item_map,
                cond.as_deref(),
                names.as_ref(),
                values.as_ref(),
                0, // return_values = NONE
            )
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, 1);
        Ok(())
    } else {
        let start = ctx.start_request();
        ctx.default_client
            .put_item(table_name.as_ref(), item_map, cond, names, values)
            .await?;
        ctx.complete_request(start, 1);
        Ok(())
    }
}

/// Get an item (Rune-exposed).
/// Options object can contain: projection_expression, consistent_read, expression_attribute_names
#[rune::function(instance, path = get_item)]
pub async fn dynamo_get_item(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    key: Ref<Object>,
    options: Option<Ref<Object>>,
) -> Result<Value, DynamoError> {
    let key_map = object_to_item(&key)?;

    let (proj, consistent, names) = if let Some(opts) = options {
        let proj = get_opt_string(&opts, "projection_expression");
        let consistent = get_opt_bool(&opts, "consistent_read");
        let names = get_opt_object(&opts, "expression_attribute_names")
            .map(|o| object_to_string_map(&o))
            .transpose()?;
        (proj, consistent, names)
    } else {
        (None, None, None)
    };

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let start = ctx.start_request();
        let result = ipc
            .get_item(
                session_id,
                table_name.as_ref(),
                &key_map,
                consistent.unwrap_or(false),
                proj.as_deref(),
                names.as_ref(),
            )
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        let item_count = if result.item.is_some() { 1 } else { 0 };
        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, item_count);

        match result.item {
            Some(item) => {
                let obj = item_to_object(item)?;
                Ok(Value::Object(Shared::new(obj).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Shared error: {}", e))
                })?))
            }
            None => Ok(Value::EmptyTuple),
        }
    } else {
        let start = ctx.start_request();
        let result = ctx
            .default_client
            .get_item(
                table_name.as_ref(),
                key_map,
                proj.as_deref(),
                consistent,
                names,
            )
            .await?;

        let item_count = if result.is_some() { 1 } else { 0 };
        ctx.complete_request(start, item_count);

        match result {
            Some(item) => {
                let obj = item_to_object(item)?;
                Ok(Value::Object(Shared::new(obj).map_err(|e| {
                    DynamoError::invalid_parameter(format!("Shared error: {}", e))
                })?))
            }
            None => Ok(Value::EmptyTuple),
        }
    }
}

/// Delete an item (Rune-exposed).
/// Options object can contain: condition_expression, expression_attribute_names, expression_attribute_values
#[rune::function(instance, path = delete_item)]
pub async fn dynamo_delete_item(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    key: Ref<Object>,
    options: Option<Ref<Object>>,
) -> Result<(), DynamoError> {
    let key_map = object_to_item(&key)?;

    let (cond, names, values) = if let Some(opts) = options {
        let cond = get_opt_string(&opts, "condition_expression");
        let names = get_opt_object(&opts, "expression_attribute_names")
            .map(|o| object_to_string_map(&o))
            .transpose()?;
        let values = get_opt_object(&opts, "expression_attribute_values")
            .map(|o| object_to_item(&o))
            .transpose()?;
        (cond, names, values)
    } else {
        (None, None, None)
    };

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let start = ctx.start_request();
        let result = ipc
            .delete_item(
                session_id,
                table_name.as_ref(),
                &key_map,
                cond.as_deref(),
                names.as_ref(),
                values.as_ref(),
                0, // return_values = NONE
            )
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, 1);
        Ok(())
    } else {
        let start = ctx.start_request();
        ctx.default_client
            .delete_item(table_name.as_ref(), key_map, cond, names, values)
            .await?;
        ctx.complete_request(start, 1);
        Ok(())
    }
}

/// Update an item (Rune-exposed).
/// Options object can contain: update_expression, condition_expression, expression_attribute_names, expression_attribute_values
#[rune::function(instance, path = update_item)]
pub async fn dynamo_update_item(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    key: Ref<Object>,
    options: Option<Ref<Object>>,
) -> Result<(), DynamoError> {
    let key_map = object_to_item(&key)?;

    let (update, cond, names, values) = if let Some(opts) = options {
        let update = get_opt_string(&opts, "update_expression");
        let cond = get_opt_string(&opts, "condition_expression");
        let names = get_opt_object(&opts, "expression_attribute_names")
            .map(|o| object_to_string_map(&o))
            .transpose()?;
        let values = get_opt_object(&opts, "expression_attribute_values")
            .map(|o| object_to_item(&o))
            .transpose()?;
        (update, cond, names, values)
    } else {
        (None, None, None, None)
    };

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let start = ctx.start_request();
        let result = ipc
            .update_item(
                session_id,
                table_name.as_ref(),
                &key_map,
                update.as_deref().unwrap_or(""),
                cond.as_deref(),
                names.as_ref(),
                values.as_ref(),
                0, // return_values = NONE
            )
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, 1);
        Ok(())
    } else {
        let start = ctx.start_request();
        ctx.default_client
            .update_item(table_name.as_ref(), key_map, update, cond, names, values)
            .await?;
        ctx.complete_request(start, 1);
        Ok(())
    }
}

/// Query a table (Rune-exposed).
/// key_condition_expression: Required (e.g., "pk = :pk")
/// expression_attribute_values: Required (map of placeholders to values)
/// options: Optional object with: index_name, filter_expression, projection_expression,
///          expression_attribute_names, limit, scan_index_forward, consistent_read
#[rune::function(instance, path = query)]
pub async fn dynamo_query(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    key_condition_expression: Ref<str>,
    expression_attribute_values: Ref<Object>,
    options: Option<Ref<Object>>,
) -> Result<Value, DynamoError> {
    let values = object_to_item(&expression_attribute_values)?;

    let (idx, filter, proj, names, limit, forward, consistent) = if let Some(opts) = options {
        let idx = get_opt_string(&opts, "index_name");
        let filter = get_opt_string(&opts, "filter_expression");
        let proj = get_opt_string(&opts, "projection_expression");
        let names = get_opt_object(&opts, "expression_attribute_names")
            .map(|o| object_to_string_map(&o))
            .transpose()?;
        let limit = get_opt_i64(&opts, "limit");
        let forward = get_opt_bool(&opts, "scan_index_forward");
        let consistent = get_opt_bool(&opts, "consistent_read");
        (idx, filter, proj, names, limit, forward, consistent)
    } else {
        (None, None, None, None, None, None, None)
    };

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let start = ctx.start_request();
        let result = ipc
            .query(
                session_id,
                table_name.as_ref(),
                idx.as_deref(),
                key_condition_expression.as_ref(),
                filter.as_deref(),
                proj.as_deref(),
                names.as_ref(),
                Some(&values),
                limit.map(|l| l as u32),
                consistent.unwrap_or(false),
                forward.unwrap_or(true),
                None, // exclusive_start_key
            )
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, result.items.len() as u64);
        items_to_rune_vec(result.items)
    } else {
        let start = ctx.start_request();
        let items = ctx
            .default_client
            .query(
                table_name.as_ref(),
                idx.as_deref(),
                key_condition_expression.as_ref(),
                filter.as_deref(),
                proj.as_deref(),
                names,
                Some(values),
                limit.map(|l| l as i32),
                forward,
                consistent,
            )
            .await?;
        ctx.complete_request(start, items.len() as u64);
        items_to_rune_vec(items)
    }
}

/// Scan a table (Rune-exposed).
/// options: Optional object with: index_name, filter_expression, projection_expression,
///          expression_attribute_names, expression_attribute_values, limit,
///          segment, total_segments, consistent_read
#[rune::function(instance, path = scan)]
pub async fn dynamo_scan(
    ctx: Ref<DynamoContext>,
    table_name: Ref<str>,
    options: Option<Ref<Object>>,
) -> Result<Value, DynamoError> {
    let (idx, filter, proj, names, values, limit, segment, total, consistent) =
        if let Some(opts) = options {
            let idx = get_opt_string(&opts, "index_name");
            let filter = get_opt_string(&opts, "filter_expression");
            let proj = get_opt_string(&opts, "projection_expression");
            let names = get_opt_object(&opts, "expression_attribute_names")
                .map(|o| object_to_string_map(&o))
                .transpose()?;
            let values = get_opt_object(&opts, "expression_attribute_values")
                .map(|o| object_to_item(&o))
                .transpose()?;
            let limit = get_opt_i64(&opts, "limit");
            let segment = get_opt_i64(&opts, "segment");
            let total = get_opt_i64(&opts, "total_segments");
            let consistent = get_opt_bool(&opts, "consistent_read");
            (
                idx, filter, proj, names, values, limit, segment, total, consistent,
            )
        } else {
            (None, None, None, None, None, None, None, None, None)
        };

    // Use IPC client if available, otherwise use direct client
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let start = ctx.start_request();
        let result = ipc
            .scan(
                session_id,
                table_name.as_ref(),
                idx.as_deref(),
                filter.as_deref(),
                proj.as_deref(),
                names.as_ref(),
                values.as_ref(),
                limit.map(|l| l as u32),
                consistent.unwrap_or(false),
                segment.map(|s| s as u32),
                total.map(|t| t as u32),
                None, // exclusive_start_key
            )
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, result.items.len() as u64);
        items_to_rune_vec(result.items)
    } else {
        let start = ctx.start_request();
        let items = ctx
            .default_client
            .scan(
                table_name.as_ref(),
                idx.as_deref(),
                filter.as_deref(),
                proj.as_deref(),
                names,
                values,
                limit.map(|l| l as i32),
                segment.map(|s| s as i32),
                total.map(|t| t as i32),
                consistent,
            )
            .await?;

        ctx.complete_request(start, items.len() as u64);
        items_to_rune_vec(items)
    }
}

/// Batch write items (Rune-exposed).
/// Takes a map of table_name -> array of write requests (objects with "put" or "delete" key).
#[rune::function(instance, path = batch_write_item)]
pub async fn dynamo_batch_write_item(
    ctx: Ref<DynamoContext>,
    request_items: Ref<Object>,
) -> Result<(), DynamoError> {
    use crate::ipc::alternator_client::BatchWriteRequest;
    use aws_sdk_dynamodb::types::{DeleteRequest, PutRequest, WriteRequest};

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let mut ipc_items_map: HashMap<String, Vec<BatchWriteRequest>> = HashMap::new();

        for (table_name, requests_val) in request_items.iter() {
            if let Value::Vec(requests) = requests_val {
                let requests_ref = requests
                    .borrow_ref()
                    .map_err(|e| DynamoError::invalid_parameter(format!("Borrow error: {}", e)))?;
                let mut write_requests = Vec::new();

                for req in requests_ref.iter() {
                    if let Value::Object(req_obj) = req {
                        let req_ref = req_obj.borrow_ref().map_err(|e| {
                            DynamoError::invalid_parameter(format!("Request borrow error: {}", e))
                        })?;

                        if let Some(Value::Object(item)) = req_ref.get("put") {
                            let item_ref = item.borrow_ref().map_err(|e| {
                                DynamoError::invalid_parameter(format!("Item borrow error: {}", e))
                            })?;
                            let item_map = object_to_item(&item_ref)?;
                            write_requests.push(BatchWriteRequest::Put(item_map));
                        } else if let Some(Value::Object(key)) = req_ref.get("delete") {
                            let key_ref = key.borrow_ref().map_err(|e| {
                                DynamoError::invalid_parameter(format!("Key borrow error: {}", e))
                            })?;
                            let key_map = object_to_item(&key_ref)?;
                            write_requests.push(BatchWriteRequest::Delete(key_map));
                        }
                    }
                }

                ipc_items_map.insert(table_name.to_string(), write_requests);
            }
        }

        let start = ctx.start_request();
        let result = ipc
            .batch_write_item(session_id, &ipc_items_map)
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, 0);
        return Ok(());
    }

    // SDK path
    let mut items_map: HashMap<String, Vec<WriteRequest>> = HashMap::new();

    for (table_name, requests_val) in request_items.iter() {
        if let Value::Vec(requests) = requests_val {
            let requests_ref = requests
                .borrow_ref()
                .map_err(|e| DynamoError::invalid_parameter(format!("Borrow error: {}", e)))?;
            let mut write_requests = Vec::new();

            for req in requests_ref.iter() {
                if let Value::Object(req_obj) = req {
                    let req_ref = req_obj.borrow_ref().map_err(|e| {
                        DynamoError::invalid_parameter(format!("Request borrow error: {}", e))
                    })?;

                    // Check for "put" key
                    if let Some(Value::Object(item)) = req_ref.get("put") {
                        let item_ref = item.borrow_ref().map_err(|e| {
                            DynamoError::invalid_parameter(format!("Item borrow error: {}", e))
                        })?;
                        let item_map = object_to_item(&item_ref)?;
                        let put_req = PutRequest::builder()
                            .set_item(Some(item_map))
                            .build()
                            .map_err(|e| {
                                DynamoError::invalid_parameter(format!(
                                    "PutRequest build error: {}",
                                    e
                                ))
                            })?;
                        write_requests.push(WriteRequest::builder().put_request(put_req).build());
                    }
                    // Check for "delete" key
                    else if let Some(Value::Object(key)) = req_ref.get("delete") {
                        let key_ref = key.borrow_ref().map_err(|e| {
                            DynamoError::invalid_parameter(format!("Key borrow error: {}", e))
                        })?;
                        let key_map = object_to_item(&key_ref)?;
                        let del_req = DeleteRequest::builder()
                            .set_key(Some(key_map))
                            .build()
                            .map_err(|e| {
                                DynamoError::invalid_parameter(format!(
                                    "DeleteRequest build error: {}",
                                    e
                                ))
                            })?;
                        write_requests
                            .push(WriteRequest::builder().delete_request(del_req).build());
                    }
                }
            }

            items_map.insert(table_name.to_string(), write_requests);
        }
    }

    let total_items: u64 = items_map.values().map(|v| v.len() as u64).sum();
    let start = ctx.start_request();
    ctx.default_client
        .batch_write_item_with_retry(items_map, ctx.config.max_retries)
        .await?;
    ctx.complete_request(start, total_items);
    Ok(())
}

/// Batch get items (Rune-exposed).
/// Takes a map of table_name -> array of key objects.
#[rune::function(instance, path = batch_get_item)]
pub async fn dynamo_batch_get_item(
    ctx: Ref<DynamoContext>,
    request_items: Ref<Object>,
) -> Result<Value, DynamoError> {
    use crate::ipc::alternator_client::BatchGetRequestItems;
    use aws_sdk_dynamodb::types::KeysAndAttributes;

    // Use IPC client if available
    if let (Some(ipc), Some(session_id)) = (&ctx.ipc_client, ctx.ipc_session_id) {
        let mut ipc_items_map: HashMap<String, BatchGetRequestItems> = HashMap::new();

        for (table_name, keys_val) in request_items.iter() {
            if let Value::Vec(keys) = keys_val {
                let keys_ref = keys
                    .borrow_ref()
                    .map_err(|e| DynamoError::invalid_parameter(format!("Borrow error: {}", e)))?;
                let mut key_list = Vec::new();

                for key in keys_ref.iter() {
                    if let Value::Object(key_obj) = key {
                        let key_ref = key_obj.borrow_ref().map_err(|e| {
                            DynamoError::invalid_parameter(format!("Key borrow error: {}", e))
                        })?;
                        let key_map = object_to_item(&key_ref)?;
                        key_list.push(key_map);
                    }
                }

                ipc_items_map.insert(
                    table_name.to_string(),
                    BatchGetRequestItems {
                        keys: key_list,
                        consistent_read: false,
                        projection_expression: None,
                        expression_attribute_names: None,
                    },
                );
            }
        }

        let start = ctx.start_request();
        let result = ipc
            .batch_get_item(session_id, &ipc_items_map)
            .await
            .map_err(|e| DynamoError::from_sdk_error_string(e.to_string()))?;

        let total_items: u64 = result.responses.values().map(|v| v.len() as u64).sum();
        ctx.complete_request_with_driver_latency(start, result.driver_latency_ns, total_items);

        // Convert response to Rune Object
        let mut rune_result = Object::new();
        for (table_name, items) in result.responses {
            let items_vec = items_to_rune_vec(items)?;
            let rune_key = rune::alloc::String::try_from(table_name.as_str())
                .map_err(|e| {
                    DynamoError::invalid_parameter(format!("Key conversion error: {}", e))
                })?;
            let _ = rune_result.insert(rune_key, items_vec);
        }

        return Ok(Value::Object(Shared::new(rune_result).map_err(|e| {
            DynamoError::invalid_parameter(format!("Shared error: {}", e))
        })?));
    }

    // SDK path
    let mut items_map: HashMap<String, KeysAndAttributes> = HashMap::new();

    for (table_name, keys_val) in request_items.iter() {
        if let Value::Vec(keys) = keys_val {
            let keys_ref = keys
                .borrow_ref()
                .map_err(|e| DynamoError::invalid_parameter(format!("Borrow error: {}", e)))?;
            let mut key_list = Vec::new();

            for key in keys_ref.iter() {
                if let Value::Object(key_obj) = key {
                    let key_ref = key_obj.borrow_ref().map_err(|e| {
                        DynamoError::invalid_parameter(format!("Key borrow error: {}", e))
                    })?;
                    let key_map = object_to_item(&key_ref)?;
                    key_list.push(key_map);
                }
            }

            let keys_and_attrs = KeysAndAttributes::builder()
                .set_keys(Some(key_list))
                .build()
                .map_err(|e| {
                    DynamoError::invalid_parameter(format!("KeysAndAttributes build error: {}", e))
                })?;
            items_map.insert(table_name.to_string(), keys_and_attrs);
        }
    }

    let start = ctx.start_request();
    let responses = ctx
        .default_client
        .batch_get_item_with_retry(items_map, ctx.config.max_retries)
        .await?;
    let total_items: u64 = responses.values().map(|v| v.len() as u64).sum();
    ctx.complete_request(start, total_items);

    // Convert response to Rune Object
    let mut result = Object::new();
    for (table_name, items) in responses {
        let items_vec = items_to_rune_vec(items)?;
        let rune_key = rune::alloc::String::try_from(table_name.as_str())
            .map_err(|e| DynamoError::invalid_parameter(format!("Key conversion error: {}", e)))?;
        let _ = result.insert(rune_key, items_vec);
    }

    Ok(Value::Object(Shared::new(result).map_err(|e| {
        DynamoError::invalid_parameter(format!("Shared error: {}", e))
    })?))
}

#[cfg(test)]
mod integration_tests {
    use super::*;
    use aws_sdk_dynamodb::types::{
        AttributeDefinition, BillingMode, KeySchemaElement, KeyType, ScalarAttributeType,
    };
    use rune::alloc::String as RuneString;

    /// Helper to create a test context pointing to DynamoDB Local.
    async fn create_test_context() -> DynamoContext {
        let conf = DynamoDbConf {
            enabled: true,
            endpoint: Some("http://localhost:8000".to_string()),
            region: "us-east-1".to_string(),
            access_key_id: Some("fakeMyKeyId".to_string()),
            secret_access_key: Some("fakeSecretAccessKey".to_string()),
            max_connections: 10,
            connect_timeout_ms: 5000,
            read_timeout_ms: 30000,
            max_retries: 3,
            compression: crate::config::CompressionConf::default(),
            alternator_driver_socket: None,
            alternator_adapter_image: None,
        };
        let (ctx, _docker_manager) = DynamoContext::new(conf)
            .await
            .expect("Failed to create context");
        ctx
    }

    /// Helper to generate a unique table name for tests.
    fn test_table_name(suffix: &str) -> String {
        use std::time::{SystemTime, UNIX_EPOCH};
        let ts = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis();
        format!("latte_ctx_test_{}_{}", suffix, ts)
    }

    /// Helper to create a simple test table (pk: String).
    async fn create_simple_table(ctx: &DynamoContext, table_name: &str) {
        let client = ctx.client();
        let key_schema = vec![KeySchemaElement::builder()
            .attribute_name("pk")
            .key_type(KeyType::Hash)
            .build()
            .unwrap()];
        let attr_defs = vec![AttributeDefinition::builder()
            .attribute_name("pk")
            .attribute_type(ScalarAttributeType::S)
            .build()
            .unwrap()];

        client
            .create_table(
                table_name,
                key_schema,
                attr_defs,
                Some(BillingMode::PayPerRequest),
                None,
                None,
                None,
            )
            .await
            .expect("Failed to create table");

        client
            .wait_table_active(table_name)
            .await
            .expect("Table did not become active");
    }

    /// Helper to create a composite key table (pk: String, sk: String).
    async fn create_composite_table(ctx: &DynamoContext, table_name: &str) {
        let client = ctx.client();
        let key_schema = vec![
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
            KeySchemaElement::builder()
                .attribute_name("sk")
                .key_type(KeyType::Range)
                .build()
                .unwrap(),
        ];
        let attr_defs = vec![
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
            AttributeDefinition::builder()
                .attribute_name("sk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        ];

        client
            .create_table(
                table_name,
                key_schema,
                attr_defs,
                Some(BillingMode::PayPerRequest),
                None,
                None,
                None,
            )
            .await
            .expect("Failed to create table");

        client
            .wait_table_active(table_name)
            .await
            .expect("Table did not become active");
    }

    /// Helper to clean up a test table.
    async fn cleanup_table(ctx: &DynamoContext, table_name: &str) {
        let _ = ctx.client().delete_table(table_name).await;
    }

    // ==========================================================================
    // Context Creation Tests
    // ==========================================================================

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_context_creation() {
        let ctx = create_test_context().await;
        assert!(ctx.is_alternator());
        assert_eq!(ctx.load_cycle_count, 0);
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_context_elapsed_secs() {
        let ctx = create_test_context().await;
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        let elapsed = ctx.elapsed_secs();
        assert!(elapsed >= 0.1);
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_context_reset() {
        let mut ctx = create_test_context().await;
        ctx.load_cycle_count = 100;
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;

        ctx.reset();

        // Elapsed should be close to 0 after reset
        let elapsed = ctx.elapsed_secs();
        assert!(elapsed < 0.05);
    }

    // ==========================================================================
    // Prepared Operations Tests
    // ==========================================================================

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_prepare_and_execute_put_item() {
        let mut ctx = create_test_context().await;
        let table_name = test_table_name("prep_put");

        create_simple_table(&ctx, &table_name).await;

        // Prepare a PutItem operation
        ctx.prepare_put_item(
            "insert",
            &table_name,
            vec!["pk".to_string(), "data".to_string()],
        );

        // Execute the prepared operation
        let mut values = rune::runtime::Vec::new();
        values
            .push(Value::String(
                Shared::new(RuneString::try_from("test_key").unwrap()).unwrap(),
            ))
            .unwrap();
        values
            .push(Value::String(
                Shared::new(RuneString::try_from("test_value").unwrap()).unwrap(),
            ))
            .unwrap();

        let result = ctx.execute_prepared("insert", &values).await;
        assert!(result.is_ok());

        // Verify the item was inserted
        let mut key = std::collections::HashMap::new();
        key.insert(
            "pk".to_string(),
            aws_sdk_dynamodb::types::AttributeValue::S("test_key".to_string()),
        );
        let item = ctx
            .client()
            .get_item(&table_name, key, None, None, None)
            .await
            .expect("get failed");
        assert!(item.is_some());
        assert_eq!(
            item.unwrap().get("data"),
            Some(&aws_sdk_dynamodb::types::AttributeValue::S(
                "test_value".to_string()
            ))
        );

        cleanup_table(&ctx, &table_name).await;
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_prepare_and_execute_get_item() {
        let mut ctx = create_test_context().await;
        let table_name = test_table_name("prep_get");

        create_simple_table(&ctx, &table_name).await;

        // Insert a test item directly
        let mut item = std::collections::HashMap::new();
        item.insert(
            "pk".to_string(),
            aws_sdk_dynamodb::types::AttributeValue::S("get_key".to_string()),
        );
        item.insert(
            "data".to_string(),
            aws_sdk_dynamodb::types::AttributeValue::S("get_value".to_string()),
        );
        ctx.client()
            .put_item(&table_name, item, None, None, None)
            .await
            .expect("put failed");

        // Prepare a GetItem operation
        ctx.prepare_get_item("read", &table_name, vec!["pk".to_string()], None);

        // Execute the prepared operation
        let mut values = rune::runtime::Vec::new();
        values
            .push(Value::String(
                Shared::new(RuneString::try_from("get_key").unwrap()).unwrap(),
            ))
            .unwrap();

        let result = ctx
            .execute_prepared("read", &values)
            .await
            .expect("execute failed");

        // Result should be an Object with the data
        if let Value::Object(obj_shared) = result {
            let obj = obj_shared.borrow_ref().expect("borrow failed");
            let data = obj.get("data").expect("data field missing");
            if let Value::String(s) = data {
                assert_eq!(s.borrow_ref().unwrap().as_str(), "get_value");
            } else {
                panic!("Expected string value for data");
            }
        } else {
            panic!("Expected Object result");
        }

        cleanup_table(&ctx, &table_name).await;
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_prepare_and_execute_query() {
        let mut ctx = create_test_context().await;
        let table_name = test_table_name("prep_query");

        create_composite_table(&ctx, &table_name).await;

        // Insert test items
        for i in 0..5 {
            let mut item = std::collections::HashMap::new();
            item.insert(
                "pk".to_string(),
                aws_sdk_dynamodb::types::AttributeValue::S("query_pk".to_string()),
            );
            item.insert(
                "sk".to_string(),
                aws_sdk_dynamodb::types::AttributeValue::S(format!("sk_{:03}", i)),
            );
            item.insert(
                "data".to_string(),
                aws_sdk_dynamodb::types::AttributeValue::N(i.to_string()),
            );
            ctx.client()
                .put_item(&table_name, item, None, None, None)
                .await
                .expect("put failed");
        }

        // Prepare a Query operation
        ctx.prepare_query(
            "query_by_pk",
            &table_name,
            "pk = :pk",
            vec![":pk".to_string()],
            None,
            None,
        );

        // Execute the prepared operation
        let mut values = rune::runtime::Vec::new();
        values
            .push(Value::String(
                Shared::new(RuneString::try_from("query_pk").unwrap()).unwrap(),
            ))
            .unwrap();

        let result = ctx
            .execute_prepared("query_by_pk", &values)
            .await
            .expect("execute failed");

        // Result should be a Vec with 5 items
        if let Value::Vec(vec_shared) = result {
            let vec = vec_shared.borrow_ref().expect("borrow failed");
            assert_eq!(vec.len(), 5);
        } else {
            panic!("Expected Vec result");
        }

        cleanup_table(&ctx, &table_name).await;
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_prepared_operation_not_found() {
        let ctx = create_test_context().await;

        let mut values = rune::runtime::Vec::new();
        values
            .push(Value::String(
                Shared::new(RuneString::try_from("test").unwrap()).unwrap(),
            ))
            .unwrap();

        let result = ctx.execute_prepared("nonexistent", &values).await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(err.message.contains("not found"));
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_prepared_operation_wrong_value_count() {
        let mut ctx = create_test_context().await;
        let table_name = test_table_name("prep_wrong");

        create_simple_table(&ctx, &table_name).await;

        // Prepare expects 2 values
        ctx.prepare_put_item(
            "insert",
            &table_name,
            vec!["pk".to_string(), "data".to_string()],
        );

        // Only provide 1 value
        let mut values = rune::runtime::Vec::new();
        values
            .push(Value::String(
                Shared::new(RuneString::try_from("key").unwrap()).unwrap(),
            ))
            .unwrap();

        let result = ctx.execute_prepared("insert", &values).await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(err.message.contains("Expected 2 values but got 1"));

        cleanup_table(&ctx, &table_name).await;
    }

    // ==========================================================================
    // Multi-Client Tests
    // ==========================================================================

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_create_named_client() {
        let mut ctx = create_test_context().await;

        // Create a named client
        let mut config = Object::new();
        config
            .insert(
                rune::alloc::String::try_from("endpoint").unwrap(),
                Value::String(
                    Shared::new(RuneString::try_from("http://localhost:8000").unwrap()).unwrap(),
                ),
            )
            .unwrap();
        config
            .insert(
                rune::alloc::String::try_from("region").unwrap(),
                Value::String(Shared::new(RuneString::try_from("us-east-1").unwrap()).unwrap()),
            )
            .unwrap();
        config
            .insert(
                rune::alloc::String::try_from("access_key_id").unwrap(),
                Value::String(Shared::new(RuneString::try_from("fakeKey").unwrap()).unwrap()),
            )
            .unwrap();
        config
            .insert(
                rune::alloc::String::try_from("secret_access_key").unwrap(),
                Value::String(Shared::new(RuneString::try_from("fakeSecret").unwrap()).unwrap()),
            )
            .unwrap();

        ctx.create_client_named_from_object("secondary", &config)
            .await
            .expect("create client failed");

        // Get the named client
        let client = ctx.client_named("secondary");
        assert!(client.is_ok());
        assert!(client.unwrap().is_alternator());
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_client_not_found() {
        let ctx = create_test_context().await;

        let result = ctx.client_named("nonexistent");
        match result {
            Err(err) => assert!(err.message.contains("not found")),
            Ok(_) => panic!("Expected error for nonexistent client"),
        }
    }

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_multi_client_operations() {
        let mut ctx = create_test_context().await;
        let table_name = test_table_name("multi_client");

        create_simple_table(&ctx, &table_name).await;

        // Create a secondary client (same endpoint for testing)
        let mut config = Object::new();
        config
            .insert(
                rune::alloc::String::try_from("endpoint").unwrap(),
                Value::String(
                    Shared::new(RuneString::try_from("http://localhost:8000").unwrap()).unwrap(),
                ),
            )
            .unwrap();
        config
            .insert(
                rune::alloc::String::try_from("region").unwrap(),
                Value::String(Shared::new(RuneString::try_from("us-east-1").unwrap()).unwrap()),
            )
            .unwrap();
        config
            .insert(
                rune::alloc::String::try_from("access_key_id").unwrap(),
                Value::String(Shared::new(RuneString::try_from("fakeKey").unwrap()).unwrap()),
            )
            .unwrap();
        config
            .insert(
                rune::alloc::String::try_from("secret_access_key").unwrap(),
                Value::String(Shared::new(RuneString::try_from("fakeSecret").unwrap()).unwrap()),
            )
            .unwrap();

        ctx.create_client_named_from_object("writer", &config)
            .await
            .expect("create client failed");

        // Write with secondary client
        let writer = ctx.client_named("writer").expect("get writer failed");
        let mut item = std::collections::HashMap::new();
        item.insert(
            "pk".to_string(),
            aws_sdk_dynamodb::types::AttributeValue::S("multi_key".to_string()),
        );
        item.insert(
            "source".to_string(),
            aws_sdk_dynamodb::types::AttributeValue::S("writer".to_string()),
        );
        writer
            .put_item(&table_name, item, None, None, None)
            .await
            .expect("put failed");

        // Read with default client
        let reader = ctx.client();
        let mut key = std::collections::HashMap::new();
        key.insert(
            "pk".to_string(),
            aws_sdk_dynamodb::types::AttributeValue::S("multi_key".to_string()),
        );
        let result = reader
            .get_item(&table_name, key, None, None, None)
            .await
            .expect("get failed");

        assert!(result.is_some());
        assert_eq!(
            result.unwrap().get("source"),
            Some(&aws_sdk_dynamodb::types::AttributeValue::S(
                "writer".to_string()
            ))
        );

        cleanup_table(&ctx, &table_name).await;
    }

    // ==========================================================================
    // Statistics Tracking Tests
    // ==========================================================================

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_statistics_tracking() {
        let mut ctx = create_test_context().await;
        let table_name = test_table_name("stats");

        create_simple_table(&ctx, &table_name).await;

        // Prepare and execute some operations to generate stats
        ctx.prepare_put_item("insert", &table_name, vec!["pk".to_string()]);
        ctx.prepare_get_item("read", &table_name, vec!["pk".to_string()], None);

        // Execute operations
        for i in 0..5 {
            let mut values = rune::runtime::Vec::new();
            values
                .push(Value::String(
                    Shared::new(RuneString::try_from(format!("key_{}", i)).unwrap()).unwrap(),
                ))
                .unwrap();
            ctx.execute_prepared("insert", &values)
                .await
                .expect("put failed");
        }

        for i in 0..3 {
            let mut values = rune::runtime::Vec::new();
            values
                .push(Value::String(
                    Shared::new(RuneString::try_from(format!("key_{}", i)).unwrap()).unwrap(),
                ))
                .unwrap();
            ctx.execute_prepared("read", &values)
                .await
                .expect("get failed");
        }

        // Take stats
        let stats = ctx.take_session_stats();

        // Stats should reflect the operations
        // Note: Exact assertion depends on SessionStats implementation
        // At minimum, we should have recorded some requests
        assert!(stats.req_count > 0 || stats.row_count > 0);

        cleanup_table(&ctx, &table_name).await;
    }

    // ==========================================================================
    // Clone for Thread Tests
    // ==========================================================================

    #[tokio::test]
    #[ignore] // Requires DynamoDB Local
    async fn test_clone_for_thread() {
        let ctx = create_test_context().await;

        let cloned = ctx.clone_for_thread().expect("clone failed");

        // Cloned context should have same client
        assert!(cloned.is_alternator());
        assert_eq!(cloned.load_cycle_count, ctx.load_cycle_count);
    }
}
