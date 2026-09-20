#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
command -v node >/dev/null || { echo "Node.js 20+ is required on the build machine." >&2; exit 1; }
exec node "$ROOT/scripts/build-release.mjs" "$@"
