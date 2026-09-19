# Analyzer Coder 非功能需求与部署运维
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1. 范围

覆盖当前实现中可验证的安全需求、配置需求、容量边界、部署形态、启动与诊断脚本、可观测性、
升级与数据迁移。每条结论给出 `相对路径:行号` 证据。

## 2. 安全需求

### 2.1 会话与 CSRF

- 会话由 HttpOnly Cookie `AC_SESSION` 承载；`AccessTokenInterceptor` 与
  `SessionInterceptor` 均注册在 `/**`（`backend/src/main/java/com/analyzercoder/security/WebSecurityConfig.java:21-23`；
  名称定义 `backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:15`）。
- 公开路径仅 `/api/health`、`/api/auth/login`、`/api/auth/captcha`、
  `/actuator/health*`、`/error*`（`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:23-29`）。
- 库中只存会话令牌的 SHA-256 摘要（`backend/src/main/java/com/analyzercoder/security/AuthService.java:223-224,461`）。
- 双重超时：`last_seen_at + session-idle-minutes` 或 `expires_at` 任一过期即失效并删会话
  （`backend/src/main/java/com/analyzercoder/security/AuthService.java:229-234`）。
- CSRF：GET/HEAD/OPTIONS 之外必须携带 `X-CSRF-Token`，用 `MessageDigest.isEqual` 常数时间比较，
  失败 403 `CSRF_INVALID`（`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:16,58-66`）。
- `mustChangePassword` 会话只能访问 `/api/auth/change-password|logout|me`，其余 403
  `PASSWORD_CHANGE_REQUIRED`（`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:67-71`）。
- 改密、重置密码、停用、改角色都会删除该账号全部会话并撤销全部访问令牌
  （`backend/src/main/java/com/analyzercoder/security/AuthService.java:259-261,359-362,388-389`）。

### 2.2 Cookie 策略

- `AC_SESSION` 属性固定为 `httpOnly=true`、`sameSite=Lax`、`path=/`，
  `secure` 取自 `app.security.cookie-secure`，`maxAge` = `session-max-hours`
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:46-54`）。
- 登出以 `Max-Age=0` 清空同名 Cookie（`.../AuthController.java:116`）。
- `app.security.cookie-secure` 在 `application.yml` 默认 `false`
  （`backend/src/main/resources/application.yml:65`），生产模板默认 `true`
  （`compose.prod.yaml:45`；`deploy/analyzer-coder.env.example:5`）；HTTPS 部署必须设为 `true`。

### 2.3 密码与登录防护

- 密码长度 8–64，须含大写/小写/数字/特殊字符中至少三类，不得等于用户名（忽略大小写），
  不得命中 6 条弱密码黑名单
  （`backend/src/main/java/com/analyzercoder/security/PasswordHasher.java:19-20,73-96`）。
- 散列算法 PBKDF2WithHmacSHA256，210000 次迭代、256 位密钥、16 字节随机盐，格式
  `pbkdf2-sha256$<iterations>$<salt>$<hash>`（`.../PasswordHasher.java:16-17,46-56`）。
- 用户名须匹配 `[A-Za-z0-9._-]{3,32}`，显示名 1–50 字符
  （`backend/src/main/java/com/analyzercoder/security/AuthService.java:33,128-138`）。
- 连续失败 3 次起要求验证码（429 `CAPTCHA_REQUIRED`），累计 5 次锁定 `lock-minutes` 分钟
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:82-87`；
  `.../AuthService.java:197-211`）。
- 登录失败统一文案，不区分用户不存在/密码错/账号停用
  （`.../AuthService.java:35,182-191`）。
- 临时密码有效期 24 小时，过期登录返回 401 `TEMPORARY_PASSWORD_EXPIRED`
  （`.../AuthService.java:192-196,312-320,380-387`）。
- 必须保留至少一个已启用超级管理员；不得停用当前登录账号；仍持有仓库所有权的账号不得停用
  （`.../AuthService.java:341-352`）。

### 2.4 账户访问令牌

- 明文形如 `acp_` + 43 位 URL-safe Base64；库中仅存 SHA-256 摘要与 12 位前缀；有效期 1–365 天，
  名称 1–80 字符，越界 400 `TOKEN_INPUT_INVALID`
  （`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:39-57`）。
- 只能管理自己的令牌（超级管理员可管理任意账号）
  （`.../AccessTokenService.java:99-102`）。
- 令牌仅被允许调用 `/api/mcp`、`GET /api/repositories/{uuid}/evidence-search`、
  `POST /api/repositories/{uuid}/contexts`；其他路径带 `Authorization` 头返回 403
  `TOKEN_ENDPOINT_FORBIDDEN`（`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:12-15,29-30,50-53`）。
- MCP 请求额外校验 `Origin` 与请求自身 scheme/host/port 完全一致，否则 403 `MCP_ORIGIN_FORBIDDEN`
  （`.../AccessTokenInterceptor.java:55-75`）。
- 令牌只代表身份，仓库权限每次请求实时判定
  （`.../AccessTokenService.java:16`；`AccessControlService.java:19-35`）。

### 2.5 凭据与模型密钥加密

- Git/GitLab 凭据：AES/GCM/NoPadding，128 位 GCM tag，密钥为
  `SHA-256("git-credential-encrypt:" + app.credentials.master-key)`，算法标签 `AES-256-GCM`
  （`backend/src/main/java/com/analyzercoder/application/repository/CredentialSecretCipher.java:22,26,34-41,49-53`）。
- 模型 API Key：AES/GCM/NoPadding，密钥为 `SHA-256("encrypt:" + app.llm.master-key)`
  （`backend/src/main/java/com/analyzercoder/application/llm/LlmSecretCipher.java:26,35,43-50,58-62`）。
- 密文/IV/摘要持久化，算法列默认 `'AES-256-GCM'`
  （`backend/src/main/resources/db/migration/V1__init_schema.sql:213`；
  `backend/src/main/resources/mappers/RepositoryCredentialMapper.xml:5-6,28-29`；
  `.../LlmSettingsMapper.xml:37-41`）。
- `app.credentials.master-key` 未单独配置时回退到 `APP_LLM_MASTER_KEY`
  （`backend/src/main/resources/application.yml:52-53`）；主密钥更换后已存密文无法解密，
  因此保存密钥后不得随意更换（`application.yml:96-97`）。

### 2.6 路径白名单与远程主机策略

- 仓库路径白名单按 `,`/`;` 拆分，要求至少一个根；目标 `toRealPath()` 后必须 `startsWith`
  某允许根，且必须是存在且可读的目录，否则报
  `Repository path is outside configured allowed roots`
  （`backend/src/main/java/com/analyzercoder/infrastructure/repository/RepositoryPathPolicy.java:20-39,47-66`）；
  导入暂存目录额外限制在受管数据根内（`.../RepositoryPathPolicy.java:52-55`）。
- 快照复制拒绝符号链接与非普通文件，并校验目标路径不逃逸
  （`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:128-148`）。
- 远端仓库目标策略：仅 HTTPS、禁止 user-info、端口必须为 -1 或 443、拒绝
  `localhost`/`*.localhost`/`*.local`，DNS 解析后拒绝环回、链路本地、站点本地、组播、
  CGNAT 及内网地址段（`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:14-80`）。
  该策略在导入任务、凭据校验、分支远端操作与同步导入入口均被调用
  （`.../RepositoryImportJobService.java:60`；`.../RepositoryCredentialService.java:90`；
  `.../branch/BranchRemoteService.java:55`；
  `backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:74`）。
- 模型端点 SSRF 策略：默认要求 HTTPS；仅当 `app.llm.allow-insecure-local=true` 且主机为
  localhost/127.0.0.1/::1 时允许 HTTP；禁止 user-info/query/fragment；拒绝端口 0 与 >65535；
  解析地址不得为私有/环回/链路本地/组播
  （`backend/src/main/java/com/analyzercoder/application/llm/LlmEndpointPolicy.java:18,22-39,41-67,69-104`）。

### 2.7 输出安全响应头

- Java 侧：`/api/repositories/{repositoryId}/files/raw` 设置
  `X-Content-Type-Options: nosniff` 与
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`，并带
  `Cache-Control: no-cache`
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryCodeBrowserController.java:72-79`）。
- Java 侧：知识附件下载设置 `Content-Disposition: attachment`（UTF-8 文件名）与
  `X-Content-Type-Options: nosniff`
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/KnowledgeAttachmentController.java:55-64`）。
- Java 侧：MCP 与令牌列表设置 `Cache-Control: no-store`
  （`.../McpController.java:149`；`.../AccountAccessTokenController.java:32,42`）。
- Nginx 侧统一注入 `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、
  `Referrer-Policy: strict-origin-when-cross-origin`；HTTPS 模板额外注入 HSTS
  `max-age=31536000`（`deploy/nginx-container.conf:13-15`；
  `deploy/nginx-host.conf:19-21`）。
- 全站 `Content-Security-Policy` 头在任何 nginx 配置中都未设置；CSP 仅存在于上述 Java 单端点。
- 后端不泄漏内部细节：未预期异常统一 500 `INTERNAL_ERROR` 固定文案，数据约束异常 409
  `CONFLICT` 固定文案（`backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:70-81`）；
  MCP 工具内部异常只记日志，不回显数据库或文件系统信息
  （`.../McpController.java:125-139`）。

### 2.8 不得公网暴露的组件

- 开发 compose 中 PostgreSQL 只绑定 `127.0.0.1:${POSTGRES_PORT:-5432}`
  （`compose.yaml:14-15`）；`compose.prod.yaml` 与 `deploy/compose.images.yaml` 不为数据库发布宿主端口。
- 后端容器不发布宿主端口：prod compose 只为 frontend 声明端口映射
  （`compose.prod.yaml:73-74`），后端经 compose 网络被 nginx 以 `http://backend:8080` 访问
  （`deploy/nginx-container.conf:21`）。
- Actuator 仅暴露 `health,info,metrics`（`backend/src/main/resources/application.yml:43-48`）；
  其中只有 `/actuator/health` 属公开路径，`/actuator/info` 与 `/actuator/metrics` 仍需有效会话
  （`.../SessionInterceptor.java:23-29`）。
- 内置 nginx 只反代 `/api/`，不透传 `/actuator/**`
  （`deploy/nginx-container.conf:21`；`deploy/nginx-components.conf:27`；`deploy/nginx-host.conf:27`）。
- 后端容器以非 root（uid/gid 10001）运行（`backend/Dockerfile:14-15,20`；`compose.prod.yaml:21`）；
  仓库目录在 prod 与 images 形态下只读挂载（`compose.prod.yaml:52`；`deploy/compose.images.yaml:45`）。
- systemd 加固：`UMask=0077`、`NoNewPrivileges`、`PrivateTmp`、`ProtectHome`、`ProtectSystem=strict`、
  `ReadWritePaths=/var/lib/analyzer-coder`、`ReadOnlyPaths=/srv/analyzer-repositories`
  （`deploy/analyzer-coder.service:16-22`）。

### 2.9 HTTPS 与反向代理要求

- 后端默认 `server.forward-headers-strategy: none`，配置注释要求"仅在受信任反向代理后按部署方式
  设置 framework/native；直连部署保持 none"
  （`backend/src/main/resources/application.yml:40-41`）；生产模板设为 `framework`
  （`compose.prod.yaml:46`；`deploy/analyzer-coder.env.example:4`；`scripts/start.sh:195`）。
- 后端无 `server.ssl.*`，TLS 只在 nginx 终止（`application.yml:38-41`）。
- `deploy/nginx-host.conf` 提供 80→443 跳转与 TLS 服务块，证书路径
  `/etc/ssl/analyzer-coder/fullchain.pem` 与 `privkey.pem`，`server_name` 为占位域名
  `analyzer.example.com`，部署前必须替换（`deploy/nginx-host.conf:1-13`）；
  `deploy/nginx-compose-edge.conf` 以 TLS 边缘代理 `127.0.0.1:8088`（`...:1-28`）。
- 反向代理需转发 `X-Forwarded-Proto`（容器模板取 `$http_x_forwarded_proto`，回退 `$scheme`）与
  `X-Forwarded-For`，并设置 `proxy_read_timeout 300s`
  （`deploy/nginx-container.conf:1-4,27-28`；`deploy/nginx-host.conf:30-34`）；
  上传体积上限 `client_max_body_size 60m`（`deploy/nginx-container.conf:11`；`deploy/nginx-host.conf:17`）。

## 3. 配置需求

配置文件只有 `backend/src/main/resources/application.yml`（103 行），无 `application-*.yml` profile。
下表中"必填"指无默认值、缺失会导致启动失败或功能不可用。

| 领域 | 配置键 | 环境变量 | 默认值 | 必填 | 来源 |
| --- | --- | --- | --- | --- | --- |
| 调度 | `spring.task.scheduling.pool.size` | `APP_WORKER_POOL_SIZE` | 4 | 否 | yml:6 |
| 应用 | `spring.application.name` | 无 | `codebase-knowledge-backend` | 否 | yml:8 |
| 数据源 | `spring.datasource.url` | `APP_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/codebase_kb` | 否 | yml:10 |
| 数据源 | `spring.datasource.username` | `APP_DATASOURCE_USERNAME` | `codebase_kb` | 否 | yml:11 |
| 数据源 | `spring.datasource.password` | `APP_DATASOURCE_PASSWORD` | 无 | **是** | yml:12-13 |
| Flyway | `spring.flyway.enabled` | `APP_FLYWAY_ENABLED` | `true` | 否 | yml:16 |
| Flyway | `spring.flyway.baseline-on-migrate` | 无 | `true` | 否 | yml:17 |
| Flyway | `spring.flyway.baseline-version` | 无 | `0` | 否 | yml:18 |
| Flyway | `spring.flyway.default-schema` | 无 | `public` | 否 | yml:19 |
| Flyway | `spring.flyway.schemas` | 无 | `public` | 否 | yml:20 |
| Flyway | `spring.flyway.create-schemas` | 无 | `true` | 否 | yml:21 |
| MyBatis | `mybatis.mapper-locations` | 无 | `classpath*:/mappers/**/*.xml` | 否 | yml:24 |
| MyBatis | `mybatis.type-handlers-package` | 无 | `com.analyzercoder.infrastructure.persistence.type` | 否 | yml:25 |
| MyBatis | `mybatis.configuration.map-underscore-to-camel-case` | 无 | `true` | 否 | yml:27 |
| MyBatis | `mybatis.configuration.cache-enabled` | 无 | `false` | 否 | yml:28 |
| MyBatis | `mybatis.configuration.local-cache-scope` | 无 | `statement` | 否 | yml:29 |
| MyBatis | `mybatis.configuration.jdbc-type-for-null` | 无 | `'NULL'` | 否 | yml:30 |
| 分页 | `pagehelper.helper-dialect` | 无 | `postgresql` | 否 | yml:34 |
| 分页 | `pagehelper.reasonable` | 无 | `false` | 否 | yml:35 |
| 分页 | `pagehelper.page-size-zero` | 无 | `false` | 否 | yml:36 |
| 分页 | `pagehelper.support-methods-arguments` | 无 | `false` | 否 | yml:37 |
| 服务器 | `server.port` | `APP_SERVER_PORT` | `8080` | 否 | yml:39 |
| 服务器 | `server.forward-headers-strategy` | `APP_FORWARD_HEADERS_STRATEGY` | `none` | 否 | yml:41 |
| 管理 | `management.endpoints.web.exposure.include` | 无 | `health,info,metrics` | 否 | yml:48 |
| 凭据 | `app.credentials.master-key` | `APP_CREDENTIAL_MASTER_KEY`（回退 `APP_LLM_MASTER_KEY`） | 回退到 LLM 主密钥 | 否 | yml:52-53 |
| 安全 | `app.security.initial-admin-username` | `APP_INITIAL_ADMIN_USERNAME` | 无 | **是**（仅库中无账号时使用） | yml:55-56 |
| 安全 | `app.security.initial-admin-password` | `APP_INITIAL_ADMIN_PASSWORD` | 无 | **是**（同上） | yml:57 |
| 安全 | `app.security.session-idle-minutes` | `APP_SESSION_IDLE_MINUTES` | 30 | 否 | yml:59 |
| 安全 | `app.security.session-max-hours` | `APP_SESSION_MAX_HOURS` | 12 | 否 | yml:61 |
| 安全 | `app.security.lock-minutes` | `APP_LOGIN_LOCK_MINUTES` | 15 | 否 | yml:63 |
| 安全 | `app.security.cookie-secure` | `APP_SESSION_COOKIE_SECURE` | `false` | 否（HTTPS 须改 `true`） | yml:65 |
| 仓库 | `app.repository.allowed-roots` | `APP_REPOSITORY_ALLOWED_ROOTS` | 无 | **是** | yml:67-68 |
| 仓库 | `app.repository.managed-data-root` | `APP_MANAGED_DATA_ROOT` | 无 | **是** | yml:70 |
| 仓库 | `app.repository.snapshot-root` | 无（派生） | `<managed-data-root>/repositories` | 否 | yml:72 |
| 仓库 | `app.repository.import-root` | 无（派生） | `<managed-data-root>/staging/imports` | 否 | yml:73 |
| 仓库 | `app.repository.snapshot-max-files` | `APP_REPOSITORY_SNAPSHOT_MAX_FILES` | 20000 | 否 | yml:75 |
| 仓库 | `app.repository.snapshot-max-total-bytes` | `APP_REPOSITORY_SNAPSHOT_MAX_TOTAL_BYTES` | 2147483648 | 否 | yml:77 |
| 仓库 | `app.repository.browser-max-file-bytes` | `APP_REPOSITORY_BROWSER_MAX_FILE_BYTES` | 2097152 | 否 | yml:79 |
| 索引 | `app.indexing.poll-interval-ms` | 无（固定） | 5000 | 否 | yml:82 |
| 索引 | `app.indexing.max-file-bytes` | 无（固定） | 524288 | 否 | yml:84 |
| 图谱 | `app.codegraph.executable` | `APP_CODEGRAPH_EXECUTABLE` | `codegraph` | 否（缺失时图谱阶段失败，检索仍可用） | yml:87 |
| 图谱 | `app.codegraph.timeout-minutes` | `APP_CODEGRAPH_TIMEOUT_MINUTES` | 10 | 否 | yml:89 |
| 图谱 | `app.codegraph.task-timeout-minutes` | `APP_CODEGRAPH_TASK_TIMEOUT_MINUTES` | 12 | 否 | yml:91 |
| 图谱 | `app.codegraph.poll-interval-ms` | `APP_CODEGRAPH_POLL_INTERVAL_MS` | 2000 | 否 | yml:92 |
| 图谱 | `app.codegraph.artifact-root` | 无（派生） | `<managed-data-root>/repositories` | 否 | yml:94 |
| 模型 | `app.llm.master-key` | `APP_LLM_MASTER_KEY` | 无 | **是** | yml:96-97 |
| 模型 | `app.llm.allow-insecure-local` | `APP_LLM_ALLOW_INSECURE_LOCAL` | `false` | 否 | yml:99 |
| 模型 | `app.llm.connectivity-timeout-seconds` | `APP_LLM_CONNECTIVITY_TIMEOUT_SECONDS` | 15（运行时钳制 5–30） | 否 | yml:101 |
| 模型 | `app.llm.breaker-failure-threshold` | `APP_LLM_BREAKER_FAILURE_THRESHOLD` | 3 | 否 | yml:103 |

上表 `yml:` 均指 `backend/src/main/resources/application.yml`。

不在 `application.yml` 中、只能经环境变量或 JVM 参数注入的键：

| 配置键 | 默认值 | 用途 | 来源 |
| --- | --- | --- | --- |
| `app.repository.branch-concurrency` | 2 | 分支准备并发度 | backend/src/main/java/com/analyzercoder/worker/BranchPreparationWorker.java:22 |
| `app.knowledge.drift-task-timeout-minutes` | 5 | 知识漂移任务时限 | .../application/knowledge/KnowledgeDriftJobProcessor.java:26 |
| `SERVER_ADDRESS`（绑定 `server.address`） | 部署模板给出 | 后端监听地址 | deploy/analyzer-coder.env.example:3；scripts/start.sh:194 |

- 不存在 `spring.datasource.hikari.*`，未配置连接池大小；也不存在 `server.tomcat.*`、
  `server.servlet.*`、`logging.*`、`info.*`、`management.endpoint.*` 与
  `management.endpoints.web.base-path`。Actuator 基路径与 health 明细可见性取框架默认值（需人工确认）。
- 若库中无任何账号且未提供初始管理员凭据，启动后仅记一条 WARN，不创建账号
  （`backend/src/main/java/com/analyzercoder/security/AuthService.java:150-161`）。

### 3.1 仅在全容器编排下使用的宿主机路径变量

`compose.prod.yaml` 额外要求两个"宿主机路径"变量，它们**只在该 compose 形态下使用**，
不出现在 `application.yml` 中，也不应与会话内的容器路径变量混淆：

| 变量 | 必填 | 作用 | 容器内对应变量 | 来源 |
| --- | --- | --- | --- | --- |
| `APP_REPOSITORY_HOST_ROOT` | **是**（`:?` 语法） | 作为 bind mount 源挂载到容器 `/repositories`，且该挂载为只读 | `APP_REPOSITORY_ALLOWED_ROOTS=/repositories` | compose.prod.yaml:38,49-52 |
| `APP_MANAGED_DATA_HOST_ROOT` | **是**（`:?` 语法） | 作为 bind mount 源挂载到容器 `/data/analyzer-coder`（读写），承接快照、索引产物、图谱产物与附件 | `APP_MANAGED_DATA_ROOT=/data/analyzer-coder` | compose.prod.yaml:39,53-55 |

- 两者语义不同：`*_HOST_ROOT` 是宿主机上的目录，`APP_REPOSITORY_ALLOWED_ROOTS` 与
  `APP_MANAGED_DATA_ROOT` 是容器内可见的路径（compose.prod.yaml:38-39,49-55）。
  仓库挂载为 `read_only: true`，因此后端对源码目录只读、只向受管数据根写入
  （compose.prod.yaml:52）。
- 该 compose 形态下其他隐含默认值：`APP_INITIAL_ADMIN_USERNAME` 默认 `admin`
  （compose.prod.yaml:36）、`APP_CREDENTIAL_MASTER_KEY` 回退到 `APP_LLM_MASTER_KEY`
  （compose.prod.yaml:43）、`APP_SESSION_COOKIE_SECURE` 默认 `true`
  （compose.prod.yaml:45）、`TZ` 默认 `Asia/Shanghai`（compose.prod.yaml:47）。

## 4. 容量与性能边界

### 4.1 快照、文件与索引

- 单快照最大文件数 20000，超限抛 `仓库文件数量超过系统限制`
  （`application.yml:75`；`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:41,116-118`）。
- 单快照最大总字节数 2147483648（2 GiB），用 `Math.addExact` 累加后判定
  （`application.yml:77`；`.../FileSystemRepositorySnapshotAdapter.java:42,135-138`）。
- 源码预览单文件上限 2097152（2 MiB）
  （`application.yml:79`；`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCodeBrowserService.java:29`）。
- 纳入索引的单文件上限 524288（512 KiB），固定不可配
  （`application.yml:84`；`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:84,94-101`）。
- 分块 `MAX_CHUNK_LINES = 120`、`CHUNK_OVERLAP_LINES = 20`、增量重建阈值
  `MAX_INCREMENTAL_CHANGE_RATIO = 0.35`（`.../application/indexing/IndexJobProcessor.java:30-32`）；
  单文件符号上限 `MAX_SYMBOLS_PER_FILE = 500`（`.../application/code/CodeSymbolExtractor.java:18`）。
- 远端分支探测上限 `MAX_DISCOVERED_BRANCHES = 2000`；分支名长度上限 200
  （`.../application/repository/GitCredentialExecutor.java:18`；
  `.../infrastructure/repository/GitBranchSnapshotFactory.java:41`）。

### 4.2 图谱结果上限

- `MAX_NODES = 500000`、`MAX_EDGES = 2000000`，超限抛 `CODEGRAPH_DATABASE_LIMIT_EXCEEDED`
  （`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:16-17,56-58,82-84`）。
- 影响面分析深度上限 `MAX_IMPACT_DEPTH = 5`；子进程错误消息截断到 1000 字符
  （`.../application/intelligence/ManagedCodeGraphService.java:30-31,144,375`）。
- MCP 工具入参边界：`maxFiles` 1–20、`offset` 0–10000、`limit` 1–100（文件/节点）与 1–50（其余）、
  `depth` 1–5、`page` 1–100000、`pageSize` 1–50
  （`.../application/mcp/McpCodeGraphTools.java:67,77-79,87,94,101,121,150-151,213-217`；
  同步约束见 `backend/src/main/resources/mcp-tools.json:84-516`）。

### 4.3 请求超时与任务超时

- HTTP 服务端未配置请求超时；实际生效的是 nginx `proxy_read_timeout 300s`
  （`deploy/nginx-container.conf:28`；`deploy/nginx-host.conf:34`）。
- CodeGraph CLI 单次构建超时 10 分钟（`application.yml:89`；
  `.../application/intelligence/CodeGraphService.java:30,34,62`）；Worker 任务整体时限 12 分钟，
  写入 `timeout_at`（`application.yml:91`；
  `.../application/intelligence/CodeGraphJobProcessor.java:30,34,43`；
  `backend/src/main/resources/mappers/IndexJobMapper.xml:76-80`）。
- 知识漂移任务时限 5 分钟，不可经 `application.yml` 配置
  （`.../application/knowledge/KnowledgeDriftJobProcessor.java:26,30,35`）。
- 超时任务由 `expireTimedOut` 标记为 `CODEGRAPH_TIMEOUT` / `KNOWLEDGE_DRIFT_TIMEOUT`；
  该语句按任务类型过滤，且 FULL/INCREMENTAL 索引任务领取时不写 `timeout_at`，
  因此**普通索引任务没有强制任务超时**
  （`backend/src/main/resources/mappers/IndexJobMapper.xml:64-69,86-93`）。
- 外部进程超时：Git 命令 30 秒、stderr 上限 32 KiB、摘要元数据上限 64 MiB
  （`.../infrastructure/git/ProcessGitClient.java:26-28`）；本地 Git 检查 15 秒
  （`.../infrastructure/repository/GitCliLocalGitInspector.java:26`）。
- 模型调用：连通性探测 15 秒（钳制 5–30）
  （`.../application/llm/LlmSettingsService.java:73,80,432`）；provider 级
  `connect_timeout_ms` 默认 5000（1000–10000）、`request_timeout_ms` 默认 60000（3000–120000）
  （`.../LlmSettingsService.java:604-605,631-632,808-809`）；向量请求连接超时上限
  `min(requestTimeout, 10000)`（`.../application/llm/OpenAiCompatibleClient.java:166`）。

### 4.4 分页与结果上限

- 统一分页上限 100：`PageResult.validate` 拒绝 `pageSize < 1 || pageSize > 100`
  （`backend/src/main/java/com/analyzercoder/application/common/PageResult.java:24-31`），
  所有分页服务均调用（`.../repository/RepositoryPageService.java:22`；
  `.../indexing/IndexJobPageService.java:21`；`.../indexing/VectorIndexQueryService.java:50,63`；
  `.../branch/BranchPreparationJobs.java:58`；`.../security/AuthService.java:290`）。
- 控制器默认页大小：20（`AccountController.java:43`、`IndexController.java:67`、
  `RepositoryController.java:87`），15（`VectorIndexController.java:46,58`、
  `RepositoryBranchController.java:189`）。
- 其他结果上限：片段查询默认 50、上限 200
  （`.../application/chunk/CodeChunkQueryService.java:14-15`）；
  架构符号下钻默认 80、上限 200
  （`.../application/architecture/ProjectArchitectureSymbolService.java:13-14`）；
  审计读取 `limit` 钳制到 1–200（`.../security/AuthService.java:422`）；
  每通道检索候选 40、检索词 12 个
  （`.../application/intelligence/IntelligenceService.java:40,466`；
  `.../RetrievalQueryAnalyzer.java:23`）；
  附件上限图片 10 MiB、文档 50 MiB、修订 200 MiB
  （`.../application/intelligence/KnowledgeAttachmentService.java:27-29,55,149`）；
  架构分析最多 1000 个文件、单文件 262144 字节
  （`.../application/architecture/ProjectArchitectureMapService.java:33-36`）。
- 并发：后台调度线程池 4（`application.yml:6`），分支准备并发度 2
  （`.../worker/BranchPreparationWorker.java:22`）。

## 5. 部署形态

### 5.1 Compose 文件

| 文件 | 用途与差异 |
| --- | --- |
| `compose.yaml` | 开发依赖，只起 PostgreSQL：`pgvector/pgvector:pg17`，容器 `analyzer-coder-postgres`，绑定 `127.0.0.1:${POSTGRES_PORT:-5432}:5432`，卷 `postgres-data`，`pg_isready` 健康检查，要求 `POSTGRES_PASSWORD`（compose.yaml:2-22） |
| `compose.prod.yaml` | 生产形态，postgres + backend + frontend：项目名 `analyzer-coder`；后端由 `backend/Dockerfile` 构建并传 `CODEGRAPH_VERSION`；以 `${APP_RUNTIME_UID:-10001}:${APP_RUNTIME_GID:-10001}` 运行；`APP_FORWARD_HEADERS_STRATEGY=framework`、`APP_SESSION_COOKIE_SECURE` 默认 `true`；仓库目录只读绑定；`/tmp` 为 256m tmpfs；仅 frontend 发布端口（compose.prod.yaml:21-74） |
| `compose.offline.yaml` | 离线部署，使用已导入镜像：项目名 `analyzer-coder-components`；镜像 `analyzer-coder/postgres:offline`、`analyzer-coder/nginx:offline`，均 `pull_policy: never`；nginx 只读挂载 `${APP_FRONTEND_DIST_HOST_ROOT}` 与 `deploy/nginx-components.conf`；健康检查 `/component-health`；`extra_hosts: host.docker.internal:host-gateway`（compose.offline.yaml:1-47） |
| `compose.components.yaml` | 组件形态，拓扑同离线但用上游镜像 `pgvector/pgvector:pg17`、`nginx:1.27-alpine`（compose.components.yaml:5,24） |
| `deploy/compose.images.yaml` | 预构建镜像部署：只用 `analyzer-coder/backend:${APP_IMAGE_TAG:-release-1}` 与 `analyzer-coder/frontend:${APP_IMAGE_TAG:-release-1}`（`pull_policy: never`）；要求显式 `APP_CREDENTIAL_MASTER_KEY`；默认绑定 `0.0.0.0:80`；数据目录来自必填 `APP_POSTGRES_DATA_HOST_ROOT`（deploy/compose.images.yaml:14,22-24,40,57-58,64） |

### 5.2 镜像构建

- `backend/Dockerfile`：构建阶段 `maven:3.9-eclipse-temurin-17`（:1-7）；运行阶段
  `node:20-bookworm-slim`，`ARG CODEGRAPH_VERSION=1.5.0`，安装
  `openjdk-17-jre-headless git ca-certificates curl` 与全局 npm 包
  `@colbymchenry/codegraph@${CODEGRAPH_VERSION}`；创建非 root 用户 `analyzer`(10001)；
  JAR 位于 `/app/app.jar`；`EXPOSE 8080`；HEALTHCHECK 每 30 秒请求
  `http://127.0.0.1:8080/actuator/health`（3 次重试）；入口 `java -jar /app/app.jar`
  （backend/Dockerfile:9-24）。
- `frontend/Dockerfile`：`node:20-alpine` 执行 `npm ci` + `npm run build`（:1-6）；
  运行阶段 `nginx:1.27-alpine`，配置来自 `deploy/nginx-container.conf`；`EXPOSE 8080`；
  HEALTHCHECK 用 wget 请求 `/`（frontend/Dockerfile:8-13）。
- `.dockerignore` 排除 `.git`、`.github`、`.idea`、`.vscode`、`**/target`、
  `frontend/node_modules`、`frontend/dist`、`runtime-logs`、`*.log`、`.env`、`.env.production`
  （.dockerignore:1-11）；`scripts/start.sh` 运行期生成的 `.env.components` 与
  `.env.application` **不在**排除列表内。

### 5.3 `deploy/` 目录

- systemd 单元 `deploy/analyzer-coder.service`：`User/Group=analyzer`，
  `WorkingDirectory=/opt/analyzer-coder`，`EnvironmentFile=/etc/analyzer-coder/analyzer-coder.env`，
  `ExecStart=/usr/bin/java -Xms512m -Xmx2g -jar /opt/analyzer-coder/app/analyzer-coder.jar`，
  `Restart=on-failure`、`RestartSec=5`、`TimeoutStopSec=30`，以及第 2.8 节的加固项
  （deploy/analyzer-coder.service:6-25）。
- Nginx 四份：`nginx-container.conf`（容器内监听 8080，`/api/` → `http://backend:8080`）、
  `nginx-components.conf`（额外 `location = /component-health` 返回 200 `ok` 并关闭访问日志，
  `/api/` → `http://host.docker.internal:8080`）、
  `nginx-host.conf`（80→443 + TLS + HSTS，root `/opt/analyzer-coder/web`，
  `/api/` → `http://127.0.0.1:8080`）、
  `nginx-compose-edge.conf`（TLS 边缘，代理 `127.0.0.1:8088`）
  （deploy/nginx-container.conf:1-30；deploy/nginx-components.conf:13-35；
  deploy/nginx-host.conf:1-35；deploy/nginx-compose-edge.conf:1-28）。
- 环境模板四份：`deploy/.env.production.example`（prod compose 全量模板，含
  `APP_CREDENTIAL_MASTER_KEY`、`APP_RUNTIME_UID/GID=10001`、`APP_HTTP_BIND_ADDRESS=127.0.0.1`、
  `APP_HTTP_PORT=8088`、`APP_SESSION_COOKIE_SECURE=true`、`CODEGRAPH_VERSION=1.5.0`）；
  `deploy/analyzer-coder.env.example`（systemd：`SERVER_ADDRESS=127.0.0.1`、
  `APP_FORWARD_HEADERS_STRATEGY=framework`、`APP_POSTGRES_SERVICE=postgresql`、
  `APP_NGINX_SERVICE=nginx`、`APP_JAVA_XMS=256m`、`APP_JAVA_XMX=768m`）；
  `deploy/analyzer-coder-components.env.example`（`SERVER_ADDRESS=0.0.0.0`、
  `APP_SESSION_COOKIE_SECURE=false`）；`deploy/analyzer-coder-images.env.example`
  （`APP_IMAGE_TAG`、`APP_POSTGRES_DATA_HOST_ROOT`、绑定 `0.0.0.0:80`）
  （deploy/.env.production.example:1-20；deploy/analyzer-coder.env.example:1-25；
  deploy/analyzer-coder-components.env.example:2-7；deploy/analyzer-coder-images.env.example:1-15）。
- 说明文档：`deploy/README.md` 与 `deploy/OFFLINE-README.md`（deploy/README.md:1-58；
  deploy/OFFLINE-README.md:1-49）。
- 仓库根的 `.env.production` 含明文 `POSTGRES_PASSWORD`、`APP_INITIAL_ADMIN_PASSWORD`、
  `APP_LLM_MASTER_KEY`，且 `APP_SESSION_COOKIE_SECURE=false`
  （.env.production:3,5,6,13）；该文件被 `.gitignore` 忽略且未被 Git 跟踪（.gitignore:18）。
  部署前必须确认其取值与目标环境一致。

### 5.4 健康检查端点与其公开性

| 端点 | 消费者 | 公开性 |
| --- | --- | --- |
| `/actuator/health` | 两个 Dockerfile 的 HEALTHCHECK、compose 健康检查、`check-runtime.mjs` | 公开（`SessionInterceptor` 显式放行 `path.startsWith("/actuator/health")`）（backend/Dockerfile:22-23；compose.prod.yaml:58-63；SessionInterceptor.java:27） |
| `/api/health` | 自定义探针与脚本 | 公开，返回 `{status,timestamp}`（HealthController.java:13-16；SessionInterceptor.java:24） |
| `/component-health` | nginx-components 健康检查 | 由 nginx 直接返回 200 `ok`，不经过后端（deploy/nginx-components.conf:17-21；compose.offline.yaml:42-47） |
| `/actuator/info`、`/actuator/metrics` | 无内置消费者 | 已暴露但**不公开**：不在公开路径列表内，需有效会话（application.yml:43-48；SessionInterceptor.java:23-29） |

## 6. 启动与诊断脚本

- `scripts/start.sh`（源码构建启动）：`set -Eeuo pipefail`；参数 `--skip-npm-ci`、`--https`
  （后者强制 `APP_SESSION_COOKIE_SECURE=true`）。依赖 `docker`、`od`、`npm`、`mvn`、`java`、
  `git`、`codegraph`、`curl`、`nohup`、`ps`、`find`、`grep`、`seq`、`tail`、`tr`、`sed`、`basename`
  及可用的 `docker compose` v2 与运行中的守护进程。构建前端（`NODE_OPTIONS` 默认
  `--max-old-space-size=1536`）与后端（`MAVEN_OPTS` 默认 `-Xmx1024m`，
  `mvn -pl backend -am clean package -DskipTests`），要求恰好一个后端 JAR；
  存在两个离线镜像时用 `compose.offline.yaml`，否则用 `compose.components.yaml`；
  自动生成 `.env.components` 与 `.env.application`（`chmod 600`，密钥取自 `/dev/urandom`）。
  失败处理：拒绝杀掉非本源码树启动的进程、拒绝 8080 端口上的非受管后端；
  生成的 env 文件仍含 `replace-with` 即中止；等待 postgres/nginx 最多 60×2 秒、
  等待 `/actuator/health` 最多 90×2 秒，超时则打印日志尾部 120 行、杀进程、删 PID 文件、退出 1。
  产物为 `runtime/backend.pid` 与 `runtime/logs/backend.log`
  （scripts/start.sh:2,9-10,14-26,32-34,36-46,61-143,149-165,187-261）。
- `scripts/start-prebuilt-host.sh`（预构建主机启动，不依赖 Docker/npm/Maven）：
  参数 `--env-file`、`--jar`、`--frontend-root`、`--skip-service-start`、`--reload-nginx`。
  依赖 `java`、`curl`、`pg_isready`、`systemctl`、`ps`、`find`、`xargs`；特权经 root 或
  `sudo -n` 获取，否则退出 1。校验 9 个环境变量非空（含 `APP_DATASOURCE_*`、
  `APP_INITIAL_ADMIN_*`、`APP_REPOSITORY_ALLOWED_ROOTS`、`APP_MANAGED_DATA_ROOT`、
  `APP_LLM_MASTER_KEY`、`APP_CREDENTIAL_MASTER_KEY`）、占位符守卫、
  `frontend/dist/index.html` 存在性、唯一后端 JAR、每个允许根存在且可读、`pg_isready` 门禁；
  `nginx -t` 在无 sudo 时跳过并告警，nginx 仅在 `--reload-nginx` 时重载。
  健康等待 90×2 秒，失败则输出日志尾部 120 行、杀进程、删 PID 文件、退出 1
  （scripts/start-prebuilt-host.sh:2,20-37,50-110）。
- `scripts/check-runtime.mjs`（只读诊断）：读取 `APP_DATASOURCE_URL`、`APP_SERVER_PORT`、
  `APP_CODEGRAPH_EXECUTABLE`、`APP_REPOSITORY_ALLOWED_ROOTS`、`APP_MANAGED_DATA_ROOT`；
  检查 Node ≥ 20、`java`/`mvn`/`git` 必须具备、CodeGraph 缺失仅记 WARN、
  PostgreSQL 端口 2.5 秒 TCP 可达、后端 `/actuator/health` 必须返回 `UP`（3 秒超时）、
  仓库根存在且为目录、`APP_MANAGED_DATA_ROOT` 已设置。
  默认逐行打印 `[PASS|WARN|FAIL]`，`--json` 输出结构化结果，`--help` 打印用法并退出 0；
  存在任一 FAIL 时退出 1（scripts/check-runtime.mjs:5-13,32,38-69）。
- `scripts/verify-core.mjs`（端到端核心验证）：依赖 Docker；启动一次性
  `pgvector/pgvector:pg17` 容器（`--port` 默认 15439，校验 1024–65535），随机生成数据库密码、
  管理员密码与主密钥，数据根 `.runtime/verification/<uuid>`；每条命令超时 600 秒；
  依次运行 vitest、`vue-tsc`、`vite build`、
  `mvn -pl backend -am -Dtest='*Test,*Tests,*IT' test`、mcp-server `node --test` 与脚本测试。
  任一失败即打印信息并置退出码 1；无论如何都在 `finally` 删除容器，删除失败同样置退出码 1
  （scripts/verify-core.mjs:12-31,35,61-80）。
- `scripts/evaluate-quality.mjs`（评测门槛）：读取 `evaluation/manifest.json` 与
  `evaluation/thresholds.json`，支持 `--root`。数据集校验包括条目数与 `expectedCount` 一致、
  用例 ID 唯一、仓库类型在 `repositoryTypes` 内、`expectedPaths`/`evidence` 引用文件存在、
  QA 用例须有人工审阅信息（`curation.method = HUMAN` 且有 `reviewedAt`）。
  `--validate` 打印 `{valid,scoreable,datasetVersion,counts}` 并退出 0；
  `--results <文件>` 计算 `retrievalRecallAt10`、`citationCoverageRate`、
  `statementSupportRate`、`p95LatencyMs` 并与阈值比较，未通过退出 2；
  任何异常打印 `评测失败：<message>` 并退出 1
  （scripts/evaluate-quality.mjs:6-14,42-67,124-136,142-153）。
- 离线打包与安装：`scripts/build-offline-package.sh` 按 `--platform`（默认 `linux/amd64`）
  拉取 `pgvector/pgvector:pg17` 与 `nginx:1.27-alpine` 并重标记为
  `analyzer-coder/postgres:offline`、`analyzer-coder/nginx:offline`（版本号须匹配
  `^[A-Za-z0-9._-]+$`），打包内容含两个安装脚本、`deploy/OFFLINE-README.md` 以及由
  `docs/15-deployment-runbook.md` 复制而来的 `STARTUP-GUIDE.md`，生成 `MANIFEST.txt`，
  `docker save` 为 `images.tar`，`sha256sum`/`shasum` 均不可用时退出 1，最后 `chmod +x install.sh`
  并 `tar -czf`（scripts/build-offline-package.sh:7,18,25-28,57-97）；
  `build-offline-package.ps1` 为等价实现并额外等待 Docker Desktop 最多 120 秒
  （scripts/build-offline-package.ps1:36-54,59-113）；
  `scripts/offline-install.sh` 校验 `SHA256SUMS` 后 `docker load --input images.tar`，
  要求 docker、compose v2 与运行中的守护进程，否则退出 1，未知参数退出 2
  （scripts/offline-install.sh:2,12,20-41）；`offline-install.ps1` 为 Windows 等价实现，
  正则校验 `SHA256SUMS` 格式并用 `Get-FileHash` 比对，不匹配即抛错
  （scripts/offline-install.ps1:12-35）。

## 7. 可观测性

- Actuator 暴露端点固定为 `health,info,metrics`，健康与诊断入口见第 5.4 节
  （`backend/src/main/resources/application.yml:43-48`）。
- 任务状态诊断：`GET /api/index-jobs/page`、`GET /api/index-jobs/{jobId}`、
  `GET /api/repositories/{repositoryId}/index-jobs`、
  `GET /api/repositories/{repositoryId}/branch-preparation-jobs`、
  `GET /api/repositories/{repositoryId}/branch-preparation-jobs/history`；
  `IndexJobResponse` 含 `currentStep`、`executionMode`、`fallbackReason`、`failureCode`、
  `errorMessage`、`heartbeatAt`、`timeoutAt`
  （`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:64-99,123-137`；
  `.../RepositoryBranchController.java:90-94,184-192`）。
- 日志：启动脚本把后端输出重定向到 `runtime/logs/backend.log`（scripts/start.sh:9-10）。
- 审计日志的范围与事件类型见账号与权限文档；唯一读取端点为 `GET /api/accounts/audit`，
  要求超级管理员（`.../AccountController.java:110-117`）。

## 8. 升级与数据迁移

- Flyway 默认启用，`baseline-on-migrate=true`、`baseline-version=0`、
  `default-schema=public`、`schemas=public`、`create-schemas=true`
  （`backend/src/main/resources/application.yml:14-21`）。
- 现有迁移为 V1–V9（`backend/src/main/resources/db/migration/`）：
  V1 初始化结构、V2 移除变更评审、V3 分支上下文、V4 分支图谱任务、V5 分支准备任务、
  V6 分支向量复用、V7 分支生命周期与溯源、V8 分支代码操作、
  V9 移除跨仓库项目。
- V1 执行 `CREATE EXTENSION IF NOT EXISTS vector`
  （`backend/src/main/resources/db/migration/V1__init_schema.sql:6`），
  因此数据库角色需有权创建扩展；`pgvector/pgvector:pg17` 镜像已提供该扩展
  （`compose.yaml:18`）。
- `V9` 的数据守卫（升级前置条件，来自
  `backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql`）：
  - 迁移全程对 `engineering_projects`、`engineering_project_repositories`、
    `engineering_project_contracts`、`knowledge_cards`、`knowledge_card_revisions`
    取 `ACCESS EXCLUSIVE` 锁（V9:2-3），期间相关表不可读写，应在停机窗口执行。
  - 三张 `engineering_project*` 表中只要存在任意一行，迁移抛
    `Cross-repository project data must be exported/migrated before V9` 并失败（V9:6-11）。
  - 任一 `knowledge_cards` 或 `knowledge_card_revisions` 行的
    `scope_payload->repositoryIds|serviceNames|contractIds` 非空时，迁移抛
    `Cross-repository knowledge scopes must be migrated before V9` 并失败（V9:12-24）。
  - 因此**带历史跨仓库项目或跨仓库知识作用域的库必须先导出/迁移数据，再执行 V9**，
    否则升级会中断在迁移阶段。
  - 守卫通过后，迁移清理这三类键、重置列默认值、加上更严格的 CHECK 约束，
    最后删除三张 `engineering_project*` 表（V9:27-59,63-65）。
- 备份要求：V9 会 DROP 三张表并重写知识卡作用域载荷，属不可逆结构变更，
  升级前必须完成数据库全量备份；备份流程本身未在仓库中脚本化（需人工确认）。
