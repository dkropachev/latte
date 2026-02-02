# ==============================================================================
# Build stage
# ==============================================================================
FROM golang:1.24-alpine AS builder

WORKDIR /build

# Install build dependencies
RUN apk add --no-cache git

# Copy go mod files first for better caching
COPY go.mod go.sum* ./
RUN go mod download

# Copy source code
COPY . .

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o adapter ./cmd/adapter

# ==============================================================================
# Runtime stage
# ==============================================================================
FROM alpine:latest

ARG ADAPTER_VERSION=dev

# OCI labels
LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Alternator Adapter (alternator-client-golang)"
LABEL org.opencontainers.image.description="Alternator adapter using scylladb/alternator-client-golang with load balancing"
LABEL org.opencontainers.image.version="${ADAPTER_VERSION}"
LABEL org.opencontainers.image.vendor="ScyllaDB"

# Install runtime dependencies (CA certificates for HTTPS)
RUN apk add --no-cache ca-certificates

# Copy binary from builder
COPY --from=builder /build/adapter /app/adapter

# ==============================================================================
# Socket Configuration
# ==============================================================================
# Default socket path - MUST be inside /sockets/ directory
ENV LATTE_ALTERNATOR_SOCKET=/sockets/latte-alternator.sock

# Default endpoint for Alternator/DynamoDB
ENV LATTE_ALTERNATOR_ENDPOINT=http://localhost:8000

# Default AWS region
ENV LATTE_ALTERNATOR_REGION=us-east-1

# Concurrency limit
ENV LATTE_ALTERNATOR_INFLIGHT=512

# Create sockets directory
RUN mkdir -p /sockets

# Declare /sockets as a volume mount point
VOLUME /sockets

ENTRYPOINT ["/app/adapter"]
