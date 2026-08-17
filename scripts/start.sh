#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="$ROOT_DIR/.env.production"
COMPOSE_FILE="$ROOT_DIR/compose.prod.yaml"
ENABLE_HTTPS=false
BUILD_IMAGES=true
COMPONENTS_ONLY=false
OFFLINE_COMPONENTS=false

for argument in "$@"; do
  case "$argument" in
    --https) ENABLE_HTTPS=true ;;
    --no-build) BUILD_IMAGES=false ;;
    --components) COMPONENTS_ONLY=true ;;
    --offline-components) COMPONENTS_ONLY=true; OFFLINE_COMPONENTS=true ;;
    -h|--help)
      echo "Usage: bash scripts/start.sh [--https] [--no-build] [--components|--offline-components]"
      echo "  --https    Enable Secure cookies; use only behind the HTTPS proxy."
      echo "  --no-build Start existing images without rebuilding."
      echo "  --components Start only PostgreSQL/pgvector and Nginx; application code stays on the host."
      echo "  --offline-components Start components from the locally imported offline images."
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

require_command docker
require_command od

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required (docker compose)." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not running or the current user cannot access it." >&2
  exit 1
fi

cd "$ROOT_DIR"

if [[ "$COMPONENTS_ONLY" == true ]]; then
  if [[ "$ENABLE_HTTPS" == true ]]; then
    echo "--https is not supported in component mode; configure HTTPS in an external proxy." >&2
    exit 2
  fi

  ENV_FILE="$ROOT_DIR/.env.components"
  if [[ "$OFFLINE_COMPONENTS" == true ]]; then
    COMPOSE_FILE="$ROOT_DIR/compose.offline.yaml"
  else
    COMPOSE_FILE="$ROOT_DIR/compose.components.yaml"
  fi
  frontend_dist="$ROOT_DIR/frontend/dist"
  mkdir -p "$frontend_dist"

  if [[ ! -f "$ENV_FILE" ]]; then
    postgres_password="Db$(random_hex 24)"
    cat > "$ENV_FILE" <<EOF
POSTGRES_DB=codebase_kb
POSTGRES_USER=codebase_kb
POSTGRES_PASSWORD=$postgres_password
POSTGRES_PORT=5432
APP_HTTP_BIND_ADDRESS=127.0.0.1
APP_HTTP_PORT=8088
APP_FRONTEND_DIST_HOST_ROOT="$frontend_dist"
TZ=Asia/Shanghai
EOF
    chmod 600 "$ENV_FILE"
    echo "Created $ENV_FILE"
    echo "PostgreSQL password: $postgres_password"
    echo "Save this password for the host backend configuration."
  fi

  if grep -q "replace-with" "$ENV_FILE"; then
    echo "$ENV_FILE still contains placeholder secrets; replace them before starting." >&2
    exit 1
  fi

  compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
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
    echo "Component services did not become healthy in time." >&2
    "${compose[@]}" logs --tail=120 >&2
    exit 1
  fi

  "${compose[@]}" ps
  http_port="$(sed -n 's/^APP_HTTP_PORT=//p' "$ENV_FILE" | tail -n 1)"
  postgres_port="$(sed -n 's/^POSTGRES_PORT=//p' "$ENV_FILE" | tail -n 1)"
  echo "Components are ready: Nginx http://127.0.0.1:${http_port:-8088}, PostgreSQL 127.0.0.1:${postgres_port:-5432}"
  echo "Frontend assets: $frontend_dist (run npm build on the host)"
  echo "Backend API: Nginx expects the host backend at http://host.docker.internal:8080"
  compose_filename="$(basename "$COMPOSE_FILE")"
  echo "Logs: docker compose --env-file .env.components -f $compose_filename logs -f"
  echo "Stop: docker compose --env-file .env.components -f $compose_filename down"
  exit 0
fi

mkdir -p "$ROOT_DIR/runtime/repositories" "$ROOT_DIR/runtime/data"

if [[ ! -f "$ENV_FILE" ]]; then
  postgres_password="Db$(random_hex 24)"
  admin_password="Ac!$(random_hex 12)9z"
  llm_master_key="$(random_hex 32)"
  credential_master_key="$(random_hex 32)"
  runtime_uid="$(id -u)"
  runtime_gid="$(id -g)"
  if [[ "$runtime_uid" == "0" ]]; then
    runtime_uid=10001
    runtime_gid=10001
    chown -R 10001:10001 "$ROOT_DIR/runtime/data"
  fi
  secure_cookie=false
  [[ "$ENABLE_HTTPS" == true ]] && secure_cookie=true

  cat > "$ENV_FILE" <<EOF
POSTGRES_DB=codebase_kb
POSTGRES_USER=codebase_kb
POSTGRES_PASSWORD=$postgres_password
APP_INITIAL_ADMIN_USERNAME=admin
APP_INITIAL_ADMIN_PASSWORD=$admin_password
APP_LLM_MASTER_KEY=$llm_master_key
APP_CREDENTIAL_MASTER_KEY=$credential_master_key
APP_REPOSITORY_HOST_ROOT="$ROOT_DIR/runtime/repositories"
APP_MANAGED_DATA_HOST_ROOT="$ROOT_DIR/runtime/data"
APP_RUNTIME_UID=$runtime_uid
APP_RUNTIME_GID=$runtime_gid
APP_HTTP_BIND_ADDRESS=127.0.0.1
APP_HTTP_PORT=8088
APP_SESSION_COOKIE_SECURE=$secure_cookie
APP_LLM_ALLOW_INSECURE_LOCAL=false
CODEGRAPH_VERSION=1.5.0
TZ=Asia/Shanghai
EOF
  chmod 600 "$ENV_FILE"
  echo "Created $ENV_FILE"
  echo "Initial administrator: admin"
  echo "Initial password: $admin_password"
  echo "Save this password now. The first login requires a password change."
fi

if grep -q "replace-with" "$ENV_FILE"; then
  echo "$ENV_FILE still contains placeholder secrets; replace them before starting." >&2
  exit 1
fi

if [[ "$ENABLE_HTTPS" == true ]]; then
  export APP_SESSION_COOKIE_SECURE=true
fi

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
"${compose[@]}" config --quiet

if [[ "$BUILD_IMAGES" == true ]]; then
  "${compose[@]}" up -d --build
else
  "${compose[@]}" up -d
fi

healthy=false
for _ in $(seq 1 90); do
  if "${compose[@]}" exec -T backend curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    healthy=true
    break
  fi
  sleep 2
done

if [[ "$healthy" != true ]]; then
  echo "Backend did not become healthy in time." >&2
  "${compose[@]}" logs --tail=120 backend >&2
  exit 1
fi

"${compose[@]}" ps
http_port="$(sed -n 's/^APP_HTTP_PORT=//p' "$ENV_FILE" | tail -n 1)"
http_port="${http_port:-8088}"
echo "Analyzer Coder is ready at http://127.0.0.1:${http_port}"
if [[ "$ENABLE_HTTPS" != true ]] && ! grep -q '^APP_SESSION_COOKIE_SECURE=true$' "$ENV_FILE"; then
  echo "HTTP bootstrap mode is active. Before external access, configure deploy/nginx-compose-edge.conf and restart with --https."
fi
echo "Logs: docker compose --env-file .env.production -f compose.prod.yaml logs -f"
echo "Stop: docker compose --env-file .env.production -f compose.prod.yaml down"
