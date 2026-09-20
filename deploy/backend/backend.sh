#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ACTION="${1:-help}"
[[ $# -eq 0 ]] || shift
PORT=8080
TIMEOUT=180
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="${2:?port required}"; shift 2 ;;
    --timeout) TIMEOUT="${2:?seconds required}"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
if [[ "$ACTION" == help || "$ACTION" == --help || "$ACTION" == -h ]]; then
  echo "Usage: bash backend.sh start|stop|restart|status [--port 8080] [--timeout 180]"
  echo "Port must match config/application.yml. JVM: APP_JAVA_XMS=256m APP_JAVA_XMX=768m."
  exit 0
fi
case "$ACTION" in start|stop|restart|status) ;; *) echo "Unknown action: $ACTION" >&2; exit 2 ;; esac
[[ "$PORT" =~ ^[0-9]+$ && "$TIMEOUT" =~ ^[0-9]+$ ]] || exit 2
(( PORT > 0 && PORT < 65536 && TIMEOUT > 0 )) || exit 2
cd "$ROOT"
umask 077
mkdir -p run logs
PID_FILE="$ROOT/run/backend.pid"
JAR="$ROOT/app.jar"
HEALTH="http://127.0.0.1:$PORT/actuator/health"
mkdir "$ROOT/run/control.lock" 2>/dev/null || {
  echo "Another control command is running. If it crashed, remove run/control.lock after checking." >&2
  exit 1
}
trap 'rmdir "$ROOT/run/control.lock" 2>/dev/null || true' EXIT

running() {
  [[ -f "$PID_FILE" ]] || return 1
  mapfile -t identity < "$PID_FILE"
  backend_pid="${identity[0]:-}"
  [[ "$backend_pid" =~ ^[0-9]+$ ]] || { echo "Invalid PID file; refusing to act." >&2; exit 1; }
  kill -0 "$backend_pid" 2>/dev/null || return 1
  local stamp
  stamp="$(ps -p "$backend_pid" -o lstart=)"
  [[ "$stamp" == "${identity[1]:-}" ]] || {
    echo "PID was reused; refusing to act on another process." >&2; exit 1;
  }
  local args=() i found=false
  mapfile -d '' -t args < "/proc/$backend_pid/cmdline"
  for ((i=0; i+1<${#args[@]}; i++)); do
    if [[ "${args[i]}" == -jar && "${args[i+1]}" == "$JAR" ]]; then found=true; break; fi
  done
  [[ "$found" == true ]] || { echo "PID does not run this app.jar; refusing to act." >&2; exit 1; }
}
healthy() {
  curl --noproxy '*' --fail --silent --max-time 3 "$HEALTH" |
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
}
stop_backend() {
  if ! running; then
    rm -f "$PID_FILE"
    echo "Backend is stopped."
    return
  fi
  kill "$backend_pid"
  for ((i=0; i<30; i++)); do
    if ! kill -0 "$backend_pid" 2>/dev/null || [[ "$(ps -p "$backend_pid" -o stat=)" == Z* ]]; then
      rm -f "$PID_FILE"
      echo "Backend stopped."
      return
    fi
    sleep 1
  done
  echo "Backend did not stop in 30 seconds; PID retained. No forced kill was performed." >&2
  exit 1
}
start_backend() {
  for tool in java curl git nohup ps; do
    command -v "$tool" >/dev/null || { echo "Missing command: $tool" >&2; exit 1; }
  done
  if running; then
    healthy && { echo "Backend is already healthy (PID $backend_pid)."; return; }
    echo "Backend is running but unhealthy. Check logs before restarting." >&2; exit 1
  fi
  [[ -f "$JAR" && -f config/application.yml ]] || {
    echo "Missing app.jar or config/application.yml." >&2; exit 1;
  }
  if sed '/^[[:space:]]*#/d' config/application.yml | grep -q 'replace-with'; then
    echo "Fill in config/application.yml before starting." >&2; exit 1
  fi
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    echo "Port $PORT is already occupied; refusing to launch." >&2; exit 1
  fi
  mkdir -p data repositories
  [[ ! -f logs/console.log ]] || mv -f logs/console.log logs/console.previous.log
  nohup java "-Xms${APP_JAVA_XMS:-256m}" "-Xmx${APP_JAVA_XMX:-768m}" -jar "$JAR" \
    > logs/console.log 2>&1 < /dev/null &
  backend_pid=$!
  printf '%s\n%s\n' "$backend_pid" "$(ps -p "$backend_pid" -o lstart=)" > "$PID_FILE"
  local deadline=$((SECONDS + TIMEOUT))
  while (( SECONDS < deadline )); do
    if ! running; then break; fi
    if healthy && running; then echo "Backend ready: $HEALTH (PID $backend_pid)"; return; fi
    sleep 2
  done
  echo "Startup failed. See logs/console.log and logs/backend.log." >&2
  stop_backend
  exit 1
}
case "$ACTION" in
  start) start_backend ;;
  stop) stop_backend ;;
  restart) stop_backend; start_backend ;;
  status)
    if running; then
      echo "Backend PID: $backend_pid"
      healthy && { echo "Health: UP"; exit 0; }
      echo "Health: unavailable" >&2; exit 1
    fi
    echo "Backend is stopped."; exit 1 ;;
esac
