# cpp-rs-driver Adapter

Driver adapter for Latte using the [ScyllaDB cpp-rs-driver](https://github.com/scylladb/cpp-rust-driver) (C/C++ wrapper around the Rust driver). This adapter runs as a standalone process, listens on a Unix domain socket, and executes CQL queries via the cpp-rs-driver's C API.

## Driver

- **Driver**: [cpp-rs-driver](https://github.com/scylladb/cpp-rust-driver)
- **Language**: C++17
- **Repository**: https://github.com/scylladb/cpp-rust-driver
- **API**: Compatible with DataStax C/C++ driver

## Docker Image

- **Image**: `scylladb/latte-driver-adapters`
- **Tags**: `cpp-rs-driver-latest`, `cpp-rs-driver-<version>` (e.g., `cpp-rs-driver-1.0.0`)

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter (release mode) |
| `make build-debug` | Build the adapter (debug mode) |
| `make test` | Run tests |
| `make lint` | Run clang-format and clang-tidy |
| `make lint-fix` | Auto-fix formatting issues |
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
| `rack` | Not yet | Rack-aware routing (planned) |
| `connections_per_shard` | Yes | Pool size per shard |
| `request_timeout_ms` | Yes | Per-request timeout |
| `connect_timeout_ms` | Yes | Connection timeout |
| `consistency` | Yes | Default consistency level |
| `serial_consistency` | Yes | Serial consistency for LWT |
| `default_page_size` | Not yet | Page size (planned) |
| `ssl_enabled` | Yes | Enable SSL/TLS |
| `ssl_verify_peer` | Yes | Verify server certificate (default: true) |
| `ssl_ca_cert` | Yes | CA certificate (file path or PEM content) |
| `ssl_cert` | Yes | Client certificate (file path or PEM content) |
| `ssl_key` | Yes | Client private key (file path or PEM content) |

**Backward Compatibility**: Unknown parameters are silently ignored. This allows newer Latte versions to work with older driver adapter versions as long as unsupported parameters are not critical for the workload.

## Build Prerequisites

### System Dependencies

```bash
# Ubuntu/Debian
sudo apt install build-essential cmake libuv1-dev libssl-dev pkg-config

# Fedora
sudo dnf install gcc-c++ cmake libuv-devel openssl-devel pkgconfig
```

### cpp-rs-driver

The adapter requires the cpp-rs-driver library. You can either:

1. **Build from source** (recommended for development):
   ```bash
   git clone https://github.com/scylladb/cpp-rust-driver.git
   cd cpp-rust-driver
   cargo build --release
   sudo cp target/release/libscylla_cpp_driver.so /usr/local/lib/
   sudo cp include/*.h /usr/local/include/cassandra/
   sudo ldconfig
   ```

2. **Use Docker** (recommended for production): The Dockerfile handles building the driver automatically.

## Usage

### Build and Run Locally

```bash
# Build
make build

# Run
LATTE_DRIVER_SOCKET=/tmp/latte.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
./target/release/latte-cpp-rs-driver
```

### Build and Run with Docker

```bash
# Build image (creates scylladb/latte-driver-adapters:cpp-rs-driver-1.0.0)
make build-docker-image DRIVER_VERSION=1.0.0

# Run container
make docker-run

# Or manually:
docker run --rm \
    --network host \
    -v /tmp/latte-driver:/sockets \
    -e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
    -e LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
    scylladb/latte-driver-adapters:cpp-rs-driver-1.0.0
```

### Integration Tests

Requires a running ScyllaDB/Cassandra instance:

```bash
# Run all integration tests
make test-integration SCYLLA_CONTACT_POINTS=127.0.0.1:9042

# Run specific test suite
make test-integration-primitives
make test-integration-collections
make test-integration-vectors
```

## Development

```bash
# Format code
make fmt

# Run linters
make lint

# Run all checks (lint + test + build)
make ci
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   latte-cpp-rs-driver                        │
├─────────────────────────────────────────────────────────────┤
│  main.cpp          Entry point, config loading              │
│  server.cpp        libuv Unix socket server                 │
│  protocol.cpp      Frame encoding/decoding                  │
│  session.cpp       Session registry, cluster management     │
│  types.cpp         CQL value encoding/decoding              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    cpp-rs-driver                             │
│              (libscylla_cpp_driver.so)                       │
│                                                              │
│  C API compatible with DataStax driver                      │
│  Backed by ScyllaDB Rust driver                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  ScyllaDB /     │
                    │  Cassandra      │
                    └─────────────────┘
```

## Limitations

- Some advanced type coercions for complex nested types may not be fully supported
- Rack-aware routing not yet implemented
- Page size configuration not yet exposed
