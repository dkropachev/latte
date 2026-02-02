//! DynamoDB error types exposed to Rune scripts.

use rune::alloc::fmt::TryWrite;
use rune::alloc::String as RuneString;
use rune::runtime::{Shared, VmResult};
use rune::{vm_try, vm_write, Any};
use std::fmt;

/// Error kinds for DynamoDB operations.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DynamoErrorKind {
    // Client errors
    ValidationError,
    AccessDenied,
    ResourceNotFound,
    ResourceInUse,
    ConditionalCheckFailed,
    TransactionConflict,
    TransactionCanceled,
    ItemCollectionSizeLimitExceeded,

    // Server/throttling errors
    ProvisionedThroughputExceeded,
    RequestLimitExceeded,
    InternalServerError,
    ServiceUnavailable,

    // Connection errors
    ConnectionError,
    Timeout,

    // Latte-specific errors
    PreparedOperationNotFound,
    InvalidParameter,
    ClientNotFound,
    AlternatorUnsupported,

    // Generic error
    Other,
}

impl fmt::Display for DynamoErrorKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ValidationError => write!(f, "ValidationError"),
            Self::AccessDenied => write!(f, "AccessDenied"),
            Self::ResourceNotFound => write!(f, "ResourceNotFound"),
            Self::ResourceInUse => write!(f, "ResourceInUse"),
            Self::ConditionalCheckFailed => write!(f, "ConditionalCheckFailed"),
            Self::TransactionConflict => write!(f, "TransactionConflict"),
            Self::TransactionCanceled => write!(f, "TransactionCanceled"),
            Self::ItemCollectionSizeLimitExceeded => write!(f, "ItemCollectionSizeLimitExceeded"),
            Self::ProvisionedThroughputExceeded => write!(f, "ProvisionedThroughputExceeded"),
            Self::RequestLimitExceeded => write!(f, "RequestLimitExceeded"),
            Self::InternalServerError => write!(f, "InternalServerError"),
            Self::ServiceUnavailable => write!(f, "ServiceUnavailable"),
            Self::ConnectionError => write!(f, "ConnectionError"),
            Self::Timeout => write!(f, "Timeout"),
            Self::PreparedOperationNotFound => write!(f, "PreparedOperationNotFound"),
            Self::InvalidParameter => write!(f, "InvalidParameter"),
            Self::ClientNotFound => write!(f, "ClientNotFound"),
            Self::AlternatorUnsupported => write!(f, "AlternatorUnsupported"),
            Self::Other => write!(f, "Other"),
        }
    }
}

/// DynamoDB error type exposed to Rune scripts.
#[derive(Debug, Any)]
#[rune(item = ::dynamodb)]
pub struct DynamoError {
    pub kind: DynamoErrorKind,
    pub message: String,
}

impl DynamoError {
    pub fn new(kind: DynamoErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }

    pub fn validation_error(message: impl Into<String>) -> Self {
        Self::new(DynamoErrorKind::ValidationError, message)
    }

    pub fn resource_not_found(message: impl Into<String>) -> Self {
        Self::new(DynamoErrorKind::ResourceNotFound, message)
    }

    pub fn conditional_check_failed(message: impl Into<String>) -> Self {
        Self::new(DynamoErrorKind::ConditionalCheckFailed, message)
    }

    pub fn prepared_operation_not_found(name: &str) -> Self {
        Self::new(
            DynamoErrorKind::PreparedOperationNotFound,
            format!("Prepared operation not found: {}", name),
        )
    }

    pub fn client_not_found(name: &str) -> Self {
        Self::new(
            DynamoErrorKind::ClientNotFound,
            format!("Client not found: {}", name),
        )
    }

    pub fn invalid_parameter(message: impl Into<String>) -> Self {
        Self::new(DynamoErrorKind::InvalidParameter, message)
    }

    pub fn alternator_unsupported(operation: &str) -> Self {
        Self::new(
            DynamoErrorKind::AlternatorUnsupported,
            format!(
                "Operation '{}' is not supported by ScyllaDB Alternator",
                operation
            ),
        )
    }

    pub fn connection_error(message: impl Into<String>) -> Self {
        Self::new(DynamoErrorKind::ConnectionError, message)
    }

    /// Alias for connection_error (used for IPC adapter failures).
    pub fn connection(message: impl Into<String>) -> Self {
        Self::new(DynamoErrorKind::ConnectionError, message)
    }

    /// Create an error from a string message (typically from IPC adapter).
    pub fn from_sdk_error_string(message: String) -> Self {
        // Try to determine error kind from message content
        let msg_lower = message.to_lowercase();
        let kind = if msg_lower.contains("conditionalcheckfailed") {
            DynamoErrorKind::ConditionalCheckFailed
        } else if msg_lower.contains("resourcenotfound") || msg_lower.contains("table") && msg_lower.contains("not found") {
            DynamoErrorKind::ResourceNotFound
        } else if msg_lower.contains("validation") {
            DynamoErrorKind::ValidationError
        } else if msg_lower.contains("throughput") || msg_lower.contains("throttl") {
            DynamoErrorKind::ProvisionedThroughputExceeded
        } else if msg_lower.contains("internal") {
            DynamoErrorKind::InternalServerError
        } else if msg_lower.contains("timeout") || msg_lower.contains("connection") {
            DynamoErrorKind::ConnectionError
        } else {
            DynamoErrorKind::Other
        };
        Self::new(kind, message)
    }

    /// Returns true if this error is a conditional check failure.
    /// Useful for handling optimistic locking in workload scripts.
    pub fn is_conditional_check_failed(&self) -> bool {
        self.kind == DynamoErrorKind::ConditionalCheckFailed
    }

    /// Returns true if this error is retryable (throttling, temporary server issues).
    pub fn is_retryable(&self) -> bool {
        matches!(
            self.kind,
            DynamoErrorKind::ProvisionedThroughputExceeded
                | DynamoErrorKind::RequestLimitExceeded
                | DynamoErrorKind::InternalServerError
                | DynamoErrorKind::ServiceUnavailable
                | DynamoErrorKind::Timeout
        )
    }

    pub fn from_sdk_error<E: std::fmt::Display + std::fmt::Debug>(err: E) -> Self {
        let msg = err.to_string();

        // Compute debug string once upfront for pattern matching.
        // This avoids repeated format!() calls in the closure.
        let debug_msg = format!("{:?}", err);

        // Helper to check if a pattern exists in either msg or debug output.
        let matches =
            |pattern: &str| -> bool { msg.contains(pattern) || debug_msg.contains(pattern) };

        // Try to categorize based on error message or debug output
        // Check both display and debug output since DynamoDB Local may format differently
        let kind = if matches("ResourceNotFoundException") {
            DynamoErrorKind::ResourceNotFound
        } else if matches("ResourceInUseException") {
            DynamoErrorKind::ResourceInUse
        } else if matches("ValidationException") {
            DynamoErrorKind::ValidationError
        } else if matches("ConditionalCheckFailedException") || matches("ConditionalCheckFailed") {
            DynamoErrorKind::ConditionalCheckFailed
        } else if matches("ProvisionedThroughputExceededException") {
            DynamoErrorKind::ProvisionedThroughputExceeded
        } else if matches("RequestLimitExceeded") {
            DynamoErrorKind::RequestLimitExceeded
        } else if matches("TransactionConflictException") {
            DynamoErrorKind::TransactionConflict
        } else if matches("TransactionCanceledException") {
            DynamoErrorKind::TransactionCanceled
        } else if matches("AccessDeniedException") {
            DynamoErrorKind::AccessDenied
        } else if matches("InternalServerError") {
            DynamoErrorKind::InternalServerError
        } else if matches("ServiceUnavailable") {
            DynamoErrorKind::ServiceUnavailable
        } else if msg.contains("timeout") || msg.contains("Timeout") {
            DynamoErrorKind::Timeout
        } else if msg.contains("connection") || msg.contains("Connection") {
            DynamoErrorKind::ConnectionError
        } else {
            DynamoErrorKind::Other
        };

        Self::new(kind, msg)
    }

    /// Returns the error kind as a string (for Rune).
    #[rune::function(vm_result)]
    pub fn kind(&self) -> VmResult<rune::Value> {
        let s = self.kind.to_string();
        let rune_str = vm_try!(
            RuneString::try_from(s).map_err(|e| rune::runtime::VmError::panic(format!(
                "Failed to create RuneString: {}",
                e
            )))
        );
        let shared = vm_try!(Shared::new(rune_str)
            .map_err(|e| rune::runtime::VmError::panic(format!("Failed to create Shared: {}", e))));
        VmResult::Ok(rune::Value::String(shared))
    }

    /// Returns the error message (for Rune).
    #[rune::function(vm_result)]
    pub fn message(&self) -> VmResult<rune::Value> {
        let rune_str = vm_try!(RuneString::try_from(self.message.clone()).map_err(|e| {
            rune::runtime::VmError::panic(format!("Failed to create RuneString: {}", e))
        }));
        let shared = vm_try!(Shared::new(rune_str)
            .map_err(|e| rune::runtime::VmError::panic(format!("Failed to create Shared: {}", e))));
        VmResult::Ok(rune::Value::String(shared))
    }

    /// Returns true if this error is a conditional check failure (for Rune).
    #[rune::function(instance, path = is_conditional_check_failed)]
    pub fn is_conditional_check_failed_rune(&self) -> bool {
        self.kind == DynamoErrorKind::ConditionalCheckFailed
    }

    /// Returns true if this error is retryable (for Rune).
    #[rune::function(instance, path = is_retryable)]
    pub fn is_retryable_rune(&self) -> bool {
        self.is_retryable()
    }

    /// Returns a string representation of the error (for Rune).
    #[rune::function(vm_result, protocol = STRING_DISPLAY)]
    pub fn string_display(&self, f: &mut rune::runtime::Formatter) -> VmResult<()> {
        vm_write!(f, "DynamoError({}: {})", self.kind, self.message);
        VmResult::Ok(())
    }
}

impl fmt::Display for DynamoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DynamoError({}: {})", self.kind, self.message)
    }
}

impl std::error::Error for DynamoError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_kind_display() {
        assert_eq!(
            DynamoErrorKind::ValidationError.to_string(),
            "ValidationError"
        );
        assert_eq!(DynamoErrorKind::AccessDenied.to_string(), "AccessDenied");
        assert_eq!(
            DynamoErrorKind::ResourceNotFound.to_string(),
            "ResourceNotFound"
        );
        assert_eq!(
            DynamoErrorKind::ConditionalCheckFailed.to_string(),
            "ConditionalCheckFailed"
        );
        assert_eq!(
            DynamoErrorKind::ProvisionedThroughputExceeded.to_string(),
            "ProvisionedThroughputExceeded"
        );
        assert_eq!(
            DynamoErrorKind::AlternatorUnsupported.to_string(),
            "AlternatorUnsupported"
        );
    }

    #[test]
    fn test_error_new() {
        let err = DynamoError::new(DynamoErrorKind::ValidationError, "test message");
        assert_eq!(err.kind, DynamoErrorKind::ValidationError);
        assert_eq!(err.message, "test message");
    }

    #[test]
    fn test_validation_error() {
        let err = DynamoError::validation_error("invalid input");
        assert_eq!(err.kind, DynamoErrorKind::ValidationError);
        assert!(err.message.contains("invalid input"));
    }

    #[test]
    fn test_resource_not_found() {
        let err = DynamoError::resource_not_found("table does not exist");
        assert_eq!(err.kind, DynamoErrorKind::ResourceNotFound);
        assert!(err.message.contains("table does not exist"));
    }

    #[test]
    fn test_conditional_check_failed() {
        let err = DynamoError::conditional_check_failed("condition not met");
        assert_eq!(err.kind, DynamoErrorKind::ConditionalCheckFailed);
        assert!(err.message.contains("condition not met"));
    }

    #[test]
    fn test_is_conditional_check_failed() {
        let err_cond = DynamoError::conditional_check_failed("test");
        let err_other = DynamoError::validation_error("test");

        assert!(err_cond.is_conditional_check_failed());
        assert!(!err_other.is_conditional_check_failed());
    }

    #[test]
    fn test_is_retryable() {
        let retryable_errors = vec![
            DynamoError::new(DynamoErrorKind::ProvisionedThroughputExceeded, "test"),
            DynamoError::new(DynamoErrorKind::RequestLimitExceeded, "test"),
            DynamoError::new(DynamoErrorKind::InternalServerError, "test"),
            DynamoError::new(DynamoErrorKind::ServiceUnavailable, "test"),
            DynamoError::new(DynamoErrorKind::Timeout, "test"),
        ];

        for err in retryable_errors {
            assert!(
                err.is_retryable(),
                "Expected {:?} to be retryable",
                err.kind
            );
        }

        let non_retryable_errors = vec![
            DynamoError::new(DynamoErrorKind::ValidationError, "test"),
            DynamoError::new(DynamoErrorKind::AccessDenied, "test"),
            DynamoError::new(DynamoErrorKind::ResourceNotFound, "test"),
            DynamoError::new(DynamoErrorKind::ConditionalCheckFailed, "test"),
        ];

        for err in non_retryable_errors {
            assert!(
                !err.is_retryable(),
                "Expected {:?} to not be retryable",
                err.kind
            );
        }
    }

    #[test]
    fn test_prepared_operation_not_found() {
        let err = DynamoError::prepared_operation_not_found("my_query");
        assert_eq!(err.kind, DynamoErrorKind::PreparedOperationNotFound);
        assert!(err.message.contains("my_query"));
    }

    #[test]
    fn test_client_not_found() {
        let err = DynamoError::client_not_found("my_client");
        assert_eq!(err.kind, DynamoErrorKind::ClientNotFound);
        assert!(err.message.contains("my_client"));
    }

    #[test]
    fn test_invalid_parameter() {
        let err = DynamoError::invalid_parameter("bad param");
        assert_eq!(err.kind, DynamoErrorKind::InvalidParameter);
        assert!(err.message.contains("bad param"));
    }

    #[test]
    fn test_alternator_unsupported() {
        let err = DynamoError::alternator_unsupported("transact_write_items");
        assert_eq!(err.kind, DynamoErrorKind::AlternatorUnsupported);
        assert!(err.message.contains("transact_write_items"));
        assert!(err.message.contains("Alternator"));
    }

    #[test]
    fn test_connection_error() {
        let err = DynamoError::connection_error("connection refused");
        assert_eq!(err.kind, DynamoErrorKind::ConnectionError);
        assert!(err.message.contains("connection refused"));
    }

    #[test]
    fn test_from_sdk_error_categorization() {
        // Test that SDK errors are properly categorized based on message content
        let test_cases = vec![
            (
                "ResourceNotFoundException: Table not found",
                DynamoErrorKind::ResourceNotFound,
            ),
            (
                "ResourceInUseException: Table is being created",
                DynamoErrorKind::ResourceInUse,
            ),
            (
                "ValidationException: Invalid key",
                DynamoErrorKind::ValidationError,
            ),
            (
                "ConditionalCheckFailedException: Condition failed",
                DynamoErrorKind::ConditionalCheckFailed,
            ),
            (
                "ProvisionedThroughputExceededException: Rate exceeded",
                DynamoErrorKind::ProvisionedThroughputExceeded,
            ),
            (
                "RequestLimitExceeded: Too many requests",
                DynamoErrorKind::RequestLimitExceeded,
            ),
            (
                "TransactionConflictException: Conflict",
                DynamoErrorKind::TransactionConflict,
            ),
            (
                "TransactionCanceledException: Canceled",
                DynamoErrorKind::TransactionCanceled,
            ),
            (
                "AccessDeniedException: No access",
                DynamoErrorKind::AccessDenied,
            ),
            (
                "InternalServerError: Server error",
                DynamoErrorKind::InternalServerError,
            ),
            (
                "ServiceUnavailable: Service down",
                DynamoErrorKind::ServiceUnavailable,
            ),
            ("timeout occurred", DynamoErrorKind::Timeout),
            ("Timeout while connecting", DynamoErrorKind::Timeout),
            ("connection refused", DynamoErrorKind::ConnectionError),
            ("Connection reset", DynamoErrorKind::ConnectionError),
            ("Some unknown error", DynamoErrorKind::Other),
        ];

        for (msg, expected_kind) in test_cases {
            let err = DynamoError::from_sdk_error(msg);
            assert_eq!(
                err.kind, expected_kind,
                "Message '{}' should produce {:?} but got {:?}",
                msg, expected_kind, err.kind
            );
        }
    }

    #[test]
    fn test_error_display() {
        let err = DynamoError::new(DynamoErrorKind::ValidationError, "test message");
        let display = format!("{}", err);
        assert!(display.contains("ValidationError"));
        assert!(display.contains("test message"));
    }
}
