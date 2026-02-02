# Latte Python Driver Adapter Dockerfile
FROM python:3.14-rc-slim

ARG DRIVER_VERSION=latest

# Install runtime dependencies only (libev4 for event loop)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libev4 \
    && rm -rf /var/lib/apt/lists/*

# Create app directory
WORKDIR /app

# Copy and install dependencies (uses pre-built wheels, no gcc needed)
COPY pyproject.toml ./
RUN pip install --no-cache-dir .

# Copy source code
COPY src/ ./src/

# Set environment defaults
ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock
ENV LATTE_DRIVER_CONTACT_POINTS=127.0.0.1:9042
ENV LATTE_DRIVER_INFLIGHT=512
ENV LATTE_DRIVER_LOG_LEVEL=INFO

# Create sockets directory
RUN mkdir -p /sockets

ENTRYPOINT ["python", "-m", "src.main"]
