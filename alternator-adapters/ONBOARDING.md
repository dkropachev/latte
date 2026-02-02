# Alternator Adapter Onboarding Guide

This document contains all technical information required to implement a new Alternator driver adapter for Latte. It covers the IPC protocol specification, value encoding, implementation steps, and acceptance criteria.

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [IPC Protocol Specification](#ipc-protocol-specification)
4. [Data Type Encoding](#data-type-encoding)
5. [Request Frame Bodies](#request-frame-bodies)
6. [Response Frame Bodies](#response-frame-bodies)
7. [Error Codes](#error-codes)
8. [Session Management](#session-management)
9. [Backpressure and Flow Control](#backpressure-and-flow-control)
10. [Implementation Guide](#implementation-guide) (Steps 1-11, including Parent Makefile Registration)
11. [Performance Considerations](#performance-considerations)
12. [CI/CD Integration](#cicd-integration)
13. [Acceptance Criteria](#acceptance-criteria)
14. [Desired Features](#desired-features)

---

## Overview

This document describes the architecture for Alternator (DynamoDB-compatible) driver adapters in Latte. The design mirrors the CQL driver adapter approach: adapters run as separate processes communicating with Latte over Unix domain sockets using a binary protocol.

Alternator is ScyllaDB's DynamoDB-compatible API. Unlike CQL, it uses HTTP with JSON payloads following the DynamoDB wire protocol. This design enables Latte to benchmark Alternator/DynamoDB workloads using any driver implementation (AWS SDK for various languages, custom clients, etc.) without modifying the core Latte binary.

### Goals

1. **Driver Isolation**: Run any Alternator/DynamoDB driver in a separate process/container
2. **Language Agnostic**: Adapters can be implemented in any language (Go, Python, Java, Node.js, Rust, etc.)
3. **Low Overhead**: Binary IPC protocol minimizes serialization overhead
4. **Accurate Latency Measurement**: Capture driver-side latency separate from IPC overhead
5. **Reuse Existing Infrastructure**: Same socket management, container lifecycle, and backpressure as CQL adapters
6. **Latte-Owned Sockets**: Latte creates and binds the Unix domain socket; adapters connect to it as clients
7. **Automatic Container Lifecycle**: Latte can start and stop adapter Docker containers via `--alternator-adapter-image`

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Latte Host Process                            │
│  ┌─────────────┐    ┌──────────────┐    ┌───────────────────────────┐  │
│  │ Rune Script │───▶│ IpcClient    │───▶│ Unix Domain Socket        │  │
│  │ (workload)  │    │ (Alternator) │    │ (created by Latte)        │  │
│  └─────────────┘    └──────────────┘    └─────────────┬─────────────┘  │
│                                                        │                │
│  ┌──────────────────┐                                  │                │
│  │ DockerManager    │─── starts/stops container ───────┼──────┐        │
│  │ (if --alternator │    via `docker run/stop`         │      │        │
│  │  -adapter-image) │                                  │      │        │
│  └──────────────────┘                                  │      │        │
└───────────────────────────────────────────────────────┼──────┼─────────┘
                                                        │      │
                                              Binary Protocol  │
                                                        │      │
┌───────────────────────────────────────────────────────┼──────┼─────────┐
│                    Alternator Driver Adapter (Container/Process)        │
│                                              started by Latte ◀────────┘
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                         Adapter Binary                           │   │
│  │  ┌──────────────┐   ┌─────────────────┐   ┌─────────────────┐   │   │
│  │  │ Frame Parser │──▶│ Request Router  │──▶│ Session Registry│   │   │
│  │  └──────────────┘   └─────────────────┘   └────────┬────────┘   │   │
│  │                                                     │            │   │
│  │  ┌──────────────────────────────────────────────────▼──────────┐│   │
│  │  │                    DynamoDB/Alternator Driver               ││   │
│  │  │  (AWS SDK, boto3, aws-sdk-go, custom client, etc.)          ││   │
│  │  └──────────────────────────────────────────────────┬──────────┘│   │
│  └─────────────────────────────────────────────────────┼───────────┘   │
└───────────────────────────────────────────────────────┼─────────────────┘
                                                        │
                                                  HTTP/HTTPS
                                                        │
                                            ┌───────────▼───────────┐
                                            │  ScyllaDB Alternator  │
                                            │    or Amazon DynamoDB │
                                            └───────────────────────┘
```

**How Latte manages adapter containers:**

When `--alternator-adapter-image` is specified, Latte handles the full container lifecycle:

1. Latte creates and binds the Unix domain socket
2. Latte starts the adapter Docker container (`docker run`) with the socket directory mounted as a volume
3. The adapter container connects to the socket
4. Latte communicates with the adapter via the binary protocol
5. When Latte exits, the container is automatically stopped (`docker stop` in `Drop` handler)

This means users only need a single command:
```bash
latte run --dynamodb --dynamodb-endpoint http://localhost:8000 \
    --alternator-adapter-image scylladb/latte-alternator-adapters:my-adapter-dev \
    workload.rn
```

Alternatively, the adapter can be started manually (as a local process or Docker container), and Latte connects via `--alternator-driver-socket`.

---

## IPC Protocol Specification

### Frame Format

The protocol uses a binary frame format similar to CQL but adapted for DynamoDB operations.

#### Frame Header (12 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    Version    |     Flags     |           Stream ID           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    Opcode     |   Reserved    |          Reserved             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Body Length                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Field       | Size    | Description                                      |
|-------------|---------|--------------------------------------------------|
| Version     | 1 byte  | Protocol version (0x01 request, 0x81 response)   |
| Flags       | 1 byte  | Reserved for future use (compression, tracing)   |
| Stream ID   | 2 bytes | Request/response correlation ID (big-endian i16) |
| Opcode      | 1 byte  | Operation type (see Opcodes section)             |
| Reserved    | 3 bytes | Reserved for future use                          |
| Body Length | 4 bytes | Length of frame body in bytes (big-endian u32)   |

#### Opcodes

**Request Opcodes** (Version = 0x01):

| Opcode | Name             | Description                              |
|--------|------------------|------------------------------------------|
| 0x01   | CREATE_SESSION   | Create a new driver session              |
| 0x02   | CLOSE_SESSION    | Close an existing session                |
| 0x10   | GET_ITEM         | DynamoDB GetItem operation               |
| 0x11   | PUT_ITEM         | DynamoDB PutItem operation               |
| 0x12   | DELETE_ITEM      | DynamoDB DeleteItem operation            |
| 0x13   | UPDATE_ITEM      | DynamoDB UpdateItem operation            |
| 0x14   | QUERY            | DynamoDB Query operation                 |
| 0x15   | SCAN             | DynamoDB Scan operation                  |
| 0x20   | BATCH_GET_ITEM   | DynamoDB BatchGetItem operation          |
| 0x21   | BATCH_WRITE_ITEM | DynamoDB BatchWriteItem operation        |
| 0x22   | TRANSACT_GET     | DynamoDB TransactGetItems operation      |
| 0x23   | TRANSACT_WRITE   | DynamoDB TransactWriteItems operation    |
| 0x30   | CREATE_TABLE     | DynamoDB CreateTable operation           |
| 0x31   | DELETE_TABLE     | DynamoDB DeleteTable operation           |
| 0x32   | DESCRIBE_TABLE   | DynamoDB DescribeTable operation         |
| 0x33   | LIST_TABLES      | DynamoDB ListTables operation            |
| 0xFE   | SHUTDOWN         | Graceful shutdown signal                 |

**Response Opcodes** (Version = 0x81):

| Opcode | Name           | Description                                |
|--------|----------------|--------------------------------------------|
| 0x00   | ERROR          | Operation failed                           |
| 0x01   | SESSION_CREATED| Session successfully created               |
| 0x02   | SESSION_CLOSED | Session successfully closed                |
| 0x10   | ITEM_RESULT    | Single item result (GetItem, PutItem, etc.)|
| 0x14   | QUERY_RESULT   | Query/Scan result with items               |
| 0x20   | BATCH_RESULT   | Batch operation result                     |
| 0x22   | TRANSACT_RESULT| Transaction result                         |
| 0x30   | TABLE_RESULT   | Table operation result                     |
| 0x33   | LIST_RESULT    | List tables result                         |
| 0xFE   | SHUTDOWN_ACK   | Shutdown acknowledged                      |

---

## Data Type Encoding

### Primitive Encodings

All multi-byte integers are big-endian.

| Type     | Encoding                                           |
|----------|----------------------------------------------------|
| u8       | 1 byte unsigned                                    |
| u16      | 2 bytes unsigned big-endian                        |
| u32      | 4 bytes unsigned big-endian                        |
| u64      | 8 bytes unsigned big-endian                        |
| i64      | 8 bytes signed big-endian                          |
| bytes    | u32 length + raw bytes                             |
| string   | u32 length + UTF-8 bytes                           |
| bool     | 1 byte (0x00 = false, 0x01 = true)                 |

### DynamoDB AttributeValue Encoding

DynamoDB uses a tagged union format for attribute values. We encode this efficiently in binary:

```
AttributeValue := type_tag (u8) + type-specific payload

Type Tags:
  0x00 = NULL          payload: (none)
  0x01 = BOOL          payload: u8 (0=false, 1=true)
  0x02 = N (Number)    payload: string (decimal number as string)
  0x03 = S (String)    payload: string
  0x04 = B (Binary)    payload: bytes
  0x05 = SS (StringSet)payload: u32 count + count * string
  0x06 = NS (NumberSet)payload: u32 count + count * string
  0x07 = BS (BinarySet)payload: u32 count + count * bytes
  0x08 = L (List)      payload: u32 count + count * AttributeValue
  0x09 = M (Map)       payload: u32 count + count * (string key + AttributeValue)
```

### Key Encoding

Primary keys and sort keys are encoded as a map of attribute name to AttributeValue:

```
Key := u16 attr_count + attr_count * (string attr_name + AttributeValue)
```

### Item Encoding

An item is a map of attribute names to values:

```
Item := u32 attr_count + attr_count * (string attr_name + AttributeValue)
```

### Condition Expression Encoding

```
ConditionExpression := {
  expression: string                           // Expression string
  expr_attr_names: u16 count + count * (string placeholder + string attr_name)
  expr_attr_values: u16 count + count * (string placeholder + AttributeValue)
}
```

---

## Request Frame Bodies

### CREATE_SESSION

Creates a new session with the Alternator/DynamoDB endpoint.

```
Body := {
  param_count: u16
  params: param_count * {
    key: string
    value: string
  }
}
```

**Session Parameters:**

| Key                 | Description                                      | Default            |
|---------------------|--------------------------------------------------|--------------------|
| `endpoint`          | Alternator/DynamoDB endpoint URL                 | http://localhost:8000 |
| `region`            | AWS region                                       | us-east-1          |
| `access_key_id`     | AWS access key ID                                | (none)             |
| `secret_access_key` | AWS secret access key                            | (none)             |
| `session_token`     | AWS session token (for temporary credentials)   | (none)             |
| `max_connections`   | Connection pool size                             | 100                |
| `request_timeout_ms`| Per-request timeout in milliseconds              | 5000               |
| `connect_timeout_ms`| Connection timeout in milliseconds               | 3000               |
| `retry_mode`        | Retry strategy: `none`, `standard`, `adaptive`   | none               |
| `max_retries`       | Maximum retry attempts                           | 0                  |

### GET_ITEM

```
Body := {
  session_id: u64
  table_name: string
  key: Key
  consistent_read: bool
  projection_expression: optional<string>         // 0x00 = absent, 0x01 + string = present
  expr_attr_names: u16 count + count * (string + string)
}
```

### PUT_ITEM

```
Body := {
  session_id: u64
  table_name: string
  item: Item
  condition_expression: optional<ConditionExpression>
  return_values: u8                               // 0=NONE, 1=ALL_OLD
}
```

### DELETE_ITEM

```
Body := {
  session_id: u64
  table_name: string
  key: Key
  condition_expression: optional<ConditionExpression>
  return_values: u8                               // 0=NONE, 1=ALL_OLD
}
```

### UPDATE_ITEM

```
Body := {
  session_id: u64
  table_name: string
  key: Key
  update_expression: string
  condition_expression: optional<ConditionExpression>
  expr_attr_names: u16 count + count * (string + string)
  expr_attr_values: u16 count + count * (string + AttributeValue)
  return_values: u8                               // 0=NONE, 1=ALL_OLD, 2=UPDATED_OLD, 3=ALL_NEW, 4=UPDATED_NEW
}
```

### QUERY

```
Body := {
  session_id: u64
  table_name: string
  index_name: optional<string>
  key_condition_expression: string
  filter_expression: optional<string>
  projection_expression: optional<string>
  expr_attr_names: u16 count + count * (string + string)
  expr_attr_values: u16 count + count * (string + AttributeValue)
  limit: optional<u32>                            // 0x00 = no limit, 0x01 + u32 = limit
  consistent_read: bool
  scan_forward: bool                              // true = ascending, false = descending
  exclusive_start_key: optional<Key>              // For pagination
}
```

### SCAN

```
Body := {
  session_id: u64
  table_name: string
  index_name: optional<string>
  filter_expression: optional<string>
  projection_expression: optional<string>
  expr_attr_names: u16 count + count * (string + string)
  expr_attr_values: u16 count + count * (string + AttributeValue)
  limit: optional<u32>
  consistent_read: bool
  segment: optional<u32>                          // For parallel scan
  total_segments: optional<u32>
  exclusive_start_key: optional<Key>
}
```

### BATCH_GET_ITEM

```
Body := {
  session_id: u64
  request_items: u16 table_count + table_count * {
    table_name: string
    keys: u32 key_count + key_count * Key
    consistent_read: bool
    projection_expression: optional<string>
    expr_attr_names: u16 count + count * (string + string)
  }
}
```

### BATCH_WRITE_ITEM

```
Body := {
  session_id: u64
  request_items: u16 table_count + table_count * {
    table_name: string
    requests: u32 req_count + req_count * WriteRequest
  }
}

WriteRequest := {
  request_type: u8                                // 0x01 = PutRequest, 0x02 = DeleteRequest
  item_or_key: Item | Key                         // Item for Put, Key for Delete
}
```

### TRANSACT_GET

```
Body := {
  session_id: u64
  transact_items: u16 count + count * {
    table_name: string
    key: Key
    projection_expression: optional<string>
    expr_attr_names: u16 count + count * (string + string)
  }
}
```

### TRANSACT_WRITE

```
Body := {
  session_id: u64
  transact_items: u16 count + count * TransactWriteItem
  client_request_token: optional<string>          // Idempotency token
}

TransactWriteItem := {
  action_type: u8                                 // 0x01=Put, 0x02=Delete, 0x03=Update, 0x04=ConditionCheck
  table_name: string
  key: Key                                        // For Delete, Update, ConditionCheck
  item: optional<Item>                            // For Put
  update_expression: optional<string>             // For Update
  condition_expression: optional<ConditionExpression>
  return_values_on_failure: u8                    // 0=NONE, 1=ALL_OLD
}
```

### CREATE_TABLE

```
Body := {
  session_id: u64
  table_name: string
  attribute_definitions: u16 count + count * {
    name: string
    type: u8                                      // 0x02=N, 0x03=S, 0x04=B
  }
  key_schema: u16 count + count * {
    name: string
    key_type: u8                                  // 0x01=HASH, 0x02=RANGE
  }
  billing_mode: u8                                // 0x01=PROVISIONED, 0x02=PAY_PER_REQUEST
  provisioned_throughput: optional<{
    read_capacity_units: u64
    write_capacity_units: u64
  }>
  global_secondary_indexes: u16 count + count * GSIDefinition
  local_secondary_indexes: u16 count + count * LSIDefinition
}
```

### DELETE_TABLE

```
Body := {
  session_id: u64
  table_name: string
}
```

### DESCRIBE_TABLE

```
Body := {
  session_id: u64
  table_name: string
}
```

---

## Response Frame Bodies

### SESSION_CREATED

```
Body := {
  session_id: u64
}
```

### ERROR

```
Body := {
  error_code: u32                                 // See Error Codes section
  error_type: string                              // DynamoDB exception type
  message: string                                 // Human-readable message
}
```

### ITEM_RESULT

```
Body := {
  has_item: bool
  item: optional<Item>                            // Present if has_item=true
  consumed_capacity: optional<ConsumedCapacity>
  driver_latency_ns: u64                          // Measured driver-side latency
}
```

### QUERY_RESULT

```
Body := {
  item_count: u32
  items: item_count * Item
  last_evaluated_key: optional<Key>               // For pagination
  scanned_count: u32
  consumed_capacity: optional<ConsumedCapacity>
  driver_latency_ns: u64
}
```

### BATCH_RESULT

```
Body := {
  // For BatchGetItem:
  responses: u16 table_count + table_count * {
    table_name: string
    items: u32 count + count * Item
  }
  unprocessed_keys: u16 table_count + table_count * {
    table_name: string
    keys: u32 count + count * Key
  }

  // For BatchWriteItem:
  unprocessed_items: u16 table_count + table_count * {
    table_name: string
    requests: u32 count + count * WriteRequest
  }

  consumed_capacity: optional<ConsumedCapacity[]>
  driver_latency_ns: u64
}
```

### TABLE_RESULT

```
Body := {
  table_description: {
    table_name: string
    table_status: u8                              // 0x01=CREATING, 0x02=ACTIVE, 0x03=DELETING, etc.
    key_schema: KeySchema
    attribute_definitions: AttributeDefinitions
    // ... additional fields as needed
  }
  driver_latency_ns: u64
}
```

---

## Error Codes

| Code       | Name                     | Description                                |
|------------|--------------------------|--------------------------------------------|
| 0x0000     | UNKNOWN                  | Unknown error                              |
| 0x0001     | PROTOCOL_ERROR           | Invalid frame format or unknown opcode     |
| 0x0002     | SESSION_NOT_FOUND        | Invalid session ID                         |
| 0x0003     | CONNECTION_ERROR         | Failed to connect to endpoint              |
| 0x0004     | TIMEOUT                  | Request timed out                          |
| 0x0005     | OVERLOADED               | Too many in-flight requests                |
| 0x1001     | RESOURCE_NOT_FOUND       | Table or index not found                   |
| 0x1002     | RESOURCE_IN_USE          | Resource is being created/deleted          |
| 0x1003     | VALIDATION_ERROR         | Invalid request parameters                 |
| 0x1004     | CONDITIONAL_CHECK_FAILED | Condition expression evaluated to false    |
| 0x1005     | TRANSACTION_CANCELED     | Transaction was canceled                   |
| 0x1006     | PROVISIONED_THROUGHPUT   | Exceeded provisioned throughput            |
| 0x1007     | ITEM_COLLECTION_SIZE     | Item collection size limit exceeded        |
| 0x1008     | LIMIT_EXCEEDED           | Too many operations in a single request    |
| 0x1009     | REQUEST_LIMIT_EXCEEDED   | Request rate exceeded                      |
| 0x100A     | INTERNAL_SERVER_ERROR    | DynamoDB internal error                    |
| 0x100B     | SERVICE_UNAVAILABLE      | Service temporarily unavailable            |

---

## Session Management

### Session Lifecycle

```
1. CREATE_SESSION Request
   ├─ Adapter receives connection parameters
   ├─ Creates HTTP client with configured endpoint
   ├─ Optionally validates connection (DescribeLimits or ListTables)
   ├─ Stores session in registry with unique ID
   └─ Returns SESSION_CREATED with session_id

2. During Operation
   ├─ Each request includes session_id
   ├─ Adapter looks up session in registry
   ├─ Executes operation using session's HTTP client
   └─ Returns result with driver_latency_ns

3. CLOSE_SESSION Request
   ├─ Adapter looks up session
   ├─ Drains pending requests (optional)
   ├─ Closes HTTP client/connection pool
   ├─ Removes session from registry
   └─ Returns SESSION_CLOSED

4. Connection Close / SHUTDOWN
   ├─ Close all sessions
   ├─ Stop accepting new requests
   ├─ Drain in-flight operations
   └─ Exit cleanly
```

### Multi-Session Support

Adapters MUST support multiple concurrent sessions. Each session:
- Has an independent HTTP client/connection pool
- Can target different endpoints (useful for multi-region testing)
- Has independent configuration (timeouts, retry policies)
- Is identified by a unique u64 session_id

---

## Backpressure and Flow Control

### In-Flight Request Limiting

```
┌─────────────────────────────────────────────────────────────┐
│                    Adapter Process                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │            Semaphore (configurable permits)          │   │
│  └────────────────────────┬────────────────────────────┘   │
│                           │                                 │
│  ┌──────────┐      ┌──────▼──────┐      ┌──────────────┐   │
│  │  Reader  │─────▶│ acquire()   │─────▶│ Dispatch     │   │
│  │  Loop    │      │ permit      │      │ Task         │   │
│  └──────────┘      └─────────────┘      └──────┬───────┘   │
│                                                 │           │
│                                          ┌──────▼───────┐   │
│                                          │ Driver Call  │   │
│                                          └──────┬───────┘   │
│                                                 │           │
│                                          ┌──────▼───────┐   │
│                                          │ release()    │   │
│                                          │ permit       │   │
│                                          └──────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

Configuration:
- `LATTE_ALTERNATOR_INFLIGHT`: Maximum concurrent requests (default: 512)

When limit is exceeded:
- Reader blocks on semaphore acquire
- Does NOT return OVERLOADED error (backpressure at socket level)
- Ensures adapter doesn't spawn unbounded tasks

---

## Implementation Guide

### Step 1: Socket Client

Connect to the Unix domain socket that Latte has already created:
- Read the socket path from the `LATTE_ALTERNATOR_SOCKET` environment variable
- Connect to the socket as a client (Latte is the server/listener)
- Split the connection into read/write halves for full-duplex I/O

**Important:** Latte creates and owns the socket file. The adapter must **not** create or bind a socket. It should connect to the existing socket file, retrying for up to 30 seconds to allow time for Latte to create it.

**Socket connection pseudocode:**
```
fn main():
    // Read socket path from environment (REQUIRED)
    socket_path = env.get("LATTE_ALTERNATOR_SOCKET")
        .unwrap_or("/tmp/latte-alternator.sock")

    // Connect to the socket created by Latte (retry for up to 30s)
    deadline = now() + 30 seconds
    loop:
        try:
            stream = UnixSocket::connect(socket_path)
            break
        catch error:
            if now() >= deadline:
                error("Timeout connecting to latte socket at " + socket_path)
                exit(1)
            sleep(100ms)

    // Split into read/write halves
    (reader, writer) = stream.split()
    handle_connection(reader, writer)
```

**Important socket behaviors:**
- The adapter is a **client** — it connects to a socket that Latte has already bound
- Do **not** call `bind()` or create the socket file — Latte does this
- Retry connecting for up to 30 seconds (100ms between attempts) to handle startup timing
- If the socket is not available after 30 seconds, fail with a clear timeout error
- The adapter process should exit cleanly when the connection is closed by Latte

### Step 2: Frame Parsing

Implement frame reading:
```
1. Read 12-byte header
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
- CREATE_SESSION creates a new HTTP client/connection pool and returns a new ID
- All other operations include a session_id to identify which session to use

### Step 5: Request Handlers

Implement handlers for each DynamoDB operation:
- Parse request body according to opcode
- Build DynamoDB request using the driver SDK
- Execute operation and measure latency
- Encode response with driver_latency_ns

### Step 6: Configuration

Support these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_ALTERNATOR_SOCKET` | `/tmp/latte-alternator.sock` | Socket path |
| `LATTE_ALTERNATOR_ENDPOINT` | `http://localhost:8000` | Default Alternator/DynamoDB endpoint |
| `LATTE_ALTERNATOR_REGION` | `us-east-1` | Default AWS region |
| `LATTE_ALTERNATOR_ACCESS_KEY` | (none) | Default AWS access key ID |
| `LATTE_ALTERNATOR_SECRET_KEY` | (none) | Default AWS secret access key |
| `LATTE_ALTERNATOR_INFLIGHT` | `512` | Max concurrent requests |

**Environment variable details:**

**`LATTE_ALTERNATOR_SOCKET`** (Required)
- Path to the Unix domain socket that Latte has created
- In Docker: Set to `/sockets/latte-alternator.sock` (inside the mounted volume)
- Native: Set to `/tmp/latte-alternator.sock` or any accessible path
- The adapter connects to this socket — it does **not** create it
- The adapter retries connecting for up to 30 seconds, then fails with a clear timeout error

**`LATTE_ALTERNATOR_ENDPOINT`** (Required)
- HTTP(S) URL of the Alternator or DynamoDB endpoint
- For Alternator: `http://localhost:8000` or `http://<scylla-host>:8000`
- For DynamoDB: Use the regional endpoint or `http://localhost:8000` for DynamoDB Local

**`LATTE_ALTERNATOR_INFLIGHT`** (Optional)
- Maximum concurrent requests handled by the adapter
- Controls semaphore size for backpressure
- Higher values: more throughput, more memory
- Lower values: less throughput, better latency stability

**Latte CLI flags for adapter Docker management:**

These are Latte-side flags (not adapter environment variables) that control how Latte connects to the adapter:

| Flag | Description |
|------|-------------|
| `--alternator-adapter-image IMAGE` | Latte starts a Docker container from IMAGE, mounts the socket directory, and stops it on exit |
| `--alternator-driver-socket PATH` | Latte connects to an adapter already listening at PATH (manual mode) |
| `--dynamodb-endpoint URL` | Passed to the adapter as `LATTE_ALTERNATOR_ENDPOINT` when using `--alternator-adapter-image` |

When `--alternator-adapter-image` is used, Latte:
1. Creates a temporary socket directory and binds a Unix domain socket
2. Runs `docker run --rm -d --network=host` with the socket directory mounted at `/sockets`
3. Sets `LATTE_ALTERNATOR_SOCKET` and `LATTE_ALTERNATOR_ENDPOINT` inside the container
4. Waits for the adapter to connect to the socket
5. Stops the container when the Latte command finishes (via `Drop` handler, even on error)

### Step 7: Docker Image

#### Socket Path Convention

The adapter and Latte communicate via a Unix domain socket. **Latte creates the socket file** and the adapter connects to it. The socket file must be accessible to both processes via a shared volume mount:

```
┌─────────────────┐          ┌─────────────────────────────────┐
│   HOST SYSTEM   │          │        DOCKER CONTAINER         │
│                 │          │                                 │
│  /tmp/latte-alternator/  ◀──volume mount──▶  /sockets/      │
│        │        │          │                │                │
│        └── latte-alternator.sock ────────▶ │                │
│                 │          │                                 │
│   Latte creates │          │   Adapter connects to socket at:│
│   socket here   │          │   /sockets/latte-alternator.sock│
└─────────────────┘          └─────────────────────────────────┘
```

**Key points:**
- Latte creates the socket file on the host (e.g., `/tmp/latte-alternator/latte-alternator.sock`)
- Volume mount maps the host directory to `/sockets/` inside the container
- The adapter connects to the socket at the path given by `LATTE_ALTERNATOR_SOCKET`
- The adapter must **not** create the socket — it retries connecting for up to 30 seconds

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

ARG ADAPTER_VERSION=dev

# OCI labels (recommended)
LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Alternator Adapter (<Driver Name>)"
LABEL org.opencontainers.image.version="${ADAPTER_VERSION}"

# Install runtime dependencies only
RUN <install-runtime-deps>

# Copy binary/artifact from builder
COPY --from=builder /build/<artifact> /app/

# ==============================================================================
# Socket Configuration (REQUIRED)
# ==============================================================================
# Default socket path - the adapter connects to this socket (created by Latte)
ENV LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock

# Default endpoint for Alternator/DynamoDB
ENV LATTE_ALTERNATOR_ENDPOINT=http://localhost:8000

# Default AWS region
ENV LATTE_ALTERNATOR_REGION=us-east-1

# Concurrency limit
ENV LATTE_ALTERNATOR_INFLIGHT=512

# Declare /sockets as a volume mount point (Latte creates the socket on the host)
VOLUME /sockets

ENTRYPOINT ["/app/<binary>"]
```

#### Running the Container

**Automatic mode (recommended) — Latte manages the container:**

Latte can start and stop the adapter container automatically using `--alternator-adapter-image`. Latte creates the socket, starts the container with the socket directory mounted, and stops the container when the command finishes:

```bash
latte schema --dynamodb --dynamodb-endpoint http://127.0.0.1:8000 \
    --alternator-adapter-image scylladb/latte-alternator-adapters:<adapter>-<version> \
    workload.rn

latte load --dynamodb --dynamodb-endpoint http://127.0.0.1:8000 \
    --alternator-adapter-image scylladb/latte-alternator-adapters:<adapter>-<version> \
    workload.rn

latte run --dynamodb --dynamodb-endpoint http://127.0.0.1:8000 \
    --alternator-adapter-image scylladb/latte-alternator-adapters:<adapter>-<version> \
    workload.rn
```

Under the hood, Latte:
1. Creates a socket directory and binds a Unix domain socket
2. Runs `docker run --rm -d --network=host -v <socket_dir>:/sockets -e LATTE_ALTERNATOR_SOCKET=... -e LATTE_ALTERNATOR_ENDPOINT=... <image>`
3. Waits for the adapter to connect
4. Stops the container on exit (including on error/crash via `Drop` handler)

**Manual mode — start the container yourself:**

For development or advanced use cases, you can start the container manually and point Latte at the socket:

```bash
# Create socket directory on host
mkdir -p /tmp/latte-alternator

# Run container
docker run --rm -it \
    --network host \
    -v /tmp/latte-alternator:/sockets \
    -e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
    -e LATTE_ALTERNATOR_ENDPOINT=http://127.0.0.1:8000 \
    scylladb/latte-alternator-adapters:<adapter>-<version>

# In another terminal, connect Latte to the adapter
latte run --dynamodb --dynamodb-endpoint http://127.0.0.1:8000 \
    --alternator-driver-socket /tmp/latte-alternator/latte-alternator.sock \
    workload.rn
```

#### Makefile docker-run Target

Include a `docker-run` target in your Makefile for local testing (manual mode):

```makefile
.PHONY: docker-run
docker-run: ## Run the alternator adapter container locally
	@mkdir -p /tmp/latte-alternator
	docker run --rm -it \
		--network host \
		-v /tmp/latte-alternator:/sockets \
		-e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
		-e LATTE_ALTERNATOR_ENDPOINT=http://127.0.0.1:8000 \
		$(DOCKER_IMAGE)
```

For automatic mode, no Makefile target is needed — Latte starts the container itself:
```bash
latte run --dynamodb --dynamodb-endpoint http://127.0.0.1:8000 \
    --alternator-adapter-image $(DOCKER_IMAGE) workload.rn
```

### Step 8: Directory Structure

Each adapter must follow this directory structure:

```
alternator-adapters/<driver-name>/
├── README.md           # Required: adapter documentation
├── Makefile            # Required: standard build interface
├── Dockerfile          # Required: container build
├── src/                # Source code
└── ...                 # Language-specific files (Cargo.toml, pom.xml, go.mod, etc.)
```

### Step 9: Docker Image Naming

All alternator adapter images must be pushed to the shared repository with a consistent naming scheme:

```
scylladb/latte-alternator-adapters:<driver-folder-name>-<driver-version>
```

Examples:
- `scylladb/latte-alternator-adapters:rust-aws-sdk-1.0.0`
- `scylladb/latte-alternator-adapters:rust-aws-sdk-latest`
- `scylladb/latte-alternator-adapters:go-aws-sdk-2.0.0`
- `scylladb/latte-alternator-adapters:python-boto3-1.28.0`

The `<driver-folder-name>` must match the adapter's directory name under `alternator-adapters/`.

### Step 10: Adapter README

Each adapter must include a `README.md` with the following sections:

```markdown
# <Adapter Name>

Short description of what this adapter does and its purpose.

## Driver

- **Driver**: <driver name and version>
- **Language**: <implementation language>
- **Repository**: <link to upstream driver>

## Docker Image

- **Image**: scylladb/latte-alternator-adapters:<driver-folder-name>-<version>
- **Tags**: `<driver-folder-name>-latest`, `<driver-folder-name>-<version>`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | ... |
| `make test` | ... |
| `make lint` | ... |
| `make lint-fix` | ... |
| `make build-docker-image` | ... |
| `make push-docker-image` | ... |

## Configuration

<environment variables and options>

## Usage

<example usage>
```

### Step 11: Parent Makefile Registration

After implementing the adapter, register it in the parent `alternator-adapters/Makefile` so it is included in all orchestrated targets (build, test, benchmark, profile).

#### How the parent Makefile runs adapters

The parent Makefile's `test-benchmark` target runs each adapter **from its Docker image** using Latte's `--alternator-adapter-image` flag. Latte automatically starts the Docker container, manages the socket, and stops the container when done. This eliminates host-level runtime dependencies (e.g., specific Java or Go versions) since each Dockerfile bundles the correct runtime.

The flow is:

```
test-benchmark
  │
  ├─ for each adapter:
  │    make -C $adapter build-docker-image      ← builds Docker image
  │
  └─ for each workload × adapter:
       _run-adapter-benchmark
         │
         ├─ latte schema --alternator-adapter-image $IMAGE ...
         │    ├─ latte creates socket             ← Latte binds the socket
         │    ├─ latte starts container            ← docker run (automatic)
         │    ├─ adapter connects to socket
         │    ├─ schema created
         │    └─ latte stops container             ← docker stop (automatic)
         │
         ├─ latte load --alternator-adapter-image $IMAGE ...
         │    └─ (same lifecycle as above)
         │
         └─ latte run --alternator-adapter-image $IMAGE ...
              └─ (same lifecycle as above)
```

Because the `_run-adapter-benchmark` target derives the Docker image tag from `$(ADAPTER)` using the standard naming convention (`$(DOCKER_REGISTRY)/latte-alternator-adapters:$(ADAPTER)-$(ADAPTER_VERSION)`), **no per-adapter case statement is needed**. As long as your adapter's Docker image follows the naming convention from [Step 9](#step-9-docker-image-naming), it will work automatically.

#### 1. Add to ADAPTERS list

Add your adapter directory name to the `ADAPTERS` variable:

```makefile
ADAPTERS := alternator-client-golang alternator-client-java <your-adapter-name>
```

This is the only change needed for your adapter to be picked up by `test-benchmark`, `test-functional`, `build`, and all other orchestrated targets.

#### 2. Add per-adapter shortcut targets

Add shortcut targets so users can build/test/benchmark/profile your adapter individually:

```makefile
.PHONY: build-<your-adapter-name>
build-<your-adapter-name>: ## Build <your-adapter-name> adapter
	$(MAKE) -C <your-adapter-name> build

.PHONY: test-functional-<your-adapter-name>
test-functional-<your-adapter-name>: ## Run functional tests for <your-adapter-name> adapter
	$(MAKE) -C <your-adapter-name> test-functional

.PHONY: test-benchmark-<your-adapter-name>
test-benchmark-<your-adapter-name>: ## Run benchmarks for <your-adapter-name> adapter
	$(MAKE) -C <your-adapter-name> test-benchmark

.PHONY: profile-<your-adapter-name>
profile-<your-adapter-name>: _check-latte-dep _ensure-db ## Run profiling for <your-adapter-name>
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C <your-adapter-name> profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=../$(PROFILE_OUTPUT_DIR)
```

#### Verification

After registration, verify your adapter is picked up by the orchestrated targets:

```bash
# Should list your adapter
make list-adapters

# Should build your adapter along with others
make build

# Should run functional tests for all adapters (including yours)
make test-functional

# Should run a single workload across all adapters (including yours)
make test-functional-basic
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

### HTTP Connection Pooling

Configure HTTP client with appropriate connection pool:
- Match pool size to expected concurrency
- Use keep-alive connections
- Consider separate pools per endpoint (for multi-region testing)

---

## CI/CD Integration

### GitHub Actions Workflow

Each adapter should include a `.github/workflows/ci.yml` (or be included in the repository's main workflow). Here's a template:

```yaml
name: CI

on:
  push:
    branches: [main]
    paths:
      - 'alternator-adapters/<adapter-name>/**'
  pull_request:
    branches: [main]
    paths:
      - 'alternator-adapters/<adapter-name>/**'

env:
  ADAPTER_NAME: <adapter-name>
  DOCKER_IMAGE: scylladb/latte-alternator-adapters

jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup language environment
        # Language-specific setup (e.g., setup-go, setup-python, setup-java)
        uses: actions/setup-<language>@v4
        with:
          <language>-version: '<version>'
      - name: Run linter
        run: make -C alternator-adapters/${{ env.ADAPTER_NAME }} lint

  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup language environment
        uses: actions/setup-<language>@v4
        with:
          <language>-version: '<version>'
      - name: Run unit tests
        run: make -C alternator-adapters/${{ env.ADAPTER_NAME }} test

  integration-test:
    runs-on: ubuntu-latest
    services:
      dynamodb-local:
        image: amazon/dynamodb-local:latest
        ports:
          - 8000:8000
    steps:
      - uses: actions/checkout@v4
      - name: Setup language environment
        uses: actions/setup-<language>@v4
        with:
          <language>-version: '<version>'
      - name: Wait for DynamoDB Local
        run: |
          for i in {1..30}; do
            curl -s http://localhost:8000 && break || sleep 1
          done
      - name: Run integration tests
        run: make -C alternator-adapters/${{ env.ADAPTER_NAME }} test-integration
        env:
          LATTE_ALTERNATOR_ENDPOINT: http://localhost:8000

  build-docker:
    runs-on: ubuntu-latest
    needs: [lint, test]
    steps:
      - uses: actions/checkout@v4
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3
      - name: Build Docker image
        run: make -C alternator-adapters/${{ env.ADAPTER_NAME }} build-docker-image
      - name: Test Docker image starts
        run: |
          # The adapter will retry connecting for up to 30s since no Latte is running.
          # We just verify the image starts and produces expected log output.
          docker run --rm -d --name test-adapter \
            -e LATTE_ALTERNATOR_SOCKET=/tmp/test.sock \
            ${{ env.DOCKER_IMAGE }}:${{ env.ADAPTER_NAME }}-dev
          sleep 5
          docker logs test-adapter
          docker stop test-adapter

  release:
    runs-on: ubuntu-latest
    needs: [lint, test, integration-test, build-docker]
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    steps:
      - uses: actions/checkout@v4
      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}
      - name: Build and push
        run: make -C alternator-adapters/${{ env.ADAPTER_NAME }} push-docker-image
        env:
          ADAPTER_VERSION: ${{ github.sha }}
```

### Required Makefile Layout

Each adapter Makefile must follow this layout. The parent Makefile relies on the `build-docker-image` target to produce a Docker image that is used to run benchmarks. The image must follow the naming convention from [Step 9](#step-9-docker-image-naming).

**Path Convention:** All paths that reference files outside the adapter directory **must** be relative to the Makefile's own location, not the working directory. Use `MAKEFILE_DIR` (defined via `$(dir $(abspath $(lastword $(MAKEFILE_LIST))))`) as a prefix for all external paths. This ensures the Makefile works correctly regardless of how `make` is invoked (e.g., `make -C <dir>`, `make -f <path>/Makefile`, or from the parent Makefile).

The sections below form a complete, copy-pasteable Makefile schematic. Replace `<placeholders>` with language-specific commands.

```makefile
# ==============================================================================
# Configuration
# ==============================================================================
MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
ADAPTER_NAME := <adapter-name>
ADAPTER_VERSION ?= dev
DOCKER_REGISTRY := scylladb
DOCKER_IMAGE := $(DOCKER_REGISTRY)/latte-alternator-adapters:$(ADAPTER_NAME)-$(ADAPTER_VERSION)
DOCKER_IMAGE_LATEST := $(DOCKER_REGISTRY)/latte-alternator-adapters:$(ADAPTER_NAME)-latest

# Test configuration
TEST_ENDPOINT ?= http://localhost:8000
TEST_REGION ?= us-east-1
TEST_SOCKET ?= /tmp/test-alternator-<short-name>.sock

# Latte integration test configuration
LATTE_BIN ?= $(MAKEFILE_DIR)../../target/release/latte
WORKLOAD_DIR ?= $(MAKEFILE_DIR)../../workloads/dynamodb/cicd
E2E_DURATION ?= 10s
E2E_ITEM_COUNT ?= 100

# Benchmark configuration
BENCHMARK_DURATION ?= 5s
BENCHMARK_ITEM_COUNT ?= 10000
BENCHMARK_WORKLOAD_DIR := $(MAKEFILE_DIR)../.workloads/benchmark
BENCHMARK_WORKLOADS := $(patsubst $(BENCHMARK_WORKLOAD_DIR)/%.rn,%,$(wildcard $(BENCHMARK_WORKLOAD_DIR)/*.rn))
BENCHMARK_REPORTS_DIR ?= .benchmark-reports

# Language-specific configuration
# e.g., GO := go; BINARY := bin/adapter
# e.g., JAVA ?= java; MVN := mvn; JAR := target/<name>.jar

# ==============================================================================
# Build Targets
# ==============================================================================
.PHONY: build
build: ## Build the adapter binary/JAR
	# Language-specific build command
	# e.g., go build -o bin/adapter ./cmd/adapter
	# e.g., mvn -q package -DskipTests
	# e.g., cargo build --release

.PHONY: build-force
build-force: ## Force rebuild (requires build tools)
	# Language-specific clean + build

.PHONY: clean
clean: ## Clean build artifacts
	# Language-specific clean command

# ==============================================================================
# Test Targets
# ==============================================================================
.PHONY: test
test: ## Run unit tests
	# e.g., go test ./...
	# e.g., mvn test
	# e.g., cargo test

.PHONY: test-integration
test-integration: ## Run integration tests (requires DynamoDB Local or Alternator)
	# Language-specific integration test command

.PHONY: test-all
test-all: test test-integration ## Run all tests

# ==============================================================================
# Functional Tests (with Latte)
# ==============================================================================
# These tests run each DynamoDB workload end-to-end through the adapter.
# Requires: Latte binary, DynamoDB Local or Alternator running, Docker image built.
#
# Latte starts the adapter Docker container automatically via --alternator-adapter-image.
# This ensures the test environment matches production and avoids host-level runtime
# dependencies. Latte handles the full container lifecycle (start, socket, stop).

# All DynamoDB workloads directory (for functional tests covering all workloads)
DYNAMODB_WORKLOAD_DIR ?= $(MAKEFILE_DIR)../../workloads/dynamodb

# Functional test runner function
# Usage: $(call run-functional-test,workload-relative-path)
# Latte starts/stops the adapter Docker container for each command via --alternator-adapter-image.
# Verifies "Driver latency" appears in the run output.
define run-functional-test
	@echo "=== Functional Test: $(1) ==="
	@echo "Endpoint: $(TEST_ENDPOINT)"
	@echo "Image: $(DOCKER_IMAGE)"
	@echo "Running schema..."; \
	$(LATTE_BIN) schema --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-adapter-image $(DOCKER_IMAGE) -P "items=$(E2E_ITEM_COUNT)" $(DYNAMODB_WORKLOAD_DIR)/$(1).rn; \
	echo "Running load..."; \
	$(LATTE_BIN) load --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-adapter-image $(DOCKER_IMAGE) -P "items=$(E2E_ITEM_COUNT)" $(DYNAMODB_WORKLOAD_DIR)/$(1).rn; \
	echo "Running workload for $(E2E_DURATION)..."; \
	OUTPUT=$$($(LATTE_BIN) run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-adapter-image $(DOCKER_IMAGE) -d $(E2E_DURATION) -P "items=$(E2E_ITEM_COUNT)" $(DYNAMODB_WORKLOAD_DIR)/$(1).rn 2>&1); \
	echo "$$OUTPUT"; \
	echo "Verifying driver latency is reported..."; \
	if echo "$$OUTPUT" | grep -q "Driver latency"; then \
		echo "Driver latency: VERIFIED"; \
	else \
		echo "ERROR: Driver latency not found in output"; \
		exit 1; \
	fi; \
	echo "=== Functional Test $(1) PASSED ==="
endef

.PHONY: test-functional
test-functional: test-functional-basic test-functional-batch test-functional-conditional test-functional-multi_client test-functional-query_gsi test-functional-parallel_scan test-functional-cicd-primitives test-functional-cicd-collections test-functional-cicd-nested-collections test-functional-cicd-crud test-functional-cicd-query test-functional-cicd-scan test-functional-cicd-batch test-functional-cicd-conditional ## Run all functional tests with Latte

# --- Root-level DynamoDB workloads (workloads/dynamodb/) ---

.PHONY: test-functional-basic
test-functional-basic: build-docker-image
	$(call run-functional-test,basic)

.PHONY: test-functional-batch
test-functional-batch: build-docker-image
	$(call run-functional-test,batch)

.PHONY: test-functional-conditional
test-functional-conditional: build-docker-image
	$(call run-functional-test,conditional)

.PHONY: test-functional-multi_client
test-functional-multi_client: build-docker-image
	$(call run-functional-test,multi_client)

.PHONY: test-functional-query_gsi
test-functional-query_gsi: build-docker-image
	$(call run-functional-test,query_gsi)

.PHONY: test-functional-parallel_scan
test-functional-parallel_scan: build-docker-image
	$(call run-functional-test,parallel_scan)

# --- CI/CD workloads (workloads/dynamodb/cicd/) ---

.PHONY: test-functional-cicd-primitives
test-functional-cicd-primitives: build-docker-image
	$(call run-functional-test,cicd/primitives)

.PHONY: test-functional-cicd-collections
test-functional-cicd-collections: build-docker-image
	$(call run-functional-test,cicd/collections)

.PHONY: test-functional-cicd-nested-collections
test-functional-cicd-nested-collections: build-docker-image
	$(call run-functional-test,cicd/nested-collections)

.PHONY: test-functional-cicd-crud
test-functional-cicd-crud: build-docker-image
	$(call run-functional-test,cicd/crud)

.PHONY: test-functional-cicd-query
test-functional-cicd-query: build-docker-image
	$(call run-functional-test,cicd/query)

.PHONY: test-functional-cicd-scan
test-functional-cicd-scan: build-docker-image
	$(call run-functional-test,cicd/scan)

.PHONY: test-functional-cicd-batch
test-functional-cicd-batch: build-docker-image
	$(call run-functional-test,cicd/batch)

.PHONY: test-functional-cicd-conditional
test-functional-cicd-conditional: build-docker-image
	$(call run-functional-test,cicd/conditional)

# ==============================================================================
# Benchmark Tests (with Latte)
# ==============================================================================
# These tests run performance benchmarks and generate JSON reports.
# Requires: Latte binary, DynamoDB Local or Alternator running, Docker image built.
#
# Latte starts the adapter Docker container automatically via --alternator-adapter-image.

# Benchmark test runner function
# Usage: $(call run-benchmark-test,workload-name,report-file)
define run-benchmark-test
	@echo "=== Benchmark Test: $(1) ==="
	@echo "Endpoint: $(TEST_ENDPOINT)"
	@echo "Image: $(DOCKER_IMAGE)"
	@echo "Duration: $(BENCHMARK_DURATION)"
	@echo "Items: $(BENCHMARK_ITEM_COUNT)"
	@echo "Running schema..."; \
	$(LATTE_BIN) schema --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-adapter-image $(DOCKER_IMAGE) -P "items=$(BENCHMARK_ITEM_COUNT)" $(WORKLOAD_DIR)/$(1).rn || exit 1; \
	echo "Loading data ($(BENCHMARK_ITEM_COUNT) items)..."; \
	$(LATTE_BIN) load --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-adapter-image $(DOCKER_IMAGE) -P "items=$(BENCHMARK_ITEM_COUNT)" $(WORKLOAD_DIR)/$(1).rn || exit 1; \
	echo "Running benchmark for $(BENCHMARK_DURATION)..."; \
	$(LATTE_BIN) run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-adapter-image $(DOCKER_IMAGE) -d $(BENCHMARK_DURATION) -P "items=$(BENCHMARK_ITEM_COUNT)" --generate-report -o $(2) $(WORKLOAD_DIR)/$(1).rn; \
	result=$$?; \
	echo "=== Benchmark Test $(1) completed (exit code: $$result) ==="; \
	exit $$result
endef

.PHONY: test-benchmark
test-benchmark: build-docker-image ## Run all benchmark tests with JSON report output
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	@failed=""; \
	for workload in $(BENCHMARK_WORKLOADS); do \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Running benchmark for $$workload..."; \
		echo "============================================================"; \
		report_file="$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-$$workload.json"; \
		if $(MAKE) --no-print-directory _run-benchmark-workload \
			BENCHMARK_WORKLOAD=$$workload \
			REPORT_FILE=$$report_file; then \
			echo ">>> Benchmark $$workload PASSED"; \
		else \
			echo ">>> Benchmark $$workload FAILED"; \
			failed="$$failed $$workload"; \
		fi; \
	done; \
	echo ""; \
	echo "============================================================"; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED workloads:$$failed ==="; \
		exit 1; \
	else \
		echo "=== All benchmarks PASSED ==="; \
	fi

.PHONY: _run-benchmark-workload
_run-benchmark-workload:
	$(call run-benchmark-test,$(BENCHMARK_WORKLOAD),$(REPORT_FILE))

# ==============================================================================
# Code Quality Targets
# ==============================================================================
.PHONY: lint
lint: ## Run linter (must pass in CI)
	# Language-specific lint command
	# e.g., golangci-lint run
	# e.g., cargo clippy -- -D warnings
	# e.g., ruff check .

.PHONY: lint-fix
lint-fix: ## Run linter and fix issues
	# Language-specific lint fix command

.PHONY: fmt
fmt: ## Format code
	# Language-specific format command

.PHONY: fmt-check
fmt-check: ## Check code formatting (CI-safe)
	# Language-specific format check command

# ==============================================================================
# Docker Targets (REQUIRED — parent Makefile depends on these)
# ==============================================================================
.PHONY: build-docker-image
build-docker-image: ## Build Docker image
	@echo "Building Docker image $(DOCKER_IMAGE)..."
	docker build \
		--build-arg ADAPTER_VERSION=$(ADAPTER_VERSION) \
		-t $(DOCKER_IMAGE) \
		-t $(DOCKER_IMAGE_LATEST) \
		.

.PHONY: push-docker-image
push-docker-image: build-docker-image ## Build and push Docker image
	@echo "Pushing Docker image..."
	docker push $(DOCKER_IMAGE)
	docker push $(DOCKER_IMAGE_LATEST)

.PHONY: docker-run
docker-run: ## Run adapter container locally (manual mode, for debugging)
	@mkdir -p /tmp/latte-alternator
	docker run --rm -it \
		--network host \
		-v /tmp/latte-alternator:/sockets \
		-e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
		-e LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
		$(DOCKER_IMAGE)

# Note: For normal usage, prefer letting Latte manage the container:
#   latte run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) \
#       --alternator-adapter-image $(DOCKER_IMAGE) workload.rn

# ==============================================================================
# Development Targets
# ==============================================================================
.PHONY: run
run: build ## Run adapter locally (for development)
	@echo "Starting adapter..."
	LATTE_ALTERNATOR_SOCKET=$(TEST_SOCKET) \
	LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
	<start-adapter-command>

# Replace <start-adapter-command> with your adapter's run command, e.g.:
#   Go:   ./$(BINARY)
#   Java: $(JAVA) -jar ./$(JAR)

.PHONY: start-dynamodb-local
start-dynamodb-local: ## Start DynamoDB Local for testing
	docker run --rm -d \
		--name dynamodb-local \
		-p 8000:8000 \
		amazon/dynamodb-local:latest
	@echo "DynamoDB Local started at http://localhost:8000"

.PHONY: stop-dynamodb-local
stop-dynamodb-local: ## Stop DynamoDB Local
	docker stop dynamodb-local || true

# ==============================================================================
# Help
# ==============================================================================
.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
```

### Test Target Layout

Each adapter Makefile must provide the following `test-*` targets. The parent `alternator-adapters/Makefile` delegates to these targets via `make -C <adapter> <target>`.

```
test-*
├── test                              # Unit tests (language-specific)
├── test-integration                  # Integration tests (requires running DB)
├── test-all                          # Unit + integration tests
│
├── test-functional                   # All functional tests (runs all targets below)
│   ├── test-functional-basic         # workloads/dynamodb/basic.rn
│   ├── test-functional-batch         # workloads/dynamodb/batch.rn
│   ├── test-functional-conditional   # workloads/dynamodb/conditional.rn
│   ├── test-functional-multi_client  # workloads/dynamodb/multi_client.rn
│   ├── test-functional-query_gsi     # workloads/dynamodb/query_gsi.rn
│   ├── test-functional-parallel_scan # workloads/dynamodb/parallel_scan.rn
│   ├── test-functional-collections   # workloads/dynamodb/cicd/collections.rn
│   ├── test-functional-crud          # workloads/dynamodb/cicd/crud.rn
│   ├── test-functional-nested-collections  # workloads/dynamodb/cicd/nested-collections.rn
│   ├── test-functional-primitives    # workloads/dynamodb/cicd/primitives.rn
│   ├── test-functional-query         # workloads/dynamodb/cicd/query.rn
│   └── test-functional-scan          # workloads/dynamodb/cicd/scan.rn
│
└── test-benchmark                    # All benchmark tests (auto-discovered from .workloads/benchmark/*.rn)
    ├── test-benchmark-batch          # .workloads/benchmark/batch.rn
    ├── test-benchmark-collections    # .workloads/benchmark/collections.rn
    ├── test-benchmark-conditional    # .workloads/benchmark/conditional.rn
    ├── test-benchmark-nested-collections  # .workloads/benchmark/nested-collections.rn
    ├── test-benchmark-primitives     # .workloads/benchmark/primitives.rn
    └── test-benchmark-scan           # .workloads/benchmark/scan.rn
```

- **Functional tests** use `DYNAMODB_WORKLOAD_DIR` (pointing to `workloads/dynamodb/`) and run each workload end-to-end through the adapter, verifying "Driver latency" appears in output.
- **Benchmark tests** use `BENCHMARK_WORKLOAD_DIR` (pointing to `.workloads/benchmark/`) and are auto-discovered via glob. Each produces a JSON report in `BENCHMARK_REPORTS_DIR`. Benchmarks are limited by time only (`BENCHMARK_DURATION`, default 5s) with no iteration limit.

### Test Infrastructure Setup

#### DynamoDB Local

For local development and CI testing, use DynamoDB Local:

```bash
# Start DynamoDB Local
docker run --rm -d \
    --name dynamodb-local \
    -p 8000:8000 \
    amazon/dynamodb-local:latest

# Verify it's running
aws dynamodb list-tables \
    --endpoint-url http://localhost:8000 \
    --region us-east-1
```

#### Alternator (ScyllaDB)

For testing against Alternator:

```bash
# Start ScyllaDB with Alternator enabled
docker run --rm -d \
    --name scylla \
    -p 8000:8000 \
    -p 9042:9042 \
    scylladb/scylla:latest \
    --alternator-port 8000 \
    --alternator-write-isolation only_rmw_uses_lwt

# Wait for Alternator to be ready
until docker exec scylla cqlsh -e "SELECT * FROM system.local" 2>/dev/null; do
    echo "Waiting for ScyllaDB..."
    sleep 2
done
echo "Alternator ready at http://localhost:8000"
```

#### Docker Compose for Testing (Optional)

For integration tests that don't use Latte, Docker Compose can be used. However, for functional and benchmark tests, prefer `--alternator-adapter-image` which handles the container lifecycle automatically.

```yaml
version: '3.8'

services:
  dynamodb-local:
    image: amazon/dynamodb-local:latest
    ports:
      - "8000:8000"
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:8000 || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 10
```

Then run Latte with automatic adapter management:
```bash
# Latte starts the adapter container, runs the workload, and stops the container
latte run --dynamodb --dynamodb-endpoint http://localhost:8000 \
    --alternator-adapter-image scylladb/latte-alternator-adapters:<adapter>-dev \
    workload.rn
```

---

## Acceptance Criteria

Use this checklist when implementing a new adapter. All items must be completed before the adapter is considered production-ready.

### Protocol Implementation

- [ ] Frame header parsing (12 bytes)
- [ ] Frame header encoding (version 0x81 for responses)
- [ ] CREATE_SESSION request/response
- [ ] CLOSE_SESSION request/response
- [ ] GET_ITEM request handling
- [ ] PUT_ITEM request handling
- [ ] DELETE_ITEM request handling
- [ ] UPDATE_ITEM request handling
- [ ] QUERY request handling
- [ ] SCAN request handling
- [ ] BATCH_GET_ITEM request handling
- [ ] BATCH_WRITE_ITEM request handling
- [ ] ITEM_RESULT response encoding
- [ ] QUERY_RESULT response encoding
- [ ] BATCH_RESULT response encoding
- [ ] ERROR response encoding
- [ ] Stream ID correlation (match response to request)
- [ ] Driver latency tracking (append to result body)

### Infrastructure

- [ ] Unix domain socket client (connects to socket created by Latte; retries for up to 30s)
- [ ] Full-duplex I/O (split read/write)
- [ ] Concurrent request handling
- [ ] Semaphore for inflight limiting
- [ ] Session registry (map session_id → HTTP client)

### Value Handling

- [ ] Encode/decode all AttributeValue types (NULL, BOOL, N, S, B)
- [ ] Encode/decode set types (SS, NS, BS)
- [ ] Encode/decode collection types (L, M)
- [ ] Handle nested AttributeValues
- [ ] Key encoding/decoding
- [ ] Item encoding/decoding
- [ ] ConditionExpression encoding/decoding

### Configuration

- [ ] `LATTE_ALTERNATOR_SOCKET` environment variable
- [ ] `LATTE_ALTERNATOR_ENDPOINT` environment variable
- [ ] `LATTE_ALTERNATOR_REGION` environment variable
- [ ] `LATTE_ALTERNATOR_ACCESS_KEY` environment variable (optional)
- [ ] `LATTE_ALTERNATOR_SECRET_KEY` environment variable (optional)
- [ ] `LATTE_ALTERNATOR_INFLIGHT` environment variable

### Functional Tests

- [ ] Makefile includes `DYNAMODB_WORKLOAD_DIR`, `LATTE_BIN`, `E2E_DURATION`, `E2E_ITEM_COUNT` configuration
- [ ] `run-functional-test` macro uses `--alternator-adapter-image` (Latte manages the container lifecycle)
- [ ] Functional test targets depend on `build-docker-image` (not `build`)
- [ ] `test-functional` target runs all workloads
- [ ] Root-level workload targets: `test-functional-basic`, `test-functional-batch`, `test-functional-conditional`, `test-functional-multi_client`, `test-functional-query_gsi`, `test-functional-parallel_scan`
- [ ] CI/CD workload targets: `test-functional-cicd-primitives`, `test-functional-cicd-collections`, `test-functional-cicd-nested-collections`, `test-functional-cicd-crud`, `test-functional-cicd-query`, `test-functional-cicd-scan`, `test-functional-cicd-batch`, `test-functional-cicd-conditional`
- [ ] Each target verifies "Driver latency" appears in output
- [ ] `E2E_ITEM_COUNT` defaults to 100

### Parent Makefile Registration

- [ ] Adapter added to `ADAPTERS` list in `alternator-adapters/Makefile`
- [ ] Per-adapter shortcut targets added (`build-<name>`, `test-functional-<name>`, `test-benchmark-<name>`, `profile-<name>`)
- [ ] Docker image follows naming convention (`scylladb/latte-alternator-adapters:<name>-<version>`) so `_run-adapter-benchmark` picks it up automatically
- [ ] `make list-adapters` shows the new adapter
- [ ] `make test-functional` runs functional tests for the new adapter
- [ ] `make test-functional-basic` (and other per-workload targets) includes the new adapter

### Build & Packaging

- [ ] Makefile with all required targets (build, test, test-functional, test-benchmark, lint, lint-fix, build-docker-image, push-docker-image)
- [ ] Makefile includes `docker-run` target for local testing
- [ ] README.md with standard sections
- [ ] Docker image naming: `scylladb/latte-alternator-adapters:<name>-<version>`
- [ ] `build-docker-image` target produces a working Docker image (parent Makefile depends on this)

### Docker Image Requirements

- [ ] Dockerfile uses multi-stage build (build stage + runtime stage)
- [ ] Runtime image is minimal (slim/alpine base)
- [ ] Sets `ENV LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock`
- [ ] Sets `ENV LATTE_ALTERNATOR_ENDPOINT=http://localhost:8000`
- [ ] Sets `ENV LATTE_ALTERNATOR_INFLIGHT=512`
- [ ] Declares `VOLUME /sockets` (Latte creates the socket on the host; no `mkdir -p /sockets` needed)
- [ ] Has appropriate `ENTRYPOINT`
- [ ] Includes OCI labels (source, title, version)
- [ ] Works with `--network host` mode
- [ ] Works with `-v /tmp/latte-alternator:/sockets` volume mount

### Testing

- [ ] Unit tests pass (`make test`)
- [ ] Lint checks pass (`make lint`)
- [ ] Integration tests pass (`make test-integration`)
- [ ] Basic CRUD operations work against Alternator
- [ ] Basic CRUD operations work against DynamoDB Local
- [ ] Batch operations work (or documented as unsupported)
- [ ] Query/Scan operations work

#### Unit Test Coverage Requirements

Unit tests must cover:

**Frame Encoding/Decoding:**
- [ ] Header parsing with all opcodes
- [ ] Header encoding with correct version byte
- [ ] Body length validation (reject > 16MB)
- [ ] Stream ID preservation
- [ ] Malformed header handling

**AttributeValue Encoding/Decoding:**
- [ ] Each type tag (0x00-0x09)
- [ ] Empty strings and empty binary
- [ ] Large strings (>64KB)
- [ ] Deeply nested structures (10+ levels)
- [ ] Edge cases: empty list, empty map, empty sets

**Request Body Parsing:**
- [ ] CREATE_SESSION with all parameters
- [ ] GET_ITEM with/without projection
- [ ] PUT_ITEM with/without condition
- [ ] UPDATE_ITEM with expression attributes
- [ ] QUERY with all optional fields
- [ ] BATCH_GET_ITEM with multiple tables
- [ ] BATCH_WRITE_ITEM with mixed operations

**Response Body Encoding:**
- [ ] SESSION_CREATED with valid session_id
- [ ] ITEM_RESULT with/without item
- [ ] QUERY_RESULT with pagination
- [ ] BATCH_RESULT with unprocessed items
- [ ] ERROR with all error codes

**Error Handling:**
- [ ] Unknown opcode → PROTOCOL_ERROR
- [ ] Invalid session_id → SESSION_NOT_FOUND
- [ ] Truncated frame → graceful error
- [ ] Invalid UTF-8 in string → VALIDATION_ERROR

#### Integration Test Coverage Requirements

Integration tests must verify end-to-end behavior:

**Session Lifecycle:**
- [ ] CREATE_SESSION returns valid session_id
- [ ] Multiple sessions can coexist
- [ ] CLOSE_SESSION cleans up resources
- [ ] Operations fail after session closed

**CRUD Operations:**
- [ ] PUT_ITEM + GET_ITEM round-trip for all types
- [ ] DELETE_ITEM removes item
- [ ] UPDATE_ITEM modifies attributes
- [ ] Conditional operations succeed/fail appropriately

**Query/Scan:**
- [ ] Query returns correct items
- [ ] Query pagination works
- [ ] Scan returns all items
- [ ] Filter expressions work

**Batch Operations:**
- [ ] BatchGetItem retrieves multiple items
- [ ] BatchWriteItem writes multiple items
- [ ] Unprocessed items returned correctly

**Latency Tracking:**
- [ ] driver_latency_ns is present in responses
- [ ] driver_latency_ns is reasonable (>0, <timeout)

### Manual Verification

1. Build the Docker image (`make build-docker-image`) and use `--alternator-adapter-image`, or start the adapter locally with a socket path
2. Test each operation in isolation:
   - CREATE_SESSION → verify session ID returned
   - GET_ITEM with existing/missing keys
   - PUT_ITEM with/without conditions
   - QUERY with key conditions
   - DELETE_ITEM
   - BATCH_GET_ITEM / BATCH_WRITE_ITEM
3. Test concurrent requests (many streams in flight)
4. Test error handling (invalid tables, missing sessions)

---

## Desired Features

Features that would enhance the alternator adapter protocol but are not yet implemented.

### Comprehensive Integration Testing

Extend integration test suites to provide complete coverage of the alternator adapter protocol.

#### Session Parameters

Test all session parameters are correctly applied:
- Connection: `endpoint`, `region`, `access_key_id`, `secret_access_key`, `session_token`
- Pool: `max_connections`
- Timeouts: `request_timeout_ms`, `connect_timeout_ms`
- Retry: `retry_mode`, `max_retries`

#### Data Type Operations

For each DynamoDB AttributeValue type, test all operations:
- PUT_ITEM with each type
- GET_ITEM and verify round-trip correctness
- UPDATE_ITEM with each type
- DELETE_ITEM
- NULL value handling

Types to cover:
- Scalars: S (String), N (Number), B (Binary), BOOL, NULL
- Sets: SS (StringSet), NS (NumberSet), BS (BinarySet)
- Collections: L (List), M (Map)
- Nested structures: Maps containing Lists, Lists containing Maps

Example test cases:
```
# String round-trip
PUT_ITEM: {"pk": {"S": "test"}, "data": {"S": "hello world"}}
GET_ITEM: verify data.S == "hello world"

# Number precision
PUT_ITEM: {"pk": {"S": "test"}, "value": {"N": "123456789.123456789"}}
GET_ITEM: verify value.N == "123456789.123456789"

# Nested structure
PUT_ITEM: {"pk": {"S": "test"}, "nested": {"M": {"list": {"L": [{"S": "a"}, {"N": "1"}]}}}}
GET_ITEM: verify nested.M.list.L[0].S == "a"
```

#### Condition Expressions

Test conditional operations:
- `attribute_exists(attr)`
- `attribute_not_exists(attr)`
- `attribute_type(attr, type)`
- `begins_with(attr, substr)`
- `contains(attr, operand)`
- `size(attr)`
- Comparison operators: `=`, `<>`, `<`, `<=`, `>`, `>=`
- Logical operators: `AND`, `OR`, `NOT`
- `BETWEEN` and `IN` operators

Example test cases:
```
# Conditional PUT (insert only if not exists)
PUT_ITEM with condition_expression="attribute_not_exists(pk)"
- First call: succeeds
- Second call: fails with CONDITIONAL_CHECK_FAILED

# Conditional UPDATE
UPDATE_ITEM with condition_expression="version = :v"
- With matching version: succeeds
- With non-matching version: fails
```

#### Query Operations

Test Query with various conditions:
- Partition key equality
- Sort key conditions: `=`, `<`, `<=`, `>`, `>=`, `BETWEEN`, `begins_with`
- Filter expressions
- Projection expressions
- ScanIndexForward (ascending/descending)
- Limit parameter
- Pagination with ExclusiveStartKey

#### Scan Operations

Test Scan operations:
- Full table scan
- Filter expressions
- Projection expressions
- Limit parameter
- Pagination
- Parallel scan (Segment/TotalSegments)

#### Batch Operations

Test batch execution:
- BatchGetItem with multiple tables
- BatchGetItem with 100 items (max limit)
- BatchWriteItem with PutRequests
- BatchWriteItem with DeleteRequests
- BatchWriteItem with mixed operations
- UnprocessedItems/UnprocessedKeys handling

#### Error Handling

Test error scenarios:
- Invalid table name → RESOURCE_NOT_FOUND
- Invalid session ID → SESSION_NOT_FOUND
- Malformed request → VALIDATION_ERROR
- Condition check failure → CONDITIONAL_CHECK_FAILED
- Throughput exceeded (if using provisioned) → PROVISIONED_THROUGHPUT
- Request timeout → TIMEOUT

### Transaction Support

Full support for TransactGetItems and TransactWriteItems operations:
- TRANSACT_GET request/response
- TRANSACT_WRITE request/response
- Transaction cancellation reasons in error responses

### Table Management Operations

Support for table lifecycle operations:
- CREATE_TABLE with GSI/LSI definitions
- DELETE_TABLE
- DESCRIBE_TABLE
- LIST_TABLES

### Result Paging

Support for paging through large Query/Scan result sets:
- Return LastEvaluatedKey in responses
- Accept ExclusiveStartKey in follow-up requests
- Track paging state in Latte workload scripts

### Parallel Scan

Support for parallel scan operations:
- Segment and TotalSegments parameters
- Coordinate across multiple adapter instances

---

## Appendix A: Comparison with CQL Adapter

| Aspect              | CQL Adapter                    | Alternator Adapter               |
|---------------------|--------------------------------|----------------------------------|
| Wire Protocol       | CQL binary protocol            | Custom binary protocol           |
| Prepared Statements | Yes (cached by statement key)  | No (stateless operations)        |
| Session State       | Prepared statement cache       | HTTP client only                 |
| Consistency         | CQL consistency levels         | N/A (Alternator handles this)    |
| Batching            | CQL BATCH                      | BatchGetItem, BatchWriteItem     |
| Transactions        | LWT only                       | TransactGetItems, TransactWriteItems |
| Data Types          | CQL types                      | DynamoDB AttributeValues         |
| Authentication      | Username/password              | AWS Signature V4                 |

---

## Appendix B: AttributeValue JSON Mapping

For debugging and logging, AttributeValues map to DynamoDB JSON format:

```json
{
  "S": "string value",           // type_tag 0x03
  "N": "123.45",                 // type_tag 0x02
  "B": "base64data",             // type_tag 0x04
  "BOOL": true,                  // type_tag 0x01
  "NULL": true,                  // type_tag 0x00
  "SS": ["a", "b", "c"],         // type_tag 0x05
  "NS": ["1", "2", "3"],         // type_tag 0x06
  "BS": ["data1", "data2"],      // type_tag 0x07
  "L": [<AttributeValue>, ...],  // type_tag 0x08
  "M": {"key": <AttributeValue>} // type_tag 0x09
}
```

---

## Appendix C: Example Adapter Implementations

Reference implementations can be found in subdirectories:

- `rust-aws-sdk/` - Rust adapter using AWS SDK for Rust
- `go-aws-sdk/` - Go adapter using AWS SDK for Go v2
- `python-boto3/` - Python adapter using boto3
- `java-aws-sdk/` - Java adapter using AWS SDK for Java v2

Each implementation demonstrates:
- Socket client connection (connecting to Latte-created socket)
- Frame parsing and encoding
- Session management
- Error handling
- Latency measurement
