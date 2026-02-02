# ScyllaDB C# Driver Adapter Makefile
#
# Usage:
#   make build                                    # Build the adapter
#   make lint                                     # Run linters
#   make lint-fix                                 # Auto-fix lint issues
#   make build-docker-image DRIVER_VERSION=3.22.0.1 # Build Docker image with version
#   make push-docker-image DRIVER_VERSION=3.22.0.1  # Push Docker image to registry

# ============================================================================
# Configuration
# ============================================================================

# Directory where this Makefile is located (all paths are relative to this)
MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

# Adapter name (must match directory name)
ADAPTER_NAME := csharp-driver

# Docker image configuration
DOCKER_REGISTRY ?= scylladb
DOCKER_IMAGE_NAME ?= latte-driver-adapters
DRIVER_VERSION ?= latest

# Derived values (format: scylladb/latte-driver-adapters:<adapter-name>-<version>)
DOCKER_TAG := $(ADAPTER_NAME)-$(DRIVER_VERSION)
DOCKER_TAG_LATEST := $(ADAPTER_NAME)-latest
DOCKER_IMAGE := $(DOCKER_REGISTRY)/$(DOCKER_IMAGE_NAME):$(DOCKER_TAG)
DOCKER_IMAGE_LATEST := $(DOCKER_REGISTRY)/$(DOCKER_IMAGE_NAME):$(DOCKER_TAG_LATEST)
DRIVER_IMAGE_FLAG = --cql-adapter-image $(DOCKER_IMAGE)

# .NET configuration
DOTNET_RUNTIME ?= linux-x64
BUILD_CONFIG ?= Release

# Binary output path
OUTPUT_BIN := $(MAKEFILE_DIR)src/bin/$(BUILD_CONFIG)/net8.0/$(DOTNET_RUNTIME)/publish/latte-driver-csharp

# ============================================================================
# Build targets
# ============================================================================

.PHONY: build
build: ## Build the driver adapter (release mode)
	cd $(MAKEFILE_DIR)src && dotnet publish -c Release -r $(DOTNET_RUNTIME) --self-contained true

.PHONY: build-debug
build-debug: ## Build the driver adapter (debug mode)
	cd $(MAKEFILE_DIR)src && dotnet build -c Debug

.PHONY: restore
restore: ## Restore NuGet packages
	cd $(MAKEFILE_DIR)src && dotnet restore

.PHONY: clean
clean: ## Clean build artifacts
	cd $(MAKEFILE_DIR)src && dotnet clean
	rm -rf $(MAKEFILE_DIR)src/bin $(MAKEFILE_DIR)src/obj

# ============================================================================
# Lint targets
# ============================================================================

.PHONY: lint
lint: ## Run all linters (dotnet format check)
	cd $(MAKEFILE_DIR)src && dotnet format --verify-no-changes

.PHONY: lint-fix
lint-fix: ## Auto-fix lint issues where possible
	cd $(MAKEFILE_DIR)src && dotnet format

.PHONY: fmt
fmt: lint-fix ## Alias for lint-fix

.PHONY: fmt-check
fmt-check: lint ## Alias for lint

# ============================================================================
# Test targets
# ============================================================================

.PHONY: test
test: ## Run unit tests
	cd $(MAKEFILE_DIR)src && dotnet test

# ============================================================================
# Integration test & benchmark targets
# ============================================================================

# Common configuration
SCYLLA_CONTACT_POINTS ?= 127.0.0.1:9042
LATTE_BIN ?= $(MAKEFILE_DIR)../../target/release/latte
WORKLOADS_DIR ?= $(MAKEFILE_DIR)../.workloads

# Integration test configuration (quick validation, iteration-limited)
INTEGRATION_SOCKET ?= /tmp/latte-csharp-integration-test.sock
INTEGRATION_ROW_COUNT ?= 100
INTEGRATION_ITERATION_COUNT ?= 10

# Benchmark configuration (performance testing, time-limited)
BENCHMARK_SOCKET ?= /tmp/latte-csharp-benchmark-test.sock
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
	@echo "=== $(TEST_MODE) Test Suite ==="
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
	@echo "=== $(TEST_MODE) Test: $(SUITE_NAME) ==="
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
	@for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do \
		if [ -S $(SOCKET) ]; then \
			echo "Adapter ready."; \
			exit 0; \
		fi; \
		if [ $$i -eq 15 ]; then \
			echo "Error: Adapter failed to start"; \
			kill `cat .test.pid` 2>/dev/null || true; \
			rm -f .test.pid; \
			exit 1; \
		fi; \
		sleep 1; \
	done


# ============================================================================
# Profiling targets
# ============================================================================

# Profiling configuration
PROFILE_SOCKET ?= /tmp/latte-csharp-profile.sock
PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= primitives
PROFILE_OUTPUT_DIR ?= $(MAKEFILE_DIR)../profiles
PROFILE_WARMUP_COUNT ?= 1000

.PHONY: profile
profile: profile-cpu ## Alias for profile-cpu

.PHONY: profile-cpu
profile-cpu: build ## Run CPU profiling with dotnet-trace
	@echo "=== CPU Profiling (C# Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT) (warmup: $(PROFILE_WARMUP_COUNT))"
	@echo ""
	@if ! command -v dotnet-trace >/dev/null 2>&1; then \
		echo "============================================================"; \
		echo "ERROR: 'dotnet-trace' not found"; \
		echo ""; \
		echo "Install dotnet-trace:"; \
		echo "  dotnet tool install --global dotnet-trace"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	trace_file=$(PROFILE_OUTPUT_DIR)/csharp-$(PROFILE_WORKLOAD)-$$timestamp.nettrace; \
	echo "Starting adapter..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		$(OUTPUT_BIN) & \
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
	adapter_pid=$$(cat .profile.pid); \
	echo "Starting trace collection (pid: $$adapter_pid)..."; \
	dotnet-trace collect --process-id $$adapter_pid --output $$trace_file & \
	trace_pid=$$!; \
	sleep 2; \
	echo "Running $(PROFILE_REQUEST_COUNT) requests..."; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-d $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn || true; \
	sleep 1; \
	echo "Stopping trace..."; \
	kill -INT $$trace_pid 2>/dev/null || true; \
	wait $$trace_pid 2>/dev/null || true; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	if [ -f $$trace_file ]; then \
		echo ""; \
		echo "=== Profile saved to: $$trace_file ==="; \
		echo ""; \
		echo "View options:"; \
		echo "  1. Convert to speedscope: dotnet-trace convert $$trace_file --format Speedscope"; \
		echo "  2. Open in PerfView (Windows)"; \
		echo "  3. Open in Visual Studio"; \
	else \
		echo "WARNING: Trace file not generated"; \
	fi

.PHONY: profile-gc
profile-gc: build ## Run GC profiling with dotnet-counters
	@echo "=== GC Profiling (C# Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT)"
	@echo ""
	@if ! command -v dotnet-counters >/dev/null 2>&1; then \
		echo "============================================================"; \
		echo "ERROR: 'dotnet-counters' not found"; \
		echo ""; \
		echo "Install dotnet-counters:"; \
		echo "  dotnet tool install --global dotnet-counters"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	counters_file=$(PROFILE_OUTPUT_DIR)/csharp-$(PROFILE_WORKLOAD)-gc-$$timestamp.csv; \
	echo "Starting adapter..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		$(OUTPUT_BIN) & \
	echo $$! > .profile.pid; \
	$(MAKE) --no-print-directory _wait-for-socket SOCKET=$(PROFILE_SOCKET) || exit 1; \
	echo "Creating schema and loading data..."; \
	$(LATTE_BIN) schema --driver-socket $(PROFILE_SOCKET) $(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	$(LATTE_BIN) load --driver-socket $(PROFILE_SOCKET) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	adapter_pid=$$(cat .profile.pid); \
	echo "Starting GC counter collection (pid: $$adapter_pid)..."; \
	dotnet-counters collect --process-id $$adapter_pid \
		--counters System.Runtime[gc-heap-size,gen-0-gc-count,gen-1-gc-count,gen-2-gc-count,time-in-gc,alloc-rate] \
		--format csv --output $$counters_file & \
	counters_pid=$$!; \
	sleep 2; \
	echo "Running $(PROFILE_REQUEST_COUNT) requests..."; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-d $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn || true; \
	sleep 1; \
	kill -INT $$counters_pid 2>/dev/null || true; \
	wait $$counters_pid 2>/dev/null || true; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	if [ -f $$counters_file ]; then \
		echo ""; \
		echo "=== GC stats saved to: $$counters_file ==="; \
		echo "View with: cat $$counters_file | column -t -s,"; \
	fi

.PHONY: profile-speedscope
profile-speedscope: ## Convert latest .nettrace to speedscope format
	@latest=$$(ls -t $(PROFILE_OUTPUT_DIR)/csharp-*.nettrace 2>/dev/null | head -1); \
	if [ -z "$$latest" ]; then \
		echo "No .nettrace files found in $(PROFILE_OUTPUT_DIR)"; \
		echo "Run 'make profile-cpu' first."; \
		exit 1; \
	fi; \
	echo "Converting: $$latest"; \
	dotnet-trace convert "$$latest" --format Speedscope; \
	speedscope_file="$${latest%.nettrace}.speedscope.json"; \
	echo ""; \
	echo "=== Converted to: $$speedscope_file ==="; \
	echo "View at: https://www.speedscope.app/"

.PHONY: _cleanup-profile
_cleanup-profile:
	@echo "Stopping adapter..."
	@if [ -f .profile.pid ]; then \
		kill `cat .profile.pid` 2>/dev/null || true; \
		rm -f .profile.pid; \
	fi
	@rm -f $(PROFILE_SOCKET)

# ============================================================================
# Docker targets
# ============================================================================

.PHONY: _check-docker-image
_check-docker-image:
	@if docker image inspect $(DOCKER_IMAGE) >/dev/null 2>&1; then \
		echo "Docker image $(DOCKER_IMAGE) already exists, skipping build"; \
	else \
		$(MAKE) --no-print-directory build-docker-image; \
	fi

.PHONY: build-docker-image
build-docker-image: ## Build Docker image (use DRIVER_VERSION=x.y.z to set version)
	@echo "Building Docker image $(DOCKER_IMAGE)..."
	docker build \
		--build-arg DRIVER_VERSION=$(DRIVER_VERSION) \
		-t $(DOCKER_IMAGE) \
		.
	@if [ "$(DRIVER_VERSION)" != "latest" ]; then \
		echo "Also tagging as latest..."; \
		docker tag $(DOCKER_IMAGE) $(DOCKER_IMAGE_LATEST); \
	fi
	@echo "Built: $(DOCKER_IMAGE)"

.PHONY: push-docker-image
push-docker-image: ## Push Docker image to registry (use DRIVER_VERSION=x.y.z)
	@echo "Pushing $(DOCKER_IMAGE)..."
	docker push $(DOCKER_IMAGE)
	@if [ "$(DRIVER_VERSION)" != "latest" ]; then \
		echo "Also pushing latest tag..."; \
		docker push $(DOCKER_IMAGE_LATEST); \
	fi
	@echo "Pushed: $(DOCKER_IMAGE)"

.PHONY: docker-run
docker-run: ## Run the driver adapter container locally
	@mkdir -p /tmp/latte-driver
	docker run --rm -it \
		--network host \
		-v /tmp/latte-driver:/sockets \
		-e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
		$(DOCKER_IMAGE)

# ============================================================================
# CI targets
# ============================================================================

.PHONY: ci
ci: lint test build ## Run all CI checks

# ============================================================================
# Help
# ============================================================================

.PHONY: help
help: ## Show this help message
	@echo "ScyllaDB C# Driver Adapter Makefile"
	@echo ""
	@echo "Usage: make [target] [VARIABLE=value]"
	@echo ""
	@echo "Variables:"
	@echo "  DRIVER_VERSION          Version tag for Docker image (default: latest)"
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
	@echo "Profiling Variables:"
	@echo "  PROFILE_REQUEST_COUNT   Number of requests for profiling (default: 10000)"
	@echo "  PROFILE_WORKLOAD        Workload to profile (default: primitives)"
	@echo "  PROFILE_OUTPUT_DIR      Directory for profile output (default: ../profiles)"
	@echo ""
	@echo "Docker image: $(DOCKER_IMAGE)"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-20s %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
