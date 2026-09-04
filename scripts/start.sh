#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
COMPONENT_ENV="$ROOT_DIR/.env.components"
APPLICATION_ENV="$ROOT_DIR/.env.application"
RUNTIME_DIR="$ROOT_DIR/runtime"
LOG_DIR="$RUNTIME_DIR/logs"
PID_FILE="$RUNTIME_DIR/backend.pid"
BACKEND_LOG="$LOG_DIR/backend.log"
SKIP_NPM_CI=false
ENABLE_HTTPS=false

for argument in "$@"; do
  case "$argument" in
    --skip-npm-ci) SKIP_NPM_CI=true ;;
    --https) ENABLE_HTTPS=true ;;
    -h|--help)
      echo "Usage: bash scripts/start.sh [--skip-npm-ci] [--https]"
      echo "  --skip-npm-ci Reuse the existing frontend/node_modules directory."
      echo "  --https       Enable Secure session cookies; use only behind HTTPS."
      exit 0
      ;;
    *) echo "Unknown option: $argument" >&2; exit 2 ;;
  esac
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }
}

random_hex() {
  od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'
}

for command_name in docker od npm mvn java git codegraph curl nohup ps find grep seq tail tr sed basename; do
  require_command "$command_name"
done
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required (docker compose)." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not running or the current user cannot access it." >&2
  exit 1
fi
if [[ ! -f "$ROOT_DIR/pom.xml" || ! -f "$ROOT_DIR/backend/pom.xml" ]]; then
  echo "请在完整的代码知识平台源码目录中运行此脚本。" >&2
  exit 1
fi
if [[ ! -f "$ROOT_DIR/frontend/package.json" || ! -f "$ROOT_DIR/frontend/package-lock.json" ]]; then
  echo "Missing frontend/package.json or frontend/package-lock.json." >&2
  exit 1
fi

cd "$ROOT_DIR"
mkdir -p "$LOG_DIR" "$RUNTIME_DIR/repositories" "$RUNTIME_DIR/data/home"

# Stop only the backend previously started by this source tree. Stopping before
# compilation releases Java heap for memory-constrained deployment hosts.
if [[ -f "$PID_FILE" ]]; then
  previous_pid="$(tr -cd '0-9' < "$PID_FILE")"
  if [[ -n "$previous_pid" ]] && kill -0 "$previous_pid" >/dev/null 2>&1; then
    previous_command="$(ps -p "$previous_pid" -o args= 2>/dev/null || true)"
    if [[ "$previous_command" != *"$ROOT_DIR/backend/target/"* ]]; then
      echo "PID $previous_pid 不是从当前源码目录启动的代码知识平台后端，拒绝停止该进程。" >&2
      exit 1
    fi
    echo "Stopping previous backend process $previous_pid..."
    kill "$previous_pid"
    for _ in $(seq 1 30); do
      kill -0 "$previous_pid" >/dev/null 2>&1 || break
      sleep 1
    done
    if kill -0 "$previous_pid" >/dev/null 2>&1; then
      echo "Backend process $previous_pid did not stop within 30 seconds." >&2
      exit 1
    fi
  fi
  rm -f "$PID_FILE"
fi

if curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
  echo "Port 8080 is already serving a backend not managed by $PID_FILE; refusing to continue." >&2
  exit 1
fi

echo "[1/5] Building frontend..."
pushd "$ROOT_DIR/frontend" >/dev/null
if [[ "$SKIP_NPM_CI" != true ]]; then
  npm ci
elif [[ ! -d node_modules ]]; then
  echo "--skip-npm-ci was used but frontend/node_modules does not exist." >&2
  exit 1
fi
export NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=1536}"
npm run build
popd >/dev/null

echo "[2/5] Building backend..."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1024m}"
mvn -pl backend -am clean package -DskipTests

mapfile -t backend_jars < <(find "$ROOT_DIR/backend/target" -maxdepth 1 -type f \
  -name 'codebase-knowledge-backend-*.jar' ! -name '*.jar.original' -print)
if [[ ${#backend_jars[@]} -ne 1 ]]; then
  echo "Expected exactly one backend JAR, found ${#backend_jars[@]}." >&2
  printf '  %s\n' "${backend_jars[@]}" >&2
  exit 1
fi
backend_jar="${backend_jars[0]}"

echo "[3/5] Starting PostgreSQL and Nginx..."
component_compose_file="$ROOT_DIR/compose.components.yaml"
if [[ -f "$ROOT_DIR/compose.offline.yaml" ]] \
    && docker image inspect analyzer-coder/postgres:offline >/dev/null 2>&1 \
    && docker image inspect analyzer-coder/nginx:offline >/dev/null 2>&1; then
  component_compose_file="$ROOT_DIR/compose.offline.yaml"
  echo "Using locally imported offline component images."
fi

frontend_dist="$ROOT_DIR/frontend/dist"
if [[ ! -f "$COMPONENT_ENV" ]]; then
  postgres_password="Db$(random_hex 24)"
  cat > "$COMPONENT_ENV" <<EOF
POSTGRES_DB=codebase_kb
POSTGRES_USER=codebase_kb
POSTGRES_PASSWORD=$postgres_password
POSTGRES_PORT=5432
APP_HTTP_BIND_ADDRESS=127.0.0.1
APP_HTTP_PORT=8088
APP_FRONTEND_DIST_HOST_ROOT="$frontend_dist"
TZ=Asia/Shanghai
EOF
  chmod 600 "$COMPONENT_ENV"
  echo "Created $COMPONENT_ENV"
  echo "PostgreSQL password: $postgres_password"
  echo "Save this password with the deployment secrets."
fi
if grep -q "replace-with" "$COMPONENT_ENV"; then
  echo "$COMPONENT_ENV still contains placeholder secrets; replace them before starting." >&2
  exit 1
fi

compose=(docker compose --env-file "$COMPONENT_ENV" -f "$component_compose_file")
"${compose[@]}" config --quiet
"${compose[@]}" up -d

postgres_healthy=false
nginx_healthy=false
for _ in $(seq 1 60); do
  if "${compose[@]}" exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; then
    postgres_healthy=true
  fi
  if "${compose[@]}" exec -T nginx wget --quiet --output-document=- http://127.0.0.1:8080/component-health >/dev/null 2>&1; then
    nginx_healthy=true
  fi
  [[ "$postgres_healthy" == true && "$nginx_healthy" == true ]] && break
  sleep 2
done
if [[ "$postgres_healthy" != true || "$nginx_healthy" != true ]]; then
  echo "PostgreSQL or Nginx did not become healthy in time." >&2
  "${compose[@]}" logs --tail=120 >&2
  exit 1
fi

mapfile -t component_database < <(
  set -a
  # shellcheck disable=SC1090
  source "$COMPONENT_ENV"
  printf '%s\n' \
    "${POSTGRES_DB:-codebase_kb}" \
    "${POSTGRES_USER:-codebase_kb}" \
    "${POSTGRES_PASSWORD:-}" \
    "${POSTGRES_PORT:-5432}"
)
postgres_db="${component_database[0]:-codebase_kb}"
postgres_user="${component_database[1]:-codebase_kb}"
postgres_password="${component_database[2]:-}"
postgres_port="${component_database[3]:-5432}"
if [[ -z "$postgres_password" ]]; then
  echo "POSTGRES_PASSWORD is missing from $COMPONENT_ENV" >&2
  exit 1
fi

if [[ ! -f "$APPLICATION_ENV" ]]; then
  admin_password="Ac!$(random_hex 12)9z"
  llm_master_key="$(random_hex 32)"
  credential_master_key="$(random_hex 32)"
  codegraph_executable="$(command -v codegraph)"
  cat > "$APPLICATION_ENV" <<EOF
HOME="$RUNTIME_DIR/data/home"
APP_SERVER_PORT=8080
SERVER_ADDRESS=0.0.0.0
APP_FORWARD_HEADERS_STRATEGY=framework
APP_SESSION_COOKIE_SECURE=false
APP_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:$postgres_port/$postgres_db
APP_DATASOURCE_USERNAME=$postgres_user
APP_DATASOURCE_PASSWORD=$postgres_password
APP_FLYWAY_ENABLED=true
APP_INITIAL_ADMIN_USERNAME=admin
APP_INITIAL_ADMIN_PASSWORD=$admin_password
APP_REPOSITORY_ALLOWED_ROOTS="$RUNTIME_DIR/repositories"
APP_MANAGED_DATA_ROOT="$RUNTIME_DIR/data"
APP_CODEGRAPH_EXECUTABLE="$codegraph_executable"
APP_LLM_MASTER_KEY=$llm_master_key
APP_CREDENTIAL_MASTER_KEY=$credential_master_key
APP_LLM_ALLOW_INSECURE_LOCAL=false
APP_JAVA_XMS=256m
APP_JAVA_XMX=768m
TZ=Asia/Shanghai
EOF
  chmod 600 "$APPLICATION_ENV"
  echo "Created $APPLICATION_ENV"
  echo "Initial administrator: admin"
  echo "Initial password: $admin_password"
  echo "Save this password now. The first login requires a password change."
fi
if grep -q "replace-with" "$APPLICATION_ENV"; then
  echo "$APPLICATION_ENV still contains placeholder secrets; replace them before starting." >&2
  exit 1
fi

echo "[4/5] Starting backend..."
(
  set -a
  # shellcheck disable=SC1090
  source "$APPLICATION_ENV"
  if [[ "$ENABLE_HTTPS" == true ]]; then
    export APP_SESSION_COOKIE_SECURE=true
  fi
  set +a
  exec nohup java \
    "-Xms${APP_JAVA_XMS:-256m}" \
    "-Xmx${APP_JAVA_XMX:-768m}" \
    -jar "$backend_jar"
) >"$BACKEND_LOG" 2>&1 </dev/null &
backend_pid=$!
printf '%s\n' "$backend_pid" > "$PID_FILE"

echo "[5/5] Waiting for backend health..."
backend_healthy=false
for _ in $(seq 1 90); do
  if curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    backend_healthy=true
    break
  fi
  if ! kill -0 "$backend_pid" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
if [[ "$backend_healthy" != true ]]; then
  echo "Backend did not become healthy. Last 120 log lines:" >&2
  tail -n 120 "$BACKEND_LOG" >&2 || true
  if kill -0 "$backend_pid" >/dev/null 2>&1; then
    kill "$backend_pid" || true
  fi
  rm -f "$PID_FILE"
  exit 1
fi

http_port="$(sed -n 's/^APP_HTTP_PORT=//p' "$COMPONENT_ENV" | tail -n 1)"
compose_filename="$(basename "$component_compose_file")"
echo "代码知识平台已启动：http://127.0.0.1:${http_port:-8088}"
echo "Backend PID: $backend_pid"
echo "Backend log: $BACKEND_LOG"
echo "Component logs: docker compose --env-file .env.components -f $compose_filename logs -f"
