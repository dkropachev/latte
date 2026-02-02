# Driver Adapter Onboarding Guide

This document contains all technical information required to implement a new driver adapter for Latte. It covers the IPC protocol specification, value encoding, implementation steps, and acceptance criteria.

## Table of Contents

1. [Communication Design](#communication-design)
2. [Binary Protocol](#binary-protocol)
3. [Message Bodies](#message-bodies)
4. [Value Encoding](#value-encoding)
5. [Session Parameters](#session-parameters)
6. [Implementation Guide](#implementation-guide)
7. [Performance Considerations](#performance-considerations)
8. [Acceptance Criteria](#acceptance-criteria)
9. [Desired Features](#desired-features)

---

## Communication Design

### Socket Communication

Communication uses a **single Unix domain socket** with **full-duplex** I/O:

- Both Latte and the adapter split the socket into read/write halves
- Separate async tasks handle reading and writing concurrently
- No need for two separate socket connections

### Multiplexing

Multiple requests can be in-flight simultaneously:

- Each request frame includes a **stream ID** (16-bit signed integer)
- Responses include the same stream ID to correlate with requests
- The client maintains a map of pending requests keyed by stream ID
- Responses are routed back to the correct waiting caller

### Batched I/O

Both sides use buffered writers for efficiency:

- Writes are accumulated in a buffer (typically 64KB)
- Flush occurs when buffer reaches threshold (32KB) or no more data is immediately available
- This batching significantly improves throughput for small messages

---

## Binary Protocol

The protocol is inspired by the CQL native protocol but simplified for IPC use.

### Protocol Version

| Constant | Value | Description |
|----------|-------|-------------|
| `IPC_PROTOCOL_VERSION` | `1` | Current protocol version. Bump when making incompatible changes. |

Both Latte and driver adapters should use the same protocol version. The version is defined in:
- Latte: `src/ipc/protocol.rs`
- Adapters: Each adapter's protocol implementation

### Frame Format

All frames share a common 9-byte header:

```
 0         1         2         3         4         5         6         7         8
 +---------+---------+---------+---------+---------+---------+---------+---------+---------+
 | version |  flags  |      stream       | opcode  |              body_length              |
 +---------+---------+---------+---------+---------+---------+---------+---------+---------+
 |                                      body                                               |
 +---------+---------+---------+---------+---------+---------+---------+---------+---------+
```

| Field | Size | Description |
|-------|------|-------------|
| version | 1 byte | `0x04` for requests, `0x84` for responses |
| flags | 1 byte | Reserved, set to `0` |
| stream | 2 bytes | Signed 16-bit stream ID (big-endian) |
| opcode | 1 byte | Operation type |
| body_length | 4 bytes | Length of body in bytes (big-endian, max 16MB) |
| body | variable | Opcode-specific payload |

### Opcodes

| Opcode | Value | Direction | Description |
|--------|-------|-----------|-------------|
| ERROR | 0x00 | Response | Error response |
| QUERY | 0x07 | Request | Execute unprepared query |
| RESULT | 0x08 | Response | Query/execute result |
| PREPARE | 0x09 | Request | Prepare a statement |
| EXECUTE | 0x0A | Request | Execute prepared statement |
| BATCH | 0x0D | Request | Execute batch of statements |
| CREATE_SESSION | 0x21 | Request | Create new database session |
| SESSION_CREATED | 0x22 | Response | Session creation response |

### Primitive Types

| Type | Format |
|------|--------|
| `[short]` | 2-byte big-endian unsigned integer |
| `[int]` | 4-byte big-endian signed integer |
| `[long]` | 8-byte big-endian unsigned integer |
| `[string]` | `[short]` length + UTF-8 bytes |
| `[long_string]` | `[int]` length + UTF-8 bytes |
| `[short_bytes]` | `[short]` length + raw bytes |
| `[string_map]` | `[short]` count + (key `[string]`, value `[string]`)* |
| `[bytes]` | `[int]` length + raw bytes (-1 length = null) |

### Consistency Levels

| Level | Value |
|-------|-------|
| ANY | 0x0000 |
| ONE | 0x0001 |
| TWO | 0x0002 |
| THREE | 0x0003 |
| QUORUM | 0x0004 |
| ALL | 0x0005 |
| LOCAL_QUORUM | 0x0006 |
| EACH_QUORUM | 0x0007 |
| LOCAL_ONE | 0x000A |

---

## Message Bodies

### CREATE_SESSION (0x21)

Creates a new database session with the adapter.

**Request body:**
```
[string_map] session_params
```

See [Session Parameters](#session-parameters) for the full list of supported parameters.

**Response body (SESSION_CREATED 0x22):**
```
[long] session_id
```

### QUERY (0x07)

Execute an unprepared CQL query.

**Request body:**
```
[long]        session_id
[long_string] query
[short]       consistency
[byte]        flags (must be 0)
```

### PREPARE (0x09)

Prepare a CQL statement for later execution.

**Request body:**
```
[long]        session_id
[long_string] query
[string]      statement_key
```

The `statement_key` is a client-chosen identifier used to reference this prepared statement in EXECUTE and BATCH requests. The adapter caches the prepared statement internally.

**Response body (RESULT 0x08 with kind=PREPARED):**
```
[int]         kind (0x0004 = PREPARED)
[string]      statement_key (echoed back)
[short_bytes] prepared_id
[int]         bind_metadata_flags
[int]         bind_columns_count
[int]         result_metadata_flags
[int]         result_columns_count
```

### EXECUTE (0x0A)

Execute a previously prepared statement.

**Request body:**
```
[long]   session_id
[string] statement_key
[short]  consistency
[byte]   flags (bit 0 = values present)
[short]  value_count (if values present)
([bytes] value)*
```

Values are encoded as raw bytes in CQL binary format. The adapter uses the prepared statement's bind metadata to interpret the types.

### BATCH (0x0D)

Execute multiple prepared statements atomically.

**Request body:**
```
[long]   session_id
[byte]   batch_type (0=LOGGED, 1=UNLOGGED, 2=COUNTER)
[short]  statement_count
(
  [byte]   kind (must be 1 = prepared)
  [string] statement_key
  [short]  value_count
  ([bytes] value)*
)*
[short]  consistency
[byte]   flags (reserved, 0)
```

### RESULT (0x08)

Response for QUERY, EXECUTE, and PREPARE operations.

**Body structure depends on result kind:**

```
[int] kind
```

| Kind | Value | Body |
|------|-------|------|
| VOID | 0x0001 | (empty) |
| ROWS | 0x0002 | See below |
| SET_KEYSPACE | 0x0003 | `[string]` keyspace |
| PREPARED | 0x0004 | See PREPARE response |
| SCHEMA_CHANGE | 0x0005 | `[string]` change_type |

**ROWS result body:**
```
[int]    flags
[int]    columns_count
(
  [string] keyspace
  [string] table
  [string] column_name
  [short]  type_code
)*
[int]    row_count
(
  ([bytes] column_value)*
)*
```

### Driver Latency Extension

For QUERY, EXECUTE, and BATCH operations, the adapter appends driver-side latency to the response body. This allows Latte to measure and report the time spent in the driver separately from IPC overhead.

**Format:**
```
[original_result_body] [long] latency_ns
```

- `latency_ns`: 8-byte big-endian unsigned integer (nanoseconds)
- Appended to the END of the normal RESULT body
- Latte detects presence by checking `body.len() >= 12` (4 bytes for result kind + 8 bytes for latency)

**Example:** A VOID result with 1.5ms driver latency:
```
[0x00 0x00 0x00 0x01]                         # kind = VOID
[0x00 0x00 0x00 0x00 0x00 0x16 0xE3 0x60]     # latency = 1,500,000 ns
```

### ERROR (0x00)

Error response for any failed operation.

**Body:**
```
[int]    error_code
[string] error_message
```

**Error codes:**
| Code | Name | Description |
|------|------|-------------|
| 0x0000 | SERVER | Internal server error |
| 0x000A | PROTOCOL | Protocol violation |
| 0x1001 | OVERLOADED | Server overloaded |
| 0x2500 | UNPREPARED | Statement not prepared |

---

## Value Encoding

### Type Codes

| Type | Code | Wire Format |
|------|------|-------------|
| ascii | 0x0001 | UTF-8 bytes |
| bigint | 0x0002 | 8-byte big-endian signed |
| blob | 0x0003 | raw bytes |
| boolean | 0x0004 | 1 byte (0x00=false, 0x01=true) |
| counter | 0x0005 | 8-byte big-endian signed |
| double | 0x0007 | 8-byte IEEE 754 big-endian |
| float | 0x0008 | 4-byte IEEE 754 big-endian |
| int | 0x0009 | 4-byte big-endian signed |
| timestamp | 0x000B | 8-byte big-endian (ms since epoch) |
| uuid | 0x000C | 16 bytes |
| text/varchar | 0x000D | UTF-8 bytes |
| timeuuid | 0x000F | 16 bytes |
| inet | 0x0010 | 4 bytes (IPv4) or 16 bytes (IPv6) |
| date | 0x0011 | 4-byte unsigned (days since epoch) |
| time | 0x0012 | 8-byte signed (nanoseconds since midnight) |
| smallint | 0x0013 | 2-byte big-endian signed |
| tinyint | 0x0014 | 1-byte signed |
| decimal | 0x0006 | 4-byte scale + varint mantissa |
| varint | 0x000E | Variable-length signed integer |
| duration | 0x0015 | 3 varints: months, days, nanoseconds |
| list | 0x0020 | See Collection Encoding |
| map | 0x0021 | See Collection Encoding |
| set | 0x0022 | See Collection Encoding |
| vector | 0x0030 | See Vector Encoding |
| tuple | 0x0031 | See Tuple Encoding |
| udt | 0x0040 | See UDT Encoding |

### Value Wire Format

When sending values in EXECUTE or BATCH requests, each value is encoded with a type code prefix:

```
[short]  type_code
[int]    length (-1 for NULL)
[bytes]  data (if length >= 0)
```

- `type_code`: The CQL type code from the table above (enables type coercion)
- `length`: 4-byte signed big-endian integer (-1 = NULL, 0 = empty)
- `data`: Raw bytes in CQL binary format

**Example encoding:**
```
int value 42:        [0x00 0x09] [0x00 0x00 0x00 0x04] [0x00 0x00 0x00 0x2A]  (type=INT, length=4, value=42)
text "hello":        [0x00 0x0D] [0x00 0x00 0x00 0x05] [0x68 0x65 0x6C 0x6C 0x6F]  (type=TEXT, length=5)
NULL int:            [0x00 0x09] [0xFF 0xFF 0xFF 0xFF]  (type=INT, length=-1)
boolean true:        [0x00 0x04] [0x00 0x00 0x00 0x01] [0x01]  (type=BOOLEAN, length=1)
```

### Collection Encoding

**List (0x0020):**
```
[short]  element_type
[int]    n_elements
([int] length, [bytes] data)*
```

For `list<vector<float, N>>`, additional metadata follows element_type:
```
[short]  element_type (0x0030 = vector)
[short]  vector_element_type (0x0008 = float)
[short]  vector_dimension
[int]    n_elements
...
```

**Set (0x0022):** Same format as List.

**Map (0x0021):**
```
[short]  key_type
[short]  value_type
[int]    n_entries
([int] key_len, [bytes] key, [int] val_len, [bytes] val)*
```

### Vector Encoding (0x0030)

```
[short]  element_type (0x0008 = float)
[short]  dimension
[bytes]  data (dimension * 4 bytes for float)
```

Vector data is contiguous without per-element length prefixes.

### Tuple Encoding (0x0031)

```
[short]  n_elements
[short]* element_types (n_elements type codes)
([int] length, [bytes] data)*  (-1 length for null elements)
```

### UDT Encoding (0x0040)

```
[short]  n_fields
(
  [short]  field_name_length
  [bytes]  field_name
  [short]  field_type
)*
([int] length, [bytes] data)*  (-1 length for null fields)
```

### Type Coercion

The adapter performs automatic type coercion when the wire type doesn't match the column type:

| Wire Type | Target Type | Conversion |
|-----------|-------------|------------|
| BigInt | Int, SmallInt, TinyInt | Truncate with bounds check |
| BigInt | Varint, Decimal | Promote to arbitrary precision |
| BigInt | Timestamp, Time, Counter | Direct conversion |
| Double | Float | Truncate to 32-bit |
| Text | Date | Parse "YYYY-MM-DD" |
| Text | Time | Parse "HH:MM:SS" |
| Text | Duration | Parse "1mo2d3h4m5s" format |
| Text | Inet | Parse IP address string |
| Text | Timeuuid | Parse UUID string |
| Text | Decimal | Parse decimal string |
| List | Set | Convert container type |

Nested collections and UDT fields are coerced recursively.

---

## Session Parameters

Session parameters are passed as a string map in the CREATE_SESSION request. All parameters are optional and use string values. Drivers should ignore unknown parameters for forward compatibility.

### Backward Compatibility

| Scenario | Behavior |
|----------|----------|
| New Latte + Old driver, no new params | Works normally |
| New Latte + Old driver, new params set | Works (unknown params silently ignored) |
| Old Latte + New driver | Works (driver uses defaults for missing params) |
| New Latte + New driver | Full feature support |

**Important**: Drivers must use `.get()` to read parameters and provide sensible defaults for missing values. Unknown keys must be silently ignored.

### Parameter Reference

#### Connection Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `contact_points` | string | Comma-separated list of host:port (e.g., `"10.0.0.1:9042,10.0.0.2:9042"`) |
| `keyspace` | string | Default keyspace to use |
| `username` | string | Authentication username |
| `password` | string | Authentication password |
| `connections_per_shard` | string (u32) | Number of connections per shard/node (e.g., `"2"`) |

#### Topology Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `datacenter` | string | Preferred datacenter for DC-aware routing |
| `rack` | string | Preferred rack for rack-aware routing (requires `datacenter`) |

#### Timeout Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `request_timeout_ms` | string (u64) | Per-request timeout in milliseconds (e.g., `"5000"`) |
| `connect_timeout_ms` | string (u64) | Connection establishment timeout in milliseconds |

#### Query Defaults

| Parameter | Type | Description |
|-----------|------|-------------|
| `consistency` | string | Default consistency level (e.g., `"LOCAL_QUORUM"`, `"ONE"`) |
| `serial_consistency` | string | Serial consistency for LWT (e.g., `"LOCAL_SERIAL"`, `"SERIAL"`) |
| `default_page_size` | string (u32) | Default page size for SELECT queries |

#### SSL/TLS Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `ssl_enabled` | string (bool) | Enable SSL/TLS (`"true"` or `"false"`) |
| `ssl_ca_cert` | string | CA certificate (PEM content or file path) |
| `ssl_cert` | string | Client certificate (PEM content or file path) |
| `ssl_key` | string | Client private key (PEM content or file path) |
| `ssl_verify_peer` | string (bool) | Verify server certificate (`"true"` or `"false"`) |

### Implementation Notes

```
// Pseudocode for parameter handling
fn connect_with_params(params: Map<String, String>) {
    // Use .get() - returns None if missing, enabling forward compatibility
    let dc = params.get("datacenter");

    // Parse with fallback to default
    let timeout = params.get("request_timeout_ms")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(5000);  // Default: 5 seconds

    // Boolean parsing
    let ssl = params.get("ssl_enabled")
        .map(|v| v == "true")
        .unwrap_or(false);

    // Unknown keys are simply never accessed - no special handling needed
}
```

---

## Implementation Guide

### Step 1: Socket Server

Create a Unix domain socket server that:
- Binds to the path specified by `LATTE_DRIVER_SOCKET` environment variable
- Accepts connections and splits each into read/write halves
- Handles multiple concurrent connections (though typically only one)

> **Note:** When Latte starts the adapter via `--cql-adapter-image`, the adapter runs
> inside a Docker container with the socket directory volume-mounted. Latte
> polls for the socket to appear and then connects to it. The adapter does **not**
> need to connect to Latte — it only needs to listen on the socket.

**Socket path handling pseudocode:**
```
fn main():
    // Read socket path from environment (REQUIRED)
    socket_path = env.get("LATTE_DRIVER_SOCKET")
        .unwrap_or("/tmp/latte-driver.sock")

    // Remove stale socket file if it exists (important!)
    if file_exists(socket_path):
        remove_file(socket_path)

    // Ensure parent directory exists
    create_dir_all(parent_of(socket_path))

    // Bind and listen
    listener = UnixListener::bind(socket_path)

    // Accept connections
    loop:
        stream = listener.accept()
        spawn handle_connection(stream)
```

**Important socket behaviors:**
- Always remove existing socket file before binding (prevents "Address already in use")
- Create parent directory if it doesn't exist
- The socket file is created automatically by `bind()`
- Clean up socket file on graceful shutdown (optional but good practice)

### Step 2: Frame Parsing

Implement frame reading:
```
1. Read 9-byte header
2. Parse version, flags, stream, opcode, body_length
3. Read body_length bytes
4. Return complete frame
```

### Step 3: Request Dispatch

For each incoming frame:
1. Spawn an async task to handle the request
2. Acquire a semaphore permit to limit concurrency (recommended: 512)
3. Dispatch based on opcode to appropriate handler
4. Send response frame back via a channel to the writer task

### Step 4: Session Management

Maintain a registry of sessions:
- Map session IDs (u64) to driver session objects
- CREATE_SESSION creates a new driver connection and returns a new ID
- All other operations include a session_id to identify which session to use

### Step 5: Prepared Statement Cache

For each session, maintain a cache mapping statement keys to prepared statements:
- PREPARE: Parse query, prepare with driver, store in cache by key
- EXECUTE: Look up statement by key, bind values, execute
- BATCH: Look up each statement by key, execute as batch

### Step 6: Value Encoding/Decoding

Values are transmitted as raw CQL binary bytes:
- Use the prepared statement's bind metadata to determine types
- Decode bytes according to CQL binary protocol specification
- Encode result values similarly

### Step 7: Configuration

Support these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_DRIVER_SOCKET` | `/tmp/latte-driver.sock` | Socket path |
| `LATTE_DRIVER_INFLIGHT` | `512` | Max concurrent requests |

**Environment variable details:**

**`LATTE_DRIVER_SOCKET`** (Required)
- Path where the adapter creates the Unix domain socket
- In Docker: Set to `/sockets/latte-driver.sock` (inside the mounted volume)
- Native: Set to `/tmp/latte-driver.sock` or any accessible path
- The adapter must create this socket on startup
- When Latte manages the container (`--cql-adapter-image`), it sets this variable automatically

**`LATTE_DRIVER_INFLIGHT`** (Optional)
- Maximum concurrent requests handled by the adapter
- Controls semaphore size for backpressure
- Higher values: more throughput, more memory
- Lower values: less throughput, better latency stability

**Configuration pseudocode:**
```
fn load_config():
    config = Config {
        socket_path: env("LATTE_DRIVER_SOCKET", "/tmp/latte-driver.sock"),
        max_inflight: env("LATTE_DRIVER_INFLIGHT", "512").parse(),
    }
    return config
```

### Step 8: Docker Image

#### Socket Path Convention

The adapter and Latte communicate via a Unix domain socket. The socket file must be accessible to both processes:

```
┌─────────────────┐          ┌─────────────────────────────────┐
│   HOST SYSTEM   │          │        DOCKER CONTAINER         │
│                 │          │                                 │
│  /tmp/latte-driver/  ◀──volume mount──▶  /sockets/          │
│        │        │          │                │                │
│        └── latte-driver.sock ◀────────────┘                 │
│                 │          │                                 │
│   Latte reads   │          │   Adapter creates socket at:   │
│   socket here   │          │   /sockets/latte-driver.sock   │
└─────────────────┘          └─────────────────────────────────┘
```

**Key points:**
- Inside container: socket lives at `/sockets/latte-driver.sock`
- On host: socket lives at `/tmp/latte-driver/latte-driver.sock` (or similar)
- Volume mount maps host directory to `/sockets/` inside container
- Adapter reads `LATTE_DRIVER_SOCKET` env var for socket path

#### Required Dockerfile Elements

```dockerfile
# ==============================================================================
# Build stage (multi-stage build recommended)
# ==============================================================================
FROM <build-image> AS builder

WORKDIR /build

# Copy source and build
COPY . .
RUN <build-commands>

# ==============================================================================
# Runtime stage
# ==============================================================================
FROM <runtime-image>

ARG DRIVER_VERSION=dev

# OCI labels (recommended)
LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Driver Adapter (<Driver Name>)"
LABEL org.opencontainers.image.version="${DRIVER_VERSION}"

# Install runtime dependencies only
RUN <install-runtime-deps>

# Copy binary/artifact from builder
COPY --from=builder /build/<artifact> /app/

# ==============================================================================
# Socket Configuration (REQUIRED)
# ==============================================================================
# Default socket path - MUST be inside /sockets/ directory
ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock

# Concurrency limit
ENV LATTE_DRIVER_INFLIGHT=512

# Create sockets directory (required)
RUN mkdir -p /sockets

# Declare /sockets as a volume mount point (optional but recommended)
VOLUME /sockets

ENTRYPOINT ["/app/<binary>"]
```

#### Running the Container

**Recommended: Latte manages the container (automatic lifecycle)**

Latte can start and stop the Docker container automatically using `--cql-adapter-image`.
This is the preferred approach — Latte handles the volume mount, environment variables,
and container cleanup:

```bash
# Latte starts the container, runs the workload, and stops the container
latte schema --cql-adapter-image scylladb/latte-driver-adapters:<adapter>-<version> \
    --driver-socket /tmp/latte-driver.sock workload.rn

latte load --cql-adapter-image scylladb/latte-driver-adapters:<adapter>-<version> \
    --driver-socket /tmp/latte-driver.sock workload.rn

latte run --cql-adapter-image scylladb/latte-driver-adapters:<adapter>-<version> \
    --driver-socket /tmp/latte-driver.sock workload.rn
```

When `--cql-adapter-image` is specified, Latte:
1. Starts the container with `--network host` and a volume mount for the socket directory
2. Passes `LATTE_DRIVER_SOCKET` to the container
3. Polls for the socket to appear (up to 30 seconds)
4. Connects to the adapter and runs the operation
5. Stops the container on exit (including on error)

**Alternative: Manual container management**

You can also start the container yourself and point Latte at the socket:

```bash
# Start container manually
mkdir -p /tmp/latte-driver
docker run --rm -d \
    --network host \
    -v /tmp/latte-driver:/sockets \
    -e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
    scylladb/latte-driver-adapters:<adapter>-<version>

# Latte connects to the existing socket (no --cql-adapter-image needed)
latte run --driver-socket /tmp/latte-driver/latte-driver.sock workload.rn
```

#### Makefile docker-run Target

Include a `docker-run` target in your Makefile for local testing:

```makefile
.PHONY: docker-run
docker-run: ## Run the driver adapter container locally
	@mkdir -p /tmp/latte-driver
	docker run --rm -it \
		--network host \
		-v /tmp/latte-driver:/sockets \
		-e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
		$(DOCKER_IMAGE)
```

### Step 9: Directory Structure

Each adapter must follow this directory structure:

```
cql-adapters/<driver-name>/
├── README.md           # Required: adapter documentation
├── Makefile            # Required: standard build interface
├── Dockerfile          # Required: container build
├── src/                # Source code
└── ...                 # Language-specific files (Cargo.toml, pom.xml, etc.)
```

### Step 10: Docker Image Naming

All driver adapter images must be pushed to the shared repository with a consistent naming scheme:

```
scylladb/latte-driver-adapters:<driver-folder-name>-<driver-version>
```

Examples:
- `scylladb/latte-driver-adapters:scylla-rust-driver-1.4.0`
- `scylladb/latte-driver-adapters:scylla-rust-driver-latest`
- `scylladb/latte-driver-adapters:java-driver-4.15.0`
- `scylladb/latte-driver-adapters:python-driver-3.28.0`

The `<driver-folder-name>` must match the adapter's directory name under `cql-adapters/`.

### Step 11: Adapter README

Each adapter must include a `README.md` with the following sections:

```markdown
# <Adapter Name>

Short description of what this adapter does and its purpose.

## Driver

- **Driver**: <driver name and version>
- **Language**: <implementation language>
- **Repository**: <link to upstream driver>

## Docker Image

- **Image**: scylladb/latte-driver-adapters:<driver-folder-name>-<version>
- **Tags**: `<driver-folder-name>-latest`, `<driver-folder-name>-<version>`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | ... |
| `make test` | ... |
| `make test-integration` | ... |
| `make lint` | ... |
| `make lint-fix` | ... |
| `make build-docker-image` | ... |
| `make push-docker-image` | ... |

## Configuration

<environment variables and options>

## Usage

<example usage>
```

---

## Performance Considerations

### Concurrency Control

Use a semaphore to limit in-flight requests:
- Prevents overwhelming the database
- Provides backpressure to Latte when overloaded
- Recommended limit: 512 concurrent requests

### Lock-Free Data Structures

For hot paths, use lock-free concurrent maps:
- Session registry lookups (very frequent)
- Pending request tracking (per-request)
- DashMap or similar concurrent hash maps work well

### Buffer Sizing

- Read buffer: 8KB initial, grows as needed
- Write buffer: 64KB for batching
- Flush threshold: 32KB or when no more immediate data

### Prepared Statement Caching

Cache prepared statements to avoid re-preparing:
- Key by the client-provided statement key
- Store bind type metadata for value decoding
- Use read-write lock (reads are much more frequent)

---

## Acceptance Criteria

Use this checklist when implementing a new adapter. All items must be completed before the adapter is considered production-ready.

### Protocol Implementation

- [ ] Frame header parsing (9 bytes)
- [ ] Frame header encoding (version 0x84 for responses)
- [ ] CREATE_SESSION request/response
- [ ] QUERY request handling
- [ ] PREPARE request handling (cache by statement_key)
- [ ] EXECUTE request handling (lookup by statement_key)
- [ ] BATCH request handling
- [ ] RESULT response encoding (VOID, ROWS, PREPARED)
- [ ] ERROR response encoding
- [ ] Stream ID correlation (match response to request)
- [ ] Driver latency tracking (append to RESULT body)

### Infrastructure

- [ ] Unix domain socket server
- [ ] Full-duplex I/O (split read/write)
- [ ] Concurrent request handling
- [ ] Semaphore for inflight limiting
- [ ] Session registry (map session_id → driver session)
- [ ] Prepared statement cache per session

### Value Handling

- [ ] Decode bind values using prepared statement metadata
- [ ] Encode result values in ROWS response
- [ ] Handle NULL values (length = -1)
- [ ] Support core types: int, bigint, text, boolean, uuid, blob
- [ ] Support temporal types: date, time, timestamp
- [ ] Support numeric types: float, double, decimal, varint
- [ ] Support collection types: list, set, map (if targeting collections workload)
- [ ] Support vector type (if targeting vectors workload)
- [ ] Type coercion for common conversions

### Configuration

- [ ] `LATTE_DRIVER_SOCKET` environment variable
- [ ] `LATTE_DRIVER_INFLIGHT` environment variable

### Build & Packaging

- [ ] Makefile with all required targets (build, test, lint, lint-fix, build-docker-image, push-docker-image)
- [ ] Makefile includes `docker-run` target for local testing
- [ ] Makefile `test-integration` target runs via Docker (`--cql-adapter-image`)
- [ ] README.md with standard sections
- [ ] Docker image naming: `scylladb/latte-driver-adapters:<name>-<version>`

### Docker Image Requirements

- [ ] Dockerfile uses multi-stage build (build stage + runtime stage)
- [ ] Runtime image is minimal (slim/alpine base)
- [ ] Sets `ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock`
- [ ] Sets `ENV LATTE_DRIVER_INFLIGHT=512`
- [ ] Creates `/sockets` directory (`RUN mkdir -p /sockets`)
- [ ] Declares `VOLUME /sockets` (optional but recommended)
- [ ] Has appropriate `ENTRYPOINT`
- [ ] Includes OCI labels (source, title, version)
- [ ] Works with `--network host` mode
- [ ] Works with `-v /tmp/latte-driver:/sockets` volume mount
- [ ] Works with Latte's `--cql-adapter-image` flag (automatic container lifecycle)

### Testing

- [ ] Unit tests pass (`make test`)
- [ ] Lint checks pass (`make lint`)
- [ ] Integration tests pass (`make test-integration`)
- [ ] `primitives` workload passes
- [ ] `collections` workload passes (or documented as unsupported)
- [ ] `vectors` workload passes (or documented as unsupported)

### Manual Verification

1. Start your adapter with a socket path
2. Test each operation in isolation:
   - CREATE_SESSION → verify session ID returned
   - QUERY → execute simple SELECT
   - PREPARE → prepare INSERT statement
   - EXECUTE → execute with values
   - BATCH → execute multiple statements
3. Test concurrent requests (many streams in flight)
4. Test error handling (invalid queries, missing sessions)

---

## Desired Features

Features that would enhance the driver adapter protocol but are not yet implemented.

### CLOSE_SESSION Opcode

Add explicit session closure support to the protocol.

**Proposed opcode**: `CLOSE_SESSION (0x23)`

**Request body:**
```
[long] session_id
```

**Response**: `RESULT` with kind `VOID` on success, or `ERROR` if session not found.

**Why it's useful**:
- Allows graceful cleanup of database connections without terminating the socket
- Enables session rotation during long-running benchmarks
- Provides explicit resource management instead of relying on connection close
- Useful for testing session lifecycle and reconnection scenarios

**Current behavior**: Sessions are only closed when the socket connection terminates. All sessions associated with a connection are cleaned up together.

### Result Paging

Support for paging through large result sets. This requires coordinated changes across:

1. **Protocol changes**:
   - Add `page_size` field to QUERY/EXECUTE request frames
   - Add `paging_state` field to RESULT response frames (opaque bytes)
   - Add `paging_state` field to follow-up QUERY/EXECUTE requests

2. **Latte core changes**:
   - Support iterating through paged results in workload scripts
   - Track paging state across requests
   - Expose paging APIs in the Rune scripting context

3. **Adapter changes**:
   - Pass `page_size` to driver's execute calls
   - Extract and encode paging state from result sets
   - Accept paging state in follow-up requests

**Why it's useful**: Enables benchmarking of workloads that scan large tables or need to iterate through many rows without loading entire result sets into memory.

**Current workaround**: The `default_page_size` session parameter controls driver-side paging behavior, but results are still returned as a single response to Latte.

### Comprehensive Integration Testing

Extend integration test suites to provide complete coverage of the driver adapter protocol.

#### Session Parameters

Test all session parameters are correctly applied:
- Connection: `contact_points`, `keyspace`, `username`, `password`, `connections_per_shard`
- Topology: `datacenter`, `rack`
- Timeouts: `request_timeout_ms`, `connect_timeout_ms`
- Query defaults: `consistency`, `serial_consistency`, `default_page_size`
- SSL/TLS: `ssl_enabled`, `ssl_ca_cert`, `ssl_cert`, `ssl_key`, `ssl_verify_peer`

#### Data Type Operations

For each supported data type, test all operations:
- INSERT with literal values
- INSERT with prepared statement bindings
- SELECT and verify round-trip correctness
- UPDATE operations
- DELETE operations
- NULL value handling

Types to cover:
- Primitives: tinyint, smallint, int, bigint, float, double, boolean, text, ascii, varchar, blob
- Temporal: date, time, timestamp, duration
- Identifiers: uuid, timeuuid, inet
- Arbitrary precision: varint, decimal
- Collections: list, set, map (frozen and non-frozen, nested)
- Complex: tuple, UDT, vector

#### Lightweight Transactions (LWT)

Test conditional operations:
- `INSERT ... IF NOT EXISTS`
- `UPDATE ... IF EXISTS`
- `UPDATE ... IF column = value`
- `DELETE ... IF EXISTS`
- Verify `[applied]` result column handling

#### Consistency Levels

Test all consistency levels for reads and writes:
- Basic: `ANY`, `ONE`, `TWO`, `THREE`, `QUORUM`, `ALL`
- DC-aware: `LOCAL_ONE`, `LOCAL_QUORUM`, `EACH_QUORUM`
- Serial (for LWT): `SERIAL`, `LOCAL_SERIAL`

#### Batch Operations

Test batch execution:
- `LOGGED` batches
- `UNLOGGED` batches
- `COUNTER` batches
- Mixed statements in a single batch
- Batch with different consistency levels
