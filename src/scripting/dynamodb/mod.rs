//! DynamoDB/Alternator support for Latte workloads.
//!
//! This module provides types and functions for running workloads against
//! DynamoDB-compatible databases (AWS DynamoDB, ScyllaDB Alternator).
//!
//! # Architecture
//!
//! The module is organized into several submodules:
//!
//! - [`client`]: Low-level AWS SDK DynamoDB client wrapper
//! - [`context`]: High-level context for workload scripts (similar to CQL's `Context`)
//! - [`error`]: Error types and handling
//! - [`operations`]: Helper functions for building DynamoDB operations
//! - [`types`]: Type conversions between Rune and DynamoDB AttributeValues
//!
//! # Session Configuration
//!
//! Create custom client configurations using the fluent builder API:
//!
//! ```rune
//! use dynamodb::{session_config, s, n, m};
//!
//! pub async fn prepare(ctx) {
//!     // Create a custom session with compression enabled
//!     let config = session_config()
//!         .endpoint("http://localhost:8000")
//!         .region("us-east-1")
//!         .connect_timeout_ms(5000)
//!         .read_timeout_ms(30000)
//!         .max_retries(3)
//!         .request_compression(DynamoDbCompression::Gzip)
//!         .request_compression_min_size(1024)
//!         .response_compression(DynamoDbCompression::Gzip);
//!
//!     // Create a named client with the config
//!     ctx.create_client_named("secondary", config).await;
//!
//!     ctx.load_cycle_count = 1000;
//! }
//! ```
//!
//! # Basic Usage
//!
//! ```rune
//! use dynamodb::{DynamoContext, s, n, m};
//!
//! pub async fn prepare(ctx) {
//!     // Set the number of cycles for the load phase
//!     ctx.load_cycle_count = 1000;
//!
//!     // Prepare a put_item operation for reuse
//!     ctx.prepare_put_item("insert", "my_table",
//!         ["pk", "sk"],  // Key attributes to substitute
//!         ["data"]       // Non-key attributes to substitute
//!     );
//! }
//!
//! pub async fn load(ctx, i) {
//!     // Execute prepared operation with values
//!     ctx.execute_prepared("insert", [s(f"pk_{i}"), s(f"sk_{i}"), s("value")]);
//! }
//!
//! pub async fn run(ctx, i) {
//!     // Direct API calls also work
//!     let result = ctx.get_item("my_table", m(#{
//!         "pk": s(f"pk_{i % 1000}"),
//!         "sk": s(f"sk_{i % 1000}")
//!     }));
//! }
//! ```
//!
//! # Error Handling
//!
//! DynamoDB errors are returned as [`DynamoError`] which provides:
//! - `kind()`: Error category (e.g., "ConditionalCheckFailed", "ResourceNotFound")
//! - `message()`: Detailed error message
//! - `is_retryable()`: Whether the operation can be retried
//! - `is_conditional_check_failed()`: Check for conditional write failures
//!
//! # Known Limitations vs AWS DynamoDB
//!
//! When using ScyllaDB Alternator:
//! - Transactions (`transact_write_items`, `transact_get_items`) are not supported
//! - Some consistency models may differ
//! - Certain advanced features may have different behavior
//!
//! See ALTERNATOR.md for detailed compatibility information.

pub mod client;
pub mod compression;
pub mod context;
pub mod error;
pub mod operations;
pub mod types;

pub use client::SessionConfig;
pub use context::DynamoContext;
pub use error::DynamoError;
pub use types::DynamoValue;

use rune::{ContextError, Module};
use std::collections::HashMap;

/// Install the DynamoDB module into the Rune context.
pub fn install(
    rune_ctx: &mut rune::Context,
    _params: &HashMap<String, String>,
) -> Result<(), ContextError> {
    // DynamoDB context module
    let mut dynamo_module = Module::with_crate("dynamodb")?;

    // Register types
    dynamo_module.ty::<DynamoContext>()?;
    dynamo_module.ty::<DynamoError>()?;
    dynamo_module.ty::<DynamoValue>()?;
    dynamo_module.ty::<SessionConfig>()?;
    dynamo_module.ty::<crate::config::DynamoDbCompression>()?;
    dynamo_module.ty::<operations::KeySchemaWrapper>()?;
    dynamo_module.ty::<operations::AttributeDefWrapper>()?;
    dynamo_module.ty::<operations::ThroughputWrapper>()?;
    dynamo_module.ty::<operations::TransactWriteItemWrapper>()?;
    dynamo_module.ty::<operations::TransactGetItemWrapper>()?;

    // Register DynamoError methods
    dynamo_module.function_meta(DynamoError::kind)?;
    dynamo_module.function_meta(DynamoError::message)?;
    dynamo_module.function_meta(DynamoError::is_conditional_check_failed_rune)?;
    dynamo_module.function_meta(DynamoError::is_retryable_rune)?;
    dynamo_module.function_meta(DynamoError::string_display)?;

    // Register SessionConfig builder function and methods
    dynamo_module.function_meta(client::session_config)?;
    dynamo_module.function_meta(SessionConfig::endpoint)?;
    dynamo_module.function_meta(SessionConfig::region)?;
    dynamo_module.function_meta(SessionConfig::access_key_id)?;
    dynamo_module.function_meta(SessionConfig::secret_access_key)?;
    dynamo_module.function_meta(SessionConfig::max_connections)?;
    dynamo_module.function_meta(SessionConfig::connect_timeout_ms)?;
    dynamo_module.function_meta(SessionConfig::read_timeout_ms)?;
    dynamo_module.function_meta(SessionConfig::max_retries)?;
    dynamo_module.function_meta(SessionConfig::request_compression)?;
    dynamo_module.function_meta(SessionConfig::request_compression_min_size)?;
    dynamo_module.function_meta(SessionConfig::response_compression)?;

    // Register AttributeValue helper functions
    dynamo_module.function_meta(operations::dynamo_s)?;
    dynamo_module.function_meta(operations::dynamo_n)?;
    dynamo_module.function_meta(operations::dynamo_n_f64)?;
    dynamo_module.function_meta(operations::dynamo_bool)?;
    dynamo_module.function_meta(operations::dynamo_null)?;
    dynamo_module.function_meta(operations::dynamo_b)?;
    dynamo_module.function_meta(operations::dynamo_ss)?;
    dynamo_module.function_meta(operations::dynamo_ns)?;
    dynamo_module.function_meta(operations::dynamo_bs)?;
    dynamo_module.function_meta(operations::dynamo_l)?;
    dynamo_module.function_meta(operations::dynamo_m)?;

    // Register schema helper functions
    dynamo_module.function_meta(operations::dynamo_key_schema)?;
    dynamo_module.function_meta(operations::dynamo_attribute_def)?;
    dynamo_module.function_meta(operations::dynamo_throughput)?;

    // Register transaction helper functions
    dynamo_module.function_meta(operations::dynamo_transact_put)?;
    dynamo_module.function_meta(operations::dynamo_transact_delete)?;
    dynamo_module.function_meta(operations::dynamo_transact_get)?;
    dynamo_module.function_meta(operations::dynamo_transact_update)?;
    dynamo_module.function_meta(operations::dynamo_transact_condition_check)?;

    // Register DynamoContext prepared operation methods
    dynamo_module.function_meta(context::dynamo_prepare_put_item)?;
    dynamo_module.function_meta(context::dynamo_prepare_get_item)?;
    dynamo_module.function_meta(context::dynamo_prepare_query)?;
    dynamo_module.function_meta(context::dynamo_execute_prepared)?;

    // Register DynamoContext client creation methods
    dynamo_module.function_meta(context::dynamo_create_client)?;
    dynamo_module.function_meta(context::dynamo_create_client_named)?;

    // Register DynamoContext database operation methods
    dynamo_module.function_meta(context::dynamo_create_table)?;
    dynamo_module.function_meta(context::dynamo_delete_table)?;
    dynamo_module.function_meta(context::dynamo_wait_table_active)?;
    dynamo_module.function_meta(context::dynamo_put_item)?;
    dynamo_module.function_meta(context::dynamo_get_item)?;
    dynamo_module.function_meta(context::dynamo_delete_item)?;
    dynamo_module.function_meta(context::dynamo_update_item)?;
    dynamo_module.function_meta(context::dynamo_query)?;
    dynamo_module.function_meta(context::dynamo_scan)?;
    dynamo_module.function_meta(context::dynamo_batch_write_item)?;
    dynamo_module.function_meta(context::dynamo_batch_get_item)?;

    // Install the module
    rune_ctx.install(&dynamo_module)?;

    Ok(())
}
