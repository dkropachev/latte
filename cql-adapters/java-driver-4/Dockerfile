# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy pom.xml and source, then build
# Note: We skip dependency:go-offline because the ScyllaDB driver has
# transitive dependencies with OS-specific classifiers that require
# the os-maven-plugin extension to resolve properly
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the fat jar from builder (maven-shade-plugin creates latte-driver-java.jar)
COPY --from=builder /build/target/latte-driver-java.jar /app/latte-driver-java.jar

# Default configuration
ENV LATTE_DRIVER_SOCKET=/sockets/latte-driver.sock
ENV LATTE_DRIVER_INFLIGHT=512

# Create sockets directory
RUN mkdir -p /sockets

ENTRYPOINT ["java", "-jar", "/app/latte-driver-java.jar"]
