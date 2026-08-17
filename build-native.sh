#!/usr/bin/env bash
#
# Builds the GraalVM native-image binary that runs on the `provided.al2023`
# Lambda runtime, and drops it at build/native/slack-ai-assistant.
#
# `sam build` invokes this via the build-SlackBotFunctionNative target in the
# Makefile, which then copies the binary to $ARTIFACTS_DIR/bootstrap.
#
# The build always runs inside Docker. The binary is dynamically linked against
# the glibc of the image it was built in, so it has to be produced on a Linux
# base matching the Lambda runtime and for the Lambda's CPU architecture — a
# macOS host cannot produce it directly, whatever its own architecture is.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Must match `Architectures:` for SlackBotFunctionNative in template.yaml.
LAMBDA_ARCH="${LAMBDA_ARCH:-x86_64}"
case "$LAMBDA_ARCH" in
  x86_64) DOCKER_PLATFORM="linux/amd64" ;;
  arm64)  DOCKER_PLATFORM="linux/arm64" ;;
  *)
    echo "error: LAMBDA_ARCH must be x86_64 or arm64, got '$LAMBDA_ARCH'" >&2
    exit 1
    ;;
esac

IMAGE="slack-ai-assistant-native:${LAMBDA_ARCH}"
OUT_DIR="$ROOT/build/native"
OUT_BIN="$OUT_DIR/slack-ai-assistant"
BIN_IN_IMAGE="/build/build/native/nativeCompile/slack-ai-assistant"

if ! command -v docker >/dev/null 2>&1; then
  echo "error: docker is not installed; it is required to build the Lambda binary" >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "error: the Docker daemon is not running." >&2
  echo "       Start Rancher Desktop, or run: colima start --cpu 4 --memory 8" >&2
  exit 1
fi

# Building linux/amd64 on an arm64 host runs native-image under emulation, which
# is slow enough to look like a hang. Say so rather than leaving it a mystery.
HOST_ARCH="$(uname -m)"
if { [ "$LAMBDA_ARCH" = "x86_64" ] && [ "$HOST_ARCH" = "arm64" ]; } ||
   { [ "$LAMBDA_ARCH" = "arm64" ] && [ "$HOST_ARCH" = "x86_64" ]; }; then
  echo "note: building $DOCKER_PLATFORM on a $HOST_ARCH host uses emulation."
  echo "      Under QEMU the native-image step is slow enough to look hung."
  echo "      On Apple silicon, Rosetta translates amd64 containers far faster."
  echo "      Note this is an arm64 VM: Rosetta handles the amd64 binaries via"
  echo "      binfmt. (colima --arch x86_64 is a different thing and needs QEMU.)"
  echo "        colima start --profile rosetta --vm-type vz --vz-rosetta \\"
  echo "          --cpu 8 --memory 24 --disk 80"
  echo "        docker context use colima-rosetta"
fi

# The Gradle wrapper downloads its distribution from services.gradle.org, which
# 307s to github.com. Where GitHub resolves only through the host's VPN resolver,
# that redirect fails inside the build container while working fine on the host.
# So fetch the distribution here and hand it to the image, which keeps the
# container off GitHub entirely and makes repeat builds faster.
DIST_DIR="$ROOT/.gradle-dist"
DIST_ZIP="$DIST_DIR/gradle-dist.zip"
WRAPPER_PROPS="$ROOT/gradle/wrapper/gradle-wrapper.properties"

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

GRADLE_DIST_URL="$(sed -n 's/^distributionUrl=//p' "$WRAPPER_PROPS" | sed 's|\\:|:|g')"
[ -n "$GRADLE_DIST_URL" ] || {
  echo "error: no distributionUrl found in $WRAPPER_PROPS" >&2
  exit 1
}

mkdir -p "$DIST_DIR"
EXPECTED_SHA="$(curl -fsSL "${GRADLE_DIST_URL}.sha256")" || {
  echo "error: could not fetch the published checksum for $GRADLE_DIST_URL" >&2
  exit 1
}

ACTUAL_SHA=""
[ -f "$DIST_ZIP" ] && ACTUAL_SHA="$(sha256_of "$DIST_ZIP")"

if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  echo "==> Fetching $GRADLE_DIST_URL"
  curl -fSL --progress-bar -o "$DIST_ZIP" "$GRADLE_DIST_URL"
  ACTUAL_SHA="$(sha256_of "$DIST_ZIP")"
  if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    echo "error: Gradle distribution checksum mismatch" >&2
    echo "       expected $EXPECTED_SHA" >&2
    echo "       actual   $ACTUAL_SHA" >&2
    rm -f "$DIST_ZIP"
    exit 1
  fi
fi
echo "==> Gradle distribution verified ($EXPECTED_SHA)"

echo "==> Building native image for $LAMBDA_ARCH ($DOCKER_PLATFORM)"
docker build --platform "$DOCKER_PLATFORM" --target builder \
  --build-arg "GRADLE_DIST_SHA256=$EXPECTED_SHA" -t "$IMAGE" .

echo "==> Extracting binary from the build image"
mkdir -p "$OUT_DIR"
CONTAINER_ID="$(docker create --platform "$DOCKER_PLATFORM" "$IMAGE")"
trap 'docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true' EXIT
docker cp "$CONTAINER_ID:$BIN_IN_IMAGE" "$OUT_BIN"
chmod +x "$OUT_BIN"

echo "==> Built $OUT_BIN"
file "$OUT_BIN" 2>/dev/null || ls -lh "$OUT_BIN"
