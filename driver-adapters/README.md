# Driver Adapters

Driver adapters allow Latte to benchmark databases using different driver implementations. Instead of being limited to the built-in ScyllaDB Rust driver, you can run benchmarks using the Java driver, Python driver, Go driver, or any other driver implementation.

## Overview

A driver adapter is a standalone process that:
1. Listens on a Unix domain socket for commands from Latte
2. Connects to the database using its native driver
3. Executes queries and returns results over the socket

This architecture enables:
- **Driver comparison**: Benchmark the same workload with different drivers
- **Language-specific drivers**: Use official drivers in their native languages
- **Isolation**: Driver issues don't crash the benchmarking tool
- **Docker deployment**: Run adapters in containers for consistent environments

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              LATTE (Host)                               │
│                                                                         │
│  ┌─────────────┐    ┌─────────────────┐    ┌──────────────────────────┐ │
│  │   Workload  │───▶│  SessionManager │───▶│       IpcClient          │ │
│  │   Script    │    │                 │    │  (multiplexed req/resp)  │ │
│  └─────────────┘    └─────────────────┘    └────────────┬─────────────┘ │
│                                                         │               │
└─────────────────────────────────────────────────────────┼───────────────┘
                                                          │
                                            Unix Domain Socket
                                           (full-duplex, single connection)
                                                          │
┌─────────────────────────────────────────────────────────┼───────────────┐
│                        DRIVER ADAPTER                   │               │
│                                                         ▼               │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                         Server                                    │   │
│  │  ┌─────────────┐              ┌─────────────┐                    │   │
│  │  │ Reader Loop │──(frames)──▶│  Dispatcher  │                    │   │
│  │  │             │              │  (spawns     │                    │   │
│  │  │             │              │   per-req)   │                    │   │
│  │  └─────────────┘              └──────┬───────┘                    │   │
│  │                                      │                            │   │
│  │                                      ▼                            │   │
│  │  ┌─────────────┐              ┌─────────────┐                    │   │
│  │  │ Writer Task │◀──(frames)──│   Response   │                    │   │
│  │  │ (batched)   │              │   Channel    │                    │   │
│  │  └─────────────┘              └─────────────┘                    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                     Session Registry                              │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │   │
│  │  │  Session 1  │  │  Session 2  │  │  Session N  │  ...          │   │
│  │  │  (Driver    │  │  (Driver    │  │  (Driver    │               │   │
│  │  │   Session)  │  │   Session)  │  │   Session)  │               │   │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘               │   │
│  └─────────┼────────────────┼────────────────┼──────────────────────┘   │
│            │                │                │                          │
└────────────┼────────────────┼────────────────┼──────────────────────────┘
             │                │                │
             ▼                ▼                ▼
        ┌─────────────────────────────────────────┐
        │          Database Cluster               │
        │   (ScyllaDB / Cassandra / etc.)         │
        └─────────────────────────────────────────┘
```

## Latte CLI Options

| Option | Description |
|--------|-------------|
| `--driver-image <IMAGE>` | Docker image to use for the driver adapter. When specified, Latte starts a container and delegates query execution to it via Unix domain socket. Example: `--driver-image scylladb/latte-driver-adapters:scylla-rust-driver-1.4.0` |
| `--driver-socket <PATH>` | Path to the Unix domain socket for driver communication. Default: `/tmp/latte-driver.sock`. Without `--driver-image`: connects to an existing driver at this socket. With `--driver-image`: specifies where the driver should create its socket. |

### Usage Examples

```bash
# Use Docker image (Latte manages container lifecycle)
latte run --driver-image scylladb/latte-driver-adapters:scylla-rust-driver-latest workload.rn

# Connect to an already-running driver adapter
latte run --driver-socket /tmp/my-driver.sock workload.rn

# Specify both image and custom socket path
latte run --driver-image scylladb/latte-driver-adapters:scylla-rust-driver-1.4.0 \
          --driver-socket /var/run/latte/driver.sock workload.rn
```

## Adapter Compatibility Matrix

Not all drivers support all workloads. The table below shows which workloads are supported by each adapter:

| Adapter | basic | primitives | collections | vectors | Notes |
|---------|:-----:|:----------:|:-----------:|:-------:|-------|
| scylla-rust-driver | ✅ | ✅ | ✅ | ✅ | Full support |
| java-driver-4 | ✅ | ✅ | ✅ | ✅ | Full support |
| java-driver-3 | ✅ | ✅ | ✅ | ✅ | Full support |
| gocql | ✅ | ✅ | ✅ | ❌ | Vectors not supported |
| python-driver | ✅ | ✅ | ✅ | ❌ | Vectors not supported |
| csharp-driver | ✅ | ✅ | ❌ | ❌ | Collections/vectors not supported |
| cpp-rs-driver | ✅ | ✅ | ❌ | ❌ | Collections/vectors not supported |
| cpp-driver | ✅ | ✅ | ⏭️ | ✅ | Collections skipped (nested types crash) |

Legend:
- ✅ Supported and tested
- ❌ Not supported (will fail)
- ⏭️ Skipped in automated tests (known incompatibility)

**Why some workloads fail:**
- **collections**: Tests nested collection types (list of lists, UDTs, tuples with embedded collections) which some drivers cannot encode/decode properly
- **vectors**: Tests `vector<float, N>` type which requires driver support for the vector CQL type

## Makefile Commands

Each driver adapter includes a Makefile with a standard interface.

### Required Targets

| Target | Description |
|--------|-------------|
| `make build` | Build the adapter (release mode) |
| `make test` | Run tests |
| `make test-integration` | Run integration tests (~10s per suite, 100 rows) |
| `make test-benchmark` | Run benchmark tests (5m per suite, 10,000 rows) |
| `make lint` | Run all linters |
| `make lint-fix` | Auto-fix lint issues where possible |
| `make build-docker-image` | Build Docker image |
| `make push-docker-image` | Push Docker image to registry |

### Optional Targets

| Target | Description |
|--------|-------------|
| `make build-debug` | Build in debug mode |
| `make clean` | Clean build artifacts |
| `make fmt` | Format code |
| `make docker-run` | Run adapter container locally |
| `make profile` | Run CPU profiling |
| `make profile-cpu` | Run CPU profiling |
| `make profile-mem` | Run memory profiling |

### Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DRIVER_VERSION` | `latest` | Version tag for Docker image |
| `DOCKER_REGISTRY` | `scylladb` | Docker registry |
| `PROFILE_REQUEST_COUNT` | `10000` | Number of requests for profiling |
| `PROFILE_WORKLOAD` | `primitives` | Workload to profile |

## Integration Testing & Benchmarking

### Test Suites

| Suite | File | Types Tested |
|-------|------|--------------|
| **primitives** | `primitives.rn` | tinyint, smallint, int, bigint, float, double, varint, decimal, boolean, ascii, text, varchar, blob, date, time, timestamp, duration, uuid, timeuuid, inet |
| **collections** | `collections.rn` | list, set, map (frozen and non-frozen), nested collections, UDT, tuple |
| **vectors** | `vectors.rn` | vector\<float, N\> with various dimensions |

### Requirements

- ScyllaDB or Cassandra running on `127.0.0.1:9042` (or set `SCYLLA_CONTACT_POINTS`)
- The `latte` binary available in PATH (or set `LATTE_BIN`)

### Running Tests

```bash
# Run all integration test suites (~10 seconds per suite, 100 rows)
make test-integration

# Run individual suites
make test-integration-primitives
make test-integration-collections
make test-integration-vectors

# Run benchmark tests (5 minutes per suite, 10,000 rows)
make test-benchmark

# Custom settings
make test-integration SCYLLA_CONTACT_POINTS=192.168.1.100:9042
make test-benchmark BENCHMARK_DURATION=10m BENCHMARK_ROW_COUNT=100000
```

## Profiling

| Adapter | Tool | Output |
|---------|------|--------|
| **scylla-rust-driver** | `perf` + `flamegraph` | SVG flamegraph |
| **gocql** | `pprof` (built-in) | pprof binary |
| **python-driver** | `py-spy`, `memray` | SVG flamegraph, HTML |
| **java-driver-4** | `async-profiler`, JFR | HTML flamegraph, JFR |
| **csharp-driver** | `dotnet-trace` | nettrace, CSV |

```bash
# Profile from driver-adapters/ directory
make profile
make profile-gocql
make profile-java

# Profile from individual adapter directory
cd gocql
make profile-cpu
make profile-mem

# Custom settings
make profile PROFILE_WORKLOAD=vectors PROFILE_REQUEST_COUNT=20000
```

## Existing Adapters

| Adapter | Location | Language |
|---------|----------|----------|
| scylla-rust-driver | `driver-adapters/scylla-rust-driver/` | Rust |
| java-driver-4 | `driver-adapters/java-driver-4/` | Java |
| java-driver-3 | `driver-adapters/java-driver-3/` | Java |
| gocql | `driver-adapters/gocql/` | Go |
| python-driver | `driver-adapters/python-driver/` | Python |
| csharp-driver | `driver-adapters/csharp-driver/` | C# |
| cpp-driver | `driver-adapters/cpp-driver/` | C++ |
| cpp-rs-driver | `driver-adapters/cpp-rs-driver/` | Rust bindings |

### Quick Start

```bash
cd driver-adapters/scylla-rust-driver

# Build
make build

# Run manually
LATTE_DRIVER_SOCKET=/tmp/latte.sock \
LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
./target/release/latte-driver

# Build and push Docker image
make build-docker-image DRIVER_VERSION=1.4.0
make push-docker-image DRIVER_VERSION=1.4.0
```

## Debugging

Enable trace logging:
```bash
RUST_LOG=trace ./latte-driver
```

Common issues:
- **Socket permission errors**: Ensure socket directory is writable
- **Connection refused**: Check contact points are reachable
- **Unprepared errors**: Statement keys must match between prepare/execute
- **Value decode errors**: Verify type encoding matches CQL binary spec

## Further Reading

- [ONBOARDING.md](ONBOARDING.md) - Technical guide for implementing new adapters (IPC protocol, value encoding, implementation checklist)
