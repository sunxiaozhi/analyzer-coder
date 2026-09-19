# 部署与启动操作手册

> 本文档由当前实现反推生成（2026-09-19）。内容依据 `scripts/`、`compose*.yaml`、`deploy/` 与 `backend/Dockerfile`、`frontend/Dockerfile` 的实际行为，不是新设计。

## 1 四种部署形态

| 形态 | 入口 | 需要什么 | 谁构建 |
| --- | --- | --- | --- |
| 源码一键启动 | `bash scripts/start.sh` | Docker + Docker Compose v2、npm、Maven、JDK、Git、CodeGraph CLI、curl | 脚本本机构建前端与后端 |
| 预构建宿主机启动 | `bash scripts/start-prebuilt-host.sh` | 已存在的 `frontend/dist`、后端 JAR、宿主机 PostgreSQL/pgvector 与 Nginx | 不构建，只启动 |
| 全容器编排 | `docker compose -f compose.prod.yaml up -d` | 预构建的后端/前端镜像或可构建的源码 | Compose 构建镜像 |
| 离线组件包 | `install.sh`（包内） | Docker | 联网机构建镜像并导出 |

## 2 源码一键启动：`scripts/start.sh`

用法：

```bash
bash scripts/start.sh [--skip-npm-ci] [--https]
```

- `--skip-npm-ci`：复用已有 `frontend/node_modules`（目录不存在时报错退出）。
- `--https`：把 `APP_SESSION_COOKIE_SECURE` 置为 `true`，仅在 HTTPS 反向代理后使用。

脚本先做前置检查（`scripts/start.sh:36-54`）：要求 `docker`、`od`、`npm`、`mvn`、`java`、`git`、`codegraph`、`curl`、`nohup`、`ps`、`find`、`grep`、`seq`、`tail`、`tr`、`sed`、`basename` 全部存在，Docker 守护进程可用，且当前目录同时含根 `pom.xml`、`backend/pom.xml`、`frontend/package.json` 与 `frontend/package-lock.json`。

执行流程（共 5 步）：

| 步骤 | 行为 |
| --- | --- |
| 1/5 前端构建 | `npm ci`（或用 `--skip-npm-ci`）+ `npm run build`；`NODE_OPTIONS` 默认 `--max-old-space-size=1536`（`scripts/start.sh:88-98`） |
| 2/5 后端构建 | `MAVEN_OPTS` 默认 `-Xmx1024m`，执行 `mvn -pl backend -am clean package -DskipTests`，并要求 `backend/target` 中恰好一个 `codebase-knowledge-backend-*.jar`（`:100-111`） |
| 3/5 基础组件 | 启动 PostgreSQL/pgvector 与 Nginx；等待两者健康（最多 60 次 × 2 秒），失败时输出 120 行日志并退出（`:113-165`） |
| 4/5 后端启动 | `nohup java -Xms256m -Xmx768m -jar <jar>`，PID 写入 `runtime/backend.pid`（`:224-239`） |
| 5/5 健康检查 | 轮询 `http://127.0.0.1:8080/actuator/health`，最多 90 次 × 2 秒；失败则终止进程、删除 PID 文件并退出（`:241-261`） |

自动生成两个环境文件（权限 `0600`，已存在则不覆盖）：

- `.env.components`：`POSTGRES_DB`、`POSTGRES_USER`、随机 `POSTGRES_PASSWORD`、`POSTGRES_PORT`、`APP_HTTP_BIND_ADDRESS`（默认 `127.0.0.1`）、`APP_HTTP_PORT`（默认 `8088`）、`APP_FRONTEND_DIST_HOST_ROOT`、`TZ`。
- `.env.application`：`HOME`、`APP_SERVER_PORT`、`SERVER_ADDRESS`、`APP_FORWARD_HEADERS_STRATEGY=framework`、`APP_SESSION_COOKIE_SECURE=false`、数据源四项、`APP_FLYWAY_ENABLED=true`、初始管理员账号 `admin` 与随机密码、`APP_REPOSITORY_ALLOWED_ROOTS`、`APP_MANAGED_DATA_ROOT`、`APP_CODEGRAPH_EXECUTABLE`（取自 `command -v codegraph`）、随机 `APP_LLM_MASTER_KEY` 与 `APP_CREDENTIAL_MASTER_KEY`、`APP_LLM_ALLOW_INSECURE_LOCAL=false`、`APP_JAVA_XMS`/`APP_JAVA_XMX`、`TZ`。

两个文件都包含 `replace-with` 占位检查：命中占位值即拒绝启动（`:140-143`、`:219-222`）。初始管理员密码只在生成时打印一次，首次登录必须改密（`:215-217`）。

组件编排文件选择：默认 `compose.components.yaml`；若 `compose.offline.yaml` 存在且本地已导入 `analyzer-coder/postgres:offline` 与 `analyzer-coder/nginx:offline` 两个镜像，则自动改用离线编排（`:114-120`）。

组件 Nginx（`deploy/nginx-components.conf`）要点：监听 8080，静态根为挂载的前端 `dist`，SPA 路由回退到 `index.html`，`client_max_body_size 60m`（ZIP 上传上限受此约束），带 `X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` 响应头，`/api/` 代理到 `host.docker.internal:8080` 且读超时 300 秒；`/component-health` 为容器健康检查端点。该配置不代理 `/actuator`，因此管理端点不经组件 Nginx 暴露。

安全保护：

- 若 `runtime/backend.pid` 指向仍在运行的进程，但该进程命令行不属于当前源码目录，脚本拒绝停止它（`:61-68`）。
- 若 `127.0.0.1:8080/actuator/health` 已有响应而 PID 文件不受本脚本管理，脚本拒绝继续（`:83-86`）。

完成后访问 `http://127.0.0.1:${APP_HTTP_PORT:-8088}`。

## 3 预构建宿主机启动：`scripts/start-prebuilt-host.sh`

用法：

```bash
bash scripts/start-prebuilt-host.sh [--env-file PATH] [--jar PATH] [--frontend-root PATH] [--skip-service-start] [--reload-nginx]
```

该脚本**不调用 Docker、npm 或 Maven**（`scripts/start-prebuilt-host.sh:17`）。前置条件：

- 环境文件（默认 `.env.application`）必须存在，且至少设置：`APP_DATASOURCE_URL`、`APP_DATASOURCE_USERNAME`、`APP_DATASOURCE_PASSWORD`、`APP_INITIAL_ADMIN_USERNAME`、`APP_INITIAL_ADMIN_PASSWORD`、`APP_REPOSITORY_ALLOWED_ROOTS`、`APP_MANAGED_DATA_ROOT`、`APP_LLM_MASTER_KEY`、`APP_CREDENTIAL_MASTER_KEY`（`:51-53`）。
- 环境文件不得含 `replace-with` 占位值（`:54`）。
- 前端产物必须存在：`<frontend-root>/index.html`（默认 `frontend/dist`，`:55`）。
- 后端 JAR 必须唯一可定位，或通过 `--jar` 指定（`:56-61`）。
- `APP_MANAGED_DATA_ROOT` 会被创建；`APP_REPOSITORY_ALLOWED_ROOTS` 中每个根目录必须存在且可读（`:62-67`）。
- 检查 `pg_isready`；通过 systemd 确认 PostgreSQL 与 Nginx 处于 active（服务名可用 `APP_POSTGRES_SERVICE`、`APP_NGINX_SERVICE` 覆盖，默认 `postgresql`、`nginx`）；`--skip-service-start` 表示要求它们已经运行；`--reload-nginx` 会先 `nginx -t` 再 reload（`:68-78`）。

启动与健康检查行为与 `start.sh` 一致（PID 文件保护、端口占用拒绝、90 次 × 2 秒等待 `/actuator/health`）。日志写入 `runtime/logs/backend.log`。

## 4 全容器编排：`compose.prod.yaml`

必需环境变量（缺失即编排失败）：

| 变量 | 作用 |
| --- | --- |
| `POSTGRES_PASSWORD` | 数据库口令（`compose.prod.yaml:10`） |
| `APP_INITIAL_ADMIN_PASSWORD` | 初始管理员口令（`:37`） |
| `APP_LLM_MASTER_KEY` | 模型密钥加密主密钥（`:42`） |
| `APP_REPOSITORY_HOST_ROOT` | 宿主机仓库根目录，只读挂载到 `/repositories`（`:50-52`） |
| `APP_MANAGED_DATA_HOST_ROOT` | 受管数据根目录，读写挂载到 `/data/analyzer-coder`（`:54-55`） |

可选变量：`POSTGRES_DB`、`POSTGRES_USER`、`TZ`、`APP_INITIAL_ADMIN_USERNAME`、`APP_CREDENTIAL_MASTER_KEY`、`APP_LLM_ALLOW_INSECURE_LOCAL`、`APP_SESSION_COOKIE_SECURE`、`APP_RUNTIME_UID`/`APP_RUNTIME_GID`、`CODEGRAPH_VERSION`、`APP_HTTP_BIND_ADDRESS`、`APP_HTTP_PORT`。

关键行为：

- 后端以非 root 用户运行（默认 UID/GID `10001`），仓库目录只读挂载，`/tmp` 使用 256 MB tmpfs（`:21`、`:52`、`:56-57`）。
- 后端健康检查使用 `curl --fail http://127.0.0.1:8080/actuator/health`，前端在 `backend` 健康后启动（`:58-63`、`:70-72`）。
- 前端镜像构建自 `frontend/Dockerfile`，对外端口 `${APP_HTTP_BIND_ADDRESS:-127.0.0.1}:${APP_HTTP_PORT:-8088}`（`:66-74`）。
- 数据库数据位于命名卷 `postgres-data`（`:76-77`）。

## 5 离线组件包

联网构建机：

```bash
bash scripts/build-offline-package.sh --version 1.0.0
```

Windows PowerShell 7：`pwsh -File scripts/build-offline-package.ps1 -Version 1.0.0`。

脚本拉取并重标记 `pgvector/pgvector:pg17` → `analyzer-coder/postgres:offline`、`nginx:1.27-alpine` → `analyzer-coder/nginx:offline`，然后打包：`install.sh`、`install.ps1`、`README.md`（来自 `deploy/OFFLINE-README.md`）、`STARTUP-GUIDE.md`（来自本手册）、`MANIFEST.txt`、`images.tar`、`SHA256SUMS`，最后输出 `release/analyzer-coder-components-offline-<version>.tar.gz`。

目标机解压后执行 `bash install.sh` 导入镜像，再通过 Git 获取源码并在源码根目录运行 `scripts/start.sh`（此时脚本会自动选用 `compose.offline.yaml`）。

`compose.offline.yaml` 与 `compose.components.yaml` 的服务定义一致，区别只在于镜像改为 `analyzer-coder/postgres:offline` 与 `analyzer-coder/nginx:offline`，并设置 `pull_policy: never`，确保离线环境不会尝试联网拉取。

包内**不含**源码、前端产物或后端 JAR。

## 6 运行诊断：`scripts/check-runtime.mjs`

只读检查，不启动服务、不打印凭据：

```bash
node scripts/check-runtime.mjs [--json]
```

检查项与判定（`scripts/check-runtime.mjs:42-65`）：

| 检查 | 必需 | 判定 |
| --- | --- | --- |
| Node.js ≥ 20 | 是 | 版本号主版本 |
| `java`、`mvn`、`git` | 是 | 在 PATH 中可执行 |
| CodeGraph（`APP_CODEGRAPH_EXECUTABLE`，默认 `codegraph`） | 否（`WARN`） | 缺失时图谱阶段失败，源码检索仍可用 |
| PostgreSQL TCP 可达 | 是 | 解析 `APP_DATASOURCE_URL` 后仅验证端口连通，不验证认证与 pgvector |
| 后端健康 | 是 | `GET /actuator/health` 返回 `UP` |
| 仓库根目录 | 是 | `APP_REPOSITORY_ALLOWED_ROOTS` 非空且每个目录存在 |
| 受管数据目录 | 是 | 已设置 `APP_MANAGED_DATA_ROOT` |

存在任一 `FAIL` 时退出码为 1。

## 7 停止与日志

| 内容 | 位置 |
| --- | --- |
| 后端 PID | `runtime/backend.pid` |
| 后端日志 | `runtime/logs/backend.log` |
| 组件日志 | `docker compose --env-file .env.components -f <compose 文件> logs -f` |
| 停止本脚本启动的后端 | 终止 `runtime/backend.pid` 中的进程 |

`runtime/` 位于源码根目录且被 `.gitignore` 忽略。

## 8 升级与数据

- 数据库结构由 Flyway 在后端启动时自动迁移（`APP_FLYWAY_ENABLED` 默认 `true`，`baseline-on-migrate: true`）。
- `V9__remove_cross_repository_projects.sql` 带**数据守卫**：若存在跨仓项目记录或非空的跨仓知识范围，迁移会主动抛错并停止，必须先导出/迁移这些数据。升级前必须备份数据库。
- 受管数据根目录（快照、索引产物、图谱产物、附件）与数据库需**一起**备份，单独恢复其中一方会导致版本与引用不一致。

## 9 常见故障对照

| 现象 | 处理 |
| --- | --- |
| 提示缺失命令 | 按 `start.sh` 的前置清单补齐 Docker/Compose v2、npm、Maven、JDK、Git、CodeGraph CLI、curl |
| `... still contains placeholder secrets` | 修改 `.env.components` / `.env.application` 中的占位值 |
| `Port 8080 is already serving a backend not managed by ...` | 先停止占用 8080 的进程，或清理不属于本源码目录的 `runtime/backend.pid` |
| 后端未在 180 秒内健康 | 查看 `runtime/logs/backend.log` 末 120 行；常见原因是数据库不可达、必需环境变量缺失或 Flyway 迁移失败 |
| 前端可打开但接口失败 | 确认 Nginx 已把 `/api` 与 `/actuator` 代理到宿主机后端端口 |
| CodeGraph 阶段失败 | 安装并配置 `APP_CODEGRAPH_EXECUTABLE`；缺失时仅图谱能力不可用 |
| 离线包构建失败 | 需要可用的 Docker 守护进程与联网拉取基础镜像的权限 |
