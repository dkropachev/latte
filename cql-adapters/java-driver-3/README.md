# ScyllaDB Java Driver 3.x Adapter

A Latte driver adapter using the ScyllaDB Java Driver 3.x. This adapter enables benchmarking ScyllaDB and Apache Cassandra using the legacy Java driver implementation, which is still widely used in production environments.

## Driver

- **Driver**: ScyllaDB Java Driver 3.11.5.3
- **Language**: Java 17
- **Repository**: https://github.com/scylladb/java-driver/tree/3.x

## Docker Image

- **Image**: scylladb/latte-driver-adapters:java-driver-3-\<version\>
- **Tags**: `java-driver-3-latest`, `java-driver-3-3.11.5.3`

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
LATTE_DRIVER_SOCKET=/tmp/latte-driver-java3.sock \
java -jar target/latte-driver-java3.jar

# In another terminal, run a benchmark
latte run --driver-socket /tmp/latte-driver-java3.sock workload.rn
```

### Using Docker

```bash
# Build the image
make build-docker-image DRIVER_VERSION=3.11.5.3

# Run with Latte
latte run --cql-adapter-image scylladb/latte-driver-adapters:java-driver-3-3.11.5.3 workload.rn
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
- Tuple types: native tuple support with element type coercion
- UDT (User-Defined Types): full dynamic UDT support via Java driver's UDTValue
- Varint and decimal types
- Prepared statement caching
- Connection pooling
- Configurable consistency levels
- DC-aware load balancing

## Limitations

Compared to the Java Driver 4.x adapter, this 3.x adapter has the following limitations:

- **No vector types**: `vector<float, N>` is not supported in Driver 3.x. Workloads using vectors will fail with an error message.
- **Legacy API**: Uses the older Cluster/Session model instead of CqlSession builder pattern.

## Architecture

The adapter implements the Latte IPC protocol:

1. **Socket Server**: Listens on a Unix domain socket for incoming connections
2. **Request Handler**: Dispatches protocol messages to appropriate handlers
3. **Session Registry**: Manages database sessions and their lifecycle
4. **Value Encoder/Decoder**: Converts between CQL binary format and Java types

See the main [cql-adapters README](../README.md) for protocol details.

## API Differences from Driver 4.x

This adapter is adapted from the Java Driver 4.x adapter with the following key changes:

| Aspect | Driver 4.x | Driver 3.x |
|--------|-----------|-----------|
| Package prefix | `com.datastax.oss.driver.*` | `com.datastax.driver.*` |
| Session creation | `CqlSession.builder().build()` | `Cluster.builder().build().connect()` |
| Type constants | `DataTypes.INT` | `DataType.Name.INT` |
| Duration | `CqlDuration` | `Duration` |
| Timestamp | `java.time.Instant` | `java.util.Date` |
| Statement builder | Immutable (returns new) | Mutable (modifies in place) |
| UDT classes | `UdtValue`, `UserDefinedType` | `UDTValue`, `UserType` |
