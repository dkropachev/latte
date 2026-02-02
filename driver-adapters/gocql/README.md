# Latte GoCQL Driver Adapter

A driver adapter for Latte that uses the [ScyllaDB GoCQL](https://github.com/scylladb/gocql) Go driver for ScyllaDB/Cassandra.

## Driver

- **Driver**: ScyllaDB GoCQL v1.17.1
- **Language**: Go 1.25+
- **Repository**: https://github.com/scylladb/gocql

## Docker Image

- **Image**: `scylladb/latte-driver-adapters:gocql-<version>`
- **Tags**: `gocql-latest`, `gocql-<version>`

## Make Commands

| Command | Description |
|---------|-------------|
| `make build` | Build release binary (with PGO if default.pgo exists) |
| `make build-nopgo` | Build release binary without PGO |
| `make test` | Run tests |
| `make lint` | Run golangci-lint |
| `make lint-fix` | Auto-fix lint issues |
| `make fmt` | Format code |
| `make build-docker-image` | Build Docker image |
| `make push-docker-image` | Push Docker image to registry |
| `make docker-run` | Run adapter in Docker locally |
| `make pgo-generate` | Generate PGO profile automatically (requires ScyllaDB + latte) |
| `make pgo-full` | Generate PGO profile and rebuild with it |
| `make pgo-collect` | Collect CPU profile from running adapter |
| `make pgo-build` | Build with freshly collected profile |
| `make clean` | Clean build artifacts |

## Configuration

### Environment Variables

The adapter is configured via environment variables (used as defaults when session parameters are not provided):

| Variable | Default | Description |
|----------|---------|-------------|
| `LATTE_DRIVER_SOCKET` | `/tmp/latte-driver.sock` | Unix socket path |
| `LATTE_DRIVER_CONTACT_POINTS` | `127.0.0.1` | Comma-separated ScyllaDB/Cassandra hosts |
| `LATTE_DRIVER_KEYSPACE` | (none) | Default keyspace |
| `LATTE_DRIVER_INFLIGHT` | `512` | Max concurrent in-flight requests |
| `LATTE_DRIVER_PPROF` | (none) | pprof HTTP address (e.g., `localhost:6060`) for PGO profiling |

### Session Parameters

Session parameters can be passed via the CREATE_SESSION request and override environment variable defaults. This adapter supports the following parameters:

| Parameter | Supported | Notes |
|-----------|-----------|-------|
| `contact_points` | Yes | Overrides `LATTE_DRIVER_CONTACT_POINTS` |
| `keyspace` | Yes | Overrides `LATTE_DRIVER_KEYSPACE` |
| `username` | Yes | Authentication username |
| `password` | Yes | Authentication password |
| `datacenter` | Yes | Enables DC-aware load balancing |
| `rack` | Partial | Logged but gocql lacks native rack awareness |
| `connections_per_shard` | Yes | Maps to gocql `NumConns` |
| `request_timeout_ms` | Yes | Per-request timeout |
| `connect_timeout_ms` | Yes | Connection establishment timeout |
| `consistency` | Yes | Default consistency level |
| `serial_consistency` | Yes | Serial consistency for LWT |
| `default_page_size` | Not yet | Page size (planned) |
| `ssl_enabled` | Not yet | SSL support (planned) |
| `ssl_*` | Not yet | SSL configuration (planned) |

**Backward Compatibility**: Unknown parameters are silently ignored. This allows newer Latte versions to work with older driver adapter versions as long as unsupported parameters are not critical for the workload.

## Usage

### Building

```bash
# Build the adapter
make build

# Or build without PGO
make build-nopgo
```

### Running Locally

```bash
# Start the adapter
LATTE_DRIVER_SOCKET=/tmp/latte.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
./latte-gocql-driver

# In another terminal, run Latte with the driver
latte run --driver /tmp/latte.sock workloads/basic/read.rn
```

### Running with Docker

```bash
# Build the Docker image
make build-docker-image DRIVER_VERSION=1.7.0

# Run the container
docker run --rm \
    -v /tmp/latte-sockets:/sockets \
    -e LATTE_DRIVER_CONTACT_POINTS=host.docker.internal:9042 \
    scylladb/latte-driver-adapters:gocql-1.7.0
```

## Profile-Guided Optimization (PGO)

This adapter supports Go's Profile-Guided Optimization for improved performance (typically 2-7% improvement).

### Automated PGO Generation (Recommended)

The easiest way to generate a PGO profile is using the automated target:

```bash
# Generate profile and rebuild in one step (requires ScyllaDB running)
make pgo-full

# Or just generate the profile without rebuilding
make pgo-generate
```

You can customize the workload parameters:

```bash
make pgo-generate \
    PGO_CONTACT_POINTS=192.168.1.100:9042 \
    PGO_WORKLOAD=../../workloads/basic/write.rn \
    PGO_DURATION=120s \
    PGO_RATE=20000 \
    LATTE_BIN=/path/to/latte
```

### Manual Profile Collection

For more control, you can collect profiles manually:

1. Build without PGO:
   ```bash
   make build-nopgo
   ```

2. Start the adapter with pprof enabled:
   ```bash
   LATTE_DRIVER_PPROF=localhost:6060 \
   LATTE_DRIVER_SOCKET=/tmp/latte.sock \
   ./latte-gocql-driver
   ```

3. Run a representative workload:
   ```bash
   latte run --driver /tmp/latte.sock workloads/basic/read.rn -d 60s -r 10000
   ```

4. Collect the profile:
   ```bash
   make pgo-collect
   ```

5. Rebuild with PGO:
   ```bash
   make build
   ```

### PGO Best Practices

- Collect profiles from realistic, representative workloads
- Run workloads for at least 30-60 seconds
- Merge profiles from different workload types for broader optimization
- Commit `default.pgo` to the repository for reproducible builds
- Re-collect profiles after significant code changes

## Architecture

The adapter implements the Latte IPC binary protocol:

```
┌─────────────────────────────────────────────────┐
│                 LATTE (Host)                     │
│                      │                           │
│              Unix Domain Socket                  │
│                      │                           │
└──────────────────────┼──────────────────────────┘
                       │
┌──────────────────────┼──────────────────────────┐
│            GOCQL DRIVER ADAPTER                  │
│                      │                           │
│  ┌───────────────────┴───────────────────────┐  │
│  │              Server                        │  │
│  │  ┌─────────┐         ┌─────────────────┐  │  │
│  │  │ Reader  │────────▶│   Dispatcher    │  │  │
│  │  │  Loop   │         │ (goroutines)    │  │  │
│  │  └─────────┘         └────────┬────────┘  │  │
│  │                               │           │  │
│  │  ┌─────────┐         ┌────────▼────────┐  │  │
│  │  │ Writer  │◀────────│    Response     │  │  │
│  │  │  Loop   │         │    Channel      │  │  │
│  │  └─────────┘         └─────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
│                      │                           │
│  ┌───────────────────┴───────────────────────┐  │
│  │          Session Registry                  │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐   │  │
│  │  │Session 1│  │Session 2│  │Session N│   │  │
│  │  └────┬────┘  └────┬────┘  └────┬────┘   │  │
│  └───────┼────────────┼────────────┼────────┘  │
└──────────┼────────────┼────────────┼───────────┘
           │            │            │
           ▼            ▼            ▼
     ┌─────────────────────────────────────┐
     │       ScyllaDB / Cassandra          │
     └─────────────────────────────────────┘
```

## Performance Features

- **Token-Aware Routing**: Uses gocql's token-aware host selection policy
- **Connection Pooling**: gocql handles connection pooling internally
- **Buffered I/O**: 64KB write buffer with smart batching
- **Semaphore Backpressure**: Limits concurrent requests to prevent overload
- **Prepared Statement Cache**: Avoids re-preparing statements
- **Profile-Guided Optimization**: Optional PGO for hot path optimization

## Supported Operations

- `CREATE_SESSION` - Create a new database session
- `QUERY` - Execute unprepared CQL queries
- `PREPARE` - Prepare CQL statements
- `EXECUTE` - Execute prepared statements with values
- `BATCH` - Execute batches of prepared statements

## Supported CQL Types

| Type | Support |
|------|---------|
| int | ✓ |
| bigint | ✓ |
| text/varchar | ✓ |
| boolean | ✓ |
| float | ✓ |
| double | ✓ |
| uuid | ✓ |
| timeuuid | ✓ |
| blob | ✓ |
| timestamp | ✓ |
| date | ✓ |
| time | ✓ |
| smallint | ✓ |
| tinyint | ✓ |
| inet | ✓ |
| counter | ✓ |

## Troubleshooting

### Connection Issues

```bash
# Check if ScyllaDB is reachable
cqlsh 127.0.0.1 9042

# Enable debug logging
LATTE_DRIVER_SOCKET=/tmp/latte.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
./latte-gocql-driver 2>&1 | tee adapter.log
```

### Socket Permission Issues

The adapter sets socket permissions to 0666. If you encounter permission issues:

```bash
# Check socket permissions
ls -la /tmp/latte-driver.sock

# Manually set if needed
chmod 666 /tmp/latte-driver.sock
```

## License

This adapter is part of the Latte project. See the main repository for license information.
