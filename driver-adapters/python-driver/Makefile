# Latte Python Driver Adapter Makefile

DRIVER_VERSION ?= latest
DOCKER_REGISTRY ?= scylladb
IMAGE_NAME = $(DOCKER_REGISTRY)/latte-driver-adapters
IMAGE_TAG = python-driver-$(DRIVER_VERSION)
PYTHON ?= python3.14
VENV_DIR = .venv
VENV_BIN = $(VENV_DIR)/bin

.PHONY: help
help:
	@echo "Latte Python Driver Adapter"
	@echo ""
	@echo "Usage:"
	@echo "  make build              Build/install in virtual environment"
	@echo "  make test               Run unit tests"
	@echo "  make test-integration   Run all integration test suites (~10s each)"
	@echo "  make test-integration-primitives   Run primitives integration suite"
	@echo "  make test-integration-collections  Run collections integration suite"
	@echo "  make test-integration-vectors      Run vectors integration suite"
	@echo "  make test-benchmark     Run all benchmark suites (5m each)"
	@echo "  make test-benchmark-primitives     Run primitives benchmark"
	@echo "  make test-benchmark-collections    Run collections benchmark"
	@echo "  make test-benchmark-vectors        Run vectors benchmark"
	@echo "  make lint               Run ruff linter"
	@echo "  make lint-fix           Auto-fix lint issues"
	@echo "  make fmt                Format code"
	@echo "  make profile            Run CPU profiling with py-spy"
	@echo "  make profile-cpu        Run CPU profiling with py-spy"
	@echo "  make profile-mem        Run memory profiling with memray"
	@echo "  make build-docker-image Build Docker image"
	@echo "  make push-docker-image  Push Docker image to registry"
	@echo "  make docker-run         Run adapter in Docker locally"
	@echo "  make run                Run adapter locally"
	@echo "  make clean              Clean build artifacts"
	@echo ""
	@echo "Variables:"
	@echo "  DRIVER_VERSION          Docker image version tag (default: latest)"
	@echo "  DOCKER_REGISTRY         Docker registry (default: scylladb)"
	@echo "  PYTHON                  Python interpreter (default: python3.14)"
	@echo ""
	@echo "Test Variables:"
	@echo "  SCYLLA_CONTACT_POINTS   Database contact points (default: 127.0.0.1:9042)"
	@echo "  LATTE_BIN               Path to latte binary (default: latte)"
	@echo "  INTEGRATION_ROW_COUNT   Rows for integration tests (default: 100)"
	@echo "  INTEGRATION_DURATION    Duration for integration tests (default: 10s)"
	@echo "  BENCHMARK_ROW_COUNT     Rows for benchmarks (default: 10000)"
	@echo "  BENCHMARK_DURATION      Duration for benchmarks (default: 5s)"
	@echo ""
	@echo "Profiling Variables:"
	@echo "  PROFILE_REQUEST_COUNT   Number of requests for profiling (default: 10000)"
	@echo "  PROFILE_WORKLOAD        Workload to profile (default: primitives)"
	@echo "  PROFILE_OUTPUT_DIR      Directory for profile output (default: ../profiles)"

# Build targets
.PHONY: build
build: $(VENV_DIR)
	@echo "Python driver adapter ready."

$(VENV_DIR): pyproject.toml
	@if ! command -v $(PYTHON) >/dev/null 2>&1; then \
		echo ""; \
		echo "============================================================"; \
		echo "ERROR: $(PYTHON) not found"; \
		echo ""; \
		echo "Please install Python 3.14:"; \
		echo "  - Using pyenv: pyenv install 3.14.0a3"; \
		echo "  - Or from source: https://www.python.org/downloads/"; \
		echo "============================================================"; \
		exit 1; \
	fi
	@echo "Creating virtual environment with $(PYTHON)..."
	$(PYTHON) -m venv --without-pip $(VENV_DIR) || $(PYTHON) -m venv $(VENV_DIR)
	@if ! $(VENV_BIN)/pip --version >/dev/null 2>&1; then \
		echo "Installing pip via get-pip.py..."; \
		curl -sS https://bootstrap.pypa.io/get-pip.py -o /tmp/get-pip.py; \
		$(VENV_BIN)/python /tmp/get-pip.py; \
		rm -f /tmp/get-pip.py; \
	fi
	$(VENV_BIN)/pip install --upgrade pip
	$(VENV_BIN)/pip install -e ".[dev]"
	@touch $(VENV_DIR)

.PHONY: test
test: $(VENV_DIR)
	$(VENV_BIN)/pytest -v tests/

.PHONY: lint
lint: $(VENV_DIR)
	$(VENV_BIN)/ruff check src/ tests/ 2>/dev/null || $(VENV_BIN)/ruff check src/

.PHONY: lint-fix
lint-fix: $(VENV_DIR)
	$(VENV_BIN)/ruff check --fix src/ tests/ 2>/dev/null || $(VENV_BIN)/ruff check --fix src/

.PHONY: fmt
fmt: $(VENV_DIR)
	$(VENV_BIN)/ruff format src/ tests/ 2>/dev/null || $(VENV_BIN)/ruff format src/

.PHONY: clean
clean:
	rm -rf $(VENV_DIR)
	rm -rf __pycache__ src/__pycache__ tests/__pycache__
	rm -rf .pytest_cache .ruff_cache
	rm -rf *.egg-info
	rm -f .test.pid

.PHONY: run
run: $(VENV_DIR)
	$(VENV_BIN)/python -m src.main

# ============================================================================
# Integration test & benchmark targets
# ============================================================================

# Common configuration
SCYLLA_CONTACT_POINTS ?= 127.0.0.1:9042
LATTE_BIN ?= $(shell command -v latte 2>/dev/null || echo "$(CURDIR)/../../target/release/latte")
WORKLOADS_DIR ?= ../workloads

# Integration test configuration (quick validation)
INTEGRATION_SOCKET ?= /tmp/latte-python-integration-test.sock
INTEGRATION_ROW_COUNT ?= 100
INTEGRATION_DURATION ?= 10s

# Benchmark configuration (performance testing)
BENCHMARK_SOCKET ?= /tmp/latte-python-benchmark-test.sock
BENCHMARK_ROW_COUNT ?= 10000
BENCHMARK_DURATION ?= 5s

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
# Benchmark tests (performance testing, 5 minutes per suite, time-limited only)
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

.PHONY: test-simple
test-simple: build ## Run simple workload (avoids Python driver timestamp limitations)
	@$(MAKE) --no-print-directory _run-single-suite \
		TEST_MODE=integration \
		SOCKET=$(INTEGRATION_SOCKET) \
		ROW_COUNT=$(INTEGRATION_ROW_COUNT) \
		DURATION=$(INTEGRATION_DURATION) \
		RUN_FUNCTION=validate \
		SUITE_NAME=simple \
		SUITE_FILE=$(WORKLOADS_DIR)/simple.rn

# ----------------------------------------------------------------------------
# Internal helper targets
# ----------------------------------------------------------------------------

.PHONY: _run-all-suites
_run-all-suites:
	@echo "=== $(TEST_MODE) Test Suite (Python Driver) ==="
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
		$(VENV_BIN)/python -m src.main & \
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
	@echo "=== $(TEST_MODE) Test: $(SUITE_NAME) (Python Driver) ==="
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
		$(VENV_BIN)/python -m src.main & \
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
PROFILE_SOCKET ?= /tmp/latte-python-profile.sock
PROFILE_REQUEST_COUNT ?= 10000
PROFILE_WORKLOAD ?= primitives
PROFILE_OUTPUT_DIR ?= ../profiles
PROFILE_WARMUP_COUNT ?= 1000

.PHONY: profile
profile: profile-cpu ## Alias for profile-cpu

.PHONY: profile-cpu
profile-cpu: build ## Run CPU profiling with py-spy
	@echo "=== CPU Profiling (Python Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT) (warmup: $(PROFILE_WARMUP_COUNT))"
	@echo ""
	@if ! command -v py-spy >/dev/null 2>&1; then \
		echo "============================================================"; \
		echo "ERROR: 'py-spy' not found"; \
		echo ""; \
		echo "Install py-spy:"; \
		echo "  pip install py-spy"; \
		echo "  # or"; \
		echo "  cargo install py-spy"; \
		echo ""; \
		echo "Note: py-spy requires root or ptrace permissions."; \
		echo "============================================================"; \
		exit 1; \
	fi
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	echo "Starting adapter..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
		$(VENV_BIN)/python -m src.main & \
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
	adapter_pid=$$(cat .profile.pid); \
	echo "Profiling $(PROFILE_REQUEST_COUNT) requests (pid: $$adapter_pid)..."; \
	py-spy record -o $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-$$timestamp.svg \
		--pid $$adapter_pid --subprocesses & \
	spy_pid=$$!; \
	sleep 1; \
	$(LATTE_BIN) run --driver-socket $(PROFILE_SOCKET) \
		-n $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn || true; \
	sleep 1; \
	kill -INT $$spy_pid 2>/dev/null || true; \
	wait $$spy_pid 2>/dev/null || true; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	if [ -f $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-$$timestamp.svg ]; then \
		echo ""; \
		echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-$$timestamp.svg ==="; \
		echo "View in browser: firefox $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-$$timestamp.svg"; \
	else \
		echo ""; \
		echo "WARNING: SVG not generated (py-spy may need root/ptrace permissions)"; \
		echo "Try: sudo make profile-cpu"; \
	fi

.PHONY: profile-mem
profile-mem: build ## Run memory profiling with memray
	@echo "=== Memory Profiling (Python Adapter) ==="
	@echo "Workload: $(PROFILE_WORKLOAD)"
	@echo "Requests: $(PROFILE_REQUEST_COUNT)"
	@echo ""
	@if ! $(VENV_BIN)/pip show memray >/dev/null 2>&1; then \
		echo "Installing memray..."; \
		$(VENV_BIN)/pip install memray; \
	fi
	@mkdir -p $(PROFILE_OUTPUT_DIR)
	@rm -f $(PROFILE_SOCKET)
	@timestamp=$$(date +%Y%m%d-%H%M%S); \
	profile_file=$(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-mem-$$timestamp.bin; \
	echo "Starting adapter with memray..."; \
	LATTE_DRIVER_SOCKET=$(PROFILE_SOCKET) \
		LATTE_DRIVER_CONTACT_POINTS=$(SCYLLA_CONTACT_POINTS) \
		$(VENV_BIN)/python -m memray run -o $$profile_file -m src.main & \
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
		-n $(PROFILE_REQUEST_COUNT) \
		-P row_count=$(BENCHMARK_ROW_COUNT) \
		$(WORKLOADS_DIR)/$(PROFILE_WORKLOAD).rn; \
	$(MAKE) --no-print-directory _cleanup-profile; \
	if [ -f $$profile_file ]; then \
		echo "Generating flamegraph..."; \
		$(VENV_BIN)/python -m memray flamegraph $$profile_file \
			-o $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-mem-$$timestamp.html; \
		echo ""; \
		echo "=== Profile saved to: $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-mem-$$timestamp.html ==="; \
		echo "View: firefox $(PROFILE_OUTPUT_DIR)/python-$(PROFILE_WORKLOAD)-mem-$$timestamp.html"; \
	fi

.PHONY: _cleanup-profile
_cleanup-profile:
	@echo "Stopping adapter..."
	@if [ -f .profile.pid ]; then \
		kill `cat .profile.pid` 2>/dev/null || true; \
		rm -f .profile.pid; \
	fi
	@rm -f $(PROFILE_SOCKET)

# Docker targets
.PHONY: build-docker-image
build-docker-image:
	docker build \
		--build-arg DRIVER_VERSION=$(DRIVER_VERSION) \
		-t $(IMAGE_NAME):$(IMAGE_TAG) \
		.
	@if [ "$(DRIVER_VERSION)" = "latest" ]; then \
		docker tag $(IMAGE_NAME):$(IMAGE_TAG) $(IMAGE_NAME):python-driver-latest; \
	fi

.PHONY: push-docker-image
push-docker-image:
	docker push $(IMAGE_NAME):$(IMAGE_TAG)
	@if [ "$(DRIVER_VERSION)" = "latest" ]; then \
		docker push $(IMAGE_NAME):python-driver-latest; \
	fi

.PHONY: docker-run
docker-run:
	docker run --rm -it \
		-v /tmp/latte-sockets:/sockets \
		-e LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock \
		-e LATTE_DRIVER_CONTACT_POINTS=host.docker.internal:9042 \
		$(IMAGE_NAME):$(IMAGE_TAG)

# CI target
.PHONY: ci
ci: lint test build
	@echo "CI checks passed"
