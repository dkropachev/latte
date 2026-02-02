# cpp-driver Adapter

Driver adapter for Latte using the [ScyllaDB/DataStax C/C++ driver](https://github.com/scylladb/cpp-driver). This adapter runs as a standalone process, listens on a Unix domain socket, and executes CQL queries via the cpp-driver's C API.

> **Note**: This driver is deprecated in favor of [cpp-rs-driver](https://github.com/scylladb/cpp-rust-driver). Consider this adapter for benchmarking legacy deployments or comparing driver performance.

## Driver

- **Driver**: [cpp-driver](https://github.com/scylladb/cpp-driver)
- **Language**: C++17
- **Repository**: https://github.com/scylladb/cpp-driver
- **API**: DataStax C/C++ driver (`cassandra.h`)

## Docker Image

- **Image**: `scylladb/latte-driver-adapters`
- **Tags**: `cpp-driver-latest`, `cpp-driver-<version>` (e.g., `cpp-driver-1.0.0`)

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter (release mode) |
| `make build-debug` | Build the adapter (debug mode) |
| `make test` | Run unit tests |
| `make test-integration` | Run all integration tests |
| `make test-integration-primitives` | Run primitives integration tests |
| `make test-integration-collections` | Run collections integration tests |
| `make test-integration-vectors` | Run vectors integration tests |
| `make test-benchmark` | Run all benchmark tests |
| `make lint` | Run clang-format and clang-tidy |
| `make lint-fix` | Auto-fix formatting issues |
| `make profile` | Run CPU profiling with perf |
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
| `rack` | Not yet | Rack-aware routing (not supported by cpp-driver) |
| `connections_per_shard` | Yes | Core connections per host |
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

### cpp-driver

The adapter requires the cpp-driver library. You can either:

1. **Build from source** (recommended for development):
   ```bash
   git clone https://github.com/scylladb/cpp-driver.git
   cd cpp-driver
   mkdir build && cd build
   cmake -DCASS_BUILD_STATIC=OFF -DCMAKE_BUILD_TYPE=Release ..
   make -j$(nproc)
   sudo cp libcassandra.so* /usr/local/lib/
   sudo cp ../include/*.h /usr/local/include/cassandra/
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
./target/release/latte-cpp-driver
```

### Build and Run with Docker

```bash
# Build image (creates scylladb/latte-driver-adapters:cpp-driver-1.0.0)
make build-docker-image DRIVER_VERSION=1.0.0

# Run container
make docker-run

# Or manually:
docker run --rm \
    --network host \
    -v /tmp/latte-driver:/sockets \
    -e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
    -e LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
    scylladb/latte-driver-adapters:cpp-driver-1.0.0
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
+-------------------------------------------------------------+
|                     latte-cpp-driver                         |
+-------------------------------------------------------------+
|  main.cpp          Entry point, config loading              |
|  server.cpp        libuv Unix socket server                 |
|  protocol.cpp      Frame encoding/decoding                  |
|  session.cpp       Session registry, cluster management     |
|  types.cpp         CQL value encoding/decoding              |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                      cpp-driver                              |
|                   (libcassandra.so)                          |
|                                                              |
|  DataStax C/C++ driver API (cassandra.h)                    |
+-------------------------------------------------------------+
                              |
                              v
                    +-----------------+
                    |  ScyllaDB /     |
                    |  Cassandra      |
                    +-----------------+
```

## Limitations

- This driver is deprecated and no longer receives new features
- Some ScyllaDB-specific optimizations present in newer drivers may be missing
- Vector type support may require custom handling
- Rack-aware routing not supported

## References

- [cpp-driver GitHub](https://github.com/scylladb/cpp-driver)
- [cpp-driver API Docs](https://docs.datastax.com/en/developer/cpp-driver/latest/)
- [Latte Driver Adapters README](../README.md)
- [cpp-rs-driver Adapter](../cpp-rs-driver/) (recommended modern alternative)
