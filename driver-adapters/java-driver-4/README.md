# ScyllaDB Java Driver Adapter

A Latte driver adapter using the official ScyllaDB Java driver. This adapter enables benchmarking ScyllaDB and Apache Cassandra using the Java driver implementation.

## Driver

- **Driver**: ScyllaDB Java Driver 4.19.0.4
- **Language**: Java 17
- **Repository**: https://github.com/scylladb/java-driver

## Docker Image

- **Image**: scylladb/latte-driver-adapters:java-driver-\<version\>
- **Tags**: `java-driver-latest`, `java-driver-4.19.0.4`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter (release mode) |
| `make test` | Run unit tests |
| `make lint` | Run all linters (spotless check) |
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
| `connect_timeout_ms` | Connection timeout in milliseconds |

## Usage

### Running Locally

```bash
# Build the adapter
make build

# Start the adapter
LATTE_DRIVER_SOCKET=/tmp/latte-driver.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
java -jar target/latte-driver-java-1.0.0.jar

# In another terminal, run a benchmark
latte run --driver-socket /tmp/latte-driver.sock workload.rn
```

### Using Docker

```bash
# Build the image
make build-docker-image DRIVER_VERSION=4.19.0.4

# Run with Latte
latte run --driver-image scylladb/latte-driver-adapters:java-driver-4.19.0.4 workload.rn
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

- Java 17 or later (for building and running)
- Maven 3.9 or later (for building)
- ScyllaDB or Apache Cassandra (for testing)
- Latte binary (for integration tests)

## Building

```bash
# Build release jar (includes all dependencies)
make build

# Build debug
make build-debug

# Clean
make clean
```

## Features

- Core protocol implementation (CREATE_SESSION, QUERY, PREPARE, EXECUTE, BATCH)
- Primitive types: tinyint, smallint, int, bigint, float, double, boolean, text, blob, uuid, timeuuid, inet
- Temporal types: date, time, timestamp, duration
- Collection types: list, set, map with automatic element type coercion
- Nested collections: full support for deeply nested types (list of lists, map of maps, etc.)
- Vector types: vector<float, N> including lists of vectors
- Tuple types: native tuple support with element type coercion
- UDT (User-Defined Types): full dynamic UDT support via Java driver's UdtValue
- Varint and decimal types
- Prepared statement caching
- Connection pooling
- Configurable consistency levels
- DC-aware load balancing
- Virtual threads for high concurrency (Java 21 feature backported via --enable-preview or using Java 17 Executors)

## Architecture

The adapter implements the Latte IPC protocol:

1. **Socket Server**: Listens on a Unix domain socket for incoming connections
2. **Request Handler**: Dispatches protocol messages to appropriate handlers
3. **Session Registry**: Manages database sessions and their lifecycle
4. **Value Encoder/Decoder**: Converts between CQL binary format and Java types

See the main [driver-adapters README](../README.md) for protocol details.
