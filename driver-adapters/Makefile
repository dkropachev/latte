# Driver Adapters Makefile
#
# Orchestrates benchmark and integration tests across all driver adapters.
#
# Usage:
#   make test-benchmark                                    # Run benchmarks for all adapters/workloads
#   make test-benchmark ADAPTERS=gocql WORKLOADS=basic     # Run specific adapter/workload
#   make test-benchmark-generate-chart                     # Regenerate chart from existing reports
#   make test-integration                                  # Run integration tests for all adapters
#   make build                                             # Build all adapters

# ============================================================================
# Configuration
# ============================================================================

# List of all adapters (subdirectories with Makefiles)
ADAPTERS := scylla-rust-driver gocql csharp-driver python-driver java-driver-4 java-driver-3 cpp-rs-driver cpp-driver

# Workloads to skip for specific adapters (adapter:workload pairs)
# See README.md "Workload Support Matrix" for details on why certain workloads are skipped.
# cpp-driver: collections fails due to nested collection types (UDTs, tuples, list of lists)
SKIP_WORKLOADS := cpp-driver:collections

# Test configuration (can be overridden)
SCYLLA_CONTACT_POINTS ?= 127.0.0.1:9042
BENCHMARK_ROW_COUNT ?= 10000

# Latte binary: use from PATH, or fallback to local build (absolute path)
LATTE_BIN ?= $(shell command -v latte 2>/dev/null || echo "$(CURDIR)/../target/release/latte")

# Docker configuration for ScyllaDB
SCYLLA_DOCKER_IMAGE ?= scylladb/scylla:latest
SCYLLA_CONTAINER_NAME ?= latte-scylla-test
BENCHMARK_DURATION ?= 5s
INTEGRATION_ROW_COUNT ?= 100
INTEGRATION_DURATION ?= 10s

# Export variables for sub-makes
export SCYLLA_CONTACT_POINTS
export LATTE_BIN
export BENCHMARK_ROW_COUNT
export BENCHMARK_DURATION
export INTEGRATION_ROW_COUNT
export INTEGRATION_DURATION

# ============================================================================
# Build targets
# ============================================================================

.PHONY: build
build: ## Build all driver adapters
	@echo "=== Building all driver adapters ==="
	@for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Building $$adapter..."; \
		$(MAKE) -C $$adapter build || exit 1; \
	done
	@echo ""
	@echo "=== All adapters built ==="

.PHONY: build-scylla
build-scylla: ## Build scylla-rust-driver adapter
	$(MAKE) -C scylla-rust-driver build

.PHONY: build-gocql
build-gocql: ## Build gocql adapter
	$(MAKE) -C gocql build

.PHONY: build-java
build-java: ## Build java-driver-4 adapter
	$(MAKE) -C java-driver-4 build

.PHONY: build-java3
build-java3: ## Build java-driver-3 adapter
	$(MAKE) -C java-driver-3 build

.PHONY: build-cpp
build-cpp: ## Build cpp-rs-driver adapter
	$(MAKE) -C cpp-rs-driver build

.PHONY: build-cpp-driver
build-cpp-driver: ## Build cpp-driver adapter (deprecated driver)
	$(MAKE) -C cpp-driver build

.PHONY: build-docker-image
build-docker-image: ## Build Docker images for all adapters
	@echo "=== Building Docker images for all driver adapters ==="
	@for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Building Docker image for $$adapter..."; \
		$(MAKE) -C $$adapter build-docker-image || exit 1; \
	done
	@echo ""
	@echo "=== All adapter Docker images built ==="

# ============================================================================
# Benchmark targets
# ============================================================================

# Output directory for benchmark reports
BENCHMARK_REPORTS_DIR ?= .benchmark-reports
BENCHMARK_WORKLOAD ?= primitives
BENCHMARK_WORKLOADS ?= basic primitives collections vectors
CHART_PERCENTILE ?= p99
CHART_OUTPUT ?= $(BENCHMARK_REPORTS_DIR)/benchmark_latencies.png
CHART_LOG_SCALE ?= true

# ----------------------------------------------------------------------------
# Dependency checking for chart generation
# ----------------------------------------------------------------------------

# Python interpreter: prefer 'python' (which respects virtual environments) over 'python3'
PYTHON ?= $(shell command -v python 2>/dev/null || command -v python3 2>/dev/null)

.PHONY: _check-python-deps
_check-python-deps:
	@missing=""; \
	if [ -z "$(PYTHON)" ]; then \
		missing="$$missing  - python not found\n"; \
	else \
		if ! $(PYTHON) -c "import matplotlib" 2>/dev/null; then \
			missing="$$missing  - Python package 'matplotlib' not installed\n"; \
		fi; \
		if ! $(PYTHON) -c "import numpy" 2>/dev/null; then \
			missing="$$missing  - Python package 'numpy' not installed\n"; \
		fi; \
	fi; \
	if [ -n "$$missing" ]; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: Missing Python dependencies:"; \
		printf "$$missing"; \
		echo "Using Python: $(PYTHON)"; \
		echo ""; \
		echo "To fix, install Python packages:"; \
		echo "  pip install matplotlib numpy"; \
		echo "============================================================"; \
		exit 1; \
	fi

.PHONY: _check-latte-dep
_check-latte-dep:
	@if [ ! -x "$(LATTE_BIN)" ] && ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "latte binary not found, building it..."; \
		echo "============================================================"; \
		echo ""; \
		$(MAKE) -C .. build || { \
			echo ""; \
			echo "============================================================"; \
			echo "ERROR: Failed to build latte"; \
			echo ""; \
			echo "Try building manually:"; \
			echo "  cd .. && make build"; \
			echo "============================================================"; \
			exit 1; \
		}; \
		echo ""; \
		echo "latte built successfully."; \
		echo ""; \
	fi
	@if [ ! -x "$(LATTE_BIN)" ] && ! command -v $(LATTE_BIN) >/dev/null 2>&1; then \
		echo "============================================================"; \
		echo "ERROR: latte binary still not found after build"; \
		echo "  Expected at: $(LATTE_BIN)"; \
		echo "============================================================"; \
		exit 1; \
	fi

.PHONY: _check-chart-deps
_check-chart-deps: _check-latte-dep _check-python-deps
	@echo "All dependencies satisfied."
	@echo ""

# ----------------------------------------------------------------------------
# Database management
# ----------------------------------------------------------------------------

.PHONY: _check-docker
_check-docker:
	@if ! command -v docker >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: Docker not found"; \
		echo ""; \
		echo "Please install Docker: https://docs.docker.com/get-docker/"; \
		echo "============================================================"; \
		exit 1; \
	fi

.PHONY: _check-db-connection
_check-db-connection:
	@# Check if database is reachable using nc or timeout with bash
	@host=$$(echo $(SCYLLA_CONTACT_POINTS) | cut -d: -f1); \
	port=$$(echo $(SCYLLA_CONTACT_POINTS) | cut -d: -f2); \
	if command -v nc >/dev/null 2>&1; then \
		nc -z $$host $$port 2>/dev/null; \
	else \
		(echo >/dev/tcp/$$host/$$port) 2>/dev/null; \
	fi

.PHONY: start-scylla
start-scylla: _check-docker ## Start ScyllaDB in Docker for testing
	@if $(MAKE) --no-print-directory _check-cql-ready 2>/dev/null; then \
		echo "Database already running at $(SCYLLA_CONTACT_POINTS)"; \
	else \
		echo "Starting ScyllaDB container..."; \
		if docker ps -a --format '{{.Names}}' | grep -q "^$(SCYLLA_CONTAINER_NAME)$$"; then \
			echo "Container $(SCYLLA_CONTAINER_NAME) exists, starting it..."; \
			docker start $(SCYLLA_CONTAINER_NAME); \
		else \
			echo "Creating new container $(SCYLLA_CONTAINER_NAME)..."; \
			docker run -d --name $(SCYLLA_CONTAINER_NAME) \
				-p 9042:9042 \
				$(SCYLLA_DOCKER_IMAGE) \
				--smp 2 --memory 2G --overprovisioned 1; \
		fi; \
		echo "Waiting for ScyllaDB CQL to be ready (this may take 30-60 seconds)..."; \
		for i in $$(seq 1 90); do \
			if $(MAKE) --no-print-directory _check-cql-ready 2>/dev/null; then \
				echo ""; \
				echo "ScyllaDB is ready!"; \
				exit 0; \
			fi; \
			printf "."; \
			sleep 2; \
		done; \
		echo ""; \
		echo "ERROR: ScyllaDB failed to start within 180 seconds"; \
		docker logs $(SCYLLA_CONTAINER_NAME) --tail 20; \
		exit 1; \
	fi

.PHONY: _check-cql-ready
_check-cql-ready:
	@# Check if CQL is actually responding, not just port open
	@docker exec $(SCYLLA_CONTAINER_NAME) cqlsh -e "SELECT now() FROM system.local" >/dev/null 2>&1

.PHONY: stop-scylla
stop-scylla: ## Stop ScyllaDB Docker container
	@if docker ps --format '{{.Names}}' | grep -q "^$(SCYLLA_CONTAINER_NAME)$$"; then \
		echo "Stopping ScyllaDB container..."; \
		docker stop $(SCYLLA_CONTAINER_NAME); \
	else \
		echo "ScyllaDB container not running."; \
	fi

.PHONY: clean-scylla
clean-scylla: stop-scylla ## Stop and remove ScyllaDB Docker container
	@if docker ps -a --format '{{.Names}}' | grep -q "^$(SCYLLA_CONTAINER_NAME)$$"; then \
		echo "Removing ScyllaDB container..."; \
		docker rm $(SCYLLA_CONTAINER_NAME); \
	fi

.PHONY: _ensure-db
_ensure-db:
	@if ! $(MAKE) --no-print-directory _check-cql-ready 2>/dev/null; then \
		echo "No database ready at $(SCYLLA_CONTACT_POINTS), starting ScyllaDB..."; \
		$(MAKE) --no-print-directory start-scylla; \
	fi

.PHONY: test-benchmark
test-benchmark: _check-chart-deps ## Run benchmarks (use ADAPTERS=a,b WORKLOADS=x,y to filter)
	@# Normalize comma-separated lists to space-separated
	@adapters=$$(echo "$(ADAPTERS)" | tr ',' ' '); \
	workloads=$$(echo "$(WORKLOADS)" | tr ',' ' '); \
	: $${workloads:=$(BENCHMARK_WORKLOADS)}; \
	echo "============================================================"; \
	echo "=== Benchmark with Chart Generation ==="; \
	echo "============================================================"; \
	echo "Contact points: $(SCYLLA_CONTACT_POINTS)"; \
	echo "Rows: $(BENCHMARK_ROW_COUNT), Duration: $(BENCHMARK_DURATION)"; \
	echo "Workloads: $$workloads"; \
	echo "Adapters: $$adapters"; \
	echo "Output: $(CHART_OUTPUT)"; \
	echo ""; \
	mkdir -p $(BENCHMARK_REPORTS_DIR); \
	echo ">>> Building adapters..."; \
	for adapter in $$adapters; do \
		$(MAKE) --no-print-directory -C $$adapter build || exit 1; \
	done; \
	failed_benchmarks=""; \
	for workload in $$workloads; do \
		echo ""; \
		echo "############################################################"; \
		echo "### Workload: $$workload"; \
		echo "############################################################"; \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Running pure latte benchmark for $$workload..."; \
		echo "============================================================"; \
		$(LATTE_BIN) schema .workloads/$$workload.rn $(SCYLLA_CONTACT_POINTS) || { \
			echo ">>> FAILED: pure latte schema for $$workload"; \
			failed_benchmarks="$$failed_benchmarks $$workload(pure-schema)"; \
			continue; \
		}; \
		$(LATTE_BIN) load .workloads/$$workload.rn $(SCYLLA_CONTACT_POINTS) \
			-P row_count=$(BENCHMARK_ROW_COUNT) || { \
			echo ">>> FAILED: pure latte load for $$workload"; \
			failed_benchmarks="$$failed_benchmarks $$workload(pure-load)"; \
			continue; \
		}; \
		$(LATTE_BIN) run .workloads/$$workload.rn $(SCYLLA_CONTACT_POINTS) \
			-d $(BENCHMARK_DURATION) \
			-P row_count=$(BENCHMARK_ROW_COUNT) \
			--generate-report \
			-o $(BENCHMARK_REPORTS_DIR)/pure-latte-$$workload.json || { \
			echo ">>> FAILED: pure latte run for $$workload"; \
			failed_benchmarks="$$failed_benchmarks $$workload(pure-run)"; \
			continue; \
		}; \
		echo ">>> Pure latte benchmark for $$workload DONE"; \
		for adapter in $$adapters; do \
			skip_pair="$$adapter:$$workload"; \
			if echo "$(SKIP_WORKLOADS)" | grep -q "$$skip_pair"; then \
				echo ""; \
				echo "============================================================"; \
				echo ">>> SKIPPING $$adapter for $$workload (not supported)"; \
				echo "============================================================"; \
				continue; \
			fi; \
			echo ""; \
			echo "============================================================"; \
			echo ">>> Benchmarking $$adapter for $$workload..."; \
			echo "============================================================"; \
			report_file="$(BENCHMARK_REPORTS_DIR)/$$adapter-$$workload.json"; \
			if $(MAKE) --no-print-directory _run-adapter-benchmark \
				ADAPTER=$$adapter \
				BENCHMARK_WORKLOAD=$$workload \
				REPORT_FILE=$$report_file; then \
				echo ">>> $$adapter benchmark for $$workload PASSED"; \
			else \
				echo ">>> $$adapter benchmark for $$workload FAILED"; \
				failed_benchmarks="$$failed_benchmarks $$workload($$adapter)"; \
			fi; \
		done; \
	done; \
	echo ""; \
	echo "############################################################"; \
	echo "### Generating Combined Chart"; \
	echo "############################################################"; \
	if [ -n "$$failed_benchmarks" ]; then \
		echo "Warning: Some benchmarks failed:$$failed_benchmarks"; \
	fi; \
	$(MAKE) --no-print-directory test-benchmark-generate-chart WORKLOADS="$$workloads" ADAPTERS="$$adapters"

.PHONY: _run-adapter-benchmark
_run-adapter-benchmark:
	@# Run benchmark for a single adapter with JSON report output
	@socket="/tmp/latte-benchmark-chart-$(ADAPTER).sock"; \
	rm -f $$socket; \
	echo "Starting $(ADAPTER) adapter..."; \
	case "$(ADAPTER)" in \
		scylla-rust-driver) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			./scylla-rust-driver/target/release/latte-driver & \
			;; \
		gocql) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			./gocql/latte-gocql-driver & \
			;; \
		csharp-driver) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			./csharp-driver/src/bin/Release/net8.0/linux-x64/publish/latte-driver-csharp & \
			;; \
		python-driver) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			./python-driver/.venv/bin/python -m src.main & \
			;; \
		java-driver-4) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			java -jar ./java-driver-4/target/latte-driver-java.jar & \
			;; \
		java-driver-3) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			java -jar ./java-driver-3/target/latte-driver-java3.jar & \
			;; \
		cpp-rs-driver) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			./cpp-rs-driver/target/release/latte-cpp-rs-driver & \
			;; \
		cpp-driver) \
			LATTE_DRIVER_SOCKET=$$socket \
			LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
			./cpp-driver/target/release/latte-cpp-driver & \
			;; \
		*) \
			echo "Unknown adapter: $(ADAPTER)"; \
			exit 1; \
			;; \
	esac; \
	adapter_pid=$$!; \
	echo "Waiting for adapter (pid $$adapter_pid)..."; \
	for i in 1 2 3 4 5 6 7 8 9 10; do \
		if [ -S $$socket ]; then \
			echo "Adapter ready."; \
			break; \
		fi; \
		if [ $$i -eq 10 ]; then \
			echo "Error: Adapter failed to start"; \
			kill $$adapter_pid 2>/dev/null || true; \
			exit 1; \
		fi; \
		sleep 1; \
	done; \
	echo "Creating schema..."; \
	$(LATTE_BIN) schema --driver-socket $$socket .workloads/$(BENCHMARK_WORKLOAD).rn || { \
		kill $$adapter_pid 2>/dev/null || true; \
		rm -f $$socket; \
		exit 1; \
	}; \
	echo "Loading data ($(BENCHMARK_ROW_COUNT) rows)..."; \
	$(LATTE_BIN) load --driver-socket $$socket \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		.workloads/$(BENCHMARK_WORKLOAD).rn || { \
		kill $$adapter_pid 2>/dev/null || true; \
		rm -f $$socket; \
		exit 1; \
	}; \
	echo "Running benchmark for $(BENCHMARK_DURATION)..."; \
	$(LATTE_BIN) run --driver-socket $$socket \
		-d $(BENCHMARK_DURATION) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		--generate-report \
		-o $(REPORT_FILE) \
		.workloads/$(BENCHMARK_WORKLOAD).rn; \
	result=$$?; \
	echo "Stopping adapter..."; \
	kill $$adapter_pid 2>/dev/null || true; \
	rm -f $$socket; \
	exit $$result

.PHONY: test-benchmark-generate-chart
test-benchmark-generate-chart: _check-python-deps ## Generate chart from existing reports (use ADAPTERS=a,b WORKLOADS=x,y to filter)
	@if [ ! -d "$(BENCHMARK_REPORTS_DIR)" ]; then \
		echo "Error: No benchmark reports found in $(BENCHMARK_REPORTS_DIR)"; \
		echo "Run 'make test-benchmark' first to generate reports."; \
		exit 1; \
	fi
	@# Normalize comma-separated lists to space-separated
	@adapters=$$(echo "$(ADAPTERS)" | tr ',' ' '); \
	workloads=$$(echo "$(WORKLOADS)" | tr ',' ' '); \
	: $${workloads:=$(BENCHMARK_WORKLOADS)}; \
	: $${adapters:=$(ADAPTERS)}; \
	workload_args=""; \
	for workload in $$workloads; do \
		pure_report="$(BENCHMARK_REPORTS_DIR)/pure-latte-$$workload.json"; \
		if [ -f "$$pure_report" ]; then \
			adapter_specs=""; \
			for adapter in $$adapters; do \
				adapter_report="$(BENCHMARK_REPORTS_DIR)/$$adapter-$$workload.json"; \
				if [ -n "$$adapter_specs" ]; then \
					adapter_specs="$$adapter_specs,$$adapter=$$adapter_report"; \
				else \
					adapter_specs="$$adapter=$$adapter_report"; \
				fi; \
			done; \
			workload_args="$$workload_args --workload $$workload:$$pure_report:$$adapter_specs"; \
		fi; \
	done; \
	if [ -z "$$workload_args" ]; then \
		echo "Error: No reports found in $(BENCHMARK_REPORTS_DIR)"; \
		exit 1; \
	fi; \
	log_scale_arg=""; \
	if [ "$(CHART_LOG_SCALE)" = "true" ]; then \
		log_scale_arg="--log-scale"; \
	fi; \
	$(PYTHON) .scripts/plot_benchmark_latencies.py \
		--output $(CHART_OUTPUT) \
		$$workload_args \
		--percentile $(CHART_PERCENTILE) \
		--title "Driver Adapter Latency Comparison" \
		$$log_scale_arg

.PHONY: test-benchmark-scylla
test-benchmark-scylla: ## Run benchmarks for scylla-rust-driver adapter
	$(MAKE) -C scylla-rust-driver test-benchmark

.PHONY: test-benchmark-gocql
test-benchmark-gocql: ## Run benchmarks for gocql adapter
	$(MAKE) -C gocql test-benchmark

.PHONY: test-benchmark-csharp
test-benchmark-csharp: ## Run benchmarks for csharp-driver adapter
	$(MAKE) -C csharp-driver test-benchmark

.PHONY: test-benchmark-python
test-benchmark-python: ## Run benchmarks for python-driver adapter
	$(MAKE) -C python-driver test-benchmark

.PHONY: test-benchmark-java
test-benchmark-java: ## Run benchmarks for java-driver-4 adapter
	$(MAKE) -C java-driver-4 test-benchmark

.PHONY: test-benchmark-java3
test-benchmark-java3: ## Run benchmarks for java-driver-3 adapter
	$(MAKE) -C java-driver-3 test-benchmark

.PHONY: test-benchmark-cpp
test-benchmark-cpp: ## Run benchmarks for cpp-rs-driver adapter
	$(MAKE) -C cpp-rs-driver test-benchmark

.PHONY: test-benchmark-cpp-driver
test-benchmark-cpp-driver: ## Run benchmarks for cpp-driver adapter (deprecated driver)
	$(MAKE) -C cpp-driver test-benchmark

# ============================================================================
# Integration test targets
# ============================================================================

.PHONY: test-integration
test-integration: _check-latte-dep _ensure-db ## Run integration tests for all adapters
	@echo "============================================================"
	@echo "=== Running Integration Tests for All Driver Adapters ==="
	@echo "============================================================"
	@echo "Contact points: $(SCYLLA_CONTACT_POINTS)"
	@echo "Rows: $(INTEGRATION_ROW_COUNT), Duration: $(INTEGRATION_DURATION)"
	@echo ""
	@failed=""; \
	for adapter in $(ADAPTERS); do \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Testing $$adapter..."; \
		echo "============================================================"; \
		if $(MAKE) -C $$adapter test-integration; then \
			echo ">>> $$adapter integration PASSED"; \
		else \
			echo ">>> $$adapter integration FAILED"; \
			failed="$$failed $$adapter"; \
		fi; \
	done; \
	echo ""; \
	echo "============================================================"; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED adapters:$$failed ==="; \
		exit 1; \
	else \
		echo "=== All adapter integration tests PASSED ==="; \
	fi

.PHONY: test-integration-scylla
test-integration-scylla: ## Run integration tests for scylla-rust-driver adapter
	$(MAKE) -C scylla-rust-driver test-integration

.PHONY: test-integration-gocql
test-integration-gocql: ## Run integration tests for gocql adapter
	$(MAKE) -C gocql test-integration

.PHONY: test-integration-csharp
test-integration-csharp: ## Run integration tests for csharp-driver adapter
	$(MAKE) -C csharp-driver test-integration

.PHONY: test-integration-python
test-integration-python: ## Run integration tests for python-driver adapter
	$(MAKE) -C python-driver test-integration

.PHONY: test-integration-java
test-integration-java: ## Run integration tests for java-driver-4 adapter
	$(MAKE) -C java-driver-4 test-integration

.PHONY: test-integration-java3
test-integration-java3: ## Run integration tests for java-driver-3 adapter
	$(MAKE) -C java-driver-3 test-integration

.PHONY: test-integration-cpp
test-integration-cpp: ## Run integration tests for cpp-rs-driver adapter
	$(MAKE) -C cpp-rs-driver test-integration

.PHONY: test-integration-cpp-driver
test-integration-cpp-driver: ## Run integration tests for cpp-driver adapter (deprecated driver)
	$(MAKE) -C cpp-driver test-integration

# ============================================================================
# Unit test targets
# ============================================================================

.PHONY: test
test: ## Run unit tests for all adapters
	@echo "=== Running unit tests for all adapters ==="
	@for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Testing $$adapter..."; \
		$(MAKE) -C $$adapter test || exit 1; \
	done
	@echo ""
	@echo "=== All adapter unit tests PASSED ==="

# ============================================================================
# Lint targets
# ============================================================================

.PHONY: lint
lint: ## Run linters for all adapters
	@echo "=== Linting all adapters ==="
	@for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Linting $$adapter..."; \
		$(MAKE) -C $$adapter lint || exit 1; \
	done
	@echo ""
	@echo "=== All adapters passed lint ==="

.PHONY: lint-fix
lint-fix: ## Auto-fix lint issues for all adapters
	@for adapter in $(ADAPTERS); do \
		echo ">>> Fixing lint in $$adapter..."; \
		$(MAKE) -C $$adapter lint-fix || true; \
	done

# ============================================================================
# Clean targets
# ============================================================================

.PHONY: clean
clean: ## Clean build artifacts for all adapters
	@for adapter in $(ADAPTERS); do \
		echo ">>> Cleaning $$adapter..."; \
		$(MAKE) -C $$adapter clean || true; \
	done

# ============================================================================
# Profiling targets
# ============================================================================

# Profiling configuration
PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= primitives
PROFILE_OUTPUT_DIR ?= .profiles

export PROFILE_REQUEST_COUNT
export PROFILE_WORKLOAD
export PROFILE_OUTPUT_DIR

.PHONY: profile
profile: _check-latte-dep _ensure-db ## Run profiling benchmarks for all adapters
	@echo "============================================================"
	@echo "=== Profiling Benchmarks ==="
	@echo "============================================================"
	@echo "Request count: $(PROFILE_REQUEST_COUNT)"
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Output directory: $(PROFILE_OUTPUT_DIR)"
	@echo ""
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@failed=""; \
	for adapter in $(ADAPTERS); do \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Profiling $$adapter..."; \
		echo "============================================================"; \
		if $(MAKE) -C $$adapter profile \
			PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
			PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
			PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR); then \
			echo ">>> $$adapter profiling PASSED"; \
		else \
			echo ">>> $$adapter profiling FAILED (or profiling not implemented)"; \
			failed="$$failed $$adapter"; \
		fi; \
	done; \
	echo ""; \
	echo "============================================================"; \
	echo "=== Profiling Complete ==="; \
	echo "Profiles saved to: $(PROFILE_OUTPUT_DIR)/"; \
	if [ -n "$$failed" ]; then \
		echo "Note: Some adapters failed or don't have profiling:$$failed"; \
	fi

.PHONY: profile-scylla
profile-scylla: _check-latte-dep _ensure-db ## Run profiling for scylla-rust-driver
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C scylla-rust-driver profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-gocql
profile-gocql: _check-latte-dep _ensure-db ## Run profiling for gocql adapter
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C gocql profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-python
profile-python: _check-latte-dep _ensure-db ## Run profiling for python-driver adapter
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C python-driver profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-java
profile-java: _check-latte-dep _ensure-db ## Run profiling for java-driver-4 adapter
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C java-driver-4 profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-java3
profile-java3: _check-latte-dep _ensure-db ## Run profiling for java-driver-3 adapter
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C java-driver-3 profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-csharp
profile-csharp: _check-latte-dep _ensure-db ## Run profiling for csharp-driver adapter
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C csharp-driver profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-cpp
profile-cpp: _check-latte-dep _ensure-db ## Run profiling for cpp-rs-driver adapter
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C cpp-rs-driver profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

.PHONY: profile-cpp-driver
profile-cpp-driver: _check-latte-dep _ensure-db ## Run profiling for cpp-driver adapter (deprecated driver)
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C cpp-driver profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=$(CURDIR)/$(PROFILE_OUTPUT_DIR)

# ============================================================================
# CI targets
# ============================================================================

.PHONY: ci
ci: lint test build ## Run all CI checks for all adapters
	@echo "=== All CI checks passed ==="

# ============================================================================
# Help
# ============================================================================

.PHONY: list-adapters
list-adapters: ## List all supported driver adapters
	@echo "Supported driver adapters:"
	@for adapter in $(ADAPTERS); do \
		echo "  - $$adapter"; \
	done

.PHONY: help
help: ## Show this help message
	@echo "Driver Adapters Makefile"
	@echo ""
	@echo "Usage: make [target] [VARIABLE=value]"
	@echo ""
	@echo "Variables:"
	@echo "  SCYLLA_CONTACT_POINTS   Database contact points (default: 127.0.0.1:9042)"
	@echo "  LATTE_BIN               Path to latte binary (auto-detected or ../target/release/latte)"
	@echo "  BENCHMARK_ROW_COUNT     Rows for benchmarks (default: 10000)"
	@echo "  BENCHMARK_DURATION      Duration for benchmarks (default: 5s)"
	@echo "  INTEGRATION_ROW_COUNT   Rows for integration tests (default: 100)"
	@echo "  INTEGRATION_DURATION    Duration for integration tests (default: 10s)"
	@echo ""
	@echo "Chart Variables (for test-benchmark):"
	@echo "  BENCHMARK_WORKLOADS     Space-separated list of workloads (default: basic primitives collections vectors)"
	@echo "  CHART_OUTPUT            Output PNG file (default: benchmark_latencies.png)"
	@echo "  CHART_PERCENTILE        Percentile to plot (default: p99)"
	@echo "  CHART_LOG_SCALE         Use logarithmic Y axis (default: true)"
	@echo "  BENCHMARK_REPORTS_DIR   Directory for JSON reports (default: benchmark-reports)"
	@echo ""
	@echo "Filtering Variables (for test-benchmark, test-benchmark-generate-chart):"
	@echo "  ADAPTERS                Comma or space-separated list of adapters (default: all)"
	@echo "  WORKLOADS               Comma or space-separated list of workloads (default: all)"
	@echo ""
	@echo "Profiling Variables (for profile targets):"
	@echo "  PROFILE_REQUEST_COUNT   Number of requests for profiling (default: 10000)"
	@echo "  PROFILE_WORKLOAD        Workload to profile (default: primitives)"
	@echo "  PROFILE_OUTPUT_DIR      Directory for profile output (default: profiles)"
	@echo ""
	@echo "Example Workflows:"
	@echo "  # Run all benchmarks:"
	@echo "  make test-benchmark"
	@echo ""
	@echo "  # Run specific adapters and workloads:"
	@echo "  make test-benchmark ADAPTERS=gocql,java-driver-4 WORKLOADS=basic,primitives"
	@echo ""
	@echo "  # Re-run single adapter/workload and regenerate chart:"
	@echo "  make test-benchmark ADAPTERS=gocql WORKLOADS=basic"
	@echo "  make test-benchmark-generate-chart"
	@echo ""
	@echo "  # Profile all adapters (10k requests):"
	@echo "  make profile"
	@echo ""
	@echo "  # Profile specific adapter with custom settings:"
	@echo "  make profile-gocql PROFILE_REQUEST_COUNT=50000 PROFILE_WORKLOAD=collections"
	@echo ""
	@echo "Database Variables:"
	@echo "  SCYLLA_DOCKER_IMAGE     Docker image for ScyllaDB (default: scylladb/scylla:latest)"
	@echo "  SCYLLA_CONTAINER_NAME   Docker container name (default: latte-scylla-test)"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-25s %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
