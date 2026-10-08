#!/usr/bin/env bash
set -Eeuo pipefail

for name in POSTGRES_PASSWORD APP_INITIAL_ADMIN_USERNAME APP_INITIAL_ADMIN_PASSWORD APP_LLM_MASTER_KEY APP_CREDENTIAL_MASTER_KEY; do
    if [[ -z "${!name:-}" || "${!name}" == replace-with-* ]]; then
        echo "Missing configuration: $name. Run analyzer.sh/ps1 start to initialize .env." >&2
        exit 1
    fi
done

# Fixed internal endpoints keep the database and backend inside this container.
export APP_SERVER_PORT=8081 APP_FORWARD_HEADERS_STRATEGY=framework
export APP_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB}"
export APP_DATASOURCE_USERNAME="$POSTGRES_USER"
export APP_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export APP_MANAGED_DATA_ROOT=/data/managed
export APP_CODEGRAPH_EXECUTABLE=codegraph CODEGRAPH_NO_DOWNLOAD=1
mkdir -p /data/managed /data/repositories /data/logs
chown analyzer:analyzer /data/managed /data/repositories /data/logs
if [[ -f /config/nginx.conf ]]; then
    cp /config/nginx.conf /etc/nginx/conf.d/analyzer-coder.conf
fi
nginx -t

database_pid='' backend_pid='' nginx_pid=''
shutdown() {
    trap - EXIT TERM INT
    # Terminate the Java process group, including active Git/CodeGraph children.
    [[ -z "$nginx_pid" ]] || kill -QUIT -- "-$nginx_pid" 2>/dev/null || true
    [[ -z "$backend_pid" ]] || kill -TERM -- "-$backend_pid" 2>/dev/null || true
    [[ -z "$backend_pid" ]] || wait "$backend_pid" 2>/dev/null || true
    # Fast PostgreSQL shutdown checkpoints data and closes all connections.
    [[ -z "$database_pid" ]] || kill -INT "$database_pid" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap shutdown EXIT
trap 'exit 0' TERM INT

# Keep the official PostgreSQL initialization/migration behavior.
postgres_arguments=(postgres)
if [[ -f /config/postgresql.conf ]]; then
    postgres_arguments+=(-c config_file=/config/postgresql.conf)
fi
# Keep authentication files in persistent PGDATA when the tuning file is external.
postgres_arguments+=(-c "data_directory=$PGDATA" -c "hba_file=$PGDATA/pg_hba.conf"
    -c "ident_file=$PGDATA/pg_ident.conf" -c listen_addresses=127.0.0.1 -c port=5432)
/usr/local/bin/docker-entrypoint.sh "${postgres_arguments[@]}" &
database_pid=$!
ready=false
for ((attempt=0; attempt<120; attempt++)); do
    kill -0 "$database_pid" 2>/dev/null || { echo 'PostgreSQL exited during startup.' >&2; exit 1; }
    # The initialization server has TCP disabled, so this waits for the final server.
    if PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c 'SELECT 1' >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
[[ "$ready" == true ]] || { echo 'PostgreSQL startup timed out.' >&2; exit 1; }

# JVM tuning can be supplied with the standard JAVA_TOOL_OPTIONS environment variable.
setsid gosu analyzer java -jar /opt/analyzer-coder/app.jar \
    --spring.config.additional-location=optional:file:/config/application.yml \
    --server.address=127.0.0.1 --server.port=8081 \
    --spring.datasource.url="$APP_DATASOURCE_URL" \
    --spring.datasource.username="$APP_DATASOURCE_USERNAME" \
    --app.repository.managed-data-root=/data/managed \
    --logging.file.name=/data/logs/backend.log &
backend_pid=$!
setsid nginx -g 'daemon off;' &
nginx_pid=$!
echo 'Analyzer Coder started; HTTP port 8080. Waiting for application health.'

# Any main service exit ends the container, allowing Docker restart policy to act.
status=0
wait -n "$database_pid" "$backend_pid" "$nginx_pid" || status=$?
echo "A required service exited (status $status); stopping container." >&2
exit 1
