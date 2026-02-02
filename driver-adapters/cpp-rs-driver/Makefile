# cpp-rs-driver Adapter Makefile
#
# Usage:
#   make build                                    # Build the adapter
#   make lint                                     # Run linters
#   make lint-fix                                 # Auto-fix lint issues
#   make build-docker-image DRIVER_VERSION=1.0.0 # Build Docker image with version
#   make push-docker-image DRIVER_VERSION=1.0.0  # Push Docker image to registry

# ============================================================================
# Configuration
# ============================================================================

# Adapter name (must match directory name)
ADAPTER_NAME := cpp-rs-driver

# Docker image configuration
DOCKER_REGISTRY ?= scylladb
DOCKER_IMAGE_NAME ?= latte-driver-adapters
DRIVER_VERSION ?= latest

# Derived values (format: scylladb/latte-driver-adapters:<adapter-name>-<version>)
DOCKER_TAG := $(ADAPTER_NAME)-$(DRIVER_VERSION)
DOCKER_TAG_LATEST := $(ADAPTER_NAME)-latest
DOCKER_IMAGE := $(DOCKER_REGISTRY)/$(DOCKER_IMAGE_NAME):$(DOCKER_TAG)
DOCKER_IMAGE_LATEST := $(DOCKER_REGISTRY)/$(DOCKER_IMAGE_NAME):$(DOCKER_TAG_LATEST)

# Build configuration
BUILD_DIR := build
BUILD_TYPE ?= Release

# ============================================================================
# Build targets
# ============================================================================

.PHONY: build
build: ## Build the driver adapter (release mode)
	@mkdir -p $(BUILD_DIR)
	@cd $(BUILD_DIR) && cmake -DCMAKE_BUILD_TYPE=Release .. && make -j$$(nproc)
	@mkdir -p target/release
	@cp $(BUILD_DIR)/latte-cpp-rs-driver target/release/

.PHONY: build-debug
build-debug: ## Build the driver adapter (debug mode)
	@mkdir -p $(BUILD_DIR)
	@cd $(BUILD_DIR) && cmake -DCMAKE_BUILD_TYPE=Debug .. && make -j$$(nproc)
	@mkdir -p target/debug
	@cp $(BUILD_DIR)/latte-cpp-rs-driver target/debug/

.PHONY: clean
clean: ## Clean build artifacts
	@rm -rf $(BUILD_DIR) target

# ============================================================================
# Lint targets
# ============================================================================

.PHONY: lint
lint: ## Run all linters (clang-tidy + clang-format check)
	@echo "Running clang-format check..."
	@find src -name '*.cpp' -o -name '*.h' | xargs clang-format --dry-run --Werror 2>&1 || \
		(echo "Format check failed. Run 'make lint-fix' to fix."; exit 1)
	@echo "Running clang-tidy..."
	@if [ -f $(BUILD_DIR)/compile_commands.json ]; then \
		find src -name '*.cpp' | xargs clang-tidy -p $(BUILD_DIR) 2>&1 || true; \
	else \
		echo "Note: Build first to enable clang-tidy (needs compile_commands.json)"; \
	fi
	@echo "Lint passed!"

.PHONY: lint-fix
lint-fix: ## Auto-fix lint issues where possible
	@echo "Running clang-format..."
	@find src -name '*.cpp' -o -name '*.h' | xargs clang-format -i
	@echo "Format fixed!"

.PHONY: fmt
fmt: lint-fix ## Format code (alias for lint-fix)

.PHONY: fmt-check
fmt-check: ## Check code formatting
	@find src -name '*.cpp' -o -name '*.h' | xargs clang-format --dry-run --Werror

# ============================================================================
# Test targets
# ============================================================================

TEST_BUILD_DIR := build-test

.PHONY: test
test: ## Run unit tests
	@echo "Building and running unit tests..."
	@mkdir -p $(TEST_BUILD_DIR)
	@echo "Compiling test_protocol..."
	@g++ -std=c++17 -I src -o $(TEST_BUILD_DIR)/test_protocol \
		tests/test_protocol.cpp src/protocol.cpp
	@echo "Running test_protocol..."
	@$(TEST_BUILD_DIR)/test_protocol
	@echo ""
	@echo "Compiling test_types..."
	@g++ -std=c++17 -I src -o $(TEST_BUILD_DIR)/test_types \
		tests/test_types.cpp
	@echo "Running test_types..."
	@$(TEST_BUILD_DIR)/test_types
	@echo ""
	@echo "All unit tests passed!"

.PHONY: test-verbose
test-verbose: test ## Run tests with verbose output

# ============================================================================
# Integration test & benchmark targets
# ============================================================================

# Common configuration
SCYLLA_CONTACT_POINTS ?= 127.0.0.1:9042
LATTE_BIN ?= latte
WORKLOADS_DIR ?= ../workloads

# Integration test configuration (quick validation)
INTEGRATION_SOCKET ?= /tmp/latte-cpp-integration-test.sock
INTEGRATION_ROW_COUNT ?= 100
INTEGRATION_DURATION ?= 10s

# Benchmark configuration (performance testing)
BENCHMARK_SOCKET ?= /tmp/latte-cpp-benchmark-test.sock
BENCHMARK_ROW_COUNT ?= 10000
BENCHMARK_DURATION ?= 5m

# ----------------------------------------------------------------------------
# Integration tests (quick validation, ~10 seconds per suite)
# ----------------------------------------------------------------------------

.PHONY: test-integration
test-integration: build ## Run all integration test suites
	@$(MAKE) --no-print-directory _run-all-suites \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		DURATION=$(INTEGRATION_DURATION) \
		RUN_FUNCTION=validate

.PHONY: test-integration-primitives
test-integration-primitives: build ## Run primitives integration test suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		DURATION=$(INTEGRATION_DURATION) \
		RUN_FUNCTION=validate \
		SUITE_NAME=primitives \
		SUITE_FILE=$(WORKLOADS_DIR)/primitives.rn

.PHONY: test-integration-collections
test-integration-collections: build ## Run collections integration test suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		DURATION=$(INTEGRATION_DURATION) \
		RUN_FUNCTION=validate \
		SUITE_NAME=collections \
		SUITE_FILE=$(WORKLOADS_DIR)/collections.rn

.PHONY: test-integration-vectors
test-integration-vectors: build ## Run vectors integration test suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		DURATION=$(INTEGRATION_DURATION) \
		RUN_FUNCTION=validate \
		SUITE_NAME=vectors \
		SUITE_FILE=$(WORKLOADS_DIR)/vectors.rn

# ----------------------------------------------------------------------------
# Benchmark tests (performance testing, 5 minutes per suite)
# ----------------------------------------------------------------------------

.PHONY: test-benchmark
test-benchmark: build ## Run all benchmark test suites
	@$(MAKE) --no-print-directory _run-all-suites \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run

.PHONY: test-benchmark-primitives
test-benchmark-primitives: build ## Run primitives benchmark suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run \
		SUITE_NAME=primitives \
		SUITE_FILE=$(WORKLOADS_DIR)/primitives.rn

.PHONY: test-benchmark-collections
test-benchmark-collections: build ## Run collections benchmark suite
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=benchmark \
		SOCKET=$(BENCHMARK_SOCKET) \
		ROW_COUNT=$(BENCHMARK_ROW_COUNT) \
		DURATION=$(BENCHMARK_DURATION) \
		RUN_FUNCTION=run \
		SUITE_NAME=collections \
		SUITE_FILE=$(WORKLOADS_DIR)/collections.rn

.PHONY: test-benchmark-vectors
test-benchmark-vectors: build ## Run vectors benchmark suite
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
	@echo "Rows: $(ROW_COUNT), Duration: $(DURATION)"
	@echo ""
	@if [ ! -x "$(LATTE_BIN)" ] && ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: latte binary not found"; \
		echo "  LATTE_BIN=$(LATTE_BIN)"; \
		echo ""; \
		echo "To fix, run from driver-adapters directory:"; \
		echo "  make test-integration   (will auto-build latte)"; \
		echo ""; \
		echo "Or build manually:"; \
		echo "  cd ../.. && make build"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@rm -f $(SOCKET)
	@echo "Starting adapter..."
	@LATTE_DRIVER_SOCKET=$(SOCKET) \
		LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
		./target/release/latte-cpp-rs-driver & \
		echo $$! > .test.pid
	@$(MAKE) --no-print-directory _wait-for-socket SOCKET=$(SOCKET) || exit 1
	@echo ""
	@echo "=== Suite 1/3: Primitives ==="
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=primitives SUITE_FILE=$(WORKLOADS_DIR)/primitives.rn RUN_FUNCTION=$(RUN_FUNCTION) || { \
		$(MAKE) --no-print-directory _cleanup-test SOCKET=$(SOCKET); \
		exit 1; \
	}
	@echo ""
	@echo "=== Suite 2/3: Collections ==="
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=collections SUITE_FILE=$(WORKLOADS_DIR)/collections.rn RUN_FUNCTION=$(RUN_FUNCTION) || { \
		$(MAKE) --no-print-directory _cleanup-test SOCKET=$(SOCKET); \
		exit 1; \
	}
	@echo ""
	@echo "=== Suite 3/3: Vectors ==="
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=vectors SUITE_FILE=$(WORKLOADS_DIR)/vectors.rn RUN_FUNCTION=$(RUN_FUNCTION) || { \
		$(MAKE) --no-print-directory _cleanup-test SOCKET=$(SOCKET); \
		exit 1; \
	}
	@$(MAKE) --no-print-directory _cleanup-test SOCKET=$(SOCKET)
	@echo ""
	@echo "=== All $(TEST_MODE) tests PASSED ==="

.PHONY: _run-single-suite
_run-single-suite:
	@echo "=== $(TEST_MODE) Test: $(SUITE_NAME) ==="
	@echo "Contact points: $(SCYLLA_CONTACT_POINTS)"
	@echo "Socket: $(SOCKET)"
	@echo "Rows: $(ROW_COUNT), Duration: $(DURATION)"
	@echo ""
	@if [ ! -x "$(LATTE_BIN)" ] && ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: latte binary not found"; \
		echo "  LATTE_BIN=$(LATTE_BIN)"; \
		echo ""; \
		echo "To fix, run from driver-adapters directory:"; \
		echo "  make test-integration   (will auto-build latte)"; \
		echo ""; \
		echo "Or build manually:"; \
		echo "  cd ../.. && make build"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@rm -f $(SOCKET)
	@echo "Starting adapter..."
	@LATTE_DRIVER_SOCKET=$(SOCKET) \
		LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
		./target/release/latte-cpp-rs-driver & \
		echo $$! > .test.pid
	@$(MAKE) --no-print-directory _wait-for-socket SOCKET=$(SOCKET) || exit 1
	@$(MAKE) --no-print-directory _run-suite SUITE_NAME=$(SUITE_NAME) SUITE_FILE=$(SUITE_FILE) RUN_FUNCTION=$(RUN_FUNCTION) || { \
		$(MAKE) --no-print-directory _cleanup-test SOCKET=$(SOCKET); \
		exit 1; \
	}
	@$(MAKE) --no-print-directory _cleanup-test SOCKET=$(SOCKET)
	@echo ""
	@echo "=== $(SUITE_NAME) $(TEST_MODE) test PASSED ==="

.PHONY: _run-suite
_run-suite:
	@if [ ! -f "$(SUITE_FILE)" ]; then \
		echo "Error: Workload file not found: $(SUITE_FILE)"; \
		exit 1; \
	fi
	@echo "Creating schema..."
	@$(LATTE_BIN) schema --driver-socket $(SOCKET) $(SUITE_FILE)
	@echo "Loading data ($(ROW_COUNT) rows)..."
	@$(LATTE_BIN) load --driver-socket $(SOCKET) \
		-P row_count=$(ROW_COUNT) \
		$(SUITE_FILE)
	@echo "Running for $(DURATION)..."
	@$(LATTE_BIN) run --driver-socket $(SOCKET) \
		-d $(DURATION) \
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

.PHONY: _cleanup-test
_cleanup-test:
	@echo "Stopping adapter..."
	@if [ -f .test.pid ]; then \
		kill `cat .test.pid` 2>/dev/null || true; \
		rm -f .test.pid; \
	fi
	@rm -f $(SOCKET)

# ============================================================================
# Profiling targets
# ============================================================================

# Profiling configuration
PROFILE_SOCKET ?= /tmp/latte-cpp-profile.sock
PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= primitives
PROFILE_OUTPUT_DIR ?= ../profiles
PROFILE_WARMUP_COUNT ?= 1000

.PHONY: profile
profile: build ## Run CPU profiling with perf + flamegraph
	@echo "=== CPU Profiling (cpp-rs-driver Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT) (warmup: $(PROFILE_WARMUP_COUNT))"
	@echo ""
	@if ! command -v perf >/dev/null 2>&1; then \
		echo "============================================================"; \
		echo "ERROR: 'perf' not found"; \
		echo ""; \
		echo "Install perf:"; \
		echo "  Ubuntu/Debian: sudo apt install linux-tools-common linux-tools-generic"; \
		echo "  Fedora: sudo dnf install perf"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	echo "Starting adapter..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
		./target/release/latte-cpp-rs-driver & \
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
		-n $(PROFILE_WARMUP_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn >/dev/null 2>&1 || true; \
	echo ""; \
	echo "Profiling $(PROFILE_REQUEST_COUNT) requests..."; \
	adapter_pid=$$(cat .profile.pid); \
	perf record -g -p $$adapter_pid -o perf.data & \
	perf_pid=$$!; \
	sleep 1; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-n $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	sleep 1; \
	kill -INT $$perf_pid 2>/dev/null || true; \
	wait $$perf_pid 2>/dev/null || true; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	if [ -f perf.data ]; then \
		if command -v flamegraph >/dev/null 2>&1; then \
			echo "Generating flamegraph..."; \
			perf script -i perf.data | flamegraph > $(PROFILE_OUTPUT_DIR)/cpp-$(PROFILE_WORKLOAD)-$$timestamp.svg; \
			rm -f perf.data; \
			echo ""; \
			echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/cpp-$(PROFILE_WORKLOAD)-$$timestamp.svg ==="; \
		else \
			mv perf.data $(PROFILE_OUTPUT_DIR)/cpp-$(PROFILE_WORKLOAD)-$$timestamp.perf.data; \
			echo ""; \
			echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/cpp-$(PROFILE_WORKLOAD)-$$timestamp.perf.data ==="; \
		fi; \
	fi

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
		-e LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
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
	@echo "cpp-rs-driver Adapter Makefile"
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
	@echo "  INTEGRATION_ROW_COUNT   Rows for integration tests (default: 100)"
	@echo "  INTEGRATION_DURATION    Duration for integration tests (default: 10s)"
	@echo "  BENCHMARK_ROW_COUNT     Rows for benchmarks (default: 10000)"
	@echo "  BENCHMARK_DURATION      Duration for benchmarks (default: 5m)"
	@echo ""
	@echo "Profiling Variables:"
	@echo "  PROFILE_REQUEST_COUNT   Number of requests for profiling (default: 10000)"
	@echo "  PROFILE_WORKLOAD        Workload to profile (default: primitives)"
	@echo "  PROFILE_OUTPUT_DIR      Directory for profile output (default: ../profiles)"
	@echo ""
	@echo "Docker image: $(DOCKER_IMAGE)"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-28s %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
