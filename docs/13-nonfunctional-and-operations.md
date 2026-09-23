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
  （`backend/src/main/resources/application.yml:65`），发布模板按 HTTP 默认设为 `false`
  （发布包 backend/config/application.yml 中 app.security.cookie-secure）；HTTP 模板为 false，HTTPS 部署必须设为 true。

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
  （`backend/src/main/resources/db/migration/V1__init_schema.sql`；
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
- 分支工作区导出拒绝符号链接与子模块，并校验归档路径不逃逸
  （`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchContentVersionFactory.java`）。
- 远端仓库目标策略：仅 HTTPS、禁止 user-info、端口必须为 -1 或 443、拒绝
  `localhost`/`*.localhost`/`*.local`，DNS 解析后拒绝环回、链路本地、站点本地、组播、
  CGNAT 及内网地址段（`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:14-80`）。
  该策略在导入任务、凭据校验、分支远端操作与同步导入入口均被调用
  （`.../RepositoryImportJobService.java:60`；`.../RepositoryCredentialService.java:90`；
  `.../branch/BranchRemoteService.java:55`；
  `backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:74`）。
- 模型端点策略：默认拒绝受保护网络并校验 HTTPS 证书。`app.llm.endpoint-exceptions`
  按完整基础地址分别配置内网访问和 TLS 例外；未列出的地址继续按默认规则处理。
  公网仍必须使用 HTTPS，禁止 user-info/query/fragment 和 HTTP 重定向
  （`backend/src/main/java/com/analyzercoder/application/llm/LlmEndpointPolicy.java`；
  `backend/src/main/java/com/analyzercoder/application/llm/OpenAiCompatibleClient.java`）。

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
- Nginx 模板统一注入 X-Content-Type-Options、X-Frame-Options、Referrer-Policy，配置见 deploy/components/nginx/nginx.conf。
- 全站 `Content-Security-Policy` 头在任何 nginx 配置中都未设置；CSP 仅存在于上述 Java 单端点。
- 后端不泄漏内部细节：未预期异常统一 500 `INTERNAL_ERROR` 固定文案，数据约束异常 409
  `CONFLICT` 固定文案（`backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:70-81`）；
  MCP 工具内部异常只记日志，不回显数据库或文件系统信息
  （`.../McpController.java:125-139`）。

### 2.8 不得公网暴露的组件

- PostgreSQL 只绑定宿主机 127.0.0.1；后端在宿主机运行，监听 0.0.0.0 供 Docker 网关访问，防火墙限制发布端口 18082 仅允许 Docker 来源。
- Nginx 只转发 /api/，拒绝 /actuator；健康检查在主机访问 /actuator/health。
- 后端运行账号只授予所需仓库和数据目录访问权限，不再依赖后端容器的 UID、挂载或 systemd 模板。

### 2.9 HTTPS 与反向代理要求

- 外部 YAML 将 server.forward-headers-strategy 设为 framework。默认 Nginx 模板提供 HTTP；公网发布按部署手册在同一配置中接入 HTTPS 并启用 app.security.cookie-secure。
- Nginx 使用实际连接信息覆盖转发头。前置 HTTPS 网关需按受信代理链调整配置，不信任公网客户端任意传入的转发头。
- API 读取超时 300 秒、上传限制 60m、关闭流式响应缓冲；见 deploy/components/nginx/nginx.conf。

## 3. 配置需求

JAR 内默认配置位于 backend/src/main/resources/application.yml；发布包的 backend/config/application.yml 按 Spring Boot 标准加载并覆盖同名属性，无需配置 APP_ 环境变量。下表保留源码默认属性及其环境变量映射。
下表中"必填"指无默认值、缺失会导致启动失败或功能不可用。

| 领域 | 配置键 | 环境变量 | 默认值 | 必填 | 来源 |
| --- | --- | --- | --- | --- | --- |
| 调度 | `spring.task.scheduling.pool.size` | `APP_WORKER_POOL_SIZE` | 4 | 否 | yml:6 |
| 应用 | `spring.application.name` | 无 | `codebase-knowledge-backend` | 否 | yml:8 |
| 数据源 | `spring.datasource.url` | `APP_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/codebase_kb` | 否 | yml:10 |
| 数据源 | `spring.datasource.username` | `APP_DATASOURCE_USERNAME` | `codebase_kb` | 否 | yml:11 |
| 数据源 | `spring.datasource.password` | `APP_DATASOURCE_PASSWORD` | 无 | **是** | yml:12-13 |
| Flyway | `spring.flyway.enabled` | `APP_FLYWAY_ENABLED` | `true` | 否 | yml:16 |
| Flyway | `spring.flyway.baseline-on-migrate` | 无 | `false` | 否 | yml:18 |
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
| 仓库 | `app.repository.workspace-root` | 无（派生） | `<managed-data-root>/repositories` | 否 | yml:72 |
| 仓库 | `app.repository.import-root` | 无（派生） | `<managed-data-root>/staging/imports` | 否 | yml:73 |
| 仓库 | `app.repository.workspace-max-files` | `APP_REPOSITORY_WORKSPACE_MAX_FILES` | 50000 | 否 | yml:79 |
| 仓库 | `app.repository.workspace-max-total-bytes` | `APP_REPOSITORY_WORKSPACE_MAX_TOTAL_BYTES` | 2147483648 | 否 | yml:77 |
| 仓库 | `app.repository.browser-max-file-bytes` | `APP_REPOSITORY_BROWSER_MAX_FILE_BYTES` | 2097152 | 否 | yml:79 |
| 索引 | `app.indexing.poll-interval-ms` | 无（固定） | 5000 | 否 | yml:82 |
| 索引 | `app.indexing.max-file-bytes` | 无（固定） | 524288 | 否 | yml:84 |
| 图谱 | `app.codegraph.executable` | `APP_CODEGRAPH_EXECUTABLE` | `codegraph` | 否（缺失时图谱阶段失败，检索仍可用） | yml:87 |
| 图谱 | `app.codegraph.timeout-minutes` | `APP_CODEGRAPH_TIMEOUT_MINUTES` | 30 | 否 | yml:93 |
| 图谱 | `app.codegraph.task-timeout-minutes` | `APP_CODEGRAPH_TASK_TIMEOUT_MINUTES` | 35 | 否 | yml:95 |
| 图谱 | `app.codegraph.poll-interval-ms` | `APP_CODEGRAPH_POLL_INTERVAL_MS` | 2000 | 否 | yml:92 |
| 图谱 | `app.codegraph.artifact-root` | 无（派生） | `<managed-data-root>/repositories` | 否 | yml:94 |
| 模型 | `app.llm.master-key` | `APP_LLM_MASTER_KEY` | 无 | **是** | yml:96-97 |
| 模型 | `app.llm.allow-insecure-local` | `APP_LLM_ALLOW_INSECURE_LOCAL` | `false` | 否 | yml:103 |
| 模型 | `app.llm.endpoint-exceptions` | 无（外部 YAML 列表） | 空列表；两项开关均为 `false` | 否 | yml:105 |
| 模型 | `app.llm.connectivity-timeout-seconds` | `APP_LLM_CONNECTIVITY_TIMEOUT_SECONDS` | 15（运行时钳制 5–30） | 否 | yml:101 |
| 模型 | `app.llm.breaker-failure-threshold` | `APP_LLM_BREAKER_FAILURE_THRESHOLD` | 3 | 否 | yml:103 |

上表 `yml:` 均指 `backend/src/main/resources/application.yml`。

不在 `application.yml` 中、只能经环境变量或 JVM 参数注入的键：

| 配置键 | 默认值 | 用途 | 来源 |
| --- | --- | --- | --- |
| `app.repository.branch-concurrency` | 2 | 分支准备并发度 | backend/src/main/java/com/analyzercoder/worker/BranchPreparationWorker.java:22 |
| `app.knowledge.drift-task-timeout-minutes` | 5 | 知识漂移任务时限 | .../application/knowledge/KnowledgeDriftJobProcessor.java:26 |
| `SERVER_ADDRESS`（绑定 `server.address`） | 部署模板给出 | 后端监听地址 | deploy/backend/config/application.yml |

- 不存在 `spring.datasource.hikari.*`，未配置连接池大小；也不存在 `server.tomcat.*`、
  `server.servlet.*`、`logging.*`、`info.*`、`management.endpoint.*` 与
  `management.endpoints.web.base-path`。Actuator 基路径与 health 明细可见性取框架默认值（需人工确认）。
- 若库中无任何账号且未提供初始管理员凭据，启动后仅记一条 WARN，不创建账号
  （`backend/src/main/java/com/analyzercoder/security/AuthService.java:150-161`）。

### 3.1 发布包的目录与组件配置

后端 YAML 中的 ./data 和 ./repositories 相对 backend 工作目录。组件参数单独保存在 components/.env，数据库账号、密码、端口需与 YAML 一致。Nginx 的前端挂载使用 ../frontend/dist；无需 APP_REPOSITORY_HOST_ROOT 或 APP_MANAGED_DATA_HOST_ROOT。

## 4. 容量与性能边界

### 4.1 分支工作区、文件与索引

- 单个受管分支工作区最大文件数 50000，超限抛文件数量限制错误
  （`application.yml`；`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchContentVersionFactory.java`）。
- 单个分支工作区最大总字节数 2147483648（2 GiB），用 `Math.addExact` 累加后判定
  （`application.yml`；`GitBranchContentVersionFactory.java`）。
- 源码预览单文件上限 2097152（2 MiB）
  （`application.yml:79`；`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCodeBrowserService.java:29`）。
- 纳入索引的单文件上限 524288（512 KiB），固定不可配
  （`application.yml:84`；`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:84,94-101`）。
- 分块 `MAX_CHUNK_LINES = 120`、`CHUNK_OVERLAP_LINES = 20`、增量重建阈值
  `MAX_INCREMENTAL_CHANGE_RATIO = 0.35`（`.../application/indexing/IndexJobProcessor.java:30-32`）；
  单文件符号上限 `MAX_SYMBOLS_PER_FILE = 500`（`.../application/code/CodeSymbolExtractor.java:18`）。
- 远端分支探测上限 `MAX_DISCOVERED_BRANCHES = 2000`；分支名长度上限 200
  （`.../application/repository/GitCredentialExecutor.java:18`；
  `.../infrastructure/repository/GitBranchContentVersionFactory.java:41`）。

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
  （deploy/components/nginx/nginx.conf）。
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

唯一支持的发布形态为 PG/pgvector、Nginx 容器加宿主机 JAR。前端为静态 dist，只读挂载到 Nginx。构建机生成完整目录和压缩包，服务器无需源码、Node.js 或 Maven。详细步骤以 [部署手册](15-deployment-runbook.md) 为准。

- 模板：deploy/backend、deploy/components。
- 打包入口：scripts/build-release.sh / .ps1，共用 build-release.mjs。
- 镜像：PG/pgvector 与 Nginx 使用版本标签导出到 components/images/components.tar，MANIFEST 记录实际镜像 ID、摘要和架构。无镜像升级包省略 TAR。
- PostgreSQL 固定使用 bind；`POSTGRES_DATA_SOURCE` 是宿主机绝对目录，容器 target 固定为 `/var/lib/postgresql/data`。更换 source 不会自动迁移数据。
- Nginx 将发布目录的 `frontend/dist` 只读挂载到 `/usr/share/nginx/html`；配置将 `/index.html` 精确匹配后再提供 SPA 回退，避免入口缺失或不可读时产生内部重定向循环。`/assets/` 缺失时固定返回 404，不回退 HTML，防止 JS/CSS MIME 类型错误。Compose 配置兼容 V1.29.2 与 V2。

### 5.4 健康检查

| 检查 | 作用 |
| --- | --- |
| PostgreSQL pg_isready | Compose 组件就绪检查 |
| /component-health | Nginx 自身健康，不验证静态文件或后端 |
| /index.html 与 / | 验证前端 dist 挂载、权限及 SPA 回退 |
| 宿主机 /actuator/health | 后端脚本验证 UP，包含数据库健康 |
| Nginx /api/health | 验证代理到宿主机后端的链路 |

## 6. 启动与诊断脚本

发布包 backend/backend.sh 和 backend/backend.ps1 提供 start、stop、restart、status。启动检查配置占位符、端口与进程身份，并等待健康；固定 backend 工作目录，不手工注入业务配置。Linux 停机发送 SIGTERM，Windows 使用 Stop-Process；详细限制见部署手册。

源码的 scripts/check-runtime.mjs 继续作为开发环境诊断入口，依赖 Node/Maven 和终端环境变量；它不读取发布包 YAML，也不作为部署机必需工具。scripts/verify-core.mjs 与评测脚本保留用于开发验证。

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
- 日志：发布包 backend/logs/backend.log 使用 Spring Boot 滚动策略；console.log（Windows 另有 stderr.log）记录启动输出，见 deploy/backend/config/application.yml 和两平台启停脚本。
- 审计日志的范围与事件类型见账号与权限文档；唯一读取端点为 `GET /api/accounts/audit`，
  要求超级管理员（`.../AccountController.java:110-117`）。

## 8. 数据库初始化与重建

- Flyway 默认启用，当前只保留 `backend/src/main/resources/db/migration/V1__init_schema.sql`。该基线一次创建当前业务结构和必要的初始配置；`baseline-on-migrate=false`，只接受空库初始化。
- V1 执行 `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public`，数据库角色须有权创建扩展；部署镜像 `pgvector/pgvector:pg17` 已包含扩展文件。
- 从旧版 V1–V9 切换时，先停止后端，并按需要备份旧数据；删除并重建应用数据库后再启动后端。旧 Flyway 校验和与合并后的 V1 不兼容，不能直接在原库上启动。
- 新建库后由 Flyway 自动执行 V1。确认 `flyway_schema_history` 仅有 V1，且内置向量模型和 `externalModelEnabled` 初始设置已写入。
