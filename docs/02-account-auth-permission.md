# 账号、认证、权限与审计需求

> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

本领域覆盖四件事：**账号生命周期**、**认证与会话**、**项目权限与仓库治理**、**系统审计日志**。术语统一如下：

- **项目（project）**：即代码中的仓库（repository），持久化标识为 `repositories.id`，对外字段名沿用 `repositoryId`。
- **权限级别**：`READ < MAINTAIN < MANAGE`，共三级，用枚举 `ordinal()` 比较，不存在第四级（`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4-11`）。
- **所有者（owner）关系**：由 `repositories.owner_account_id` 表达，不是权限级别；所有者一律按 `MANAGE` 处理，并可执行仅所有者动作（`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:30-32`、`:46-54`）。`repository_permissions` 表注释明确「不包含由 owner_account_id 表达的 OWNER」（`backend/src/main/resources/db/migration/V1__init_schema.sql`）。
- **账号角色**：`SUPER_ADMIN`（超级管理员）与 `NORMAL`（普通用户），仅此两种（`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4-7`）。
- **会话（session）**：`login_sessions` 表中的浏览器登录态；**账户访问令牌（access token）**：`acp_` 前缀的长期凭据，仅代表账号身份；**仓库治理（governance）**：成员授权、撤销、所有权转移与删除申请。

普通用户能否访问某项目，取决于可见性（所有者或已被授权）与请求所需权限级别；超级管理员在权限判定中直接放行，且不受可见性过滤限制。

## 2 需求条目

### ACC-001 账号查询（分页与全量）
- 需求：管理员查询账号摘要列表，分页端点支持按用户名或显示名称模糊检索。
- 规则：
  - 两个端点都仅超级管理员可调用，非管理员返回 403 `FORBIDDEN`。
  - 分页参数 `pageNum` 默认 1、`pageSize` 默认 20，要求 `pageNum >= 1`、`1 <= pageSize <= 100`，否则 400；`query` 为空或空白视为不筛选，匹配 `LOWER(username)` 或 `LOWER(display_name)` 的包含关系。
  - 分页排序 `created_at DESC, id DESC`，全量排序 `created_at ASC, id ASC`（两者相反，全量端点前端未接入）；摘要包含状态、被授权仓库数、最近登录时间与来源 IP、创建/更新时间、`accountVersion`，其中 `repositoryPermissionCount` 只统计 `repository_permissions` 行数，不含作为所有者拥有的项目。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:33-47`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:285-295`、`backend/src/main/resources/mappers/AuthMapper.xml:70-84`、`backend/src/main/java/com/analyzercoder/application/common/PageResult.java:24-31`

### ACC-002 账号创建
- 需求：管理员创建账号，并一次性获得初始密码。
- 规则：
  - 仅超级管理员可调用；用户名需匹配 `[A-Za-z0-9._-]{3,32}` 且保存前去空白，显示名称去空白后 1–50 字符；未带角色时默认 `NORMAL`。
  - 未带 `temporaryPassword` 或为空白时服务端生成一次性临时密码，带值时同样通过密码策略校验；新账号固定 `enabled = TRUE`、`must_change_password = TRUE`、`failed_attempts = 0`，临时密码 24 小时后过期。
  - 明文密码只在创建响应中返回一次，持久化仅保存摘要；用户名重复由数据库唯一索引 `uq_accounts_username_normalized`（`LOWER(BTRIM(username))`）拦截，映射为 409 `CONFLICT`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:49-60`、`:119-129`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:128-142`、`:297-323`、`backend/src/main/resources/mappers/AuthMapper.xml:92-95`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### ACC-003 账号编辑、停用与启用
- 需求：管理员修改显示名称、角色与启用状态，并通过账号版本号防止并发覆盖。
- 规则：
  - 仅超级管理员可调用；用户名与密码不可通过该端点修改，未传字段保持原值。
  - 请求携带的 `version` 与当前 `account_version` 不一致时返回 409 `ACCOUNT_VERSION_CONFLICT`，未携带时以当前版本更新（不做冲突校验）；成功时 `account_version` 自增 1。
  - 角色变更或停用会立即删除该账号全部会话并撤销其全部账户访问令牌；审计事件按变更内容择一记录：`ACCOUNT_DISABLED` / `ACCOUNT_ENABLED` / `ACCOUNT_ROLE_CHANGED` / `ACCOUNT_UPDATED`；界面禁止停用当前登录账号。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:62-76`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:325-373`、`backend/src/main/resources/mappers/AuthMapper.xml:107-110`、`frontend/src/features/accounts/AccountTable.vue:70-78`、`frontend/src/views/AccountsView.vue:50`

### ACC-004 停用与降权的前置约束
- 需求：停用账号、撤销管理员或角色变更前必须满足一致性约束。
- 规则：
  - 不能停用当前登录账号；目标账号不存在时返回 400。
  - 目标账号若仍是任一未删除项目的所有者（`countOwnedRepositories > 0`），不允许停用，要求先完成所有权转移。
  - 若目标账号是当前唯一处于启用状态的超级管理员，不允许降级为普通用户，也不允许停用。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:334-352`、`backend/src/main/resources/mappers/RepositoryAccessMapper.xml:61-63`、`backend/src/main/resources/mappers/AuthMapper.xml:89-91`

### ACC-005 重置密码与账号解锁
- 需求：管理员把账号密码重置为一次性临时密码，并可解除登录锁定。
- 规则：
  - 两个端点都仅超级管理员可调用。
  - 重置：新密码由服务端随机生成（不接收调用方指定值），重置后 `must_change_password = TRUE`、临时密码 24 小时过期、`failed_attempts` 清零、`locked_until` 置空，同时删除全部会话并撤销全部访问令牌；明文只在响应中返回一次，审计事件 `PASSWORD_RESET`。
  - 解锁：清零 `failed_attempts` 并置空 `locked_until`；不校验目标账号是否存在、也不校验是否真的处于锁定状态，重复解锁无副作用；审计事件 `ACCOUNT_UNLOCKED`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:78-90`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:375-397`、`backend/src/main/resources/mappers/AuthMapper.xml:111-116`、`backend/src/test/java/com/analyzercoder/security/PasswordResetSecurityTest.java:26-66`

### ACC-006 首个超级管理员引导创建
- 需求：空库首次启动时按环境变量创建唯一的初始超级管理员。
- 规则：
  - 触发器为应用就绪事件，仅当 `accounts` 表记录数为 0 时执行，否则直接返回。
  - 用户名与密码取自 `APP_INITIAL_ADMIN_USERNAME` 与 `APP_INITIAL_ADMIN_PASSWORD`；任一为空时只记录警告日志并跳过，不输出密码内容；取值需通过 ACC-002、ACC-017 的校验规则。
  - 初始账号显示名称固定为「系统管理员」，角色 `SUPER_ADMIN`，`must_change_password = TRUE`，临时密码 24 小时过期；审计事件 `INITIAL_ADMIN_CREATED`，操作者为空（系统）。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:150-177`、`backend/src/main/resources/application.yml:55-57`

### ACC-007 登录校验
- 需求：账号密码登录，并在比对密码前校验账号可用性与临时密码时效。
- 规则：
  - 失败对外统一返回 401 `INVALID_CREDENTIALS` 与同一句提示「用户名、密码或账号状态不正确」，不区分用户不存在、密码错误、已停用、已锁定；账号被停用或处于锁定有效期内时不进行密码比对。
  - 当 `must_change_password = TRUE` 且临时密码已过期时返回 401 `TEMPORARY_PASSWORD_EXPIRED`，该情形不落审计事件。
  - 用户名比对使用 `LOWER(BTRIM(username))`，大小写与首尾空白不敏感；成功记录 `last_login_at`、`last_login_ip`，清零 `failed_attempts` 与 `locked_until` 并下发会话；来源 IP 取 `request.getRemoteAddr()`，默认 `forward-headers-strategy: none`，不解析 `X-Forwarded-For`。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:179-217`、`backend/src/main/resources/mappers/AuthMapper.xml:62-65`、`:96-102`、`backend/src/main/resources/application.yml:41`

### ACC-008 登录失败计数与锁定
- 需求：连续登录失败达到阈值后临时锁定账号。
- 规则：
  - 每次密码错误时 `failed_attempts` 加 1；达到 5 次时设置 `locked_until = now + 15 分钟`（`APP_LOGIN_LOCK_MINUTES`，默认 15）。
  - 第 3、4 次失败返回 401 且错误码为 `CAPTCHA_REQUIRED`，第 1、2 次与第 5 次返回 401 `INVALID_CREDENTIALS`；第 5 次失败记录审计事件 `ACCOUNT_LOCKED`，其余失败记录 `LOGIN_FAILED`，结果列均为 `DENIED`。
  - 锁定到期后 `failed_attempts` 不会自动清零：到期后的首次失败会因计数继续递增而立即重新锁定。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:197-212`、`backend/src/main/resources/application.yml:62-63`、`backend/src/main/resources/mappers/AuthMapper.xml:96-98`

### ACC-009 登录验证码
- 需求：高风险登录需回答算术验证码。
- 规则：
  - 触发条件是独立的、按规范化用户名（去空白 + 小写）持久化的失败计数器 `login_failure_counters.failure_count >= 3`；该计数器只在登录返回 `INVALID_CREDENTIALS` 时递增，登录成功时删除整行。
  - 挑战为 `a + b` 形式（a、b 各取 1–9），有效期 300 秒；答案为 `SHA-256(id + ":" + 和)`，按 id 与用户名匹配，要求未消费且未过期，比较使用定长的 `MessageDigest.isEqual`。
  - 校验失败返回 400 `CAPTCHA_INVALID`；需要验证码但未提供 `captchaId`/`captchaAnswer` 时返回 429 `CAPTCHA_REQUIRED`；校验通过后立即标记 `used_at`，同一挑战不可复用；获取挑战的接口在计数器未达阈值时返回 400 `CAPTCHA_NOT_REQUIRED`。
- 证据：`backend/src/main/java/com/analyzercoder/security/CaptchaService.java:25-49`、`:56-85`、`backend/src/main/resources/mappers/CaptchaMapper.xml:4-18`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### ACC-010 会话建立与 Cookie 属性
- 需求：登录、改密成功后下发服务端可读的会话 Cookie。
- 规则：
  - Cookie 名固定为 `AC_SESSION`；属性固定为 `HttpOnly`、`SameSite=Lax`、`Path=/`；`Secure` 由 `app.security.cookie-secure` 控制（默认 `false`）；`Max-Age` 为 `max(1, session-max-hours)` 小时，默认 12 小时。
  - 会话明文令牌为 32 字节随机数的 Base64URL（无填充）编码，数据库只保存其 SHA-256 十六进制摘要并作为 `login_sessions` 主键；CSRF 令牌为 24 字节随机数的 Base64URL 编码，与会话同行存储，并通过登录/`me`/改密响应体返回给前端。
  - 登出时下发同名空值且 `Max-Age=0` 的 Cookie 以清除。
- 证据：`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:15`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:457-463`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:46-58`、`:113-117`、`backend/src/test/java/com/analyzercoder/interfaces/rest/AuthControllerCookieTest.java:12-30`

### ACC-011 会话校验与超时
- 需求：每个受保护请求校验会话有效性，并按空闲与绝对时长失效。
- 规则：
  - 会话令牌缺失或无效（无对应记录）时返回 401 `SESSION_EXPIRED`。
  - 失效条件：账号 `enabled = FALSE`、`expires_at` 已过、`last_seen_at + session-idle-minutes` 已过（默认 30 分钟）；命中任一条件即删除该会话记录并返回 401。
  - 每次校验通过都会刷新 `last_seen_at`；绝对时长 `session-max-hours`（默认 12 小时）在会话创建时一次性写入 `expires_at`，不随活动延长；会话 Cookie 的 `Max-Age` 等于绝对时长，因此空闲超时后浏览器仍会携带 Cookie，由服务端返回 401。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:219-246`、`:48-57`、`backend/src/main/resources/application.yml:58-61`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### ACC-012 CSRF 校验
- 需求：非安全方法必须携带与会话绑定的 CSRF 令牌。
- 规则：
  - 需要校验的方法为除 `GET`、`HEAD`、`OPTIONS` 之外的全部方法；令牌来源为请求头 `X-CSRF-Token`；缺失或不匹配返回 403 `CSRF_INVALID`，比较方式为 `MessageDigest.isEqual`。
  - 公开路径（见 ACC-015）不校验，因此登录请求本身不做 CSRF 校验；以账户访问令牌认证的请求不经过该会话校验分支。
  - 前端仅对非 `GET/HEAD/OPTIONS` 且已持有 CSRF 令牌的请求设置该头，令牌来自 `authStore` 保存的会话响应。
- 证据：`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:16`、`:42-66`、`frontend/src/api/http.ts:2`、`frontend/src/stores/authStore.ts:1`

### ACC-013 首次登录强制改密
- 需求：`must_change_password` 为真的账号在改密前不得使用业务接口。
- 规则：
  - 服务端在会话校验后拦截，仅放行 `/api/auth/change-password`、`/api/auth/logout`、`/api/auth/me`，其余路径返回 403 `PASSWORD_CHANGE_REQUIRED`。
  - 前端路由守卫把此类会话强制留在 `/login`，登录页切换到「首次登录，请修改密码」表单。
  - 以账户访问令牌认证的请求不受该拦截影响，而是在令牌校验阶段以 403 `PASSWORD_CHANGE_REQUIRED` 拒绝（见 ACC-030）。
- 证据：`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:67-71`、`backend/src/test/java/com/analyzercoder/security/PasswordResetSecurityTest.java:68-93`、`frontend/src/router/index.ts:49-55`、`frontend/src/views/LoginView.vue:1`

### ACC-014 修改密码
- 需求：已登录账号凭当前密码设置新密码。
- 规则：
  - 必须提供非空 `currentPassword` 与 `newPassword`；当前密码错误返回 400 `CURRENT_PASSWORD_INVALID`，新密码需通过 ACC-017 的完整策略校验。
  - 成功后写入新摘要、`must_change_password = FALSE`、清空 `temporary_password_expires_at`，并删除该账号全部会话、撤销其全部访问令牌。
  - 随后签发新会话并重新下发 Cookie，响应体返回新的 CSRF 令牌；审计事件 `PASSWORD_CHANGED`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:98-111`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:248-264`、`backend/src/main/resources/mappers/AuthMapper.xml:103-106`

### ACC-015 登出、当前登录信息与认证边界
- 需求：注销当前会话、恢复登录态，并明确无需认证即可访问的路径集合。
- 规则：
  - 登出删除当前会话记录，审计事件 `LOGOUT`（结果 `SUCCESS`），并清除 `AC_SESSION` Cookie；前端无论请求成败都在 `finally` 中清空本地账号状态。
  - `GET /api/auth/me` 返回账号 id、用户名、显示名称、角色、`mustChangePassword`、`lastLoginAt` 与当前会话的 `csrfToken`；无会话时 401 `SESSION_EXPIRED`；前端启动时调用一次并缓存，401 视为未登录而非抛错。
  - 不校验会话的路径：`/api/health`、`/api/auth/login`、`/api/auth/captcha`、以 `/actuator/health` 开头、以 `/error` 开头；其余路径经过拦截器链（先账户访问令牌拦截器，再会话拦截器，最后分支上下文拦截器，仅 `/api/**`），按注册顺序对 `/**` 全部路径生效；前端 `http` 封装在非登录请求返回 401 时派发 `auth-expired` 事件，`authStore` 监听后清空账号。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:92-96`、`:113-117`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:266-269`、`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:23-29`、`backend/src/main/java/com/analyzercoder/security/WebSecurityConfig.java:19-24`、`backend/src/main/java/com/analyzercoder/security/SecurityContext.java:13-19`、`frontend/src/api/http.ts:2`、`frontend/src/stores/authStore.ts:1`

### ACC-016 认证失败响应与错误码映射
- 需求：安全异常以稳定的 HTTP 状态与错误码返回，且不泄漏内部细节。
- 规则：
  - `ApiSecurityException` 按其自带状态码原样返回，响应体为 `{code, message, timestamp}`。
  - `IllegalArgumentException` 返回 400 `BAD_REQUEST`；Bean 校验失败返回 400 `VALIDATION_FAILED`；`IllegalStateException` 与数据库约束冲突返回 409 `CONFLICT`（数据库冲突提示统一为「数据约束校验失败」）；其余异常返回 500 `INTERNAL_ERROR` 且提示「服务器内部错误」。
  - 因此治理类的并发与前置约束失败（所有权版本变化、OWNER 约束、名称冲突、唯一管理员约束、仍持有仓库等）统一表现为 409 `CONFLICT`，只有自带错误码的校验使用 403/409 专用码。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:21-40`、`:70-81`

### ACC-017 密码复杂度与哈希
- 需求：所有密码入口（创建、重置、改密、初始管理员）使用同一套策略与哈希算法。
- 规则：
  - 长度 8–64 个字符；必须至少包含大写字母、小写字母、数字、特殊字符中的三类（`Character.isLetterOrDigit` 之外视为特殊字符）；不允许与用户名相等（忽略大小写），并拒绝弱密码列表 `password`、`password123`、`admin123`、`12345678`、`qwerty123`、`admin@123`。
  - 存储格式 `pbkdf2-sha256$<迭代次数>$<Base64盐>$<Base64派生密钥>`，算法 `PBKDF2WithHmacSHA256`，迭代 210000 次，密钥 256 位，盐为 16 字节随机数；校验时按存储中的迭代次数重新派生并用 `MessageDigest.isEqual` 比较，格式不合法直接返回不匹配。
  - 自动生成的临时密码形如 `A!` + 10 位随机串 + `9a`，长度 13，满足三类字符要求。
- 证据：`backend/src/main/java/com/analyzercoder/security/PasswordHasher.java:16-33`、`:46-71`、`:73-96`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:140-142`

### ACC-018 权限判定与超级管理员范围扩张
- 需求：集中判定某账号对某项目是否达到所需权限级别。
- 规则：
  - 判定顺序：超级管理员直接通过；否则读取该账号在此项目上的访问记录，无记录即拒绝；账号等于 `owner_account_id` 即通过；否则要求 `permission_level` 非空且其 `ordinal()` 不小于所需级别。
  - 判定失败返回 403 `FORBIDDEN`（提示「无权限访问该仓库」）；访问记录查询限定 `deleted_at IS NULL`，且只在该账号是所有者或存在授权行时返回；权限级别由数据库约束为 `READ`、`MAINTAIN`、`MANAGE` 三者之一。
  - `canAccess` 对超级管理员无条件返回真，不查询成员表；`requireOwner` 对超级管理员直接放行；`visibleRepositoryIds` 对超级管理员返回全部未删除项目；管理类端点统一要求超级管理员角色，超级管理员在某项目上不是所有者时关系标签为 `SUPER_ADMIN` 而非 `OWNER`，能力位仍按 `MANAGE` 计算。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:19-25`、`:46-60`、`:62-95`、`backend/src/main/resources/mappers/RepositoryAccessMapper.xml:15-22`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/java/com/analyzercoder/security/SecurityContext.java:27-33`

### ACC-019 仓库可见性
- 需求：项目列表只展示账号可见的项目。
- 规则：
  - 普通用户可见集合 = 未删除且（本人为所有者 或 存在本人授权行）的项目 id；超级管理员可见集合 = 全部未删除项目 id。
  - 可见集合用于 `GET /api/repositories` 的结果过滤、`GET /api/repositories/page` 的 SQL 条件、`GET /api/index-jobs` 的结果过滤，以及 MCP `list_codegraph_scopes` 的项目范围。
  - MCP 工具在该集合基础上额外排除来源类型为 `ZIP` 的项目；分页查询的搜索字段包含项目名称、描述、默认分支与所有者用户名/显示名称。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:56-60`、`backend/src/main/resources/mappers/RepositoryAccessMapper.xml:29-37`、`backend/src/main/resources/mappers/RepositoryMapper.xml:60-78`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:83-112`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:149-160`

### ACC-020 仓库关系与能力描述
- 需求：项目详情向调用方描述当前账号与该项目的关系统标签与能力位。
- 规则：
  - 关系标签取值 `SUPER_ADMIN`、`OWNER`、`READ`、`MAINTAIN`、`MANAGE`；非所有者、非管理员的成员按其权限级别命名关系。
  - 能力位共 10 个：`canRead` 恒为真；`canEditRepository`、`canConfigure` 需要 `MANAGE`；`canUpdate`、`canIndex`、`canBuildCodeGraph` 需要 `MAINTAIN`；`canGrant`、`canManageCredential`、`canTransferOwnership`、`canDelete` 仅所有者或超级管理员为真。
  - 响应同时给出 `ownerAccountId`、`ownerDisplayName`、`ownershipVersion`、`repositoryStatus`，无访问记录时返回 403 `FORBIDDEN`；前端把关系标签本地化为「所有者 / 只读成员 / 维护成员 / 管理成员 / 平台管理员」。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:62-95`、`backend/src/main/java/com/analyzercoder/security/RepositoryAccess.java:6-23`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:210-267`、`frontend/src/utils/displayLabels.ts:27-33`

### ACC-021 仓库所有者专属动作
- 需求：治理性动作仅项目所有者或超级管理员可执行。
- 规则：
  - 校验在账号为超级管理员时直接通过；否则要求存在访问记录且账号等于 `owner_account_id`；失败返回 403 `OWNER_REQUIRED`，提示「只有仓库所有者或超级管理员可执行此操作」。
  - 适用动作：候选账号查询、授予/调整/撤销成员权限、所有权转移、仓库删除申请、凭据绑定。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:46-54`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:45`、`:57`、`:83`、`:104`、`:140`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialBindingService.java:81`

### ACC-022 所有权版本乐观锁
- 需求：所有治理写操作通过所有权版本号串行化，避免并发覆盖。
- 规则：
  - `repositories.ownership_version` 初始为 0，每次成功的治理写操作自增 1。
  - 写入前以 `SELECT ... FOR UPDATE` 锁定项目行并校验行内版本与请求期望值一致，不一致返回 409；授予与撤销通过 `incrementVersion ... WHERE ownership_version = expected` 自增，受影响行数不为 1 时返回 409。
  - 所有权转移与删除申请的自增语句额外要求 `repository_status IN ('READY','AUTH_ERROR')` 且 `deleted_at IS NULL`；三个治理写接口的成功响应都返回新的 `ownershipVersion`，前端以此为下一次操作的期望值，并在打开治理弹窗时用项目详情的 `ownershipVersion` 初始化本地版本号。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:157-172`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:74-83`、`backend/src/main/resources/mappers/RepositoryAccessMapper.xml:55-60`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryGovernanceController.java:101`、`frontend/src/features/repositories/RepositoryGovernanceDialog.vue:69`

### ACC-023 成员与候选账号查询
- 需求：治理界面展示现有成员关系，以及可被授权的账号。
- 规则：
  - 成员列表需要 `MAINTAIN` 权限（因此维护成员也能查看成员，用于知识责任人选择），包含所有者行（关系 `OWNER`）与全部授权行（关系等于权限级别），按关系与用户名排序。
  - 候选账号查询需要所有者权限，返回全部 `enabled = TRUE` 的账号，按显示名称、用户名、id 排序。
  - 候选结果不排除已是成员或所有者的账号，由前端自行过滤已存在成员。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:39-47`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:39-56`、`backend/src/test/java/com/analyzercoder/application/repository/RepositoryGovernanceServiceTest.java:29-43`、`frontend/src/features/repositories/RepositoryGovernanceDialog.vue:16`

### ACC-024 成员权限的授予、调整与撤销
- 需求：所有者或管理员为账号授予仓库权限，并可调整或撤销。
- 规则：
  - 两个写操作都需要所有者权限并校验 `expectedOwnershipVersion`。
  - 授予：目标账号必须存在且启用，否则 400；目标账号不能是当前所有者，否则 400（提示「OWNER 不能写入普通授权」）；`permission` 不能为空且取值为 `READ`/`MAINTAIN`/`MANAGE`；写入为 upsert（已有授权行时更新级别与时间）；审计事件 `REPOSITORY_PERMISSION_CHANGED`。
  - 撤销：不能撤销当前所有者，否则 400（提示「OWNER 不能通过授权接口移除」）；删除授权行不校验其是否存在，审计事件 `REPOSITORY_PERMISSION_REVOKED`；两者成功后都自增所有权版本并在响应中返回新版本。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:49-93`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:65-73`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryGovernanceController.java:45-74`

### ACC-025 所有权转移
- 需求：把项目所有权转移给另一启用账号，并可选保留原所有者权限。
- 规则：
  - 需要当前所有者的所有权权限并校验期望版本；项目必须处于 `READY` 或 `AUTH_ERROR` 状态；新所有者必须存在且启用，且不能与当前所有者相同。
  - 转移时可提交新项目名，为空或空白时沿用原名；名称按小写规范化后校验目标所有者下不与其它未删除项目重名，冲突时 409。
  - 目标账号已有的授权行会被删除（避免所有者同时存在普通授权）；原所有者的授权行一并删除，若提交了 `previousOwnerPermission` 则为原所有者写入该级别的授权；成功后更新 `owner_account_id` 与名称、`ownership_version` 自增 1，审计事件 `REPOSITORY_OWNERSHIP_TRANSFERRED`（目标账号列为新所有者）。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:95-136`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:60-64`、`:78-83`、`backend/src/main/resources/mappers/RepositoryAccessMapper.xml:55-60`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryGovernanceController.java:76-90`

### ACC-026 仓库删除申请
- 需求：所有者申请删除项目，进入异步清理流程。
- 规则：
  - 需要所有者权限；项目不存在时 400；存在运行中的写任务时返回 409。
  - 标记 `repository_status = 'DELETING'`、写入 `deleted_at` 并自增 `ownership_version`，受影响行数不为 1 时返回 409；该端点不接收客户端版本号，内部使用锁定行的当前版本。
  - 同时写入删除墓碑 `repository_deletion_tombstones`（`cleanup_status` 默认 `PENDING`），由后台清理任务领取（`PENDING`/`FAILED`，或 `RUNNING` 超过 10 分钟）并最终把项目状态置为 `DELETED`；审计事件 `REPOSITORY_DELETION_REQUESTED`，目标账号列留空。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:138-155`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:84-93`、`:99-107`、`:118-131`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:179-183`

### ACC-027 旧授权接口停用与账号权限清单
- 需求：账号维度下的权限写入入口不再生效，统一改由仓库治理完成；管理员仍可只读查询账号授权清单。
- 规则：
  - `PUT /api/accounts/{accountId}/permissions/{repositoryId}` 无论参数如何都返回 409 `USE_REPOSITORY_GOVERNANCE`，提示「请在仓库治理页面分配权限」，且仍要求超级管理员身份；前端 `api/accounts.ts` 未封装该路径。
  - `GET /api/accounts/{accountId}/permissions` 仅超级管理员可调用，返回该账号在 `repository_permissions` 中的全部授权行，按项目名与 id 排序，排除已逻辑删除的项目，不包含该账号作为所有者拥有的项目；前端无调用方。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:399-419`、`backend/src/main/resources/mappers/AuthMapper.xml:131-134`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:92-108`、`frontend/src/api/accounts.ts:7-19`

### ACC-028 账户访问令牌创建
- 需求：账号为 MCP 客户端等外部调用方签发长期凭据。
- 规则：
  - 令牌针对 `{accountId}` 资源，非超级管理员只能操作自己的账号，否则 403 `FORBIDDEN`；名称去空白后长度 1–80，有效期天数 1–365，越界返回 400 `TOKEN_INPUT_INVALID`。
  - 目标账号必须启用（否则 401 `ACCESS_TOKEN_INVALID`）、不得处于待改密状态（403 `PASSWORD_CHANGE_REQUIRED`）、不得处于锁定期（403 `ACCOUNT_LOCKED`）。
  - 明文格式为 `acp_` + 32 字节随机数的 Base64URL（无填充）编码，共 47 字符；数据库只保存明文的 SHA-256 摘要（唯一）与明文前 12 字符作为展示前缀；明文仅在创建响应中返回一次，数据库约束要求 `expires_at > created_at`；审计事件 `ACCESS_TOKEN_CREATED`，前端默认令牌名「MCP 客户端」、默认有效期 90 天并提示「完整令牌仅本次显示」。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:35-61`、`backend/src/main/resources/mappers/AccessTokenMapper.xml:17-20`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/test/java/com/analyzercoder/security/AccessTokenServiceTest.java:50-74`、`frontend/src/features/accounts/AccountAccessTokens.vue:6-25`

### ACC-029 账户访问令牌列表与撤销
- 需求：账号查看并撤销自己的令牌。
- 规则：
  - 列表与撤销同样受「本人或超级管理员」限制，否则 403 `FORBIDDEN`。
  - 列表返回 id、名称、前缀、创建时间、到期时间、最近使用时间、撤销时间；不包含明文令牌，也不包含令牌摘要（测试显式断言序列化结果不含这些字段）；按 `created_at DESC, id` 排序。
  - 撤销为幂等写法 `revoked_at = COALESCE(revoked_at, now)`，按 `id` 与 `account_id` 定位，受影响行数为 0 时返回 404 `TOKEN_NOT_FOUND`；两个端点都返回 `Cache-Control: no-store`，审计事件 `ACCESS_TOKEN_REVOKED`；前端把「已停用账号、修改或重置密码、变更角色」标注为会撤销旧令牌的触发条件，并禁止为非启用账号创建令牌。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:30-33`、`:63-69`、`:99-102`、`:108-128`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountAccessTokenController.java:28-58`、`backend/src/main/resources/mappers/AccessTokenMapper.xml:24-34`、`backend/src/test/java/com/analyzercoder/security/AccessTokenServiceTest.java:66-73`、`frontend/src/features/accounts/AccountAccessTokens.vue:12-13`、`frontend/src/views/AccountsView.vue:73`

### ACC-030 访问令牌认证与管理范围
- 需求：每次以令牌发起的调用都重新校验令牌与账号的实时状态，且令牌只能由本人或超级管理员管理。
- 规则：
  - 明文必须匹配 `acp_[A-Za-z0-9_-]{43}`，否则 401 `ACCESS_TOKEN_INVALID`；按摘要查找记录，记录不存在、已撤销、已过期（`expires_at` 不晚于当前时间）任一成立即 401。
  - 账号必须启用（否则 401）、不得待改密（403 `PASSWORD_CHANGE_REQUIRED`）、不得锁定（403 `ACCOUNT_LOCKED`）；通过后刷新 `last_used_at`（条件为未撤销且未过期），刷新失败即 401。
  - 令牌只解析出账号身份、不携带仓库范围，仓库权限在每个业务请求中由 `AccessControlService` 实时判定；认证失败响应带 `WWW-Authenticate: Bearer realm="analyzer-mcp"`（令牌无效时附带 `error="invalid_token"`）；管理范围判定为「调用方是超级管理员，或调用方 id 等于路径中的 `accountId`」，否则 403 `FORBIDDEN`（提示「只能管理自己的访问令牌」），该规则对列表、创建、撤销一致生效。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:16`、`:71-102`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:32-46`、`backend/src/test/java/com/analyzercoder/security/AccessTokenServiceTest.java:76-153`

### ACC-031 访问令牌端点范围与 Origin 校验
- 需求：账户访问令牌只能调用受限的接口集合，且 MCP 入口校验浏览器来源。
- 规则：
  - 允许的端点只有 `POST /api/mcp`、`GET /api/repositories/{uuid}/evidence-search`、`POST /api/repositories/{uuid}/contexts`；携带 `Authorization` 头但请求其它路径时返回 403 `TOKEN_ENDPOINT_FORBIDDEN`（提示「访问令牌仅可调用 MCP 工具接口」），未携带 `Authorization` 头且目标不是 `/api/mcp` 时按会话流程继续处理。
  - `/api/mcp` 必须携带 `Authorization: Bearer <token>`，缺失时返回 401 `ACCESS_TOKEN_REQUIRED`，不接受会话 Cookie 替代；Origin 头存在时校验来源：要求 host 等于请求 `serverName`（忽略大小写）、scheme 等于请求 scheme（忽略大小写）、端口等于请求 `serverPort`（`Origin` 未写端口时按 443/80 推断）、无用户信息、无 path、无 query、无 fragment，任一不满足或无法解析为 URI 时返回 403 `MCP_ORIGIN_FORBIDDEN`。
  - 令牌请求跳过会话与 CSRF 校验，但仍受业务层仓库权限约束：`evidence-search` 与 `/contexts` 需要 `READ`，MCP 工具内部同样按 `READ` 等权限校验并受可见性过滤；因此令牌不能调用账号管理、令牌管理、账户偏好、`/api/auth/me` 等会话类端点；外部 MCP 适配器在配置了 `ANALYZER_ACCESS_TOKEN` 时发送该头，否则回退到会话 Cookie + CSRF 头。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:11-53`、`:55-75`、`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:45-46`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:59-69`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:108-115`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347-349`、`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:39-45`、`mcp-server/src/api-client.mjs:22-31`

### ACC-032 当前仓库偏好
- 需求：账号记录并读回当前选中的项目，用于界面上下文恢复。
- 规则：
  - 存储在 `accounts.last_repository_id`，外键指向 `repositories`，项目删除时置空；读取返回当前登录账号的该字段，未设置时为 `null`。
  - 写入时若 `repositoryId` 非空，必须存在该账号的访问记录（超级管理员改为校验项目存在），否则 403 `FORBIDDEN`；写入 `null` 表示清空偏好，直接允许；写入同时刷新 `updated_at`。
  - 该端点要求有效会话，不接受账户访问令牌（见 ACC-031）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountPreferenceController.java:23-38`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:271-283`、`backend/src/main/resources/mappers/AuthMapper.xml:117-119`、`backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/test/java/com/analyzercoder/security/AuthServiceRepositoryPreferenceTest.java:38-74`、`frontend/src/api/accountPreferences.ts:7-16`

### ACC-033 审计事件记录
- 需求：安全、账号与仓库治理事件写入统一审计表。
- 规则：
  - 已实现的写入事件类型：`INITIAL_ADMIN_CREATED`、`LOGIN_SUCCEEDED`、`LOGIN_FAILED`、`ACCOUNT_LOCKED`、`LOGOUT`、`PASSWORD_CHANGED`、`PASSWORD_RESET`、`ACCOUNT_CREATED`、`ACCOUNT_UPDATED`、`ACCOUNT_ENABLED`、`ACCOUNT_DISABLED`、`ACCOUNT_ROLE_CHANGED`、`ACCOUNT_UNLOCKED`、`ACCESS_TOKEN_CREATED`、`ACCESS_TOKEN_REVOKED`、`REPOSITORY_PERMISSION_CHANGED`、`REPOSITORY_PERMISSION_REVOKED`、`REPOSITORY_OWNERSHIP_TRANSFERRED`、`REPOSITORY_DELETION_REQUESTED`、`REPOSITORY_UPDATED`。
  - 登录成功、改密、重置、建号与治理类事件的结果为 `SUCCESS`，登录失败与账号锁定为 `DENIED`。
  - 记录字段：操作账号、目标账号、目标项目、事件类型、结果、请求追踪 id、来源 IP、`details`、发生时间；`details` 在两条写入路径下都固定写入空 JSON 对象，不承载扩展信息。
  - `request_id` 在每次写入时新生成随机 UUID，与入口 HTTP 请求没有关联；来源 IP 取 `request.getRemoteAddr()`，与登录路径一致。
- 证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:438-455`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:174-189`、`backend/src/main/resources/mappers/AuthMapper.xml:141-144`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:94-98`、`backend/src/main/resources/db/migration/V1__init_schema.sql`

### ACC-034 审计日志查询
- 需求：管理员按时间倒序查看审计事件。
- 规则：
  - 仅超级管理员可调用；参数为 `limit`（默认 100，服务端夹取到 1–200）与 `offset`（默认 0，负值归零）；排序 `created_at DESC, id DESC`。
  - 每条返回 id、事件类型、结果、请求 id、来源 IP、操作者用户名、目标账号用户名、目标项目名、发生时间；账号或项目已被删除时对应名称列为空。
  - 服务端不提供筛选条件，筛选与分页在浏览器中完成；前端默认一次取 200 条，界面默认每页 15 条，提供操作者、目标账号、事件类型、结果、时间范围五个本地筛选器，并支持从账号列表携带 `username` 查询参数定位目标账号。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:110-117`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:421-436`、`backend/src/main/resources/mappers/AuthMapper.xml:135-140`、`frontend/src/api/accounts.ts:18`、`frontend/src/features/accounts/AuditLogPanel.vue:8-23`、`frontend/src/views/AuditLogsView.vue:18-29`

### ACC-035 前端界面与接口封装
- 需求：账号、登录、审计与治理界面按后端契约组织调用。
- 规则：
  - 账号页 `/accounts` 与审计页 `/audit` 在路由元数据中标记 `admin: true`；未认证跳转登录页，待改密账号被强制留在登录页，非管理员访问管理页被重定向到总览页。
  - `api/accounts.ts` 封装分页、创建、更新、重置密码、解锁、审计查询；`api/accessTokens.ts` 封装令牌列表、创建、撤销；`api/repositoryGovernance.ts` 封装成员、候选、授予、撤销、转移；`api/auth.ts` 封装验证码、登录、`me`、改密、登出；`api/accountPreferences.ts` 封装当前仓库偏好；`authStore` 持有会话账号与 CSRF 令牌并提供 `restore`、`login`、`changePassword`、`logout`。
  - 账号表格按状态提供「编辑 / 访问令牌 / 查看审计 / 解锁账号（仅锁定时）/ 重置密码 / 停用或启用」操作；状态标签为「正常 / 已停用 / 已锁定 / 待修改密码」，角色标签为「管理员 / 普通用户」，并以 `currentAccountId` 禁用当前登录账号的停用按钮；账号创建与重置密码后以弹窗展示一次性临时密码并提示只显示一次；令牌管理弹窗在账号非启用状态时禁用「创建令牌」；令牌 hook 在切换账号或组件销毁时清空已展示的明文令牌，并用请求代次避免过期响应覆盖最新状态。
- 证据：`frontend/src/router/index.ts:38-57`、`frontend/src/api/accounts.ts:7-19`、`frontend/src/api/accessTokens.ts:3-7`、`frontend/src/api/repositoryGovernance.ts:22-41`、`frontend/src/api/auth.ts:1`、`frontend/src/api/accountPreferences.ts:7-16`、`frontend/src/stores/authStore.ts:1`、`frontend/src/features/accounts/AccountTable.vue:18-23`、`:48`、`:54-81`、`frontend/src/views/AccountsView.vue:46-53`、`frontend/src/features/accounts/useAccessTokens.ts:47-53`

## 3 数据与状态

### 3.1 表与关键列

| 表 | 用途 | 关键列与约束 |
| --- | --- | --- |
| `accounts` | 平台账号 | `username`（唯一索引 `LOWER(BTRIM(username))`）、`password_hash`、`account_role`、生成兼容列 `role`、`enabled`、`must_change_password`、`failed_attempts`、`locked_until`、`temporary_password_expires_at`、`last_login_at`、`last_login_ip`、`account_version`（`> 0`，默认 1）、`last_repository_id`（外键，删除时置空） |
| `login_sessions` | 登录会话 | `token_hash` 主键、`account_id`（级联删除）、`csrf_token`、`created_at`、`last_seen_at`、`expires_at` |
| `login_captcha_challenges` / `login_failure_counters` | 登录验证码挑战与验证码失败计数 | 挑战含 `username_normalized`、`answer_hash`、`expires_at`、`used_at`；计数器以 `username_normalized` 为主键，含 `failure_count`、`updated_at` |
| `repository_permissions` | 项目成员授权 | 主键 `(account_id, repo_id)`；`permission_level` 约束为 `READ`/`MAINTAIN`/`MANAGE`；注释声明不含 OWNER |
| `repositories` | 项目 | `owner_account_id`（非空外键）、`ownership_version`（默认 0）、`repository_status`（默认 `READY`）、`deleted_at`；`(owner_account_id, normalized_name)` 在未删除时唯一 |
| `account_access_tokens` | 账户访问令牌 | `token_hash` 唯一、`token_prefix`、`expires_at`（约束 `> created_at`）、`last_used_at`、`revoked_at` |
| `audit_events` | 审计事件 | `actor_account_id`、`target_account_id`、`target_repo_id`（均 `ON DELETE SET NULL`）、`event_type`、`result`、`request_id`（非空）、`source_ip`、`details`（默认 `{}`）、`created_at` |
| `repository_deletion_tombstones` | 删除后清理任务 | `cleanup_status` 默认 `PENDING`、`retry_count`、`last_error_code`、`cleanup_updated_at` |

证据：`backend/src/main/resources/db/migration/V1__init_schema.sql`

### 3.2 状态枚举

- 账号状态（由 `enabled`、`locked_until`、`must_change_password` 按优先级推导）：`DISABLED`（`enabled = FALSE`）> `LOCKED`（`locked_until` 晚于当前时间）> `PASSWORD_CHANGE_REQUIRED`（`must_change_password = TRUE`）> `ENABLED`。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:83-89`
- 账号角色：`SUPER_ADMIN`、`NORMAL`。证据：`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4-7`
- 权限级别：`READ`、`MAINTAIN`、`MANAGE`。证据：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4-7`
- 对外关系标签：`SUPER_ADMIN`、`OWNER`、`READ`、`MAINTAIN`、`MANAGE`。证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:70-75`
- 审计结果：`SUCCESS`、`DENIED`（写入路径只使用这两个值）。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:183`、`:215`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:97`
- 令牌展示状态（前端推导）：`已撤销`（`revokedAt` 非空）、`已过期`（`expiresAt <= now`）、`有效`。证据：`frontend/src/features/accounts/AccessTokenTable.vue:5`
- 与本领域相关的项目状态：所有权转移与删除申请要求 `READY` 或 `AUTH_ERROR`；删除申请后置为 `DELETING`，清理完成置为 `DELETED`。证据：`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:82`、`:85`、`:119`

### 3.3 流转

- 登录：校验账号存在 → 校验启用与未锁定 → 校验临时密码未过期 → 校验密码；失败则累加失败计数并可能锁定，成功则记录成功、清零计数、签发会话。
- 会话失效：登出、改密、重置密码、停用账号、角色变更导致会话被删除；空闲超时、绝对超时、账号停用导致校验失败。
- 令牌失效：改密、重置密码、停用账号、角色变更时全部撤销；也随到期或手动撤销失效。
- 锁定：连续 5 次密码错误进入 15 分钟锁定；成功登录或管理员解锁清除计数与锁定；锁定到期后计数不清零。
- 所有权：初始由创建者持有；通过治理转移给另一启用账号，原所有者可选保留一个权限级别；项目删除申请时所有权版本自增。

### 3.4 时长与次数类参数及默认值

| 参数 | 默认值 | 配置项 | 证据 |
| --- | --- | --- | --- |
| 会话空闲超时 / 绝对时长 / Cookie `Max-Age` | 30 分钟 / 12 小时 / `max(1, session-max-hours)` 小时 | `app.security.session-idle-minutes`、`app.security.session-max-hours` | `application.yml:58-61`、`AuthController.java:43` |
| 登录锁定阈值 / 锁定时长 | 5 次失败 / 15 分钟 | 阈值硬编码；`app.security.lock-minutes` | `AuthService.java:199`、`application.yml:62-63` |
| 验证码触发阈值 / 有效期 | 3 次失败 / 300 秒 | 硬编码 | `CaptchaService.java:41`、`:64` |
| 临时密码有效期 | 24 小时 | 硬编码 | `AuthService.java:173`、`:319`、`:386` |
| 令牌有效期 / 名称长度 | 1–365 天（前端默认 90 天）/ 1–80 字符 | 请求参数 | `AccessTokenService.java:39-41`、`AccountAccessTokens.vue:7` |
| 令牌明文长度 / 展示前缀 | 47 字符（`acp_` + 43）/ 前 12 字符 | 硬编码 | `AccessTokenService.java:43-45`、`:53` |
| 密码长度 / 字符类数 / PBKDF2 迭代 | 8–64 字符 / 至少 3 类 / 210000 | 硬编码 | `PasswordHasher.java:74`、`:77-92`、`:16` |
| 用户名格式 / 显示名称长度 | `[A-Za-z0-9._-]{3,32}` / 1–50 字符 | 硬编码 | `AuthService.java:33`、`:135` |
| 账号分页默认 | `pageNum=1`、`pageSize=20`（上限 100） | 请求参数 | `AccountController.java:41-43`、`PageResult.java:28` |
| 审计查询默认 | `limit=100`（上限 200）、`offset=0` | 请求参数 | `AccountController.java:112-113`、`AuthService.java:422` |
| 所有权版本 / 账号版本初值 | 0（治理写操作 +1）/ 1（资料更新 +1） | 硬编码 | `V1__init_schema.sql`、`AuthMapper.xml:108` |
| 删除清理领取超时 | `RUNNING` 超过 10 分钟可重新领取 | 硬编码 | `RepositoryGovernanceMapper.xml:104` |

## 4 接口清单

认证与会话（会话类端点不接受账户访问令牌）：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/auth/captcha?username=` | 获取登录验证码挑战 | 公开；失败计数 < 3 时 400 `CAPTCHA_NOT_REQUIRED` |
| POST | `/api/auth/login` | 登录并下发 `AC_SESSION` | 公开 |
| GET | `/api/auth/me` | 查询当前会话账号与 CSRF 令牌 | 有效会话 |
| POST | `/api/auth/change-password` | 修改自己的密码 | 有效会话（待改密时仍可调用） |
| POST | `/api/auth/logout` | 登出并清除 Cookie | 有效会话 |
| GET | `/api/auth/preferences/current-repository` | 读取当前仓库偏好 | 有效会话 |
| PUT | `/api/auth/preferences/current-repository` | 更新当前仓库偏好 | 有效会话 + 该项目的 `READ`（超级管理员放行） |

账号管理（全部要求超级管理员）：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/accounts` | 全量账号摘要列表 | `SUPER_ADMIN` |
| GET | `/api/accounts/page` | 分页账号查询 | `SUPER_ADMIN` |
| POST | `/api/accounts` | 创建账号并返回一次性临时密码 | `SUPER_ADMIN` |
| PATCH | `/api/accounts/{accountId}` | 编辑显示名称/角色/启用状态 | `SUPER_ADMIN` |
| POST | `/api/accounts/{accountId}/reset-password` | 重置为一次性临时密码 | `SUPER_ADMIN` |
| POST | `/api/accounts/{accountId}/unlock` | 清除失败计数与锁定 | `SUPER_ADMIN` |
| GET | `/api/accounts/{accountId}/permissions` | 查询该账号的项目授权 | `SUPER_ADMIN` |
| PUT | `/api/accounts/{accountId}/permissions/{repositoryId}` | 恒返回 409 `USE_REPOSITORY_GOVERNANCE` | `SUPER_ADMIN`（功能已停用） |
| GET | `/api/accounts/audit` | 查询审计事件 | `SUPER_ADMIN` |
| GET | `/api/accounts/{accountId}/access-tokens` | 列出令牌元数据（无明文与摘要） | 本人或 `SUPER_ADMIN` |
| POST | `/api/accounts/{accountId}/access-tokens` | 创建令牌，明文仅返回一次 | 本人或 `SUPER_ADMIN` |
| DELETE | `/api/accounts/{accountId}/access-tokens/{tokenId}` | 撤销令牌 | 本人或 `SUPER_ADMIN` |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:64-117`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountPreferenceController.java:23-34`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:33-117`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountAccessTokenController.java:28-58`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:99-102`

仓库治理：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/repositories/{repositoryId}/governance/members` | 成员与关系列表 | `MAINTAIN` |
| GET | `/api/repositories/{repositoryId}/governance/candidates` | 可授权候选账号 | 所有者或 `SUPER_ADMIN` |
| PUT | `/api/repositories/{repositoryId}/governance/members/{accountId}` | 授予或调整成员权限 | 所有者或 `SUPER_ADMIN` + 版本匹配 |
| DELETE | `/api/repositories/{repositoryId}/governance/members/{accountId}?expectedOwnershipVersion=` | 撤销成员权限 | 所有者或 `SUPER_ADMIN` + 版本匹配 |
| POST | `/api/repositories/{repositoryId}/governance/transfer` | 转移所有权 | 所有者或 `SUPER_ADMIN` + 版本匹配 |
| DELETE | `/api/repositories/{repositoryId}` | 申请删除项目（标记 `DELETING`） | 所有者或 `SUPER_ADMIN` |

令牌可调用的业务端点与可见性端点：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| POST | `/api/mcp` | MCP JSON-RPC | 账户访问令牌 + Origin 校验 + 工具自身的项目权限 |
| GET | `/api/repositories/{repositoryId}/evidence-search` | 统一证据检索 | 账户访问令牌 + `READ` |
| POST | `/api/repositories/{repositoryId}/contexts` | 解析分支阅读上下文 | 账户访问令牌 + `READ` |
| GET | `/api/repositories` | 可见项目列表 | 已认证；按 `visibleRepositoryIds` 过滤 |
| GET | `/api/repositories/page` | 可见项目分页 | 已认证；按 `visibleRepositoryIds` 过滤 |
| GET | `/api/index-jobs` | 任务列表 | `SUPER_ADMIN`，并按 `visibleRepositoryIds` 过滤 |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryGovernanceController.java:33-101`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:83-112`、`:179-183`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:11-53`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:59-69`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:108-115`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:81-91`

## 5 边界与非目标

- 不提供账号删除：`AccountController` 无删除端点，只能通过停用改变可用性。
- 不提供自助注册、密码找回、邮箱或短信验证；账号只能由超级管理员创建，密码只能由本人修改或管理员重置。
- 不提供多因素认证；登录的第二步只有算术验证码。
- 不提供会话列表、单会话远程下线或「退出其它设备」；改密、重置、停用、角色变更只能整账号失效。
- 不提供刷新令牌、令牌权限范围（scope）或有效期延长；令牌只能整体创建或撤销。
- 权限模型为「三级权限 + 所有者关系 + 超级管理员角色」，不存在 `OWNER` 权限级别，也不支持自定义角色或按功能点授权；授权粒度为项目，不支持目录、文件或符号级授权。
- 审计事件不承载变更明细（`details` 恒为空 JSON 对象），不提供导出与保留期策略，也不能按请求追踪。
- 分支上下文（`X-Branch-Context`）与账号权限正交：`BranchContextInterceptor` 只校验端点是否支持该头，不支持时返回 409 `BRANCH_CONTEXT_UNSUPPORTED`，不改变权限判定；来源类型为 `ZIP` 的项目不在 MCP 项目列表中返回。
- 前端只在超级管理员路由下渲染账号页与审计页；服务端始终独立校验角色，不依赖前端隐藏。

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:24-137`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:16`、`backend/src/main/resources/mappers/AuthMapper.xml:143`、`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:34-44`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:158`、`frontend/src/router/index.ts:55`

## 6 已知缺口

1. **验证码触发与错误码不一致**：`AuthService.login` 在第 3、4 次失败时返回 `CAPTCHA_REQUIRED`，但 `CaptchaService.required` 依据的是另一个只在错误码为 `INVALID_CREDENTIALS` 时才递增的计数器；因此第 3、4 次失败时验证码实际未被要求，前端因此去请求 `/api/auth/captcha` 会得到 400 `CAPTCHA_NOT_REQUIRED`，界面提示「验证码加载失败」。计数器只有在第 5 次失败（同时触发锁定）时才达到阈值。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:197-212`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:81-89`、`backend/src/main/java/com/analyzercoder/security/CaptchaService.java:40-49`、`frontend/src/views/LoginView.vue:1`
2. **无调用方的端点**：`PUT /api/accounts/{accountId}/permissions/{repositoryId}` 无条件抛出 409，`GET /api/accounts/{accountId}/permissions` 与 `GET /api/accounts` 也没有前端调用方（`frontend/src/api/accounts.ts` 只封装 page/create/update/resetPassword/unlock/audit）。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:411-419`、`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountController.java:33-37`、`:92-108`、`frontend/src/api/accounts.ts:7-19`
3. **审计查询无服务端筛选与分页**：服务端只接受 `limit`/`offset` 且上限 200，前端固定一次取 200 条后在浏览器内筛选与分页，超过 200 条的历史事件无法通过界面访问。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:421-422`、`frontend/src/api/accounts.ts:18`、`frontend/src/features/accounts/AuditLogPanel.vue:8-14`
4. **审计事件类型本地化不完整**：`AuditLogPanel.eventLabels` 缺少 `ACCOUNT_ENABLED`、`ACCOUNT_ROLE_CHANGED`、`ACCESS_TOKEN_CREATED`、`ACCESS_TOKEN_REVOKED`、`REPOSITORY_OWNERSHIP_TRANSFERRED`、`REPOSITORY_DELETION_REQUESTED`、`REPOSITORY_UPDATED`，这些事件在界面上回退显示英文原码。证据：`frontend/src/features/accounts/AuditLogPanel.vue:15`、`:32`、`:42`
5. **会话 Cookie 生命周期与空闲超时不匹配**：Cookie `Max-Age` 固定为绝对时长（默认 12 小时），而服务端空闲超时为 30 分钟；空闲超时后浏览器仍持有 Cookie 并继续发送，只能由服务端返回 401。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:43`、`:57`、`backend/src/main/java/com/analyzercoder/security/AuthService.java:56`、`:231`
6. **锁定到期后计数不清零**：`locked_until` 过期不会重置 `failed_attempts`，到期后的首次密码错误会因计数继续递增而立即重新锁定 15 分钟；只有成功登录或管理员解锁才会清零。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:198-200`、`backend/src/main/resources/mappers/AuthMapper.xml:96-102`
7. **解锁不校验目标**：`AuthService.unlock` 不检查账号是否存在、也不检查是否真的处于锁定状态，对任意 UUID 都会写入审计事件 `ACCOUNT_UNLOCKED`。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:394-397`
8. **审计无法按请求追踪**：`request_id` 每次写入审计时新生成随机 UUID，未与入口 HTTP 请求关联；`details` 恒为 `'{}'::jsonb`，变更前后取值均未记录。证据：`backend/src/main/java/com/analyzercoder/security/AuthService.java:445-454`、`backend/src/main/resources/mappers/AuthMapper.xml:143`
9. **账户访问令牌与 `/api/auth/me` 不兼容**：会话拦截器在存在令牌账号属性时直接放行，但 `/api/auth/me` 走 `SecurityContext.session` 会抛 401 `SESSION_EXPIRED`；实际请求会先被令牌拦截器以 403 `TOKEN_ENDPOINT_FORBIDDEN` 拒绝。令牌与账户偏好等会话类端点是否应互通，需人工确认。证据：`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:45-46`、`backend/src/main/java/com/analyzercoder/security/SecurityContext.java:13-19`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:28-30`
10. **审计 IP 的代理场景未覆盖**：来源 IP 统一取 `request.getRemoteAddr()`，默认 `forward-headers-strategy: none`；`ForwardedClientIpTest` 只验证 `ForwardedHeaderFilter` 自身行为，未验证应用是否注册该过滤器。反向代理部署下真实来源地址是否可用，需人工确认。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AuthController.java:60-62`、`backend/src/main/resources/application.yml:41`、`backend/src/test/java/com/analyzercoder/interfaces/rest/ForwardedClientIpTest.java:12-34`
11. **测试覆盖缺口**：`backend/src/test` 下没有 `CaptchaService`、`AccessControlService`、`SessionInterceptor` 的 CSRF 分支、`AccountController`，以及 `RepositoryGovernanceService` 授予/撤销/转移/删除分支的测试；前端 `useAccessTokens.spec.ts` 只覆盖令牌 hook 的创建、撤销与明文清理。证据：`backend/src/test/java/com/analyzercoder/security/AccessTokenServiceTest.java:23-164`、`backend/src/test/java/com/analyzercoder/security/PasswordResetSecurityTest.java:25-94`、`backend/src/test/java/com/analyzercoder/security/AuthServiceRepositoryPreferenceTest.java:21-75`、`backend/src/test/java/com/analyzercoder/application/repository/RepositoryGovernanceServiceTest.java:23-44`、`frontend/src/features/accounts/useAccessTokens.spec.ts:1-55`
12. **`AccountSummary.repositoryPermissionCount` 语义偏差**：该字段只统计成员授权行，作为所有者拥有的项目不计入；账号列表显示为「被授权仓库」，与账号实际可访问的项目数不等。证据：`backend/src/main/resources/mappers/AuthMapper.xml:70-73`、`frontend/src/features/accounts/AccountTable.vue:48`
13. **审计界面 `focusVersion` 自增量无效**：`AuditLogsView` 把 `focusVersion` 固定为 1 并传入面板，面板用其触发筛选重置，因此重复从账号列表跳转同一目标账号时不会重新触发定位逻辑。证据：`frontend/src/views/AuditLogsView.vue:16`、`frontend/src/features/accounts/AuditLogPanel.vue:17`
