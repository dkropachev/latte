# Alternator Adapters Makefile
#
# Orchestrates benchmark and functional tests across all Alternator (DynamoDB) adapters.
#
# Usage:
#   make test-benchmark                                    # Run benchmarks for all adapters/workloads
#   make test-benchmark ADAPTERS=alternator-client-golang WORKLOADS=crud  # Run specific adapter/workload
#   make test-benchmark-generate-chart                     # Regenerate chart from existing reports
#   make test-functional                                   # Run functional tests for all adapters
#   make build                                             # Build all adapters

# ============================================================================
# Configuration
# ============================================================================

# List of all adapters (subdirectories with Makefiles)
ADAPTERS := alternator-client-golang alternator-client-java

# Workloads to skip for specific adapters (adapter:workload pairs)
# Example: SKIP_WORKLOADS := some-adapter:conditional
SKIP_WORKLOADS :=

# Test configuration (can be overridden)
ALTERNATOR_ENDPOINT ?= http://127.0.0.1:8000
BENCHMARK_ITEM_COUNT ?= 10000

# AWS credentials for DynamoDB Local (must match between latte and adapters)
# DynamoDB Local separates data by access key ID, so both must use the same credentials
AWS_ACCESS_KEY_ID ?= test
AWS_SECRET_ACCESS_KEY ?= test

# Latte binary: use from PATH, or fallback to local build (absolute path for sub-makes)
LATTE_BIN ?= $(shell command -v latte 2>/dev/null || echo "$(abspath ../target/release/latte)")

# Docker configuration for DynamoDB Local / Alternator
DYNAMODB_LOCAL_IMAGE ?= amazon/dynamodb-local:latest
DYNAMODB_CONTAINER_NAME ?= latte-dynamodb-test
ALTERNATOR_IMAGE ?= scylladb/scylla:latest
ALTERNATOR_CONTAINER_NAME ?= latte-alternator-test

# Benchmark configuration
BENCHMARK_DURATION ?= 5s
FUNCTIONAL_ITEM_COUNT ?= 100
FUNCTIONAL_OPS_NUM ?= 10

# Export variables for sub-makes
export ALTERNATOR_ENDPOINT
export LATTE_BIN
export BENCHMARK_ITEM_COUNT
export BENCHMARK_DURATION
export FUNCTIONAL_ITEM_COUNT
export FUNCTIONAL_OPS_NUM
export AWS_ACCESS_KEY_ID
export AWS_SECRET_ACCESS_KEY
export JAVA_HOME

# ============================================================================
# Build targets
# ============================================================================

.PHONY: build
build: ## Build all alternator adapters
	@echo "=== Building all alternator adapters ==="
	@for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Building $$adapter..."; \
		$(MAKE) -C $$adapter build || exit 1; \
	done
	@echo ""
	@echo "=== All adapters built ==="

.PHONY: build-alternator-client-golang
build-alternator-client-golang: ## Build alternator-client-golang adapter
	$(MAKE) -C alternator-client-golang build

.PHONY: build-alternator-client-java
build-alternator-client-java: ## Build alternator-client-java adapter
	$(MAKE) -C alternator-client-java build

.PHONY: build-docker-image
build-docker-image: ## Build Docker images for all adapters
	@echo "=== Building Docker images for all alternator adapters ==="
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
CHART_PERCENTILE ?= p99
CHART_OUTPUT ?= $(BENCHMARK_REPORTS_DIR)/benchmark_latencies.png
CHART_LOG_SCALE ?= true

# Benchmark workload directory and discovered workloads
BENCHMARK_WORKLOAD_DIR := .workloads/benchmark
BENCHMARK_WORKLOADS := $(patsubst $(BENCHMARK_WORKLOAD_DIR)/%.rn,%,$(wildcard $(BENCHMARK_WORKLOAD_DIR)/*.rn))

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
	@# Check if DynamoDB/Alternator is reachable
	@endpoint="$(ALTERNATOR_ENDPOINT)"; \
	if curl -s "$$endpoint" >/dev/null 2>&1; then \
		exit 0; \
	else \
		exit 1; \
	fi

.PHONY: start-dynamodb-local
start-dynamodb-local: _check-docker ## Start DynamoDB Local in Docker for testing
	@if $(MAKE) --no-print-directory _check-db-connection 2>/dev/null; then \
		echo "Database already running at $(ALTERNATOR_ENDPOINT)"; \
	else \
		echo "Starting DynamoDB Local container..."; \
		if docker ps -a --format '{{.Names}}' | grep -q "^$(DYNAMODB_CONTAINER_NAME)$$"; then \
			echo "Container $(DYNAMODB_CONTAINER_NAME) exists, starting it..."; \
			docker start $(DYNAMODB_CONTAINER_NAME); \
		else \
			echo "Creating new container $(DYNAMODB_CONTAINER_NAME)..."; \
			docker run -d --name $(DYNAMODB_CONTAINER_NAME) \
				-p 8000:8000 \
				$(DYNAMODB_LOCAL_IMAGE) -jar DynamoDBLocal.jar -inMemory -sharedDb; \
		fi; \
		echo "Waiting for DynamoDB Local to be ready..."; \
		for i in $$(seq 1 30); do \
			if $(MAKE) --no-print-directory _check-db-connection 2>/dev/null; then \
				echo ""; \
				echo "DynamoDB Local is ready!"; \
				exit 0; \
			fi; \
			printf "."; \
			sleep 1; \
		done; \
		echo ""; \
		echo "ERROR: DynamoDB Local failed to start within 30 seconds"; \
		docker logs $(DYNAMODB_CONTAINER_NAME) --tail 20; \
		exit 1; \
	fi

.PHONY: start-alternator
start-alternator: _check-docker ## Start ScyllaDB Alternator in Docker for testing
	@if $(MAKE) --no-print-directory _check-db-connection 2>/dev/null; then \
		echo "Database already running at $(ALTERNATOR_ENDPOINT)"; \
	else \
		echo "Starting ScyllaDB Alternator container..."; \
		if docker ps -a --format '{{.Names}}' | grep -q "^$(ALTERNATOR_CONTAINER_NAME)$$"; then \
			echo "Container $(ALTERNATOR_CONTAINER_NAME) exists, starting it..."; \
			docker start $(ALTERNATOR_CONTAINER_NAME); \
		else \
			echo "Creating new container $(ALTERNATOR_CONTAINER_NAME)..."; \
			docker run -d --name $(ALTERNATOR_CONTAINER_NAME) \
				-p 8000:8000 \
				-p 9042:9042 \
				$(ALTERNATOR_IMAGE) \
				--alternator-port 8000 \
				--alternator-write-isolation only_rmw_uses_lwt \
				--smp 2 --memory 2G --overprovisioned 1; \
		fi; \
		echo "Waiting for Alternator to be ready (this may take 30-60 seconds)..."; \
		for i in $$(seq 1 90); do \
			if $(MAKE) --no-print-directory _check-db-connection 2>/dev/null; then \
				echo ""; \
				echo "Alternator is ready!"; \
				exit 0; \
			fi; \
			printf "."; \
			sleep 2; \
		done; \
		echo ""; \
		echo "ERROR: Alternator failed to start within 180 seconds"; \
		docker logs $(ALTERNATOR_CONTAINER_NAME) --tail 20; \
		exit 1; \
	fi

.PHONY: stop-dynamodb-local
stop-dynamodb-local: ## Stop DynamoDB Local Docker container
	@if docker ps --format '{{.Names}}' | grep -q "^$(DYNAMODB_CONTAINER_NAME)$$"; then \
		echo "Stopping DynamoDB Local container..."; \
		docker stop $(DYNAMODB_CONTAINER_NAME); \
	else \
		echo "DynamoDB Local container not running."; \
	fi

.PHONY: stop-alternator
stop-alternator: ## Stop ScyllaDB Alternator Docker container
	@if docker ps --format '{{.Names}}' | grep -q "^$(ALTERNATOR_CONTAINER_NAME)$$"; then \
		echo "Stopping Alternator container..."; \
		docker stop $(ALTERNATOR_CONTAINER_NAME); \
	else \
		echo "Alternator container not running."; \
	fi

.PHONY: clean-dynamodb-local
clean-dynamodb-local: stop-dynamodb-local ## Stop and remove DynamoDB Local Docker container
	@if docker ps -a --format '{{.Names}}' | grep -q "^$(DYNAMODB_CONTAINER_NAME)$$"; then \
		echo "Removing DynamoDB Local container..."; \
		docker rm $(DYNAMODB_CONTAINER_NAME); \
	fi

.PHONY: clean-alternator
clean-alternator: stop-alternator ## Stop and remove ScyllaDB Alternator Docker container
	@if docker ps -a --format '{{.Names}}' | grep -q "^$(ALTERNATOR_CONTAINER_NAME)$$"; then \
		echo "Removing Alternator container..."; \
		docker rm $(ALTERNATOR_CONTAINER_NAME); \
	fi

.PHONY: _ensure-db
_ensure-db:
	@if ! $(MAKE) --no-print-directory _check-db-connection 2>/dev/null; then \
		echo "No database ready at $(ALTERNATOR_ENDPOINT), starting DynamoDB Local..."; \
		$(MAKE) --no-print-directory start-dynamodb-local; \
	fi

.PHONY: test-benchmark
test-benchmark: _check-latte-dep _ensure-db ## Run benchmarks for all adapters
	@echo "============================================================"
	@echo "=== Running Benchmarks for All Alternator Adapters ==="
	@echo "============================================================"
	@echo "Endpoint: $(ALTERNATOR_ENDPOINT)"
	@echo "Items: $(BENCHMARK_ITEM_COUNT), Duration: $(BENCHMARK_DURATION)"
	@echo ""
	@failed=""; \
	for adapter in $(ADAPTERS); do \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Running benchmarks for $$adapter..."; \
		echo "============================================================"; \
		if $(MAKE) -C $$adapter test-benchmark \
			TEST_ENDPOINT=$(ALTERNATOR_ENDPOINT) \
			BENCHMARK_ITEM_COUNT=$(BENCHMARK_ITEM_COUNT) \
			BENCHMARK_DURATION=$(BENCHMARK_DURATION); then \
			echo ">>> $$adapter benchmarks PASSED"; \
		else \
			echo ">>> $$adapter benchmarks FAILED"; \
			failed="$$failed $$adapter"; \
		fi; \
	done; \
	echo ""; \
	echo "============================================================"; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED adapters:$$failed ==="; \
		exit 1; \
	else \
		echo "=== All adapter benchmarks PASSED ==="; \
	fi
	@$(MAKE) --no-print-directory test-benchmark-generate-chart 2>/dev/null || \
		echo "Note: Chart generation skipped (install matplotlib and numpy for charts)"

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
		--title "Alternator Adapter Latency Comparison" \
		$$log_scale_arg

# ----------------------------------------------------------------------------
# Per-workload benchmark targets (run across all adapters)
# ----------------------------------------------------------------------------

# Shared recipe for per-workload benchmark targets
define _run-adapter-benchmark-workload
	@failed=""; \
	for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Running $@ for $$adapter..."; \
		if $(MAKE) -C $$adapter $@ \
			TEST_ENDPOINT=$(ALTERNATOR_ENDPOINT) \
			BENCHMARK_ITEM_COUNT=$(BENCHMARK_ITEM_COUNT) \
			BENCHMARK_DURATION=$(BENCHMARK_DURATION); then \
			echo ">>> $$adapter $@ PASSED"; \
		else \
			echo ">>> $$adapter $@ FAILED"; \
			failed="$$failed $$adapter"; \
		fi; \
	done; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED adapters for $@:$$failed ==="; \
		exit 1; \
	fi
endef

.PHONY: test-benchmark-batch
test-benchmark-batch: _check-latte-dep _ensure-db ## Run batch benchmark for all adapters
	$(call _run-adapter-benchmark-workload)

.PHONY: test-benchmark-collections
test-benchmark-collections: _check-latte-dep _ensure-db ## Run collections benchmark for all adapters
	$(call _run-adapter-benchmark-workload)

.PHONY: test-benchmark-conditional
test-benchmark-conditional: _check-latte-dep _ensure-db ## Run conditional benchmark for all adapters
	$(call _run-adapter-benchmark-workload)

.PHONY: test-benchmark-nested-collections
test-benchmark-nested-collections: _check-latte-dep _ensure-db ## Run nested-collections benchmark for all adapters
	$(call _run-adapter-benchmark-workload)

.PHONY: test-benchmark-primitives
test-benchmark-primitives: _check-latte-dep _ensure-db ## Run primitives benchmark for all adapters
	$(call _run-adapter-benchmark-workload)

.PHONY: test-benchmark-scan
test-benchmark-scan: _check-latte-dep _ensure-db ## Run scan benchmark for all adapters
	$(call _run-adapter-benchmark-workload)

# ============================================================================
# Functional test targets
# ============================================================================

.PHONY: test-functional
test-functional: _check-latte-dep _ensure-db ## Run functional tests for all adapters
	@echo "============================================================"
	@echo "=== Running Functional Tests for All Alternator Adapters ==="
	@echo "============================================================"
	@echo "Endpoint: $(ALTERNATOR_ENDPOINT)"
	@echo "Items: $(FUNCTIONAL_ITEM_COUNT), Iterations: $(FUNCTIONAL_OPS_NUM)"
	@echo ""
	@failed=""; \
	for adapter in $(ADAPTERS); do \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Testing $$adapter..."; \
		echo "============================================================"; \
		if $(MAKE) -C $$adapter test-functional \
			TEST_ENDPOINT=$(ALTERNATOR_ENDPOINT) \
			E2E_ITEM_COUNT=$(FUNCTIONAL_ITEM_COUNT) \
			FUNCTIONAL_OPS_NUM=$(FUNCTIONAL_OPS_NUM); then \
			echo ">>> $$adapter functional tests PASSED"; \
		else \
			echo ">>> $$adapter functional tests FAILED"; \
			failed="$$failed $$adapter"; \
		fi; \
	done; \
	echo ""; \
	echo "============================================================"; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED adapters:$$failed ==="; \
		exit 1; \
	else \
		echo "=== All adapter functional tests PASSED ==="; \
	fi

# ----------------------------------------------------------------------------
# Per-workload functional test targets (run across all adapters)
# ----------------------------------------------------------------------------

# Shared recipe for per-workload functional test targets
define _run-adapter-functional-workload
	@failed=""; \
	for adapter in $(ADAPTERS); do \
		echo ""; \
		echo ">>> Running $@ for $$adapter..."; \
		if $(MAKE) -C $$adapter $@ \
			TEST_ENDPOINT=$(ALTERNATOR_ENDPOINT) \
			E2E_ITEM_COUNT=$(FUNCTIONAL_ITEM_COUNT) \
			FUNCTIONAL_OPS_NUM=$(FUNCTIONAL_OPS_NUM); then \
			echo ">>> $$adapter $@ PASSED"; \
		else \
			echo ">>> $$adapter $@ FAILED"; \
			failed="$$failed $$adapter"; \
		fi; \
	done; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED adapters for $@:$$failed ==="; \
		exit 1; \
	fi
endef

.PHONY: test-functional-basic
test-functional-basic: _check-latte-dep _ensure-db ## Run basic functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-batch
test-functional-batch: _check-latte-dep _ensure-db ## Run batch functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-collections
test-functional-collections: _check-latte-dep _ensure-db ## Run collections functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-conditional
test-functional-conditional: _check-latte-dep _ensure-db ## Run conditional functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-crud
test-functional-crud: _check-latte-dep _ensure-db ## Run crud functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-multi_client
test-functional-multi_client: _check-latte-dep _ensure-db ## Run multi_client functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-nested-collections
test-functional-nested-collections: _check-latte-dep _ensure-db ## Run nested-collections functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-parallel_scan
test-functional-parallel_scan: _check-latte-dep _ensure-db ## Run parallel_scan functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-primitives
test-functional-primitives: _check-latte-dep _ensure-db ## Run primitives functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-query
test-functional-query: _check-latte-dep _ensure-db ## Run query functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-query_gsi
test-functional-query_gsi: _check-latte-dep _ensure-db ## Run query_gsi functional test for all adapters
	$(call _run-adapter-functional-workload)

.PHONY: test-functional-scan
test-functional-scan: _check-latte-dep _ensure-db ## Run scan functional test for all adapters
	$(call _run-adapter-functional-workload)

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
	@rm -rf $(BENCHMARK_REPORTS_DIR)

# ============================================================================
# Profiling targets
# ============================================================================

# Profiling configuration
PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= crud
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
			PROFILE_OUTPUT_DIR=../$(PROFILE_OUTPUT_DIR); then \
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

.PHONY: profile-alternator-client-golang
profile-alternator-client-golang: _check-latte-dep _ensure-db ## Run profiling for alternator-client-golang
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C alternator-client-golang profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=../$(PROFILE_OUTPUT_DIR)

.PHONY: profile-alternator-client-java
profile-alternator-client-java: _check-latte-dep _ensure-db ## Run profiling for alternator-client-java
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	$(MAKE) -C alternator-client-java profile \
		PROFILE_REQUEST_COUNT=$(PROFILE_REQUEST_COUNT) \
		PROFILE_WORKLOAD=$(PROFILE_WORKLOAD) \
		PROFILE_OUTPUT_DIR=../$(PROFILE_OUTPUT_DIR)

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
list-adapters: ## List all supported alternator adapters
	@echo "Supported alternator adapters:"
	@for adapter in $(ADAPTERS); do \
		echo "  - $$adapter"; \
	done

.PHONY: help
help: ## Show this help message
	@echo "Alternator Adapters Makefile"
	@echo ""
	@echo "Usage: make [target] [VARIABLE=value]"
	@echo ""
	@echo "Variables:"
	@echo "  ALTERNATOR_ENDPOINT     DynamoDB/Alternator endpoint (default: http://127.0.0.1:8000)"
	@echo "  LATTE_BIN               Path to latte binary (auto-detected or ../target/release/latte)"
	@echo "  BENCHMARK_ITEM_COUNT    Items for benchmarks (default: 10000)"
	@echo "  BENCHMARK_DURATION      Duration for benchmarks (default: 5s)"
	@echo "  FUNCTIONAL_ITEM_COUNT   Items for functional tests (default: 100)"
	@echo "  FUNCTIONAL_OPS_NUM      Iterations for functional tests (default: 10)"
	@echo ""
	@echo "Chart Variables (for test-benchmark):"
	@echo "  BENCHMARK_WORKLOADS     Space-separated list of workloads (default: crud query batch scan)"
	@echo "  CHART_OUTPUT            Output PNG file (default: .benchmark-reports/benchmark_latencies.png)"
	@echo "  CHART_PERCENTILE        Percentile to plot (default: p99)"
	@echo "  CHART_LOG_SCALE         Use logarithmic Y axis (default: true)"
	@echo "  BENCHMARK_REPORTS_DIR   Directory for JSON reports (default: .benchmark-reports)"
	@echo ""
	@echo "Filtering Variables (for test-benchmark, test-benchmark-generate-chart):"
	@echo "  ADAPTERS                Comma or space-separated list of adapters (default: all)"
	@echo "  WORKLOADS               Comma or space-separated list of workloads (default: all)"
	@echo ""
	@echo "Profiling Variables (for profile targets):"
	@echo "  PROFILE_REQUEST_COUNT   Number of requests for profiling (default: 10000)"
	@echo "  PROFILE_WORKLOAD        Workload to profile (default: crud)"
	@echo "  PROFILE_OUTPUT_DIR      Directory for profile output (default: .profiles)"
	@echo ""
	@echo "Example Workflows:"
	@echo "  # Run all benchmarks:"
	@echo "  make test-benchmark"
	@echo ""
	@echo "  # Run specific adapters and workloads:"
	@echo "  make test-benchmark ADAPTERS=alternator-client-golang WORKLOADS=crud,query"
	@echo ""
	@echo "  # Re-run single adapter/workload and regenerate chart:"
	@echo "  make test-benchmark ADAPTERS=alternator-client-golang WORKLOADS=crud"
	@echo "  make test-benchmark-generate-chart"
	@echo ""
	@echo "  # Profile all adapters (10k requests):"
	@echo "  make profile"
	@echo ""
	@echo "  # Profile specific adapter with custom settings:"
	@echo "  make profile-alternator-client-golang PROFILE_REQUEST_COUNT=50000 PROFILE_WORKLOAD=query"
	@echo ""
	@echo "Database Variables:"
	@echo "  DYNAMODB_LOCAL_IMAGE    Docker image for DynamoDB Local (default: amazon/dynamodb-local:latest)"
	@echo "  DYNAMODB_CONTAINER_NAME Docker container name for DynamoDB Local (default: latte-dynamodb-test)"
	@echo "  ALTERNATOR_IMAGE        Docker image for Alternator (default: scylladb/scylla:latest)"
	@echo "  ALTERNATOR_CONTAINER_NAME Docker container name for Alternator (default: latte-alternator-test)"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-35s %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
