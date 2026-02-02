# ScyllaDB C# Driver Adapter Dockerfile
#
# Build: docker build -t scylladb/latte-driver-adapters:csharp-driver-latest .
# Run:   docker run --rm -v /tmp/sockets:/sockets scylladb/latte-driver-adapters:csharp-driver-latest

# ==============================================================================
# Build stage
# ==============================================================================
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS builder

WORKDIR /build

# Copy project file first for layer caching
COPY src/LatteDriver.csproj src/

# Restore dependencies
RUN cd src && dotnet restore -r linux-x64

# Copy source files
COPY src/ src/

# Build and publish
RUN cd src && dotnet publish -c Release -r linux-x64 --self-contained true -o /app

# ==============================================================================
# Runtime stage
# ==============================================================================
FROM mcr.microsoft.com/dotnet/runtime-deps:8.0

# Install any needed runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy the published application
COPY --from=builder /app /app

# Create socket directory
RUN mkdir -p /sockets

# Environment configuration
ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock
ENV LATTE_DRIVER_INFLIGHT=512
ENV LOG_LEVEL=info

# Run the adapter
ENTRYPOINT ["/app/latte-driver-csharp"]
