FROM rust:1.90-slim-trixie AS builder

ARG DRIVER_VERSION=dev

WORKDIR /build

# Install build dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    libssl-dev \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Copy the driver adapter source
COPY Cargo.toml Cargo.lock ./
COPY src/ ./src/

# Build the driver
RUN cargo build --release

FROM debian:trixie-slim

ARG DRIVER_VERSION=dev

LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Driver Adapter (ScyllaDB Rust Driver)"
LABEL org.opencontainers.image.version="${DRIVER_VERSION}"

RUN apt-get update \
    && apt-get install -y libssl3 ca-certificates \
    && apt-get autoremove -y \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

COPY --from=builder /build/target/release/latte-driver /usr/local/bin/latte-driver

# Default socket directory
ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock
ENV LATTE_DRIVER_CONTACT_POINTS=host.docker.internal:9042

VOLUME /sockets

ENTRYPOINT ["/usr/local/bin/latte-driver"]
