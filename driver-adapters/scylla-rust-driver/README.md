# ScyllaDB Rust Driver Adapter

Driver adapter for Latte using the official ScyllaDB Rust driver. This adapter runs as a standalone process, listens on a Unix domain socket, and executes CQL queries via the ScyllaDB Rust driver.

## Driver

- **Driver**: [scylla-rust-driver](https://github.com/scylladb/scylla-rust-driver) v1.4+
- **Language**: Rust
- **Repository**: https://github.com/scylladb/scylla-rust-driver

## Docker Image

- **Image**: `scylladb/latte-driver-adapters`
- **Tags**: `scylla-rust-driver-latest`, `scylla-rust-driver-<version>` (e.g., `scylla-rust-driver-1.4.0`)

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter (release mode) |
| `make test` | Run tests |
| `make lint` | Run clippy and format check |
| `make lint-fix` | Auto-fix formatting and lint issues |
| `make build-docker-image` | Build Docker image (use `DRIVER_VERSION=x.y.z`) |
| `make push-docker-image` | Push image to registry |
| `make docker-run` | Run adapter locally in Docker |
| `make clean` | Clean build artifacts |
| `make help` | Show all available commands |

## Configuration

### Environment Variables

Set via environment variables (used as defaults when session parameters are not provided):

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_DRIVER_SOCKET` | `/tmp/latte-driver.sock` | Unix socket path to bind |
| `LATTE_DRIVER_CONTACT_POINTS` | `127.0.0.1` | Comma-separated ScyllaDB/Cassandra nodes |
| `LATTE_DRIVER_KEYSPACE` | (none) | Default keyspace |
| `LATTE_DRIVER_INFLIGHT` | `512` | Max concurrent in-flight requests |

### Session Parameters

Session parameters can be passed via the CREATE_SESSION request and override environment variable defaults. This adapter supports the following parameters:

| Parameter | Supported | Notes |
|-----------|-----------|-------|
| `contact_points` | Yes | Overrides `LATTE_DRIVER_CONTACT_POINTS` |
| `keyspace` | Yes | Overrides `LATTE_DRIVER_KEYSPACE` |
| `username` | Yes | Authentication username |
| `password` | Yes | Authentication password |
| `datacenter` | Yes | Enables DC-aware load balancing |
| `rack` | Yes | Enables rack-aware routing (requires `datacenter`) |
| `connections_per_shard` | Yes | Pool size per shard |
| `request_timeout_ms` | Yes | Per-request timeout |
| `connect_timeout_ms` | Not yet | Connection timeout (planned) |
| `consistency` | Yes | Default consistency level |
| `serial_consistency` | Yes | Serial consistency for LWT |
| `default_page_size` | Not yet | Page size (planned) |
| `ssl_enabled` | Not yet | SSL support (planned) |
| `ssl_*` | Not yet | SSL configuration (planned) |

**Backward Compatibility**: Unknown parameters are silently ignored. This allows newer Latte versions to work with older driver adapter versions as long as unsupported parameters are not critical for the workload.

## Usage

### Build and Run Locally

```bash
# Build
make build

# Run
LATTE_DRIVER_SOCKET=/tmp/latte.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
./target/release/latte-driver
```

### Build and Run with Docker

```bash
# Build image (creates scylladb/latte-driver-adapters:scylla-rust-driver-1.4.0)
make build-docker-image DRIVER_VERSION=1.4.0

# Run container
make docker-run

# Or manually:
docker run --rm \
    --network host \
    -v /tmp/latte-driver:/sockets \
    -e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
    -e LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
    scylladb/latte-driver-adapters:scylla-rust-driver-1.4.0
```

### Integration Tests

Requires a running ScyllaDB/Cassandra instance:

```bash
SCYLLA_CONTACT_POINTS=127.0.0.1 make test
```

If `SCYLLA_CONTACT_POINTS` is unset, integration tests are skipped.

## Development

```bash
# Format code
make fmt

# Run linter
make clippy

# Run all checks (lint + test + build)
make ci
```
