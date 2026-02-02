# Alternator Client Java Adapter

A Latte Alternator adapter using the [ScyllaDB Alternator load-balancing](https://github.com/scylladb/alternator-load-balancing) Java library, which provides client-side load balancing for ScyllaDB Alternator.

## Driver

- **Driver**: ScyllaDB Alternator load-balancing 2.0.0 (AWS SDK v2 wrapper with load balancing)
- **Language**: Java 21+
- **Repository**: https://github.com/scylladb/alternator-load-balancing

## Key Features

- **Client-side load balancing**: Distributes requests across Alternator nodes without external load balancer
- **Rack and datacenter awareness**: Routes requests to nodes in the same rack/datacenter for lower latency
- **Virtual threads (Java 21)**: Lightweight concurrency matching Go goroutines
- **Connection pooling**: Apache HTTP client with configurable pool size
- **Fat JAR packaging**: Single self-contained JAR via Maven Shade plugin

## Docker Image

- **Image**: `scylladb/latte-alternator-adapters:alternator-client-java-<version>`
- **Tags**: `alternator-client-java-latest`, `alternator-client-java-<version>`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter fat JAR |
| `make clean` | Clean build artifacts |
| `make test` | Run unit tests |
| `make test-integration` | Run integration tests |
| `make test-functional` | Run functional tests with Latte |
| `make test-benchmark` | Run benchmark tests |
| `make build-docker-image` | Build Docker image |
| `make push-docker-image` | Build and push Docker image |
| `make docker-run` | Run adapter container locally |
| `make run` | Run adapter locally |
| `make start-dynamodb-local` | Start DynamoDB Local for testing |
| `make stop-dynamodb-local` | Stop DynamoDB Local |
| `make start-alternator` | Start ScyllaDB Alternator for testing |
| `make stop-alternator` | Stop ScyllaDB Alternator |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_ALTERNATOR_SOCKET` | `/tmp/latte-alternator.sock` | Unix socket path |
| `LATTE_ALTERNATOR_ENDPOINT` | `http://localhost:8000` | Default Alternator/DynamoDB endpoint |
| `LATTE_ALTERNATOR_REGION` | `us-east-1` | Default AWS region |
| `LATTE_ALTERNATOR_INFLIGHT` | `512` | Max concurrent requests |

### Session Parameters

When creating sessions, the following parameters can be passed:

| Parameter | Description | Default |
|-----------|-------------|---------|
| `endpoint` | Alternator/DynamoDB endpoint URL | `http://localhost:8000` |
| `region` | AWS region | `us-east-1` |
| `access_key_id` | AWS access key ID | (none) |
| `secret_access_key` | AWS secret access key | (none) |
| `session_token` | AWS session token | (none) |
| `max_connections` | Connection pool size | `100` |
| `request_timeout_ms` | Per-request timeout | `5000` |
| `connect_timeout_ms` | Connection timeout | `3000` |
| `retry_mode` | Retry strategy (`none`, `standard`, `adaptive`) | `none` |
| `max_retries` | Maximum retry attempts | `0` |
| `rack_awareness` | Enable rack-aware routing | `false` |
| `routing_scope` | Routing scope (`datacenter`, `rack`, `cluster`) | (none) |
| `compression` | Enable GZIP compression | `false` |
| `pool_size` | Connection pool size | `100` |

## Usage

### Running with Docker

```bash
# Create socket directory
mkdir -p /tmp/latte-alternator

# Run with host networking (Linux)
docker run --rm -it \
    --network host \
    -v /tmp/latte-alternator:/sockets \
    -e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
    -e LATTE_ALTERNATOR_ENDPOINT=http://127.0.0.1:8000 \
    scylladb/latte-alternator-adapters:alternator-client-java-latest

# Run with Docker Desktop (Mac/Windows)
docker run --rm -it \
    -v /tmp/latte-alternator:/sockets \
    -e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
    -e LATTE_ALTERNATOR_ENDPOINT=http://host.docker.internal:8000 \
    scylladb/latte-alternator-adapters:alternator-client-java-latest
```

### Running Locally

```bash
# Build and run
make run

# Or manually
make build
LATTE_ALTERNATOR_SOCKET=/tmp/test.sock \
LATTE_ALTERNATOR_ENDPOINT=http://localhost:8000 \
java -jar target/alternator-client-java-1.0-SNAPSHOT.jar
```

### Connecting Latte

```bash
# Connect Latte to the adapter
latte run --alternator-driver-socket /tmp/latte-alternator/latte-alternator.sock workload.rn
```

## Development

### Prerequisites

- Java 21 or later
- Maven 3.9+
- Docker (for container builds and testing)

### Building

```bash
# Download dependencies
make deps

# Build
make build

# Run tests
make test
```

### Testing with DynamoDB Local

```bash
# Start DynamoDB Local
make start-dynamodb-local

# Run integration tests
make test-integration

# Stop DynamoDB Local
make stop-dynamodb-local
```

## Architecture

```
+------------------------------------------------------------------------+
|                           Latte Host Process                            |
|  +-------------+    +--------------+    +---------------------------+  |
|  | Rune Script |-->| IpcClient    |-->| Unix Domain Socket        |  |
|  | (workload)  |    | (Alternator) |    | /tmp/latte-alternator.sock|  |
|  +-------------+    +--------------+    +-------------+-------------+  |
+-----------------------------------------------------------------------+
                                                        |
                                              Binary Protocol
                                                        |
+-------------------------------------------------------+----------------+
|                    alternator-client-java Adapter                       |
|  +------------------------------------------------------------------+  |
|  |                       Adapter Process (JVM)                       |  |
|  |  +--------------+   +-----------------+   +-----------------+    |  |
|  |  | Frame Reader |-->| Request Handler |-->| Session Registry|    |  |
|  |  +--------------+   +-----------------+   +--------+--------+    |  |
|  |                                                     |             |  |
|  |  +--------------------------------------------------v---------+  |  |
|  |  |    ScyllaDB Alternator load-balancing + AWS SDK v2         |  |  |
|  |  |      (Load Balancing, Rack Awareness, Pooling)             |  |  |
|  |  +--------------------------------------------------+---------+  |  |
|  +------------------------------------------------------------------+  |
+-------------------------------------------------------+-----------------+
                                                        |
                                                  HTTP/HTTPS
                                                        |
                                            +-----------v-----------+
                                            |  ScyllaDB Alternator  |
                                            |    or Amazon DynamoDB |
                                            +-----------------------+
```

## References

- [ONBOARDING.md](../ONBOARDING.md) - Full protocol specification
- [ScyllaDB Alternator load-balancing](https://github.com/scylladb/alternator-load-balancing) - Driver repository
- [AWS SDK for Java v2](https://github.com/aws/aws-sdk-java-v2) - Underlying SDK
