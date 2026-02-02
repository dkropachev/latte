# Alternator Client Golang Adapter

A Latte Alternator adapter using the [scylladb/alternator-client-golang](https://github.com/scylladb/alternator-client-golang) library, which provides client-side load balancing for ScyllaDB Alternator.

## Driver

- **Driver**: alternator-client-golang (AWS SDK v2 wrapper with load balancing)
- **Language**: Go 1.22+
- **Repository**: https://github.com/scylladb/alternator-client-golang

## Key Features

- **Client-side load balancing**: Distributes requests across Alternator nodes without external load balancer
- **Rack and datacenter awareness**: Routes requests to nodes in the same rack/datacenter for lower latency
- **Node health tracking**: Automatically detects and avoids unhealthy nodes
- **Header optimization**: Up to 56% reduction in HTTP header size
- **Request compression**: GZIP compression support
- **Connection pooling**: Efficient HTTP connection reuse

## Docker Image

- **Image**: `scylladb/latte-alternator-adapters:alternator-client-golang-<version>`
- **Tags**: `alternator-client-golang-latest`, `alternator-client-golang-<version>`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build the adapter binary |
| `make clean` | Clean build artifacts |
| `make test` | Run unit tests |
| `make test-integration` | Run integration tests |
| `make lint` | Run golangci-lint |
| `make lint-fix` | Run linter with auto-fix |
| `make fmt` | Format code |
| `make fmt-check` | Check code formatting |
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
| `LATTE_ALTERNATOR_ACCESS_KEY` | (none) | Default AWS access key ID |
| `LATTE_ALTERNATOR_SECRET_KEY` | (none) | Default AWS secret access key |
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
    scylladb/latte-alternator-adapters:alternator-client-golang-latest

# Run with Docker Desktop (Mac/Windows)
docker run --rm -it \
    -v /tmp/latte-alternator:/sockets \
    -e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
    -e LATTE_ALTERNATOR_ENDPOINT=http://host.docker.internal:8000 \
    scylladb/latte-alternator-adapters:alternator-client-golang-latest
```

### Running Locally

```bash
# Build and run
make run

# Or manually
make build
LATTE_ALTERNATOR_SOCKET=/tmp/test.sock \
LATTE_ALTERNATOR_ENDPOINT=http://localhost:8000 \
./bin/adapter
```

### Connecting Latte

```bash
# Connect Latte to the adapter
latte run --alternator-driver-socket /tmp/latte-alternator/latte-alternator.sock workload.rn
```

## Development

### Prerequisites

- Go 1.22 or later
- golangci-lint (for linting)
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

### Testing with Alternator

```bash
# Start ScyllaDB Alternator
make start-alternator

# Run integration tests
TEST_ENDPOINT=http://localhost:8000 make test-integration

# Stop Alternator
make stop-alternator
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Latte Host Process                            │
│  ┌─────────────┐    ┌──────────────┐    ┌───────────────────────────┐  │
│  │ Rune Script │───▶│ IpcClient    │───▶│ Unix Domain Socket        │  │
│  │ (workload)  │    │ (Alternator) │    │ /tmp/latte-alternator.sock│  │
│  └─────────────┘    └──────────────┘    └─────────────┬─────────────┘  │
└───────────────────────────────────────────────────────┼─────────────────┘
                                                        │
                                              Binary Protocol
                                                        │
┌───────────────────────────────────────────────────────┼─────────────────┐
│                    alternator-client-golang Adapter                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                         Adapter Binary                           │   │
│  │  ┌──────────────┐   ┌─────────────────┐   ┌─────────────────┐   │   │
│  │  │ Frame Parser │──▶│ Request Handler │──▶│ Session Registry│   │   │
│  │  └──────────────┘   └─────────────────┘   └────────┬────────┘   │   │
│  │                                                     │            │   │
│  │  ┌──────────────────────────────────────────────────▼──────────┐│   │
│  │  │            alternator-client-golang + AWS SDK v2            ││   │
│  │  │         (Load Balancing, Rack Awareness, Pooling)           ││   │
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

## References

- [ONBOARDING.md](../ONBOARDING.md) - Full protocol specification
- [alternator-client-golang](https://github.com/scylladb/alternator-client-golang) - Driver repository
- [AWS SDK for Go v2](https://github.com/aws/aws-sdk-go-v2) - Underlying SDK
