# Latte GoCQL Driver Adapter Makefile

# Directory where this Makefile is located (all paths are relative to this)
MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

DRIVER_VERSION ?= latest
DOCKER_REGISTRY ?= scylladb
IMAGE_NAME = $(DOCKER_REGISTRY)/latte-driver-adapters
IMAGE_TAG = gocql-$(DRIVER_VERSION)
DRIVER_IMAGE_FLAG = --cql-adapter-image $(IMAGE_NAME):$(IMAGE_TAG)
BINARY_NAME = latte-gocql-driver

# Go build flags
GO_LDFLAGS = -ldflags="-s -w"

.PHONY: help
help:
	@echo "Latte GoCQL Driver Adapter"
	@echo ""
	@echo "Usage:"
	@echo "  make build              Build release binary (with PGO if default.pgo exists)"
	@echo "  make build-nopgo        Build release binary without PGO"
	@echo "  make test               Run tests"
	@echo "  make test-functional   Run all integration test suites (~10s each)"
	@echo "  make test-functional-primitives   Run primitives integration suite"
	@echo "  make test-functional-collections  Run collections integration suite"
	@echo "  make test-functional-vectors      Run vectors integration suite"
	@echo "  make test-benchmark     Run all benchmark suites (5m each)"
	@echo "  make test-benchmark-primitives     Run primitives benchmark"
	@echo "  make test-benchmark-collections    Run collections benchmark"
	@echo "  make test-benchmark-vectors        Run vectors benchmark"
	@echo "  make lint               Run golangci-lint"
	@echo "  make lint-fix           Auto-fix lint issues"
	@echo "  make fmt                Format code"
	@echo "  make build-docker-image Build Docker image"
	@echo "  make push-docker-image  Push Docker image to registry"
	@echo "  make docker-run         Run adapter in Docker locally"
	@echo "  make pgo-generate       Generate PGO profile (requires ScyllaDB + latte)"
	@echo "  make pgo-full           Generate PGO profile and rebuild with it"
	@echo "  make pgo-collect        Collect CPU profile from running adapter"
	@echo "  make pgo-build          Build with freshly collected profile"
	@echo "  make profile            Run CPU profiling with pprof"
	@echo "  make profile-cpu        Run CPU profiling with pprof"
	@echo "  make profile-mem        Run heap memory profiling"
	@echo "  make profile-goroutine  Collect goroutine profile"
	@echo "  make clean              Clean build artifacts"
	@echo ""
	@echo "Variables:"
	@echo "  DRIVER_VERSION          Docker image version tag (default: latest)"
	@echo "  DOCKER_REGISTRY         Docker registry (default: scylladb)"
	@echo ""
	@echo "Test Variables:"
	@echo "  SCYLLA_CONTACT_POINTS   Database contact points (default: 127.0.0.1:9042)"
	@echo "  LATTE_BIN               Path to latte binary (default: latte)"
	@echo "  INTEGRATION_ROW_COUNT       Rows for integration tests (default: 100)"
	@echo "  INTEGRATION_ITERATION_COUNT Iterations for integration tests (default: 10)"
	@echo "  BENCHMARK_ROW_COUNT         Rows for benchmarks (default: 10000)"
	@echo "  BENCHMARK_DURATION          Duration for benchmarks (default: 5s)"
	@echo ""
	@echo "PGO Variables (for pgo-generate):"
	@echo "  PGO_CONTACT_POINTS      ScyllaDB contact points (default: 127.0.0.1:9042)"
	@echo "  PGO_WORKLOAD            Workload file (default: ../../workloads/basic/read.rn)"
	@echo "  PGO_DURATION            Workload duration (default: 60s)"
	@echo "  PGO_RATE                Request rate (default: 10000)"
	@echo "  LATTE_BIN               Path to latte binary (default: latte)"
	@echo ""
	@echo "Profiling Variables (for profile-* targets):"
	@echo "  PROFILE_REQUEST_COUNT   Number of requests for profiling (default: 10000)"
	@echo "  PROFILE_WORKLOAD        Workload to profile (default: primitives)"
	@echo "  PROFILE_OUTPUT_DIR      Directory for profile output (default: ../profiles)"

# Build with PGO if profile exists
.PHONY: build
build:
	@if ! command -v go >/dev/null 2>&1; then \
		if [ -x "$(MAKEFILE_DIR)$(BINARY_NAME)" ]; then \
			echo "Go compiler not found, but $(BINARY_NAME) binary exists - skipping build."; \
		else \
			echo ""; \
			echo "============================================================"; \
			echo "ERROR: Go compiler not found and no pre-built binary exists"; \
			echo ""; \
			echo "Please install Go: https://go.dev/doc/install"; \
			echo "============================================================"; \
			exit 1; \
		fi; \
	else \
		if [ -f default.pgo ]; then \
			echo "Building with PGO profile..."; \
			CGO_ENABLED=0 go build -pgo=default.pgo $(GO_LDFLAGS) -o $(BINARY_NAME) .; \
		else \
			echo "Building without PGO (no default.pgo found)..."; \
			CGO_ENABLED=0 go build $(GO_LDFLAGS) -o $(BINARY_NAME) .; \
		fi; \
	fi

# Build explicitly without PGO
.PHONY: build-nopgo
build-nopgo:
	CGO_ENABLED=0 go build -pgo=off $(GO_LDFLAGS) -o $(BINARY_NAME) .

# Build in debug mode
.PHONY: build-debug
build-debug:
	go build -o $(BINARY_NAME) .

.PHONY: test
test:
	go test -v ./...

# ============================================================================
# Integration test & benchmark targets
# ============================================================================

# Common configuration
SCYLLA_CONTACT_POINTS ?= 127.0.0.1:9042
LATTE_BIN ?= ${MAKEFILE_DIR}../../target/release/latte
WORKLOADS_DIR ?= $(MAKEFILE_DIR)../.workloads

# Integration test configuration (quick validation, iteration-limited)
INTEGRATION_SOCKET ?= /tmp/latte-gocql-integration-test.sock
INTEGRATION_ROW_COUNT ?= 100
INTEGRATION_ITERATION_COUNT ?= 10

# Benchmark configuration (performance testing, time-limited)
BENCHMARK_SOCKET ?= /tmp/latte-gocql-benchmark-test.sock
BENCHMARK_ROW_COUNT ?= 10000
BENCHMARK_DURATION ?= 5s

# ----------------------------------------------------------------------------
# Integration tests (quick validation, iteration-limited)
# ----------------------------------------------------------------------------

.PHONY: _ensure-db
_ensure-db:
	@$(MAKE) --no-print-directory -C $(MAKEFILE_DIR).. _ensure-db

.PHONY: test-functional
test-functional: _ensure-db _check-docker-image ## Run all integration test suites
	@$(MAKE) --no-print-directory _run-all-suites \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		ITERATION_COUNT=$(INTEGRATION_ITERATION_COUNT) \
		RUN_FUNCTION=validate

.PHONY: test-functional-primitives
test-functional-primitives: _check-docker-image ## Run primitives integration test suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		ITERATION_COUNT=$(INTEGRATION_ITERATION_COUNT) \
		RUN_FUNCTION=validate \
		SUITE_NAME=primitives \
		SUITE_FILE=$(WORKLOADS_DIR)/primitives.rn

.PHONY: test-functional-collections
test-functional-collections: _check-docker-image ## Run collections integration test suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		ITERATION_COUNT=$(INTEGRATION_ITERATION_COUNT) \
		RUN_FUNCTION=validate \
		SUITE_NAME=collections \
		SUITE_FILE=$(WORKLOADS_DIR)/collections.rn

.PHONY: test-functional-vectors
test-functional-vectors: _check-docker-image ## Run vectors integration test suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		ITERATION_COUNT=$(INTEGRATION_ITERATION_COUNT) \
		RUN_FUNCTION=validate \
		SUITE_NAME=vectors \
		SUITE_FILE=$(WORKLOADS_DIR)/vectors.rn

# ----------------------------------------------------------------------------
# Benchmark tests (performance testing, time-limited)
# ----------------------------------------------------------------------------

.PHONY: test-benchmark
test-benchmark: _check-docker-image ## Run all benchmark test suites
	@$(MAKE) --no-print-directory _run-all-suites \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run

.PHONY: test-benchmark-primitives
test-benchmark-primitives: _check-docker-image ## Run primitives benchmark suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run \
		SUITE_NAME=primitives \
		SUITE_FILE=$(WORKLOADS_DIR)/primitives.rn

.PHONY: test-benchmark-collections
test-benchmark-collections: _check-docker-image ## Run collections benchmark suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run \
		SUITE_NAME=collections \
		SUITE_FILE=$(WORKLOADS_DIR)/collections.rn

.PHONY: test-benchmark-vectors
test-benchmark-vectors: _check-docker-image ## Run vectors benchmark suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run \
		SUITE_NAME=vectors \
		SUITE_FILE=$(WORKLOADS_DIR)/vectors.rn

# ----------------------------------------------------------------------------
# Internal helper targets
# ----------------------------------------------------------------------------

.PHONY: _run-all-suites
_run-all-suites:
	@echo "=== $(TEST_MODE) Test Suite (GoCQL) ==="
	@echo "Contact points: $(SCYLLA_CONTACT_POINTS)"
	@echo "Socket: $(SOCKET)"
	@echo "Rows: $(ROW_COUNT), $(if $(DURATION),Duration: $(DURATION),Iterations: $(ITERATION_COUNT))"
	@echo ""
	@if [ ! -x "$(LATTE_BIN)" ] && ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: latte binary not found"; \
		echo "  LATTE_BIN=$(LATTE_BIN)"; \
		echo ""; \
		echo "To fix, run from cql-adapters directory:"; \
		echo "  make test-functional   (will auto-build latte)"; \
		echo ""; \
		echo "Or build manually:"; \
		echo "  cd ../.. && make build"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@echo ""
	@echo "=== Suite 1/3: Primitives ==="
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=primitives SUITE_FILE=$(WORKLOADS_DIR)/primitives.rn RUN_FUNCTION=$(RUN_FUNCTION) || exit 1
	@echo ""
	@echo "=== Suite 2/3: Collections ==="
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=collections SUITE_FILE=$(WORKLOADS_DIR)/collections.rn RUN_FUNCTION=$(RUN_FUNCTION) || exit 1
	@echo ""
	@echo "=== Suite 3/3: Vectors ==="
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=vectors SUITE_FILE=$(WORKLOADS_DIR)/vectors.rn RUN_FUNCTION=$(RUN_FUNCTION) || exit 1
	@echo ""
	@echo "=== All $(TEST_MODE) tests PASSED ==="

.PHONY: _run-single-suite
_run-single-suite:
	@echo "=== $(TEST_MODE) Test: $(SUITE_NAME) (GoCQL) ==="
	@echo "Contact points: $(SCYLLA_CONTACT_POINTS)"
	@echo "Socket: $(SOCKET)"
	@echo "Rows: $(ROW_COUNT), $(if $(DURATION),Duration: $(DURATION),Iterations: $(ITERATION_COUNT))"
	@echo ""
	@if [ ! -x "$(LATTE_BIN)" ] && ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: latte binary not found"; \
		echo "  LATTE_BIN=$(LATTE_BIN)"; \
		echo ""; \
		echo "To fix, run from cql-adapters directory:"; \
		echo "  make test-functional   (will auto-build latte)"; \
		echo ""; \
		echo "Or build manually:"; \
		echo "  cd ../.. && make build"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=$(SUITE_NAME) SUITE_FILE=$(SUITE_FILE) RUN_FUNCTION=$(RUN_FUNCTION) || exit 1
	@echo ""
	@echo "=== $(SUITE_NAME) $(TEST_MODE) test PASSED ==="

.PHONY: _run-suite
_run-suite:
	@if [ ! -f "$(SUITE_FILE)" ]; then \
		echo "Error: Workload file not found: $(SUITE_FILE)"; \
		exit 1; \
	fi
	@echo "Creating schema..."
	@$(LATTE_BIN) schema $(DRIVER_IMAGE_FLAG) --driver-socket $(SOCKET) $(SUITE_FILE)
	@echo "Loading data ($(ROW_COUNT) rows)..."
	@$(LATTE_BIN) load $(DRIVER_IMAGE_FLAG) --driver-socket $(SOCKET) \
		-P row_count=$(ROW_COUNT) \
		$(SUITE_FILE)
	@echo "Running$(if $(DURATION), for $(DURATION))$(if $(ITERATION_COUNT), $(ITERATION_COUNT) iterations)..."
	@$(LATTE_BIN) run $(DRIVER_IMAGE_FLAG) --driver-socket $(SOCKET) \
		$(if $(DURATION),-d $(DURATION)) \
		$(if $(ITERATION_COUNT),-d $(ITERATION_COUNT)) \
		-P row_count=$(ROW_COUNT) \
		-f $(RUN_FUNCTION) \
		$(SUITE_FILE)
	@echo "$(SUITE_NAME) suite PASSED"

.PHONY: _wait-for-socket
_wait-for-socket:
	@echo "Waiting for adapter to be ready..."
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
		if [ -S $(SOCKET) ]; then \
			echo "Adapter ready."; \
			exit 0; \
		fi; \
		if [ $$i -eq 10 ]; then \
			echo "Error: Adapter failed to start"; \
			kill `cat .test.pid` 2>/dev/null || true; \
			rm -f .test.pid; \
			exit 1; \
		fi; \
		sleep 1; \
	done

.PHONY: lint
lint:
	@if command -v golangci-lint >/dev/null 2>&1; then \
		golangci-lint run ./...; \
	else \
		echo "golangci-lint not installed, running go vet instead"; \
		go vet ./...; \
	fi

.PHONY: lint-fix
lint-fix:
	@if command -v golangci-lint >/dev/null 2>&1; then \
		golangci-lint run --fix ./...; \
	else \
		echo "golangci-lint not installed, running gofmt instead"; \
		gofmt -w .; \
	fi

.PHONY: fmt
fmt:
	gofmt -w .
	goimports -w . 2>/dev/null || true

.PHONY: clean
clean:
	rm -f $(BINARY_NAME)
	rm -f cpu.pprof merged.pgo
	rm -f .pgo-adapter.pid
	go clean

# Docker targets
.PHONY: _check-docker-image
_check-docker-image:
	@if docker image inspect $(IMAGE_NAME):$(IMAGE_TAG) >/dev/null 2>&1; then \
		echo "Docker image $(IMAGE_NAME):$(IMAGE_TAG) already exists, skipping build"; \
	else \
		$(MAKE) --no-print-directory build-docker-image; \
	fi

.PHONY: build-docker-image
build-docker-image:
	docker build \
		--build-arg DRIVER_VERSION=$(DRIVER_VERSION) \
		-t $(IMAGE_NAME):$(IMAGE_TAG) \
		.
	@if [ "$(DRIVER_VERSION)" = "latest" ]; then \
		docker tag $(IMAGE_NAME):$(IMAGE_TAG) $(IMAGE_NAME):gocql-latest; \
	fi

.PHONY: push-docker-image
push-docker-image:
	docker push $(IMAGE_NAME):$(IMAGE_TAG)
	@if [ "$(DRIVER_VERSION)" = "latest" ]; then \
		docker push $(IMAGE_NAME):gocql-latest; \
	fi

.PHONY: docker-run
docker-run:
	docker run --rm -it \
		-v /tmp/latte-sockets:/sockets \
		-e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
		$(IMAGE_NAME):$(IMAGE_TAG)

# PGO targets

# Configuration for PGO profile generation
PGO_SOCKET ?= /tmp/latte-pgo.sock
PGO_PPROF_ADDR ?= localhost:6060
PGO_CONTACT_POINTS ?= 127.0.0.1:9042
PGO_WORKLOAD ?= $(MAKEFILE_DIR)../../workloads/basic/read.rn
PGO_DURATION ?= 60s
PGO_RATE ?= 10000
PGO_PROFILE_DURATION ?= 30
LATTE_BIN ?= $(MAKEFILE_DIR)../../target/release/latte

.PHONY: pgo-collect
pgo-collect:
	@echo "Collecting $(PGO_PROFILE_DURATION)-second CPU profile..."
	@echo "Make sure the adapter is running with LATTE_DRIVER_PPROF=$(PGO_PPROF_ADDR)"
	curl -o cpu.pprof "http://$(PGO_PPROF_ADDR)/debug/pprof/profile?seconds=$(PGO_PROFILE_DURATION)"
	@if [ -f default.pgo ]; then \
		echo "Merging with existing profile..."; \
		go tool pprof -proto default.pgo cpu.pprof > merged.pgo; \
		mv merged.pgo default.pgo; \
	else \
		mv cpu.pprof default.pgo; \
	fi
	rm -f cpu.pprof
	@echo "Profile saved to default.pgo"

.PHONY: pgo-build
pgo-build: pgo-collect build
	@echo "PGO build complete"

# Generate PGO profile by running a full workload automatically
# Requires: ScyllaDB running, latte binary available
.PHONY: pgo-generate
pgo-generate: build-nopgo
	@echo "=== PGO Profile Generation ==="
	@echo "Socket: $(PGO_SOCKET)"
	@echo "Contact points: $(PGO_CONTACT_POINTS)"
	@echo "Workload: $(PGO_WORKLOAD)"
	@echo "Duration: $(PGO_DURATION)"
	@echo "Rate: $(PGO_RATE) req/s"
	@echo ""
	@# Check if latte is available
	@if ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo "Error: '$(LATTE_BIN)' not found in PATH."; \
		echo "Build latte first: cd ../.. && make build"; \
		echo "Or set LATTE_BIN=/path/to/latte"; \
		exit 1; \
	fi
	@# Check if workload file exists
	@if [ ! -f "$(PGO_WORKLOAD)" ]; then \
		echo "Error: Workload file not found: $(PGO_WORKLOAD)"; \
		exit 1; \
	fi
	@# Clean up any stale socket
	@rm -f $(PGO_SOCKET)
	@echo "Starting adapter with pprof enabled..."
	@LATTE_DRIVER_SOCKET=$(PGO_SOCKET) \
		LATTE_DRIVER_PPROF=$(PGO_PPROF_ADDR) \
		$(MAKEFILE_DIR)$(BINARY_NAME) & \
		echo $$! > .pgo-adapter.pid
	@# Wait for socket to be ready
	@echo "Waiting for adapter to be ready..."
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
		if [ -S $(PGO_SOCKET) ]; then \
			echo "Adapter ready."; \
			break; \
		fi; \
		if [ $$i -eq 10 ]; then \
			echo "Error: Adapter failed to start"; \
			kill `cat .pgo-adapter.pid` 2>/dev/null || true; \
			rm -f .pgo-adapter.pid; \
			exit 1; \
		fi; \
		sleep 1; \
	done
	@# Start profile collection in background, then run workload
	@echo "Running workload and collecting profile..."
	@( \
		sleep 5; \
		echo "Starting $(PGO_PROFILE_DURATION)s profile collection..."; \
		curl -s -o cpu.pprof "http://$(PGO_PPROF_ADDR)/debug/pprof/profile?seconds=$(PGO_PROFILE_DURATION)" && \
		echo "Profile collected." \
	) & \
	$(LATTE_BIN) run \
		--driver-socket $(PGO_SOCKET) \
		--duration $(PGO_DURATION) \
		--rate $(PGO_RATE) \
		$(PGO_WORKLOAD) || true
	@# Wait for profile collection to complete
	@echo "Waiting for profile collection to complete..."
	@wait
	@# Stop the adapter
	@echo "Stopping adapter..."
	@if [ -f .pgo-adapter.pid ]; then \
		kill `cat .pgo-adapter.pid` 2>/dev/null || true; \
		rm -f .pgo-adapter.pid; \
	fi
	@rm -f $(PGO_SOCKET)
	@# Process the profile
	@if [ -f cpu.pprof ]; then \
		if [ -f default.pgo ]; then \
			echo "Merging with existing profile..."; \
			go tool pprof -proto default.pgo cpu.pprof > merged.pgo; \
			mv merged.pgo default.pgo; \
		else \
			mv cpu.pprof default.pgo; \
		fi; \
		rm -f cpu.pprof; \
		echo ""; \
		echo "=== PGO profile saved to default.pgo ==="; \
		echo "Run 'make build' to build with PGO optimization."; \
	else \
		echo "Error: Profile collection failed"; \
		exit 1; \
	fi

# Full PGO workflow: generate profile and rebuild
.PHONY: pgo-full
pgo-full: pgo-generate build
	@echo ""
	@echo "=== PGO build complete ==="
	@echo "Binary '$(BINARY_NAME)' is now optimized with PGO."

# ============================================================================
# Profiling targets (for performance analysis)
# ============================================================================

# Profiling configuration
PROFILE_SOCKET ?= /tmp/latte-gocql-profile.sock
PROFILE_PPROF_ADDR ?= localhost:6061
PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= primitives
PROFILE_OUTPUT_DIR ?= $(MAKEFILE_DIR)../profiles
PROFILE_WARMUP_COUNT ?= 1000
PROFILE_DURATION_SECS ?= 30

.PHONY: profile
profile: profile-cpu ## Alias for profile-cpu

.PHONY: profile-cpu
profile-cpu: build ## Run CPU profiling with pprof
	@echo "=== CPU Profiling (Go Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT) (warmup: $(PROFILE_WARMUP_COUNT))"
	@echo ""
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	echo "Starting adapter with pprof enabled..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		LATTE_DRIVER_PPROF=$(PROFILE_PPROF_ADDR) \
		$(MAKEFILE_DIR)$(BINARY_NAME) & \
	echo $$! > .profile.pid; \
	$(MAKE) --no-print-directory _wait-for-socket SOCKET=$(PROFILE_SOCKET) || exit 1; \
	echo "Creating schema..."; \
	$(LATTE_BIN) schema --driver-socket $(PROFILE_SOCKET) $(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn || { \
		$(MAKE) --no-print-directory _cleanup-profile; \
		exit 1; \
	}; \
	echo "Loading data..."; \
	$(LATTE_BIN) load --driver-socket $(PROFILE_SOCKET) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn || { \
		$(MAKE) --no-print-directory _cleanup-profile; \
		exit 1; \
	}; \
	echo "Warmup ($(PROFILE_WARMUP_COUNT) requests)..."; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-d $(PROFILE_WARMUP_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn >/dev/null 2>&1 || true; \
	echo ""; \
	echo "Starting $(PROFILE_DURATION_SECS)s CPU profile collection..."; \
	( curl -s -o $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-cpu-$$timestamp.pprof \
		"http://$(PROFILE_PPROF_ADDR)/debug/pprof/profile?seconds=$(PROFILE_DURATION_SECS)" && \
		echo "Profile collected." ) & \
	profile_pid=$$!; \
	sleep 2; \
	echo "Running $(PROFILE_REQUEST_COUNT) requests..."; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-d $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn || true; \
	echo "Waiting for profile collection to complete..."; \
	wait $$profile_pid 2>/dev/null || true; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	if [ -f $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-cpu-$$timestamp.pprof ]; then \
		echo ""; \
		echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-cpu-$$timestamp.pprof ==="; \
		echo "View interactively: go tool pprof $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-cpu-$$timestamp.pprof"; \
		echo "Generate flamegraph: go tool pprof -http=:8080 $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-cpu-$$timestamp.pprof"; \
	else \
		echo "ERROR: Profile not generated"; \
		exit 1; \
	fi

.PHONY: profile-mem
profile-mem: build ## Run memory (heap) profiling with pprof
	@echo "=== Memory Profiling (Go Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT)"
	@echo ""
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	echo "Starting adapter with pprof enabled..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		LATTE_DRIVER_PPROF=$(PROFILE_PPROF_ADDR) \
		$(MAKEFILE_DIR)$(BINARY_NAME) & \
	echo $$! > .profile.pid; \
	$(MAKE) --no-print-directory _wait-for-socket SOCKET=$(PROFILE_SOCKET) || exit 1; \
	echo "Creating schema..."; \
	$(LATTE_BIN) schema --driver-socket $(PROFILE_SOCKET) $(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	echo "Loading data..."; \
	$(LATTE_BIN) load --driver-socket $(PROFILE_SOCKET) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	echo "Running $(PROFILE_REQUEST_COUNT) requests..."; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-d $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	echo "Collecting heap profile..."; \
	curl -s -o $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-heap-$$timestamp.pprof \
		"http://$(PROFILE_PPROF_ADDR)/debug/pprof/heap"; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	echo ""; \
	echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-heap-$$timestamp.pprof ==="; \
	echo "View: go tool pprof $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-heap-$$timestamp.pprof"

.PHONY: profile-goroutine
profile-goroutine: build ## Collect goroutine profile
	@echo "=== Goroutine Profiling (Go Adapter) ==="
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	echo "Starting adapter with pprof enabled..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		LATTE_DRIVER_PPROF=$(PROFILE_PPROF_ADDR) \
		$(MAKEFILE_DIR)$(BINARY_NAME) & \
	echo $$! > .profile.pid; \
	$(MAKE) --no-print-directory _wait-for-socket SOCKET=$(PROFILE_SOCKET) || exit 1; \
	echo "Creating schema and loading data..."; \
	$(LATTE_BIN) schema --driver-socket $(PROFILE_SOCKET) $(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	$(LATTE_BIN) load --driver-socket $(PROFILE_SOCKET) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	echo "Starting workload in background..."; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-d $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn & \
	workload_pid=$$!; \
	sleep 5; \
	echo "Collecting goroutine profile..."; \
	curl -s -o $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-goroutine-$$timestamp.pprof \
		"http://$(PROFILE_PPROF_ADDR)/debug/pprof/goroutine"; \
	wait $$workload_pid 2>/dev/null || true; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	echo ""; \
	echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-goroutine-$$timestamp.pprof ==="; \
	echo "View: go tool pprof $(PROFILE_OUTPUT_DIR)/go-$(PROFILE_WORKLOAD)-goroutine-$$timestamp.pprof"

.PHONY: _cleanup-profile
_cleanup-profile:
	@echo "Stopping adapter..."
	@if [ -f .profile.pid ]; then \
		kill `cat .profile.pid` 2>/dev/null || true; \
		rm -f .profile.pid; \
	fi
	@rm -f $(PROFILE_SOCKET)

# CI target
.PHONY: ci
ci: lint test build
	@echo "CI checks passed"

# Dependencies
.PHONY: deps
deps:
	go mod download
	go mod tidy
