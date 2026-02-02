# ==============================================================================
# Configuration
# ==============================================================================
MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
ADAPTER_NAME := alternator-client-golang
ADAPTER_VERSION ?= dev
DOCKER_REGISTRY := scylladb
DOCKER_IMAGE := $(DOCKER_REGISTRY)/latte-alternator-adapters:$(ADAPTER_NAME)-$(ADAPTER_VERSION)
DOCKER_IMAGE_LATEST := $(DOCKER_REGISTRY)/latte-alternator-adapters:$(ADAPTER_NAME)-latest
ADAPTER_IMAGE_FLAG := --alternator-adapter-image $(DOCKER_IMAGE)

# Test configuration
TEST_ENDPOINT ?= http://localhost:8000
TEST_REGION ?= us-east-1
TEST_SOCKET ?= /tmp/test-alternator.sock

# Latte integration test configuration
LATTE_BIN ?= $(MAKEFILE_DIR)../../target/release/latte
WORKLOAD_DIR ?= $(MAKEFILE_DIR)../../workloads/dynamodb/cicd
FUNCTIONAL_OPS_NUM ?= 10
E2E_ITEM_COUNT ?= 100

# Benchmark configuration
BENCHMARK_DURATION ?= 5s
BENCHMARK_ITEM_COUNT ?= 10000
BENCHMARK_REPORTS_DIR ?= .benchmark-reports
BENCHMARK_WORKLOAD_DIR := $(MAKEFILE_DIR)../.workloads/benchmark
BENCHMARK_WORKLOADS := $(patsubst $(BENCHMARK_WORKLOAD_DIR)/%.rn,%,$(wildcard $(BENCHMARK_WORKLOAD_DIR)/*.rn))

# Go configuration
GO := go
GOFLAGS := -v
BINARY := bin/adapter

# ==============================================================================
# Build Targets
# ==============================================================================
.PHONY: build
build: ## Build the adapter binary (skips if binary exists and Go unavailable)
	@if [ -x "$(BINARY)" ]; then \
		echo "$(ADAPTER_NAME) binary already exists at $(BINARY)"; \
	elif command -v $(GO) >/dev/null 2>&1; then \
		echo "Building $(ADAPTER_NAME)..."; \
		mkdir -p bin; \
		$(GO) build $(GOFLAGS) -o $(BINARY) ./cmd/adapter; \
	else \
		echo "ERROR: $(BINARY) not found and Go is not installed"; \
		echo "Please install Go or provide a pre-built binary at $(BINARY)"; \
		exit 1; \
	fi

.PHONY: build-force
build-force: ## Force rebuild the adapter binary (requires Go)
	@echo "Building $(ADAPTER_NAME)..."
	@mkdir -p bin
	$(GO) build $(GOFLAGS) -o $(BINARY) ./cmd/adapter

.PHONY: clean
clean: ## Clean build artifacts
	@echo "Cleaning..."
	rm -rf bin/
	@if command -v $(GO) >/dev/null 2>&1; then $(GO) clean -cache; fi

# ==============================================================================
# Test Targets
# ==============================================================================
.PHONY: test
test: ## Run unit tests
	@echo "Running unit tests..."
	$(GO) test -v ./...

.PHONY: test-integration
test-integration: ## Run integration tests (requires DynamoDB Local or Alternator)
	@echo "Running integration tests against $(TEST_ENDPOINT)..."
	LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
	$(GO) test -v -tags=integration ./...

.PHONY: test-all
test-all: test test-integration ## Run all tests

# ==============================================================================
# Functional Tests (with Latte)
# ==============================================================================
# These tests require:
# - Latte binary (LATTE_BIN)
# - DynamoDB Local or Alternator running (TEST_ENDPOINT)
# - Adapter Docker image (built automatically via build-docker-image dependency)

# All DynamoDB workloads directory (for functional tests covering all workloads)
DYNAMODB_WORKLOAD_DIR ?= $(MAKEFILE_DIR)../../workloads/dynamodb

# Functional test runner function
# Usage: $(call run-functional-test,workload-relative-path)
define run-functional-test
	@echo "=== Functional Test: $(1) ==="
	@echo "Endpoint: $(TEST_ENDPOINT)"
	@echo "Running schema..."
	$(LATTE_BIN) schema --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) $(ADAPTER_IMAGE_FLAG) -P "items=$(E2E_ITEM_COUNT)" $(DYNAMODB_WORKLOAD_DIR)/$(1).rn
	@echo "Running load..."
	$(LATTE_BIN) load --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) $(ADAPTER_IMAGE_FLAG) -P "items=$(E2E_ITEM_COUNT)" $(DYNAMODB_WORKLOAD_DIR)/$(1).rn
	@echo "Running workload for $(FUNCTIONAL_OPS_NUM) operations..."
	@OUTPUT=$$($(LATTE_BIN) run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) $(ADAPTER_IMAGE_FLAG) -d $(FUNCTIONAL_OPS_NUM) -P "items=$(E2E_ITEM_COUNT)" $(DYNAMODB_WORKLOAD_DIR)/$(1).rn 2>&1); \
	echo "$$OUTPUT"; \
	echo "Verifying driver latency is reported..."; \
	if echo "$$OUTPUT" | grep -q "Driver latency"; then \
		echo "Driver latency: VERIFIED"; \
	else \
		echo "ERROR: Driver latency not found in output"; \
		exit 1; \
	fi
	@echo "=== Functional Test $(1) PASSED ==="
endef

.PHONY: _ensure-db
_ensure-db:
	@$(MAKE) --no-print-directory -C $(MAKEFILE_DIR).. _ensure-db

.PHONY: test-functional
test-functional: _ensure-db test-functional-basic test-functional-batch test-functional-collections test-functional-conditional test-functional-crud test-functional-multi_client test-functional-nested-collections test-functional-parallel_scan test-functional-primitives test-functional-query test-functional-query_gsi test-functional-scan ## Run all functional tests with Latte

# --- Root-level DynamoDB workloads (workloads/dynamodb/) ---

.PHONY: test-functional-basic
test-functional-basic: _check-docker-image ## Run basic functional test
	$(call run-functional-test,basic)

.PHONY: test-functional-batch
test-functional-batch: _check-docker-image ## Run batch functional test
	$(call run-functional-test,batch)

.PHONY: test-functional-conditional
test-functional-conditional: _check-docker-image ## Run conditional functional test
	$(call run-functional-test,conditional)

.PHONY: test-functional-multi_client
test-functional-multi_client: _check-docker-image ## Run multi_client functional test
	$(call run-functional-test,multi_client)

.PHONY: test-functional-query_gsi
test-functional-query_gsi: _check-docker-image ## Run query_gsi functional test
	$(call run-functional-test,query_gsi)

.PHONY: test-functional-parallel_scan
test-functional-parallel_scan: _check-docker-image ## Run parallel_scan functional test
	$(call run-functional-test,parallel_scan)

# --- Workloads only available in cicd/ (canonical names matching .workloads/) ---

.PHONY: test-functional-collections
test-functional-collections: _check-docker-image ## Run collections functional test
	$(call run-functional-test,cicd/collections)

.PHONY: test-functional-crud
test-functional-crud: _check-docker-image ## Run crud functional test
	$(call run-functional-test,cicd/crud)

.PHONY: test-functional-nested-collections
test-functional-nested-collections: _check-docker-image ## Run nested-collections functional test
	$(call run-functional-test,cicd/nested-collections)

.PHONY: test-functional-primitives
test-functional-primitives: _check-docker-image ## Run primitives functional test
	$(call run-functional-test,cicd/primitives)

.PHONY: test-functional-query
test-functional-query: _check-docker-image ## Run query functional test
	$(call run-functional-test,cicd/query)

.PHONY: test-functional-scan
test-functional-scan: _check-docker-image ## Run scan functional test
	$(call run-functional-test,cicd/scan)


# ==============================================================================
# Benchmark Tests (with Latte)
# ==============================================================================
# These tests run performance benchmarks and generate JSON reports.
# Requires:
# - Latte binary (LATTE_BIN)
# - DynamoDB Local or Alternator running (TEST_ENDPOINT)
# - Adapter Docker image (built automatically via build-docker-image dependency)

# Benchmark test runner function
# Usage: $(call run-benchmark-test,workload-name,report-file)
define run-benchmark-test
	@echo "=== Benchmark Test: $(1) ==="
	@echo "Endpoint: $(TEST_ENDPOINT)"
	@echo "Duration: $(BENCHMARK_DURATION)"
	@echo "Items: $(BENCHMARK_ITEM_COUNT)"
	@echo "Running schema..."
	$(LATTE_BIN) schema --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) $(ADAPTER_IMAGE_FLAG) -P "items=$(BENCHMARK_ITEM_COUNT)" $(BENCHMARK_WORKLOAD_DIR)/$(1).rn || exit 1
	@echo "Loading data ($(BENCHMARK_ITEM_COUNT) items)..."
	$(LATTE_BIN) load --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) $(ADAPTER_IMAGE_FLAG) -P "items=$(BENCHMARK_ITEM_COUNT)" $(BENCHMARK_WORKLOAD_DIR)/$(1).rn || exit 1
	@echo "Running benchmark for $(BENCHMARK_DURATION)..."
	$(LATTE_BIN) run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) $(ADAPTER_IMAGE_FLAG) -d $(BENCHMARK_DURATION) -P "items=$(BENCHMARK_ITEM_COUNT)" --generate-report -o $(2) $(BENCHMARK_WORKLOAD_DIR)/$(1).rn
	@echo "=== Benchmark Test $(1) completed ==="
endef

.PHONY: test-benchmark
test-benchmark: _check-docker-image ## Run all benchmark tests with JSON report output
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	@failed=""; \
	for workload in $(BENCHMARK_WORKLOADS); do \
		echo ""; \
		echo "============================================================"; \
		echo ">>> Running benchmark for $$workload..."; \
		echo "============================================================"; \
		report_file="$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-$$workload.json"; \
		if $(MAKE) --no-print-directory _run-benchmark-workload \
			BENCHMARK_WORKLOAD=$$workload \
			REPORT_FILE=$$report_file; then \
			echo ">>> Benchmark $$workload PASSED"; \
		else \
			echo ">>> Benchmark $$workload FAILED"; \
			failed="$$failed $$workload"; \
		fi; \
	done; \
	echo ""; \
	echo "============================================================"; \
	if [ -n "$$failed" ]; then \
		echo "=== FAILED workloads:$$failed ==="; \
		exit 1; \
	else \
		echo "=== All benchmarks PASSED ==="; \
	fi

.PHONY: _run-benchmark-workload
_run-benchmark-workload:
	$(call run-benchmark-test,$(BENCHMARK_WORKLOAD),$(REPORT_FILE))

# --- Per-workload benchmark targets ---

.PHONY: test-benchmark-batch
test-benchmark-batch: _check-docker-image ## Run batch benchmark
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	$(call run-benchmark-test,batch,$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-batch.json)

.PHONY: test-benchmark-collections
test-benchmark-collections: _check-docker-image ## Run collections benchmark
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	$(call run-benchmark-test,collections,$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-collections.json)

.PHONY: test-benchmark-conditional
test-benchmark-conditional: _check-docker-image ## Run conditional benchmark
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	$(call run-benchmark-test,conditional,$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-conditional.json)

.PHONY: test-benchmark-nested-collections
test-benchmark-nested-collections: _check-docker-image ## Run nested-collections benchmark
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	$(call run-benchmark-test,nested-collections,$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-nested-collections.json)

.PHONY: test-benchmark-primitives
test-benchmark-primitives: _check-docker-image ## Run primitives benchmark
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	$(call run-benchmark-test,primitives,$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-primitives.json)

.PHONY: test-benchmark-scan
test-benchmark-scan: _check-docker-image ## Run scan benchmark
	@mkdir -p $(BENCHMARK_REPORTS_DIR)
	$(call run-benchmark-test,scan,$(BENCHMARK_REPORTS_DIR)/$(ADAPTER_NAME)-scan.json)

# ==============================================================================
# Code Quality Targets
# ==============================================================================
.PHONY: lint
lint: ## Run linter (must pass in CI)
	@echo "Running linter..."
	golangci-lint run ./...

.PHONY: lint-fix
lint-fix: ## Run linter and fix issues
	@echo "Running linter with auto-fix..."
	golangci-lint run --fix ./...

.PHONY: fmt
fmt: ## Format code
	@echo "Formatting code..."
	$(GO) fmt ./...
	gofumpt -w .

.PHONY: fmt-check
fmt-check: ## Check code formatting (CI-safe)
	@echo "Checking code format..."
	@test -z "$$(gofmt -l .)" || (echo "Code is not formatted. Run 'make fmt'" && exit 1)

.PHONY: vet
vet: ## Run go vet
	@echo "Running go vet..."
	$(GO) vet ./...

.PHONY: tidy
tidy: ## Tidy go modules
	@echo "Tidying modules..."
	$(GO) mod tidy

# ==============================================================================
# Docker Targets
# ==============================================================================
.PHONY: _check-docker-image
_check-docker-image: ## Build Docker image only if it doesn't exist
	@if docker image inspect $(DOCKER_IMAGE) >/dev/null 2>&1; then \
		echo "Docker image $(DOCKER_IMAGE) already exists, skipping build"; \
	else \
		$(MAKE) --no-print-directory build-docker-image; \
	fi

.PHONY: build-docker-image
build-docker-image: ## Build Docker image
	@echo "Building Docker image $(DOCKER_IMAGE)..."
	docker build \
		--build-arg ADAPTER_VERSION=$(ADAPTER_VERSION) \
		-t $(DOCKER_IMAGE) \
		-t $(DOCKER_IMAGE_LATEST) \
		.

.PHONY: push-docker-image
push-docker-image: build-docker-image ## Build and push Docker image
	@echo "Pushing Docker image..."
	docker push $(DOCKER_IMAGE)
	docker push $(DOCKER_IMAGE_LATEST)

.PHONY: docker-run
docker-run: ## Run adapter container locally for testing
	@mkdir -p /tmp/latte-alternator
	docker run --rm -it \
		--network host \
		-v /tmp/latte-alternator:/sockets \
		-e LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock \
		-e LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
		$(DOCKER_IMAGE)

# ==============================================================================
# Development Targets
# ==============================================================================
.PHONY: run
run: build ## Run adapter locally (for development)
	@echo "Starting adapter..."
	LATTE_ALTERNATOR_SOCKET=$(TEST_SOCKET) \
	LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
	./$(BINARY)

.PHONY: start-dynamodb-local
start-dynamodb-local: ## Start DynamoDB Local for testing
	docker run --rm -d \
		--name dynamodb-local \
		-p 8000:8000 \
		amazon/dynamodb-local:latest \
		-jar DynamoDBLocal.jar -inMemory -sharedDb
	@echo "DynamoDB Local started at http://localhost:8000"

.PHONY: stop-dynamodb-local
stop-dynamodb-local: ## Stop DynamoDB Local
	docker stop dynamodb-local || true

.PHONY: start-alternator
start-alternator: ## Start ScyllaDB Alternator for testing
	docker run --rm -d \
		--name scylla-alternator \
		-p 8000:8000 \
		-p 9042:9042 \
		scylladb/scylla:latest \
		--alternator-port 8000 \
		--alternator-write-isolation only_rmw_uses_lwt
	@echo "Waiting for Alternator to start..."
	@sleep 10
	@echo "Alternator started at http://localhost:8000"

.PHONY: stop-alternator
stop-alternator: ## Stop ScyllaDB Alternator
	docker stop scylla-alternator || true

# ==============================================================================
# Profiling Targets
# ==============================================================================
# These targets run profiling benchmarks using Go's pprof.

PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= crud
PROFILE_OUTPUT_DIR ?= .profiles
PROFILE_SOCKET ?= /tmp/latte-profile-alternator.sock

.PHONY: profile
profile: profile-cpu ## Run CPU profiling (alias for profile-cpu)

.PHONY: profile-cpu
profile-cpu: build ## Run CPU profiling with pprof
	@echo "=== CPU Profiling: $(PROFILE_WORKLOAD) ==="
	@echo "Requests: $(PROFILE_REQUEST_COUNT)"
	@echo "Endpoint: $(TEST_ENDPOINT)"
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@# Build with profiling enabled
	@$(GO) build -o bin/adapter-profile ./cmd/adapter
	@LATTE_ALTERNATOR_SOCKET=$(PROFILE_SOCKET) \
		LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
		LATTE_ALTERNATOR_PPROF=:6060 \
		./bin/adapter-profile & \
	ADAPTER_PID=$$!; \
	cleanup() { kill $$ADAPTER_PID 2>/dev/null || true; rm -f $(PROFILE_SOCKET); }; \
	trap cleanup EXIT; \
	sleep 0.5; \
	if ! kill -0 $$ADAPTER_PID 2>/dev/null; then \
		echo "ERROR: Adapter failed to start"; \
		exit 1; \
	fi; \
	echo "Adapter started (PID: $$ADAPTER_PID)"; \
	echo "Setting up workload..."; \
	$(LATTE_BIN) schema --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-driver-socket $(PROFILE_SOCKET) -P "items=1000" $(WORKLOAD_DIR)/$(PROFILE_WORKLOAD).rn; \
	$(LATTE_BIN) load --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-driver-socket $(PROFILE_SOCKET) -P "items=1000" $(WORKLOAD_DIR)/$(PROFILE_WORKLOAD).rn; \
	echo "Starting CPU profile capture..."; \
	curl -s "http://localhost:6060/debug/pprof/profile?seconds=30" > $(PROFILE_OUTPUT_DIR)/cpu-$(ADAPTER_NAME)-$(PROFILE_WORKLOAD).pprof & \
	PPROF_PID=$$!; \
	echo "Running workload during profiling (30s)..."; \
	$(LATTE_BIN) run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-driver-socket $(PROFILE_SOCKET) -d 30s -P "items=1000" $(WORKLOAD_DIR)/$(PROFILE_WORKLOAD).rn || true; \
	wait $$PPROF_PID || true; \
	echo "Profile saved to: $(PROFILE_OUTPUT_DIR)/cpu-$(ADAPTER_NAME)-$(PROFILE_WORKLOAD).pprof"; \
	echo "View with: go tool pprof -http=:8080 $(PROFILE_OUTPUT_DIR)/cpu-$(ADAPTER_NAME)-$(PROFILE_WORKLOAD).pprof"; \
	cleanup

.PHONY: profile-mem
profile-mem: build ## Run memory profiling with pprof
	@echo "=== Memory Profiling: $(PROFILE_WORKLOAD) ==="
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@$(GO) build -o bin/adapter-profile ./cmd/adapter
	@LATTE_ALTERNATOR_SOCKET=$(PROFILE_SOCKET) \
		LATTE_ALTERNATOR_ENDPOINT=$(TEST_ENDPOINT) \
		LATTE_ALTERNATOR_PPROF=:6060 \
		./bin/adapter-profile & \
	ADAPTER_PID=$$!; \
	cleanup() { kill $$ADAPTER_PID 2>/dev/null || true; rm -f $(PROFILE_SOCKET); }; \
	trap cleanup EXIT; \
	sleep 0.5; \
	if ! kill -0 $$ADAPTER_PID 2>/dev/null; then \
		echo "ERROR: Adapter failed to start"; \
		exit 1; \
	fi; \
	echo "Adapter started (PID: $$ADAPTER_PID)"; \
	echo "Setting up and running workload..."; \
	$(LATTE_BIN) schema --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-driver-socket $(PROFILE_SOCKET) -P "items=1000" $(WORKLOAD_DIR)/$(PROFILE_WORKLOAD).rn; \
	$(LATTE_BIN) load --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-driver-socket $(PROFILE_SOCKET) -P "items=1000" $(WORKLOAD_DIR)/$(PROFILE_WORKLOAD).rn; \
	$(LATTE_BIN) run --dynamodb --dynamodb-endpoint $(TEST_ENDPOINT) --alternator-driver-socket $(PROFILE_SOCKET) -d 15s -P "items=1000" $(WORKLOAD_DIR)/$(PROFILE_WORKLOAD).rn; \
	echo "Capturing heap profile..."; \
	curl -s "http://localhost:6060/debug/pprof/heap" > $(PROFILE_OUTPUT_DIR)/heap-$(ADAPTER_NAME)-$(PROFILE_WORKLOAD).pprof; \
	echo "Profile saved to: $(PROFILE_OUTPUT_DIR)/heap-$(ADAPTER_NAME)-$(PROFILE_WORKLOAD).pprof"; \
	echo "View with: go tool pprof -http=:8080 $(PROFILE_OUTPUT_DIR)/heap-$(ADAPTER_NAME)-$(PROFILE_WORKLOAD).pprof"; \
	cleanup

# ==============================================================================
# Dependency Management
# ==============================================================================
.PHONY: deps
deps: ## Download dependencies
	@echo "Downloading dependencies..."
	$(GO) mod download

.PHONY: deps-update
deps-update: ## Update dependencies
	@echo "Updating dependencies..."
	$(GO) get -u ./...
	$(GO) mod tidy

# ==============================================================================
# Help
# ==============================================================================
.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
