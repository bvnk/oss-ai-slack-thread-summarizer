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
COPY gradle.properties ./

# The wrapper's distributionUrl on services.gradle.org redirects to github.com,
# which does not resolve on every network this builds on (a VPN split-DNS setup
# will resolve it on the host but not inside the VM). build-native.sh fetches the
# distribution on the host and drops it here, so the container never has to
# reach GitHub. The checksum is the one Gradle publishes, and it is verified
# twice: once here, and again by the wrapper via distributionSha256Sum.
ARG GRADLE_DIST_SHA256
COPY .gradle-dist/gradle-dist.zip /tmp/gradle-dist.zip
RUN echo "${GRADLE_DIST_SHA256}  /tmp/gradle-dist.zip" | sha256sum -c - && \
    printf 'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=file\\:/tmp/gradle-dist.zip\ndistributionSha256Sum=%s\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\nnetworkTimeout=10000\nvalidateDistributionUrl=false\n' \
      "${GRADLE_DIST_SHA256}" > gradle/wrapper/gradle-wrapper.properties

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