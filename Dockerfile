# Build stage using GraalVM Community Edition
FROM ghcr.io/graalvm/graalvm-community:21 AS builder

# Install necessary build tools
RUN microdnf install -y findutils tar gzip

# Set working directory
WORKDIR /build

# Copy gradle wrapper and project files
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle ./
COPY settings.gradle ./

# Copy source code
COPY src ./src

RUN java -version

# Make gradlew executable
RUN chmod +x gradlew

# Build native image
RUN ./gradlew nativeCompile --no-daemon

# Runtime stage - minimal image for the native binary
FROM debian:bookworm-slim

# Install necessary runtime dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy the native binary from builder
COPY --from=builder /build/build/native/nativeCompile/slack-ai-assistant /app/slack-ai-assistant

# Set the binary as executable
RUN chmod +x /app/slack-ai-assistant

# Set working directory
WORKDIR /app

# Run the native binary
ENTRYPOINT ["/app/slack-ai-assistant"]