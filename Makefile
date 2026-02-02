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
	cd driver-adapters/scylla-rust-driver && $(MAKE) build-docker-image

.PHONY: driver-build-local
driver-build-local:
	cd driver-adapters/scylla-rust-driver && cargo build --release

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
		LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042 \
		./driver-adapters/scylla-rust-driver/target/release/latte-driver & \
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
