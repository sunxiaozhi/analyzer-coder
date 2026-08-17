#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
VERSION="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="$ROOT_DIR/release"
PLATFORM="linux/amd64"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:?--version requires a value}"; shift 2 ;;
    --output) OUTPUT_DIR="${2:?--output requires a value}"; shift 2 ;;
    --platform) PLATFORM="${2:?--platform requires a value}"; shift 2 ;;
    -h|--help)
      echo "Usage: bash scripts/build-offline-package.sh [options]"
      echo "  --version VERSION                  Package version (default: timestamp)"
      echo "  --output DIRECTORY                 Output directory (default: release)"
      echo "  --platform linux/amd64|linux/arm64 Target platform (default: linux/amd64)"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Version may contain only letters, digits, dot, underscore, and hyphen." >&2
  exit 2
fi

case "$OUTPUT_DIR" in
  /*) ;;
  *) OUTPUT_DIR="$ROOT_DIR/$OUTPUT_DIR" ;;
esac

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }
}

require_command docker
require_command tar
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not running or the current user cannot access it." >&2
  exit 1
fi

PACKAGE_NAME="analyzer-coder-components-offline-$VERSION"
PACKAGE_DIR="$OUTPUT_DIR/$PACKAGE_NAME"
ARCHIVE_PATH="$OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
if [[ -e "$PACKAGE_DIR" || -e "$ARCHIVE_PATH" ]]; then
  echo "Output already exists: $PACKAGE_DIR or $ARCHIVE_PATH" >&2
  exit 1
fi

cd "$ROOT_DIR"

echo "Fetching PostgreSQL/pgvector image for $PLATFORM..."
docker pull --platform "$PLATFORM" pgvector/pgvector:pg17
docker tag pgvector/pgvector:pg17 analyzer-coder/postgres:offline

echo "Fetching Nginx image for $PLATFORM..."
docker pull --platform "$PLATFORM" nginx:1.27-alpine
docker tag nginx:1.27-alpine analyzer-coder/nginx:offline

mkdir -p "$PACKAGE_DIR/scripts" "$PACKAGE_DIR/deploy"
cp compose.offline.yaml "$PACKAGE_DIR/compose.components.yaml"
cp deploy/nginx-components.conf "$PACKAGE_DIR/deploy/nginx-components.conf"
cp deploy/analyzer-coder.service "$PACKAGE_DIR/deploy/analyzer-coder.service"
cp deploy/analyzer-coder-components.env.example "$PACKAGE_DIR/deploy/analyzer-coder-components.env.example"
cp scripts/start.sh "$PACKAGE_DIR/scripts/start.sh"
cp scripts/start.ps1 "$PACKAGE_DIR/scripts/start.ps1"
cp scripts/offline-install.sh "$PACKAGE_DIR/install.sh"
cp scripts/offline-install.ps1 "$PACKAGE_DIR/install.ps1"
cp deploy/OFFLINE-README.md "$PACKAGE_DIR/README.md"
cp docs/07-linux-component-quickstart.md "$PACKAGE_DIR/STARTUP-GUIDE.md"

cat > "$PACKAGE_DIR/MANIFEST.txt" <<EOF
Analyzer Coder offline package
Version: $VERSION
Created: $(date -u +%Y-%m-%dT%H:%M:%SZ)
Platform: $PLATFORM
Images:
  analyzer-coder/postgres:offline
  analyzer-coder/nginx:offline
EOF

echo "Exporting Docker images..."
docker save --output "$PACKAGE_DIR/images.tar" \
  analyzer-coder/postgres:offline \
  analyzer-coder/nginx:offline

cd "$PACKAGE_DIR"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum images.tar > SHA256SUMS
elif command -v shasum >/dev/null 2>&1; then
  shasum --algorithm 256 images.tar > SHA256SUMS
else
  echo "Missing SHA-256 command: sha256sum or shasum" >&2
  exit 1
fi
chmod +x install.sh scripts/start.sh

cd "$OUTPUT_DIR"
tar -czf "$ARCHIVE_PATH" "$PACKAGE_NAME"
echo "Offline package created: $ARCHIVE_PATH"
