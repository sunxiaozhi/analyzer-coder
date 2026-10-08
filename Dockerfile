# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim AS frontend-build
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B --no-transfer-progress -pl backend -am package -DskipTests \
    && cp backend/target/codebase-knowledge-backend-*.jar /build/app.jar

FROM node:24-bookworm-slim AS node-runtime

# Bookworm supplies Java 17 and matches the Node runtime's libc.
FROM pgvector/pgvector:pg17-bookworm AS runtime
ARG CODEGRAPH_VERSION=1.6.0
ENV DEBIAN_FRONTEND=noninteractive \
    PGDATA=/data/postgres \
    POSTGRES_DB=codebase_kb \
    POSTGRES_USER=codebase_kb \
    APP_SERVER_PORT=8081 \
    APP_FORWARD_HEADERS_STRATEGY=framework \
    APP_MANAGED_DATA_ROOT=/data/managed \
    APP_REPOSITORY_ALLOWED_ROOTS=/data/repositories \
    APP_CODEGRAPH_EXECUTABLE=codegraph \
    CODEGRAPH_NO_DOWNLOAD=1 \
    TZ=Asia/Shanghai
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       openjdk-17-jre-headless nginx git openssh-client ca-certificates \
       curl tini python3 libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 analyzer \
    && useradd --uid 10001 --gid analyzer --create-home --shell /bin/bash analyzer \
    && rm -f /etc/nginx/sites-enabled/default
COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && npm install --global --registry=https://registry.npmjs.org --fetch-retries=3 --fetch-timeout=600000 \
       "@colbymchenry/codegraph@${CODEGRAPH_VERSION}" \
       "@colbymchenry/codegraph-linux-$(node -p process.arch)@${CODEGRAPH_VERSION}" \
    && codegraph --version
WORKDIR /opt/analyzer-coder/mcp-server
COPY mcp-server/package*.json ./
RUN npm ci --omit=dev
COPY mcp-server/src ./src
WORKDIR /opt/analyzer-coder
COPY --from=backend-build /build/app.jar ./app.jar
COPY --from=frontend-build /build/frontend/dist/ /usr/share/nginx/html/
COPY deploy/all-in-one/nginx.conf /etc/nginx/conf.d/analyzer-coder.conf
COPY deploy/all-in-one/entrypoint.sh /usr/local/bin/analyzer-entrypoint
COPY deploy/all-in-one/healthcheck.sh /usr/local/bin/analyzer-healthcheck
RUN chmod +x /usr/local/bin/analyzer-entrypoint /usr/local/bin/analyzer-healthcheck \
    && nginx -t
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=15s --timeout=8s --start-period=180s --retries=5 \
    CMD ["/usr/local/bin/analyzer-healthcheck"]
ENTRYPOINT ["/usr/bin/tini", "-s", "--", "/usr/local/bin/analyzer-entrypoint"]
