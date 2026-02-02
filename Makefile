.PHONY: fmt
fmt:
	cargo fmt --all

.PHONY: fmt-check
fmt-check:
	cargo fmt --all -- --check

.PHONY: check
check:
	cargo check --all-targets

.PHONY: clippy
clippy:
	RUSTFLAGS=-Dwarnings cargo clippy --all-targets

.PHONY: test
test:
	cargo test

.PHONY: build
build:
	cargo build --examples --benches

.PHONY: clean
clean:
	cargo clean

.PHONY: docker-build
docker-build:
	docker build --target production -t scylladb/latte:latest --compress .

# ============================================================================
# Universal Loader / External Driver targets
# ============================================================================

DRIVER_IMAGE ?= scylladb/latte-driver-adapters:scylla-rust-driver-latest
SCYLLA_IMAGE ?= scylladb/scylla:latest
DRIVER_SOCKET ?= /tmp/latte-driver.sock
SCYLLA_CONTAINER ?= latte-scylla

.PHONY: driver-build
driver-build:
	cd cql-adapters/scylla-rust-driver && $(MAKE) build-docker-image

.PHONY: driver-build-local
driver-build-local:
	cd cql-adapters/scylla-rust-driver && cargo build --release

.PHONY: scylla-start
scylla-start:
	@echo "Starting ScyllaDB container..."
	@docker rm -f $(SCYLLA_CONTAINER) 2>/dev/null || true
	docker run -d --name $(SCYLLA_CONTAINER) \
		-p 9042:9042 \
		$(SCYLLA_IMAGE) \
		--smp 2 --memory 2G --overprovisioned 1
	@echo "Waiting for ScyllaDB to be ready..."
	@for i in $$(seq 1 60); do \
		if docker exec $(SCYLLA_CONTAINER) cqlsh -e "SELECT now() FROM system.local" >/dev/null 2>&1; then \
			echo "ScyllaDB is ready!"; \
			exit 0; \
		fi; \
		echo "Waiting... ($$i/60)"; \
		sleep 2; \
	done; \
	echo "Timeout waiting for ScyllaDB"; exit 1

.PHONY: scylla-stop
scylla-stop:
	docker rm -f $(SCYLLA_CONTAINER) 2>/dev/null || true

# Run latte with external driver image (auto-starts/stops container)
.PHONY: run-external
run-external:
	cargo run --release -- run \
		--driver-image $(DRIVER_IMAGE) \
		--driver-socket $(DRIVER_SOCKET) \
		-d 10s \
		workloads/basic/read.rn \
		127.0.0.1:9042

# Full integration test with Docker driver (auto-managed by latte)
.PHONY: integration-test
integration-test: scylla-start driver-build
	@echo "Running integration test..."
	cargo run --release -- schema workloads/basic/read.rn 127.0.0.1:9042
	cargo run --release -- load workloads/basic/read.rn 127.0.0.1:9042
	cargo run --release -- run \
		--driver-image $(DRIVER_IMAGE) \
		--driver-socket $(DRIVER_SOCKET) \
		-d 10s \
		workloads/basic/read.rn \
		127.0.0.1:9042
	@echo "Integration test complete!"

# Clean up ScyllaDB container
.PHONY: integration-clean
integration-clean: scylla-stop
	@rm -f $(DRIVER_SOCKET) 2>/dev/null || true

# Quick local test (manually managed driver process)
.PHONY: integration-test-local
integration-test-local: scylla-start driver-build-local
	@echo "Running local integration test..."
	@rm -f $(DRIVER_SOCKET)
	@echo "Starting local driver adapter..."
	@LATTE_DRIVER_SOCKET=$(DRIVER_SOCKET) \
		./cql-adapters/scylla-rust-driver/target/release/latte-driver & \
		echo $$! > .driver.pid
	@for i in $$(seq 1 30); do \
		if [ -S $(DRIVER_SOCKET) ]; then \
			echo "Driver socket ready!"; \
			break; \
		fi; \
		if [ $$i -eq 30 ]; then \
			echo "Timeout waiting for driver socket"; \
			kill `cat .driver.pid` 2>/dev/null || true; \
			rm -f .driver.pid; \
			exit 1; \
		fi; \
		sleep 1; \
	done
	cargo run --release -- schema workloads/basic/read.rn 127.0.0.1:9042
	cargo run --release -- load workloads/basic/read.rn 127.0.0.1:9042
	cargo run --release -- run \
		--driver-socket $(DRIVER_SOCKET) \
		-d 10s \
		workloads/basic/read.rn \
		127.0.0.1:9042
	@echo "Stopping driver..."
	@kill `cat .driver.pid` 2>/dev/null || true
	@rm -f .driver.pid $(DRIVER_SOCKET)
	@echo "Local integration test complete!"

.PHONY: integration-clean-local
integration-clean-local: scylla-stop
	@pkill -f latte-driver 2>/dev/null || true
	@rm -f $(DRIVER_SOCKET) .driver.pid 2>/dev/null || true

# ============================================================================
# Alternator (DynamoDB-compatible API) integration tests
# Based on alternator-client-golang test infrastructure
# ============================================================================

ALTERNATOR_COMPOSE_FILE ?= test/alternator/docker-compose.yml
ALTERNATOR_CONTAINER ?= latte-alternator-test
ALTERNATOR_ENDPOINT ?= http://localhost:8000

# Start ScyllaDB with Alternator enabled using docker-compose
.PHONY: alternator-start
alternator-start:
	@echo "Starting ScyllaDB Alternator cluster..."
	docker compose -f $(ALTERNATOR_COMPOSE_FILE) up -d
	@echo "Waiting for Alternator to be ready..."
	@for i in $$(seq 1 60); do \
		if curl -s $(ALTERNATOR_ENDPOINT) >/dev/null 2>&1; then \
			echo "Alternator is ready!"; \
			exit 0; \
		fi; \
		echo "Waiting... ($$i/60)"; \
		sleep 2; \
	done; \
	echo "Timeout waiting for Alternator"; \
	docker compose -f $(ALTERNATOR_COMPOSE_FILE) logs; \
	exit 1

# Stop the Alternator cluster
.PHONY: alternator-stop
alternator-stop:
	@echo "Stopping Alternator cluster..."
	docker compose -f $(ALTERNATOR_COMPOSE_FILE) down -v 2>/dev/null || true

# View Alternator cluster logs
.PHONY: alternator-logs
alternator-logs:
	docker compose -f $(ALTERNATOR_COMPOSE_FILE) logs -f

# Run DynamoDB integration tests against Alternator
# This runs the Rust integration tests marked with #[ignore]
.PHONY: test-alternator-integration
test-alternator-integration: alternator-start
	@echo "Running DynamoDB/Alternator integration tests..."
	@# Set environment variable for tests to use Alternator endpoint
	DYNAMODB_ENDPOINT=$(ALTERNATOR_ENDPOINT) cargo test -- --ignored 2>&1; \
	TEST_EXIT_CODE=$$?; \
	echo "Stopping Alternator cluster..."; \
	docker compose -f $(ALTERNATOR_COMPOSE_FILE) down -v 2>/dev/null || true; \
	exit $$TEST_EXIT_CODE

# Run DynamoDB integration tests against DynamoDB Local (for comparison)
.PHONY: test-dynamodb-local-integration
test-dynamodb-local-integration:
	@echo "Starting DynamoDB Local..."
	@docker rm -f dynamodb-local 2>/dev/null || true
	docker run -d --name dynamodb-local -p 8000:8000 amazon/dynamodb-local -jar DynamoDBLocal.jar -sharedDb
	@echo "Waiting for DynamoDB Local to be ready..."
	@for i in $$(seq 1 30); do \
		if curl -s http://localhost:8000 >/dev/null 2>&1; then \
			echo "DynamoDB Local is ready!"; \
			break; \
		fi; \
		echo "Waiting... ($$i/30)"; \
		sleep 1; \
	done
	@echo "Running DynamoDB integration tests..."
	@cargo test -- --ignored 2>&1; \
	TEST_EXIT_CODE=$$?; \
	echo "Stopping DynamoDB Local..."; \
	docker rm -f dynamodb-local 2>/dev/null || true; \
	exit $$TEST_EXIT_CODE

# Clean up Alternator test resources
.PHONY: alternator-clean
alternator-clean: alternator-stop
	@echo "Cleaning up Alternator test resources..."
	docker network rm latte_alternator_network 2>/dev/null || true

# ============================================================================
# CI/CD Workload Tests
# ============================================================================

CICD_WORKLOADS = crud query batch scan conditional
CICD_WORKLOAD_DIR = workloads/dynamodb/cicd
CICD_RUN_DURATION ?= 10s
CICD_ITEMS ?= 1000

# Run all CI/CD workloads against Alternator
.PHONY: test-alternator-workloads
test-alternator-workloads: alternator-start build
	@echo "Running CI/CD workloads against Alternator..."
	@FAILED=0; \
	for workload in $(CICD_WORKLOADS); do \
		echo ""; \
		echo "========================================"; \
		echo "Running workload: $$workload"; \
		echo "========================================"; \
		./target/debug/latte schema --dynamodb --dynamodb-endpoint $(ALTERNATOR_ENDPOINT) \
			$(CICD_WORKLOAD_DIR)/$$workload.rn || { FAILED=1; continue; }; \
		./target/debug/latte load --dynamodb --dynamodb-endpoint $(ALTERNATOR_ENDPOINT) \
			-P items=$(CICD_ITEMS) \
			$(CICD_WORKLOAD_DIR)/$$workload.rn || { FAILED=1; continue; }; \
		./target/debug/latte run --dynamodb --dynamodb-endpoint $(ALTERNATOR_ENDPOINT) \
			-d $(CICD_RUN_DURATION) \
			$(CICD_WORKLOAD_DIR)/$$workload.rn || FAILED=1; \
	done; \
	echo ""; \
	echo "========================================"; \
	echo "Stopping Alternator cluster..."; \
	docker compose -f $(ALTERNATOR_COMPOSE_FILE) down -v 2>/dev/null || true; \
	if [ $$FAILED -eq 1 ]; then \
		echo "Some workloads failed!"; \
		exit 1; \
	else \
		echo "All CI/CD workloads passed!"; \
	fi

# Run a single CI/CD workload against Alternator (WORKLOAD=crud)
.PHONY: test-alternator-workload
test-alternator-workload: alternator-start build
	@if [ -z "$(WORKLOAD)" ]; then \
		echo "Usage: make test-alternator-workload WORKLOAD=<name>"; \
		echo "Available workloads: $(CICD_WORKLOADS)"; \
		exit 1; \
	fi
	@echo "Running workload: $(WORKLOAD)"
	./target/debug/latte schema --dynamodb --dynamodb-endpoint $(ALTERNATOR_ENDPOINT) \
		$(CICD_WORKLOAD_DIR)/$(WORKLOAD).rn
	./target/debug/latte load --dynamodb --dynamodb-endpoint $(ALTERNATOR_ENDPOINT) \
		-P items=$(CICD_ITEMS) \
		$(CICD_WORKLOAD_DIR)/$(WORKLOAD).rn
	./target/debug/latte run --dynamodb --dynamodb-endpoint $(ALTERNATOR_ENDPOINT) \
		-d $(CICD_RUN_DURATION) \
		$(CICD_WORKLOAD_DIR)/$(WORKLOAD).rn
	@echo "Stopping Alternator cluster..."
	@docker compose -f $(ALTERNATOR_COMPOSE_FILE) down -v 2>/dev/null || true

# Run all CI/CD workloads against DynamoDB Local
.PHONY: test-dynamodb-local-workloads
test-dynamodb-local-workloads: build
	@echo "Starting DynamoDB Local..."
	@docker rm -f dynamodb-local 2>/dev/null || true
	@docker run -d --name dynamodb-local -p 8000:8000 amazon/dynamodb-local -jar DynamoDBLocal.jar -sharedDb
	@echo "Waiting for DynamoDB Local to be ready..."
	@for i in $$(seq 1 30); do \
		if curl -s http://localhost:8000 >/dev/null 2>&1; then \
			echo "DynamoDB Local is ready!"; \
			break; \
		fi; \
		sleep 1; \
	done
	@echo "Running CI/CD workloads against DynamoDB Local..."
	@FAILED=0; \
	for workload in $(CICD_WORKLOADS); do \
		echo ""; \
		echo "========================================"; \
		echo "Running workload: $$workload"; \
		echo "========================================"; \
		./target/debug/latte schema --dynamodb --dynamodb-endpoint http://localhost:8000 \
			$(CICD_WORKLOAD_DIR)/$$workload.rn || { FAILED=1; continue; }; \
		./target/debug/latte load --dynamodb --dynamodb-endpoint http://localhost:8000 \
			-P items=$(CICD_ITEMS) \
			$(CICD_WORKLOAD_DIR)/$$workload.rn || { FAILED=1; continue; }; \
		./target/debug/latte run --dynamodb --dynamodb-endpoint http://localhost:8000 \
			-d $(CICD_RUN_DURATION) \
			$(CICD_WORKLOAD_DIR)/$$workload.rn || FAILED=1; \
	done; \
	echo ""; \
	echo "========================================"; \
	echo "Stopping DynamoDB Local..."; \
	docker rm -f dynamodb-local 2>/dev/null || true; \
	if [ $$FAILED -eq 1 ]; then \
		echo "Some workloads failed!"; \
		exit 1; \
	else \
		echo "All CI/CD workloads passed!"; \
	fi
