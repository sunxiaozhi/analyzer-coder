FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN mvn -pl backend -am dependency:go-offline
COPY backend/src backend/src
RUN mvn -pl backend -am package -DskipTests

FROM node:20-bookworm-slim AS runtime
ARG CODEGRAPH_VERSION=1.5.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless git ca-certificates curl \
    && npm install --global "@colbymchenry/codegraph@${CODEGRAPH_VERSION}" \
    && groupadd --system --gid 10001 analyzer \
    && useradd --system --uid 10001 --gid analyzer --home-dir /app --shell /usr/sbin/nologin analyzer \
    && rm -rf /var/lib/apt/lists/* /root/.npm
WORKDIR /app
COPY --from=build /workspace/backend/target/codebase-knowledge-backend-*.jar /app/app.jar
RUN chown -R analyzer:analyzer /app
USER analyzer
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]