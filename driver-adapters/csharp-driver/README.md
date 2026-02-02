# ScyllaDB C# Driver Adapter

A Latte driver adapter using the official ScyllaDB C# driver. This adapter enables benchmarking ScyllaDB and Apache Cassandra using the C# driver implementation.

## Driver

- **Driver**: ScyllaDBCSharpDriver 3.22.0.1
- **Language**: C# (.NET 8.0)
- **Repository**: https://github.com/scylladb/csharp-driver

## Docker Image

- **Image**: scylladb/latte-driver-adapters:csharp-driver-\<version\>
- **Tags**: `csharp-driver-latest`, `csharp-driver-3.22.0.1`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter (release mode) |
| `make test` | Run unit tests |
| `make lint` | Run all linters (dotnet format check) |
| `make lint-fix` | Auto-fix lint issues |
| `make test-integration` | Run integration tests (~10s per suite) |
| `make test-benchmark` | Run benchmark tests (5m per suite) |
| `make build-docker-image` | Build Docker image |
| `make push-docker-image` | Push Docker image to registry |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_DRIVER_SOCKET` | `/tmp/latte-driver.sock` | Unix socket path |
| `LATTE_DRIVER_CONTACT_POINTS` | `127.0.0.1` | Comma-separated host:port list |
| `LATTE_DRIVER_INFLIGHT` | `512` | Max concurrent requests |
| `LOG_LEVEL` | `info` | Logging level (trace, debug, info, warn, error) |

### Session Parameters

All session parameters are passed via the IPC protocol's CREATE_SESSION message:

| Parameter | Description |
|-----------|-------------|
| `contact_points` | Override default contact points |
| `keyspace` | Default keyspace |
| `username` | Authentication username |
| `password` | Authentication password |
| `datacenter` | Preferred datacenter for DC-aware routing |
| `connections_per_shard` | Connections per host |
| `request_timeout_ms` | Request timeout in milliseconds |
| `consistency` | Default consistency level |

## Usage

### Running Locally

```bash
# Build the adapter
make build

# Start the adapter
LATTE_DRIVER_SOCKET=/tmp/latte-driver.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
./src/bin/Release/net8.0/linux-x64/publish/latte-driver-csharp

# In another terminal, run a benchmark
latte run --driver-socket /tmp/latte-driver.sock workload.rn
```

### Using Docker

```bash
# Build the image
make build-docker-image DRIVER_VERSION=3.22.0.1

# Run with Latte
latte run --driver-image scylladb/latte-driver-adapters:csharp-driver-3.22.0.1 workload.rn
```

### Running Tests

```bash
# Run integration tests (requires ScyllaDB running on localhost:9042)
make test-integration

# Run benchmark tests
make test-benchmark

# Run specific test suite
make test-integration-primitives
make test-benchmark-collections
```

## Requirements

- .NET 8.0 SDK (for building)
- ScyllaDB or Apache Cassandra (for testing)
- Latte binary (for integration tests)

## Building

```bash
# Restore dependencies
make restore

# Build release
make build

# Build debug
make build-debug
```

## Features

- Core protocol implementation (CREATE_SESSION, QUERY, PREPARE, EXECUTE, BATCH)
- Primitive types: tinyint, smallint, int, bigint, float, double, boolean, text, blob, uuid, timeuuid, inet
- Temporal types: date, time, timestamp, duration (with automatic range clamping for timestamps)
- Collection types: list, set, map with automatic element type coercion
- Nested collections: full support for deeply nested types (list of lists, map of maps, etc.)
- Vector types: vector<float, N> including lists of vectors
- Tuple types: native tuple support with element type coercion
- Varint and decimal types
- Prepared statement caching
- Connection pooling
- Configurable consistency levels
- DC-aware load balancing
- Token-aware routing (via ScyllaDB driver)

## Current Limitations

The C# driver requires POCO mapping for UDTs (User-Defined Types), which cannot be done dynamically at runtime. The following types are not supported:

- **UDTs**: Passed as null values. The C# driver requires static UdtMap registration which is not compatible with dynamic workload definitions.
- **Collections containing UDTs**: Lists, sets, and maps with UDT elements are passed as null values.

These limitations do not affect workloads that don't use UDT columns. All other CQL types are fully supported.

## Architecture

The adapter implements the Latte IPC protocol:

1. **Socket Server**: Listens on a Unix domain socket for incoming connections
2. **Request Handler**: Dispatches protocol messages to appropriate handlers
3. **Session Registry**: Manages database sessions and their lifecycle
4. **Value Encoder/Decoder**: Converts between CQL binary format and .NET types

See the main [driver-adapters README](../README.md) for protocol details.
