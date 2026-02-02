# Python Driver Adapter

Latte driver adapter using the ScyllaDB Python driver (scylla-driver).

## Driver

- **Driver**: scylla-driver 3.28+
- **Language**: Python 3.14
- **Repository**: https://github.com/scylladb/python-driver

## Docker Image

- **Image**: scylladb/latte-driver-adapters:python-driver-<version>
- **Tags**: `python-driver-latest`, `python-driver-<version>`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Install dependencies in virtual environment |
| `make test` | Run unit tests |
| `make test-integration` | Run all integration test suites |
| `make test-integration-primitives` | Run primitives integration suite |
| `make test-integration-collections` | Run collections integration suite |
| `make test-integration-vectors` | Run vectors integration suite |
| `make test-benchmark` | Run all benchmark suites |
| `make test-benchmark-primitives` | Run primitives benchmark |
| `make test-benchmark-collections` | Run collections benchmark |
| `make test-benchmark-vectors` | Run vectors benchmark |
| `make test-simple` | Run simple integration test (recommended) |
| `make lint` | Run ruff linter |
| `make lint-fix` | Auto-fix lint issues |
| `make build-docker-image` | Build Docker image |
| `make push-docker-image` | Push Docker image to registry |

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_DRIVER_SOCKET` | `/tmp/latte-driver.sock` | Unix socket path |
| `LATTE_DRIVER_INFLIGHT` | `512` | Max concurrent requests |
| `LATTE_DRIVER_LOG_LEVEL` | `INFO` | Log level (DEBUG, INFO, WARNING, ERROR) |

## Usage

### Running directly

```bash
# Install dependencies
make build

# Run the adapter
source .venv/bin/activate
LATTE_DRIVER_SOCKET=/tmp/latte.sock \
python -m src.main
```

### Using with Latte

```bash
# Start the adapter
make run &

# Run Latte workload
latte run --driver-socket /tmp/latte-driver.sock workload.rn
```

### Using Docker

```bash
# Build the image
make build-docker-image

# Run the adapter
docker run --rm -it \
    -v /tmp/latte-sockets:/sockets \
    -e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
    scylladb/latte-driver-adapters:python-driver-latest
```

## Architecture

The adapter uses Python's asyncio for concurrent request handling:

1. **Server**: Listens on Unix domain socket, accepts connections
2. **Connection Handler**: Splits connection into reader/writer tasks
3. **Request Dispatcher**: Routes frames to appropriate handlers with semaphore limiting
4. **Session Registry**: Manages multiple database sessions
5. **Protocol**: Encodes/decodes CQL binary protocol frames

## Performance Notes

- Uses `asyncio` for high concurrency
- Semaphore limits in-flight requests to prevent overload
- Prepared statement caching reduces round trips
- Buffer pooling minimizes allocations in hot paths

## Type Coercion

The adapter automatically coerces values when the wire type doesn't match the column type. This allows Latte to send values in a canonical format while the adapter converts them to the appropriate CQL type.

### Coercion Rules

| Wire Type | Target Type | Conversion |
|-----------|-------------|------------|
| BigInt | Int, SmallInt, TinyInt | Truncate with bounds check |
| BigInt | Varint, Decimal | Promote to arbitrary precision |
| BigInt | Timestamp, Time, Counter | Direct conversion |
| Double | Float | Truncate to 32-bit |
| Text | Date | Parse "YYYY-MM-DD" format |
| Text | Time | Parse "HH:MM:SS" format |
| Text | Duration | Parse "1mo2d3h4m5s" format |
| Text | Inet | Parse IP address string |
| Text | UUID, Timeuuid | Parse UUID string |
| Text | Decimal | Parse decimal string |
| Text | Varint | Parse integer string |
| List | Set | Convert container type |

### Nested Collections

Nested collections (list of lists, map of tuples, etc.) are coerced recursively. Each element is converted according to the expected element type from the prepared statement metadata.

### UDT Fields

UDT fields are matched by name and coerced to the expected field type from the schema.

## Known Limitations

### Timestamp Column Decoding

The Python driver cannot decode CQL `timestamp` values that are outside the range of Python's `datetime` object. The latte workloads use `latte::hash()` to generate arbitrary 64-bit timestamp values, which can exceed this range.

When a timestamp value is too large, the driver raises:
```
Failed decoding result column "col_timestamp" of type timestamp: days=...; must have magnitude <= 999999999
```

**Affected Workloads:**
- `primitives.rn` - Contains `col_timestamp` column with arbitrary hash values

**Workaround:**
Use the `simple.rn` workload which avoids timestamp types:
```bash
make test-simple LATTE_BIN=/path/to/latte
```

This is a limitation of the Python driver, not this adapter implementation.

### Rack-Aware Routing

The Python Cassandra driver does not support rack-aware load balancing. The `rack` session parameter is accepted but ignored (with a warning logged).

### Connections Per Shard

The Python driver does not support configuring connections per shard. The `connections_per_shard` session parameter is accepted but ignored (with a warning logged).

## Troubleshooting

### Connection Issues

**Problem:** `NoHostAvailable: Unable to connect to any servers`

**Solutions:**
1. Verify the database is running and accessible:
   ```bash
   cqlsh <host> <port>
   ```
2. If using Docker, ensure the container can reach the database:
   - Use `host.docker.internal` for local databases
   - Use `--network host` for direct access

### Authentication Errors

**Problem:** `AuthenticationFailed: Bad credentials`

**Solutions:**
1. Verify username/password in session parameters
2. Check that authentication is enabled on the database
3. Verify the user has appropriate permissions

### Socket Permission Errors

**Problem:** `Permission denied: '/tmp/latte-driver.sock'`

**Solutions:**
1. Ensure the socket directory exists and is writable
2. Remove stale socket files:
   ```bash
   rm -f /tmp/latte-driver.sock
   ```
3. Check file system permissions

### High Latency

**Problem:** Requests are slow despite low database load

**Solutions:**
1. Increase `LATTE_DRIVER_INFLIGHT` for more parallelism
2. Use prepared statements (avoid QUERY, prefer EXECUTE)
3. Check network latency to the database
4. Enable debug logging to identify bottlenecks:
   ```bash
   LATTE_DRIVER_LOG_LEVEL=DEBUG python -m src.main
   ```

### Memory Usage

**Problem:** High memory consumption

**Solutions:**
1. Reduce `LATTE_DRIVER_INFLIGHT` to limit concurrent requests
2. Ensure prepared statements are being reused (not re-prepared)
3. Monitor the number of active sessions

### Debug Logging

Enable detailed logging to diagnose issues:

```bash
LATTE_DRIVER_LOG_LEVEL=DEBUG python -m src.main
```

Log messages include:
- Request correlation IDs (`[req=N]`) for tracing
- Session IDs for multi-session scenarios
- Latency measurements for performance analysis

### Signal Handling

The adapter handles shutdown signals gracefully:
- `SIGTERM`: Graceful shutdown (close sessions, complete pending requests)
- `SIGINT`: Graceful shutdown (Ctrl+C)

During shutdown:
1. New connections are rejected
2. Pending requests are given 5 seconds to complete
3. All sessions are closed
4. Socket file is removed
