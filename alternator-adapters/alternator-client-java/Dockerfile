# ==============================================================================
# Build stage
# ==============================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml first for better dependency caching
COPY pom.xml .
RUN mvn -q dependency:go-offline

# Copy source code
COPY src/ src/

# Build the fat JAR
RUN mvn -q package -DskipTests

# ==============================================================================
# Runtime stage
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine

ARG ADAPTER_VERSION=dev

# OCI labels
LABEL org.opencontainers.image.source="https://github.com/scylladb/latte"
LABEL org.opencontainers.image.title="Latte Alternator Adapter (alternator-client-java)"
LABEL org.opencontainers.image.description="Alternator adapter using ScyllaDB Alternator load-balancing Java library"
LABEL org.opencontainers.image.version="${ADAPTER_VERSION}"
LABEL org.opencontainers.image.vendor="ScyllaDB"

# Copy fat JAR from builder
COPY --from=builder /build/target/alternator-client-java-1.0-SNAPSHOT.jar /app/adapter.jar

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

ENTRYPOINT ["java", "-jar", "/app/adapter.jar"]
