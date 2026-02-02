# Multi-stage build for cpp-driver adapter
FROM ubuntu:22.04 AS builder

ARG DRIVER_VERSION=dev
ARG CPP_DRIVER_VERSION=2.9.0

# Install build dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    libuv1-dev \
    libssl-dev \
    pkg-config \
    curl \
    wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Clone and build cpp-driver from source
RUN git clone --depth 1 --branch ${CPP_DRIVER_VERSION} https://github.com/scylladb/cpp-driver.git /cpp-driver
WORKDIR /cpp-driver
RUN mkdir build && cd build && \
    cmake -DCASS_BUILD_STATIC=OFF \
          -DCASS_BUILD_TESTS=OFF \
          -DCASS_BUILD_EXAMPLES=OFF \
          -DCMAKE_BUILD_TYPE=Release \
          .. && \
    make -j$(nproc)
RUN mkdir -p /usr/local/lib /usr/local/include/cassandra && \
    cp build/libcassandra.so* /usr/local/lib/ && \
    cp include/*.h /usr/local/include/cassandra/

# Build the adapter
WORKDIR /build/adapter
COPY CMakeLists.txt ./
COPY src/ ./src/

RUN mkdir build && cd build && \
    cmake -DCMAKE_BUILD_TYPE=Release \
          -DCASSANDRA_DRIVER_LIBRARIES=/usr/local/lib/libcassandra.so \
          -DCASSANDRA_DRIVER_INCLUDE_DIRS=/usr/local/include/cassandra \
          .. && \
    make -j$(nproc)

# Runtime image
FROM ubuntu:22.04

ARG DRIVER_VERSION=dev

LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Driver Adapter (cpp-driver)"
LABEL org.opencontainers.image.version="${DRIVER_VERSION}"
LABEL org.opencontainers.image.description="Deprecated cpp-driver adapter for benchmarking legacy deployments"

RUN apt-get update && apt-get install -y \
    libuv1 \
    libssl3 \
    ca-certificates \
    && apt-get autoremove -y \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Copy cpp-driver library
COPY --from=builder /usr/local/lib/libcassandra.so* /usr/local/lib/

# Copy adapter binary
COPY --from=builder /build/adapter/build/latte-cpp-driver /usr/local/bin/

# Update library cache
RUN ldconfig

# Default socket directory
ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock
ENV LATTE_DRIVER_CONTACT_POINTS=host.docker.internal:9042
ENV LD_LIBRARY_PATH=/usr/local/lib

VOLUME /sockets

ENTRYPOINT ["/usr/local/bin/latte-cpp-driver"]
