// Package protocol implements the CQL binary protocol for the Latte driver adapter.
// It provides types and functions for encoding/decoding CQL protocol frames,
// handling request/response messages, and managing session state.
package protocol

// Opcode represents a CQL protocol opcode.
// These opcodes identify the type of message being sent or received.
type Opcode byte

const (
	OpcodeError          Opcode = 0x00 // Error response
	OpcodeStartup        Opcode = 0x01 // Connection initialization request
	OpcodeReady          Opcode = 0x02 // Server ready response
	OpcodeOptions        Opcode = 0x05 // Options request
	OpcodeSupported      Opcode = 0x06 // Supported options response
	OpcodeQuery          Opcode = 0x07 // Query request (ad-hoc CQL)
	OpcodeResult         Opcode = 0x08 // Query result response
	OpcodePrepare        Opcode = 0x09 // Prepare statement request
	OpcodeExecute        Opcode = 0x0A // Execute prepared statement request
	OpcodeBatch          Opcode = 0x0D // Batch query request
	OpcodeAuthChallenge  Opcode = 0x0E // Authentication challenge
	OpcodeAuthResponse   Opcode = 0x0F // Authentication response
	OpcodeAuthSuccess    Opcode = 0x10 // Authentication success
	OpcodeCreateSession  Opcode = 0x21 // Create session request (Latte extension)
	OpcodeSessionCreated Opcode = 0x22 // Session created response (Latte extension)
)

// ErrorCode represents a CQL error code.
type ErrorCode uint32

const (
	// Server errors (0x0000 - 0x00FF)
	ErrorServer          ErrorCode = 0x0000 // Generic server error
	ErrorProtocol        ErrorCode = 0x000A // Protocol error
	ErrorBadCredentials  ErrorCode = 0x0100 // Bad credentials

	// Unavailable/Overload errors (0x1000 - 0x1FFF)
	ErrorUnavailable     ErrorCode = 0x1000 // Not enough replicas available
	ErrorOverloaded      ErrorCode = 0x1001 // Server is overloaded
	ErrorIsBootstrapping ErrorCode = 0x1002 // Server is bootstrapping
	ErrorTruncateError   ErrorCode = 0x1003 // Truncate error

	// Query errors (0x2000 - 0x2FFF)
	ErrorWriteTimeout    ErrorCode = 0x1100 // Write timeout
	ErrorReadTimeout     ErrorCode = 0x1200 // Read timeout
	ErrorReadFailure     ErrorCode = 0x1300 // Read failure
	ErrorFunctionFailure ErrorCode = 0x1400 // Function execution failure
	ErrorWriteFailure    ErrorCode = 0x1500 // Write failure

	// Syntax/Validation errors (0x2000 - 0x2FFF)
	ErrorSyntax          ErrorCode = 0x2000 // Syntax error in query
	ErrorUnauthorized    ErrorCode = 0x2100 // Unauthorized
	ErrorInvalid         ErrorCode = 0x2200 // Invalid query
	ErrorConfigError     ErrorCode = 0x2300 // Configuration error
	ErrorAlreadyExists   ErrorCode = 0x2400 // Keyspace/table already exists
	ErrorUnprepared      ErrorCode = 0x2500 // Prepared statement not found
)

// IsTransientError returns true if the error is transient and the operation may be retried.
func IsTransientError(code ErrorCode) bool {
	switch code {
	case ErrorOverloaded, ErrorIsBootstrapping, ErrorWriteTimeout, ErrorReadTimeout,
		ErrorUnavailable, ErrorWriteFailure, ErrorReadFailure:
		return true
	default:
		return false
	}
}

// IsPermanentError returns true if the error is permanent and retrying won't help.
func IsPermanentError(code ErrorCode) bool {
	switch code {
	case ErrorSyntax, ErrorUnauthorized, ErrorInvalid, ErrorConfigError,
		ErrorAlreadyExists, ErrorBadCredentials, ErrorProtocol:
		return true
	default:
		return false
	}
}

// ResultKind represents the kind of a RESULT response.
// Different result kinds indicate what data is contained in the response body.
type ResultKind int32

const (
	ResultVoid         ResultKind = 0x0001 // No result data (INSERT, UPDATE, DELETE)
	ResultRows         ResultKind = 0x0002 // Row data from SELECT
	ResultSetKeyspace  ResultKind = 0x0003 // Keyspace set via USE statement
	ResultPrepared     ResultKind = 0x0004 // Prepared statement created
	ResultSchemaChange ResultKind = 0x0005 // Schema was modified (CREATE, ALTER, DROP)
)

// BatchType represents the type of a batch operation.
// Different batch types have different atomicity and consistency guarantees.
type BatchType byte

const (
	BatchLogged   BatchType = 0 // Atomic batch with write-ahead log
	BatchUnlogged BatchType = 1 // Non-atomic batch (higher performance, lower safety)
	BatchCounter  BatchType = 2 // Batch of counter updates
)

// Consistency represents a CQL consistency level.
// The consistency level determines how many replicas must acknowledge a read/write.
type Consistency uint16

const (
	ConsistencyAny         Consistency = 0x0000 // Write: at least one node (including hints)
	ConsistencyOne         Consistency = 0x0001 // One replica
	ConsistencyTwo         Consistency = 0x0002 // Two replicas
	ConsistencyThree       Consistency = 0x0003 // Three replicas
	ConsistencyQuorum      Consistency = 0x0004 // Majority of replicas
	ConsistencyAll         Consistency = 0x0005 // All replicas
	ConsistencyLocalQuorum Consistency = 0x0006 // Majority in local datacenter
	ConsistencyEachQuorum  Consistency = 0x0007 // Majority in each datacenter
	ConsistencyLocalOne    Consistency = 0x000A // One replica in local datacenter
)
