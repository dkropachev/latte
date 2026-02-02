# Alternator Adapters

This directory contains driver adapters for DynamoDB-compatible Alternator workloads. Each adapter enables Latte to use third-party DynamoDB client libraries while measuring both IPC overhead and driver-side latencies.

## Available Adapters

| Adapter | Language | Library | Status |
|---------|----------|---------|--------|
| [alternator-client-golang](alternator-client-golang/) | Go | scylladb/alternator-client-golang | Production Ready |
| [alternator-client-java](alternator-client-java/) | Java 21 | com.scylladb.alternator:load-balancing | Production Ready |

## Workload Support Matrix

| Adapter | crud | query | scan | batch | Notes |
|---------|:----:|:-----:|:----:|:-----:|-------|
| alternator-client-golang | ✅ | ✅ | ✅ | ✅ | Full support |
| alternator-client-java | ✅ | ✅ | ✅ | ✅ | Full support |

## Quick Start

### Prerequisites

- Go 1.24+ (for Go adapters)
- Java 21+ and Maven 3.9+ (for Java adapters)
- DynamoDB Local or ScyllaDB Alternator running on port 8000
- Latte binary built (`make build` from repo root)
- Python 3.10+ with matplotlib and numpy (for chart generation)

### Running Benchmarks

```bash
# Start DynamoDB Local for testing
make start-dynamodb-local

# Run all benchmarks for all adapters
make test-benchmark

# Run specific adapters and workloads
make test-benchmark ADAPTERS=alternator-client-golang WORKLOADS=crud,query

# Generate chart from existing reports
make test-benchmark-generate-chart
```

### Running Functional Tests

```bash
# Run all functional tests
make test-functional

# Run specific adapter
make test-functional-alternator-client-golang
```

## Makefile Targets

### Build Targets
- `make build` - Build all adapters
- `make build-docker-image` - Build Docker images for all adapters
- `make clean` - Clean build artifacts

### Test Targets
- `make test` - Run unit tests for all adapters
- `make test-functional` - Run functional tests with Latte
- `make test-benchmark` - Run performance benchmarks with chart generation

### Database Management
- `make start-dynamodb-local` - Start DynamoDB Local in Docker
- `make stop-dynamodb-local` - Stop DynamoDB Local
- `make start-alternator` - Start ScyllaDB Alternator in Docker
- `make stop-alternator` - Stop Alternator

### Profiling
- `make profile` - Run profiling benchmarks for all adapters
- `make profile-alternator-client-golang` - Profile specific adapter

## Configuration Variables

### Benchmark Configuration
| Variable | Default | Description |
|----------|---------|-------------|
| `ALTERNATOR_ENDPOINT` | `http://127.0.0.1:8000` | DynamoDB/Alternator endpoint |
| `BENCHMARK_ITEM_COUNT` | `10000` | Number of items for benchmarks |
| `BENCHMARK_DURATION` | `5s` | Duration for each benchmark run |
| `BENCHMARK_WORKLOADS` | `crud query batch scan` | Workloads to benchmark |

### Chart Configuration
| Variable | Default | Description |
|----------|---------|-------------|
| `CHART_OUTPUT` | `.benchmark-reports/benchmark_latencies.png` | Output chart file |
| `CHART_PERCENTILE` | `p99` | Percentile to plot (p50, p75, p90, p95, p99, p99.9) |
| `CHART_LOG_SCALE` | `true` | Use logarithmic Y axis |

### Profiling Configuration
| Variable | Default | Description |
|----------|---------|-------------|
| `PROFILE_REQUEST_COUNT` | `10000` | Number of requests for profiling |
| `PROFILE_WORKLOAD` | `crud` | Workload to profile |
| `PROFILE_OUTPUT_DIR` | `.profiles` | Directory for profile output |

## Example Workflows

### Full Benchmark Run
```bash
# Ensure database is running
make start-dynamodb-local

# Build and run all benchmarks
make test-benchmark

# View results
open .benchmark-reports/benchmark_latencies.png
```

### Targeted Benchmark
```bash
# Run only CRUD benchmarks for alternator-client-golang
make test-benchmark ADAPTERS=alternator-client-golang WORKLOADS=crud BENCHMARK_DURATION=30s

# Regenerate chart with different percentile
make test-benchmark-generate-chart CHART_PERCENTILE=p50
```

### Performance Profiling
```bash
make profile-alternator-client-golang PROFILE_REQUEST_COUNT=50000 PROFILE_WORKLOAD=query

# View CPU profile
go tool pprof -http=:8080 .profiles/cpu-alternator-client-golang-query.pprof
```

## Adding a New Adapter

See [ONBOARDING.md](ONBOARDING.md) for detailed instructions on implementing a new adapter. Key requirements:

1. Create a subdirectory with your adapter name
2. Implement the IPC protocol (binary frames over Unix socket)
3. Provide a Makefile with standard targets: `build`, `test`, `test-functional`, `test-benchmark`, `lint`, `build-docker-image`
4. Add your adapter to the `ADAPTERS` list in the root Makefile

## Architecture

```
alternator-adapters/
├── Makefile                           # Master orchestration
├── README.md                          # This file
├── ONBOARDING.md                      # Adapter implementation guide
├── .scripts/
│   └── plot_benchmark_latencies.py   # Chart generation script
├── .benchmark-reports/                # Generated benchmark reports
├── alternator-client-golang/          # Go adapter
│   ├── Makefile
│   ├── cmd/adapter/main.go
│   └── internal/...
└── alternator-client-java/           # Java adapter
    ├── Makefile
    ├── pom.xml
    └── src/main/java/...
```

## IPC Protocol

All adapters communicate with Latte via Unix domain sockets using a binary protocol:

- **Frame Header**: 12 bytes (version, flags, stream ID, opcode, body length)
- **Operations**: CREATE_SESSION, GET_ITEM, PUT_ITEM, DELETE_ITEM, UPDATE_ITEM, QUERY, SCAN, BATCH_GET_ITEM, BATCH_WRITE_ITEM, etc.
- **Session Management**: Multiple concurrent sessions supported

See [ONBOARDING.md](ONBOARDING.md) for the complete protocol specification.
