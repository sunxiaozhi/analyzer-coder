#!/usr/bin/env bash
set -Eeuo pipefail
pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null
curl --fail --silent --max-time 3 http://127.0.0.1:8081/actuator/health >/dev/null
curl --fail --silent --max-time 3 http://127.0.0.1:8080/index.html >/dev/null
