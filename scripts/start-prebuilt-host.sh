#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="$ROOT_DIR/.env.application"
JAR_FILE="${APP_JAR_PATH:-}"
FRONTEND_ROOT="${APP_FRONTEND_ROOT:-$ROOT_DIR/frontend/dist}"
START_SERVICES=true
RELOAD_NGINX=false
usage() {
  cat <<'EOF'
Usage: bash scripts/start-prebuilt-host.sh [options]
  --env-file PATH       Default: .env.application
  --jar PATH            Default: the only backend/target application JAR
  --frontend-root PATH  Default: frontend/dist
  --skip-service-start  Require PostgreSQL and Nginx to already be active
  --reload-nginx        Validate and reload Nginx
This script never invokes Docker, npm, or Maven.
EOF
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="${2:?path required}"; shift 2 ;;
    --jar) JAR_FILE="${2:?path required}"; shift 2 ;;
    --frontend-root) FRONTEND_ROOT="${2:?path required}"; shift 2 ;;
    --skip-service-start) START_SERVICES=false; shift ;;
    --reload-nginx) RELOAD_NGINX=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done
absolute_path() { [[ "$1" = /* ]] && printf '%s\n' "$1" || printf '%s/%s\n' "$ROOT_DIR" "$1"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; exit 1; }; }
privileged() {
  if [[ $(id -u) -eq 0 ]]; then "$@"; return; fi
  command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1 && { sudo -n "$@"; return; }
  echo "Root permission required: $*" >&2; exit 1
}
service_ready() {
  systemctl is-active --quiet "$1" && return
  [[ "$START_SERVICES" == true ]] || { echo "Inactive service: $1" >&2; exit 1; }
  privileged systemctl start "$1"
  systemctl is-active --quiet "$1" || { echo "Service failed: $1" >&2; exit 1; }
}
ENV_FILE="$(absolute_path "$ENV_FILE")"
FRONTEND_ROOT="$(absolute_path "$FRONTEND_ROOT")"
[[ -f "$ENV_FILE" ]] || { echo "Missing environment file: $ENV_FILE" >&2; exit 1; }
set -a
source "$ENV_FILE"
set +a
for cmd in java curl pg_isready systemctl ps find xargs; do need "$cmd"; done
for name in APP_DATASOURCE_URL APP_DATASOURCE_USERNAME APP_DATASOURCE_PASSWORD APP_INITIAL_ADMIN_USERNAME APP_INITIAL_ADMIN_PASSWORD APP_REPOSITORY_ALLOWED_ROOTS APP_MANAGED_DATA_ROOT APP_LLM_MASTER_KEY APP_CREDENTIAL_MASTER_KEY; do
  [[ -n "${!name:-}" ]] || { echo "Missing variable: $name" >&2; exit 1; }
done
grep -q 'replace-with' "$ENV_FILE" && { echo "$ENV_FILE contains placeholder values." >&2; exit 1; }
[[ -f "$FRONTEND_ROOT/index.html" ]] || { echo "Missing frontend build: $FRONTEND_ROOT/index.html" >&2; exit 1; }
if [[ -z "$JAR_FILE" ]]; then
  mapfile -t jars < <(find "$ROOT_DIR/backend/target" -maxdepth 1 -type f -name 'codebase-knowledge-backend-*.jar' ! -name '*.jar.original')
  [[ ${#jars[@]} -eq 1 ]] || { echo "Expected one backend JAR; found ${#jars[@]}. Use --jar." >&2; exit 1; }
  JAR_FILE="${jars[0]}"
else JAR_FILE="$(absolute_path "$JAR_FILE")"; fi
[[ -f "$JAR_FILE" ]] || { echo "Missing backend JAR: $JAR_FILE" >&2; exit 1; }
mkdir -p "$APP_MANAGED_DATA_ROOT"
IFS=',;' read -ra roots <<< "$APP_REPOSITORY_ALLOWED_ROOTS"
for root in "${roots[@]}"; do
  root="$(echo "$root" | xargs)"
  [[ -n "$root" && -d "$root" && -r "$root" ]] || { echo "Unreadable repository root: $root" >&2; exit 1; }
done
POSTGRES_SERVICE="${APP_POSTGRES_SERVICE:-postgresql}"
NGINX_SERVICE="${APP_NGINX_SERVICE:-nginx}"
service_ready "$POSTGRES_SERVICE"
service_ready "$NGINX_SERVICE"
pg_isready -d "${APP_DATASOURCE_URL#jdbc:}" -U "$APP_DATASOURCE_USERNAME" >/dev/null || { echo "PostgreSQL is not ready." >&2; exit 1; }
if command -v nginx >/dev/null 2>&1; then
  if [[ $(id -u) -eq 0 ]]; then nginx -t
  elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then sudo -n nginx -t
  else echo "Warning: nginx -t skipped because sudo is unavailable." >&2; fi
else echo "Warning: nginx is active through systemd, but its executable is not in PATH." >&2; fi
[[ "$RELOAD_NGINX" == true ]] && privileged systemctl reload "$NGINX_SERVICE"
RUNTIME="$ROOT_DIR/runtime"
PID_FILE="$RUNTIME/backend.pid"
LOG_FILE="$RUNTIME/logs/backend.log"
mkdir -p "$RUNTIME/logs"
if [[ -f "$PID_FILE" ]]; then
  old_pid="$(tr -cd '0-9' < "$PID_FILE")"
  if [[ -n "$old_pid" ]] && kill -0 "$old_pid" 2>/dev/null; then
    old_cmd="$(ps -p "$old_pid" -o args= 2>/dev/null || true)"
    [[ "$old_cmd" == *"$JAR_FILE"* ]] || { echo "PID $old_pid is not the selected JAR." >&2; exit 1; }
    kill "$old_pid"
    for _ in $(seq 1 30); do kill -0 "$old_pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$old_pid" 2>/dev/null && { echo "Backend did not stop." >&2; exit 1; }
  fi
  rm -f "$PID_FILE"
fi
port="${APP_SERVER_PORT:-8080}"
curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && { echo "Port $port already has an unmanaged backend." >&2; exit 1; }
nohup java "-Xms${APP_JAVA_XMS:-256m}" "-Xmx${APP_JAVA_XMX:-768m}" -jar "$JAR_FILE" >"$LOG_FILE" 2>&1 </dev/null &
pid=$!
printf '%s\n' "$pid" > "$PID_FILE"
healthy=false
for _ in $(seq 1 90); do
  curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && { healthy=true; break; }
  kill -0 "$pid" 2>/dev/null || break
  sleep 2
done
if [[ "$healthy" != true ]]; then
  tail -n 120 "$LOG_FILE" >&2 || true
  kill -0 "$pid" 2>/dev/null && kill "$pid" || true
  rm -f "$PID_FILE"
  exit 1
fi
echo "Analyzer Coder is ready."
echo "Frontend: $FRONTEND_ROOT"
echo "Backend: http://127.0.0.1:$port/actuator/health"
echo "PID: $pid"
echo "Log: $LOG_FILE"
