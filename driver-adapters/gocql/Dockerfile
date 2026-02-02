FROM golang:1.25-alpine AS builder

ARG DRIVER_VERSION=dev

WORKDIR /build

# Copy module files first for layer caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source and PGO profile
COPY . .

# Update go.sum for replace directives
RUN go mod tidy

# Build with PGO if profile exists (provides 2-7% perf improvement)
RUN if [ -f default.pgo ]; then \
        echo "Building with PGO profile..." && \
        CGO_ENABLED=0 go build -pgo=default.pgo -ldflags="-s -w" -o latte-gocql-driver .; \
    else \
        echo "Building without PGO (no default.pgo found)..." && \
        CGO_ENABLED=0 go build -ldflags="-s -w" -o latte-gocql-driver .; \
    fi

FROM alpine:latest

ARG DRIVER_VERSION=dev

LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Driver Adapter (GoCQL)"
LABEL org.opencontainers.image.version="${DRIVER_VERSION}"
LABEL org.opencontainers.image.description="GoCQL driver adapter for Latte benchmarking tool"

RUN apk --no-cache add ca-certificates

COPY --from=builder /build/latte-gocql-driver /usr/local/bin/

ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock
ENV LATTE_DRIVER_CONTACT_POINTS=host.docker.internal:9042

VOLUME /sockets

ENTRYPOINT ["/usr/local/bin/latte-gocql-driver"]
