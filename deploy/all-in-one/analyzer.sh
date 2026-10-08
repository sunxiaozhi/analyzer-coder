#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
export ANALYZER_INSTALLATION_ROOT="$ROOT"
ACTION="${1:-start}"
PACKAGE="${2:-}"

compose() {
    docker compose --project-directory "$ROOT" --env-file "$ROOT/.env" -f "$ROOT/compose.yaml" "$@"
}
image_from() {
    local image
    image="$(sed -n 's/^ANALYZER_IMAGE=//p' "$1" | tr -d '\r')"
    [[ "$image" =~ ^analyzer-coder:[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$ ]] || {
        echo "Invalid or missing ANALYZER_IMAGE in $1" >&2; return 1;
    }
    printf '%s' "$image"
}
verify_package() {
    local file line
    [[ ! -e "$1/.incomplete" && -f "$1/SHA256SUMS" && -f "$1/image.tar" && -f "$1/image.env" ]] || {
        echo 'Expected a complete extracted release directory.' >&2; return 1;
    }
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[0-9a-f]{64}\ \ (analyzer\.sh|analyzer\.ps1|compose\.yaml|image\.env|README\.md|MANIFEST\.json|image\.tar)$ ]] || {
            echo 'Invalid checksum entry.' >&2; return 1;
        }
    done < "$1/SHA256SUMS"
    for file in analyzer.sh analyzer.ps1 compose.yaml image.env README.md MANIFEST.json image.tar; do
        [[ "$(grep -Ec "^[0-9a-f]{64}  ${file//./\\.}$" "$1/SHA256SUMS")" == 1 ]] || {
            echo "Missing or duplicate checksum: $file" >&2; return 1;
        }
    done
    (cd "$1" && sha256sum --check --strict SHA256SUMS)
}
load_image() {
    verify_package "$1"
    docker load -i "$1/image.tar"
    docker image inspect "$2" --format '{{.Os}}/{{.Architecture}}'
}
initialize() {
    local image="$1"
    shift
    docker run --rm --user "$(id -u):$(id -g)" --entrypoint node \
        -v "$ROOT:/installation" "$image" /opt/analyzer-coder/deploy/configure.mjs \
        /installation "$image" "$@"
}
assert_owner() {
    local cid owner
    cid="$(compose ps --all --quiet analyzer)"
    [[ -n "$cid" ]] || return 0
    owner="$(docker inspect "$cid" --format '{{index .Config.Labels "com.analyzer-coder.installation"}}')"
    [[ "$owner" == "$ROOT" ]] || {
        echo 'This Compose project belongs to another installation. Set a unique COMPOSE_PROJECT_NAME in .env, or migrate the old deployment first.' >&2
        return 1
    }
}
prepare() {
    local image
    if [[ -f "$ROOT/.env" ]]; then image="$(image_from "$ROOT/.env")"
    else image="$(image_from "$ROOT/image.env")"; fi
    if ! docker image inspect "$image" >/dev/null 2>&1; then load_image "$ROOT" "$image"; fi
    initialize "$image"
    compose config --quiet
    assert_owner
}
start() {
    if ! compose up -d --pull never --wait --wait-timeout 240; then
        compose logs --tail 100 analyzer >&2
        echo 'Startup failed. Data and configuration were retained; inspect the logs before retrying.' >&2
        return 1
    fi
    compose ps
    echo "Ready. Port/admin login: $ROOT/.env; data: $ROOT/data; config: $ROOT/config"
}
backup() {
    local image cid was_running=false filename
    image="$(image_from "$ROOT/.env")"
    cid="$(compose ps --quiet analyzer)"
    if [[ -n "$cid" ]]; then
        was_running="$(docker inspect "$cid" --format '{{.State.Running}}')"
    fi
    compose stop analyzer
    mkdir -p "$ROOT/backups"
    filename="analyzer-$(date -u +%Y%m%d-%H%M%S)-$RANDOM.tar.gz"
    if ! docker run --rm --entrypoint sh -v "$ROOT:/installation:ro" \
        -v "$ROOT/backups:/backup" "$image" -c \
        'umask 077; tar -czf "/backup/$1" -C /installation data config .env && chown "$2:$3" "/backup/$1"' \
        sh "$filename" "$(id -u)" "$(id -g)"; then
        [[ "$was_running" != true ]] || compose start --wait --wait-timeout 240 analyzer
        echo 'Backup failed.' >&2; return 1
    fi
    [[ "$was_running" != true ]] || compose start --wait --wait-timeout 240 analyzer
    echo "Backup: $ROOT/backups/$filename"
}
upgrade() {
    [[ -n "$PACKAGE" ]] || { echo 'Usage: bash analyzer.sh upgrade /path/to/extracted-new-release' >&2; return 1; }
    local package image
    package="$(cd "$PACKAGE" && pwd -P)"
    [[ "$package" != "$ROOT" ]] || { echo 'Extract the new package in another directory first.' >&2; return 1; }
    image="$(image_from "$package/image.env")"
    load_image "$package" "$image"
    # Upgrade metadata only; passwords, keys, data and edited config stay in place.
    initialize "$image" --upgrade
    for file in analyzer.sh analyzer.ps1 compose.yaml image.env README.md MANIFEST.json SHA256SUMS image.tar; do
        cp "$package/$file" "$ROOT/$file.new"
        mv -f "$ROOT/$file.new" "$ROOT/$file"
    done
    chmod +x "$ROOT/analyzer.sh"
    compose config --quiet
    start
}
main() {
    if [[ "$ACTION" == help || "$ACTION" == --help ]]; then
        echo 'Usage: bash analyzer.sh init|start|stop|restart|status|logs|backup|upgrade [NEW_PACKAGE_DIRECTORY]'
        return
    fi
    case "$ACTION" in init|start|stop|restart|status|logs|backup|upgrade) ;; *) echo "Unknown action: $ACTION" >&2; return 1;; esac
    [[ "$(docker info --format '{{.OSType}}')" == linux ]] || { echo 'Docker must use Linux containers.' >&2; return 1; }
    docker compose version >/dev/null
    prepare
    case "$ACTION" in
        init) echo "Configuration ready: $ROOT/.env and $ROOT/config" ;;
        start) start ;;
        stop) compose stop analyzer ;;
        restart) compose up -d --force-recreate --pull never --wait --wait-timeout 240; compose ps ;;
        status) compose ps --all ;;
        logs) compose logs --follow --tail 100 analyzer ;;
        backup) backup ;;
        upgrade) upgrade ;;
    esac
}
main
