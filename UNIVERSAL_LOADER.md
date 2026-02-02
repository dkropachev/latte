# Universal Driver Loader Design (Latte)

## Goal
- Run Latte as a universal driver loader by spinning up a driver-side counterpart inside Docker.
- Relay `db.execute` and `execute_prepared` calls over a Unix domain socket to the driver counterpart, using a binary CQL-like protocol for both requests and result rows.
- Keep all operations asynchronous: Latte schedules, sends, and awaits responses; the driver only replies to requests it receives.

## Architecture Overview
- **Latte host process**: Existing Latte binary orchestrates workloads and scripts. On startup, it also prepares a Unix domain socket path and launches the driver-side container.
- **Driver counterpart container**: New container image shipped alongside Latte (e.g., `scylladb/latte-driver:<tag>`). It exposes a single Unix domain socket volume mount for RPC with Latte.
- **IPC channel**: Unix domain socket mounted from the host into the container. CQL binary framing is used end-to-end to serialize queries, prepared statements, parameters, and result sets.
- **Async execution loop**: Latte maintains an async request table keyed by CQL stream IDs. Each outgoing request registers a waker/promise; responses resolve the matching entry.
- **No peer-initiated traffic**: The driver counterpart never initiates messages except responses; it only processes incoming framed requests and replies.

## Request/Response Flow
1. **Startup**: Latte allocates a socket path (e.g., `/tmp/latte-driver-read.sock`, `/tmp/latte-driver-write.sock`), ensures permissions, and launches the driver container with the socket bind-mounted.
2. **Connection**: Latte opens the Unix socket as a framed async stream using the CQL binary protocol encoder/decoder.
3. **Dispatch**:
   - `db.execute`: Latte builds a CQL QUERY frame with a unique stream ID and forwards it over the socket.
   - `execute_prepared`: Latte sends a CQL EXECUTE frame with the prepared-id and bound values encoded per CQL binary rules.
4. **Await**: Latte parks the future associated with the stream ID in the request table.
5. **Driver processing**: The driver counterpart receives frames, executes against its underlying driver/session, and returns RESULT/ERROR frames with matching stream IDs.
6. **Completion**: Latte matches the incoming frame to the request table entry, fulfills the future, and hands results back to the script.

## CQL Binary Protocol Expectations
- **Framing**: Use existing CQL binary header and body layout (version flags, stream ID, opcode, length).
- **Queries**: Encode statements, consistency, flags, and values exactly as CQL wire rules specify; reuse Latte’s existing CQL serializers if present.
- **Results**: Serialize result metadata (column specs) and rows using the same type encodings as the standard protocol so Latte can deserialize without special cases.
- **Prepared statements**: Prepared IDs are treated as opaque byte blobs; Latte caches them per session and reuses for EXECUTE frames.
- **Errors**: Map driver-side failures to CQL ERROR frames with standard codes/messages to keep handling uniform.

## Asynchronous Scheduling
- **Request table**: Concurrent map of stream ID → waiter (future/waker), cleared on response or timeout.
- **Backpressure**: Optional bounded in-flight limit; when exceeded, callers await capacity before sending.
- **Timeouts/retries**: Latte applies configurable timeouts; retries are caller-driven (scripts) to avoid hidden retries inside the channel.
- **Shutdown**: Draining sends a SHUTDOWN frame, waits for in-flight completions, then closes the socket and container.

## Driver Counterpart Responsibilities
- Bind to the provided Unix socket, decode CQL frames, and execute against its embedded driver/session.
- Maintain a simple request loop: read frame → execute → write response; no need for unsolicited events.
- Ensure responses preserve the incoming stream ID to match Latte’s async table.
- Provide lightweight health endpoints/logs (stdout/JSON) for observability; surface fatal errors via ERROR frames where possible.

## Driver Counterpart Architecture Details
- **Session lifecycle**:
  - On startup, create a single session/connection pool to the target cluster (or an embedded mock when testing). Pool sizing is configurable; failures during creation return an ERROR frame with a synthetic stream ID of `-1` to Latte and exit.
  - A SHUTDOWN frame triggers: stop accept loop, stop reading new frames, wait for in-flight executions to complete, close session/pool, then close the socket.
  - If the session becomes unhealthy mid-flight, mark the driver as degraded, return ERROR frames for new requests, and optionally attempt one restart; if restart fails, terminate cleanly so Latte can respawn.
- **Execution handling**:
  - Supported opcodes inbound: QUERY and EXECUTE. Any other opcode yields an ERROR (code: `0x000A`—protocol error).
  - QUERY path: decode statement, consistency, flags, and values; run via the session; map the driver’s rows to CQL RESULT.
  - EXECUTE path: decode prepared-id bytes and bound values; look up prepared in a local cache; if missing, return UNPREPARED (code: `0x2500`) ERROR.
  - Per request, enforce an in-flight cap; when exceeded, return an OVERLOADED (code: `0x1001`) ERROR.
- **Result frames** (CQL binary protocol shape):
  - Header: version flags (server response), stream ID = request stream ID, opcode = RESULT, length = body length.
  - Body variants used:
    - `kind = ROWS (0x0002)`: includes metadata (flags, column count, optional table specs), followed by row count and row data encoded per CQL types.
    - `kind = VOID (0x0001)`: for statements with no rows.
    - `kind = SET_KEYSPACE (0x0003)` if applicable (rare here).
  - All column values serialized with standard CQL encodings; no custom types.
- **ERROR frames**:
  - Header: stream ID preserved from the request (unless startup failure uses `-1`), opcode = ERROR.
  - Body: `[int error_code][string message][optional data per code]` per CQL binary rules.
  - Common codes: `0x000A PROTOCOL_ERROR` (unknown opcode), `0x1000 SERVER_ERROR` (unexpected), `0x1001 OVERLOADED`, `0x1002 IS_BOOTSTRAPPING`, `0x1100 UNAVAILABLE`, `0x1200 READ_TIMEOUT`, `0x1300 WRITE_TIMEOUT`, `0x2500 UNPREPARED`.
- **Frame encoding/decoding**:
  - Header (v4 style): `[version][flags][stream_id][opcode][body_length]` where version has server bit set.
  - Use length-delimited framing on the Unix socket; partial reads are accumulated until body_length is satisfied.
- **Prepared statement cache**:
  - Keyed by prepared-id bytes; populated when the driver prepares on first EXECUTE miss or via explicit PREPARE request (if later added).
  - Cache eviction strategy: LRU with size cap; on eviction, later EXECUTE returns UNPREPARED prompting Latte to re-prepare (future extension).
- **Observability hooks**:
  - Emit structured logs on receive/send with stream ID, opcode, latency, row count, and error code.
  - Optional metrics sink (counters, histograms) gated by env var to keep image minimal by default.

## Deployment & Configuration
- **Images**: Publish `scylladb/latte` (host) and `scylladb/latte-driver` (counterpart). Tags stay aligned.
- **Socket mount**: Latte binds a host path into the container; permissions set to allow container user access.
- **Config surface**: CLI/env flags for socket path, container image tag, resource limits, and in-flight request cap.
- **Isolation**: The container only exposes the Unix socket; no network ports required. Container runs as non-root where possible.

## Testing Strategy
- **Protocol conformance**: Golden-frame tests for QUERY/EXECUTE/RESULT/ERROR encoding/decoding.
- **Integration**: Spawn the driver container in tests (or a fake driver) over a temp Unix socket and assert round-trips for execute and prepared paths.
- **Stress**: Load tests to validate high concurrency, backpressure, and timeout handling.
- **Failure injection**: Driver returns ERROR frames and malformed frames to ensure Latte surfaces errors cleanly and tears down safely.
