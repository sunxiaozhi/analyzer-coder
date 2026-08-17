#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LOAD_ONLY=false

for argument in "$@"; do
  case "$argument" in
    --load-only) LOAD_ONLY=true ;;
    -h|--help)
      echo "Usage: bash install.sh [--load-only]"
      echo "  --load-only Verify and import images without starting services."
      exit 0
      ;;
    *) echo "Unknown option: $argument" >&2; exit 2 ;;
  esac
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }
}

require_command docker
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required (docker compose)." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not running or the current user cannot access it." >&2
  exit 1
fi

cd "$ROOT_DIR"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check SHA256SUMS
elif command -v shasum >/dev/null 2>&1; then
  shasum --algorithm 256 --check SHA256SUMS
else
  echo "Missing SHA-256 verification command: sha256sum or shasum" >&2
  exit 1
fi

echo "Importing offline Docker images..."
docker load --input images.tar

if [[ "$LOAD_ONLY" == true ]]; then
  echo "Images imported. Start later with: bash scripts/start.sh --components"
  exit 0
fi

exec bash "$ROOT_DIR/scripts/start.sh" --components
