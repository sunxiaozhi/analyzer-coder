# MCP 接入
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

本领域覆盖把平台的只读检索与代码图谱能力暴露给外部 AI 编程工具的两条入口：

- 后端 Streamable HTTP 入口：`/api/mcp`，无状态、JSON 响应模式，所有账户共用同一地址。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:22-23`、`mcp-server/README.md:3`。
- Node stdio 适配器入口：`mcp-server/src/server.mjs`，供只支持 stdio 的客户端使用，图谱类工具转发到后端 `/api/mcp` 执行，两者提供同名工具。证据：`mcp-server/src/server.mjs:10-14`、`mcp-server/src/server.mjs:81-89`。

角色与权限模型：

- 账户角色只有 `SUPER_ADMIN`（超级管理员）与 `NORMAL`（普通用户）。证据：`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4-7`。
- 仓库权限级别只有三级：`READ` < `MAINTAIN` < `MANAGE`。证据：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4-11`。
- MCP 只使用 `READ`；不存在通过 MCP 提升权限或执行写操作的路径。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:41`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:51`。
- 成员记录中的所有者（owner）关系按 `MANAGE` 处理；超级管理员在权限判断中直接放行，并在可见仓库集合中被扩张为全部仓库。证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:23-25`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:30-34`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:56-60`。

术语约定：账户访问令牌（access token）；阅读上下文（contextId）；本地证据模式（LOCAL_EVIDENCE_MODE）；代码图谱（CodeGraph）；图谱产物（artifact）；会话（thread / conversation）。

## 2 需求条目

### MCP-001 服务形态：HTTP 与 stdio 双入口
- 需求：同一套工具语义必须能通过后端 HTTP 入口和本地 stdio 适配器两种方式使用，工具名称与语义保持一致。
- 规则：
  - 后端入口为无状态 Streamable HTTP JSON 响应模式，每次请求通过账户令牌认证。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:22-23`。
  - `GET /api/mcp` 不提供服务端事件流，返回 405 且 `Allow: POST`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:39-42`。
  - `POST /api/mcp` 只消费 `application/json` 并返回 `application/json`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:44`。
  - 所有响应带 `Cache-Control: no-store`，stdio 适配器仍连接同一个 Java 后端；图谱类工具经 `POST /api/mcp` 转发，检索与上下文解析直接调用只读 REST 接口。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:149`、`mcp-server/src/server.mjs:32-39`、`mcp-server/src/server.mjs:83-88`。
- 证据：`mcp-server/src/server.mjs:10-14`

### MCP-002 协议初始化与版本协商
- 需求：客户端可完成 MCP `initialize` 握手，服务端声明自身能力与信息，并对协议版本做白名单校验。
- 规则：
  - 支持的协议版本为 `2025-03-26`、`2025-06-18`、`2025-11-25`（最新）。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:27-28`。
  - 请求头 `MCP-Protocol-Version` 存在且不在支持列表时返回 400 与 JSON-RPC 错误 `-32600`「Unsupported MCP protocol version」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:47-50`。
  - `initialize` 回显客户端请求的版本（若受支持），否则回落到最新版本。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:63-68`。
  - `capabilities` 声明 `tools.listChanged = false`；`serverInfo` 为 `analyzer-coder` / `1.0.0`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:69-73`。
  - `instructions` 固定提示：先用 `list_codegraph_scopes` 选择可见仓库与分支，再复用 CodeGraph 工具返回的 `contextId`；`search_project` 仍可用于代码与知识检索。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:73-74`。
  - `ping` 返回空结果对象；测试断言 `initialize` 回显 `2025-11-25`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:76`、`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:87`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:27-28`

### MCP-003 JSON-RPC 请求校验与错误响应
- 需求：非法请求必须以标准 JSON-RPC 错误码拒绝，通知类消息不返回结果体。
- 规则：
  - 必须是 JSON 对象、`jsonrpc == "2.0"`、`method` 为字符串、`id` 为字符串或整数，否则 400 + `-32600`「Invalid Request」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:51-55`。
  - 缺少 `id` 时：`notifications/` 前缀返回 202 Accepted；其他请求返回 400 + `-32600`「Requests require an id」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:57-60`。
  - 未知方法返回 `-32601`「Method not found」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:142-144`。
  - 未知名工具返回 `-32602`「Unknown tool」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:81-82`。
  - 无法解析的请求体返回 400 + `-32700`「Parse error」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:152-155`。
  - 错误响应的结构为 `{jsonrpc, id, error:{code, message}}`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:157-162`。
  - 测试断言无 id 通知返回 202、`GET /api/mcp` 返回 405、未知版本头返回 400。证据：`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:103`、`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:105`、`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:148`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:45-60`

### MCP-004 工具目录与只读标注
- 需求：必须暴露固定 12 个工具，全部标注为只读；工具清单与标注在 Java 侧与 Node 侧保持一致。
- 规则：
  - 目录定义在 `backend/src/main/resources/mcp-tools.json`，`tools/list` 直接返回该数组的深拷贝。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:15-23`、`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:77`。

| 工具 | 作用 | readOnlyHint | destructiveHint | idempotentHint | 来源 |
| --- | --- | --- | --- | --- | --- |
| `search_project` | 在代码与已发布知识中做一次联合排序检索 | true | false | true | `backend/src/main/resources/mcp-tools.json:45-49` |
| `resolve_project_context` | 固定已准备分支内容版本与适用知识修订，返回 contextId | true | false | false | `backend/src/main/resources/mcp-tools.json:78-82` |
| `list_codegraph_scopes` | 列出令牌账户可见项目与分支的图谱就绪状态 | true | false | true | `backend/src/main/resources/mcp-tools.json:106-110` |
| `codegraph_explore` | 在固定内容版本中探索相关源码与调用路径 | true | false | true | `backend/src/main/resources/mcp-tools.json:151-155` |
| `codegraph_node` | 读取符号或文件及其关系与行号 | true | false | true | `backend/src/main/resources/mcp-tools.json:206-210` |
| `codegraph_search` | 在固定内容版本中搜索已索引符号 | true | false | true | `backend/src/main/resources/mcp-tools.json:251-255` |
| `codegraph_callers` | 查询某符号的调用方 | true | false | true | `backend/src/main/resources/mcp-tools.json:296-300` |
| `codegraph_callees` | 查询某符号的被调用方 | true | false | true | `backend/src/main/resources/mcp-tools.json:341-345` |
| `codegraph_impact` | 从某符号做下游影响分析 | true | false | true | `backend/src/main/resources/mcp-tools.json:386-390` |
| `codegraph_files` | 列出固定内容版本中已索引的文件 | true | false | true | `backend/src/main/resources/mcp-tools.json:429-433` |
| `codegraph_status` | 读取固定内容版本的 CodeGraph 状态 | true | false | true | `backend/src/main/resources/mcp-tools.json:462-466` |
| `codegraph_affected` | 由改动文件推导受影响符号线索 | true | false | true | `backend/src/main/resources/mcp-tools.json:511-515` |

- 规则：`resolve_project_context` 的 `idempotentHint` 为 `false`，其余 11 个为 `true`；12 个工具的 `readOnlyHint` 全为 `true`、`destructiveHint` 全为 `false`。证据：`backend/src/main/resources/mcp-tools.json:45-49`、`backend/src/main/resources/mcp-tools.json:78-82`。
  - Node 侧注册顺序与 JSON 顺序一致：`search_project`、`resolve_project_context`，随后 10 个 `codegraph_*`。证据：`mcp-server/src/server.mjs:16-90`。
  - 数量与顺序由两侧测试同时固定。证据：`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:94-96`、`mcp-server/test/server.test.mjs:22`。
- 证据：`backend/src/main/resources/mcp-tools.json:1`

### MCP-005 工具参数、默认值与上下限
- 需求：每个工具的参数必须有明确的类型、默认值与上下限，并在服务端强制生效。
- 规则：

| 工具 | 主要参数（默认值 / 范围） | 来源 |
| --- | --- | --- |
| `search_project` | `repositoryId`、`query`(1–1000) 必填；`limit` 默认 20，1–50；`branchId` / `contextId` 可选 | `backend/src/main/resources/mcp-tools.json:6-44`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:62` |
| `resolve_project_context` | `repositoryId` 必填；`branchId` 或 `contextId` 至少一个（服务端校验） | `backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:54-56` |
| `list_codegraph_scopes` | `page` 默认 1，1–100000；`pageSize` 默认 20，1–50 | `backend/src/main/resources/mcp-tools.json:88-105`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:150-151` |
| `codegraph_explore` | `repositoryId`、`query`(1–500) 必填；`maxFiles` 默认 8，1–20 | `backend/src/main/resources/mcp-tools.json:116-150`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:64-69` |
| `codegraph_node` | `repositoryId` 必填；`name` 或 `file` 至少一个；`offset` 默认 0，0–10000；`limit` 默认 50，1–100 | `backend/src/main/resources/mcp-tools.json:161-205`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:70-83` |
| `codegraph_search` | `repositoryId`、`query`(1–500) 必填；`limit` 默认 20，1–50 | `backend/src/main/resources/mcp-tools.json:216-250`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:84-90` |
| `codegraph_callers` / `codegraph_callees` | `repositoryId`、`symbol`(1–500) 必填；`limit` 默认 20，1–50 | `backend/src/main/resources/mcp-tools.json:261-295`、`backend/src/main/resources/mcp-tools.json:306-340`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:91-97` |
| `codegraph_impact` | `repositoryId`、`symbol`(1–500) 必填；`depth` 默认 2，1–5 | `backend/src/main/resources/mcp-tools.json:351-385`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:98-104` |
| `codegraph_files` | `repositoryId` 必填；`filter`(1–500)、`pattern`(1–500) 可选 | `backend/src/main/resources/mcp-tools.json:396-428`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:105-116` |
| `codegraph_status` | `repositoryId` 必填，无额外参数 | `backend/src/main/resources/mcp-tools.json:439-461`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:117` |
| `codegraph_affected` | `repositoryId`、`files`(1–20 项，每项 1–500) 必填；`depth` 默认 2，1–5 | `backend/src/main/resources/mcp-tools.json:472-510`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:118-127` |

- 规则：除 `list_codegraph_scopes` 外，其余 11 个工具的 `repositoryId` 均为必填；除 `resolve_project_context` 与 `search_project` 外，图谱类工具都必须提供 `branchId` 或 `contextId`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:50-55`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:213-217`

### MCP-006 参数 schema 校验
- 需求：工具调用参数必须先按 `mcp-tools.json` 的 `inputSchema` 校验，校验失败不得执行工具。
- 规则：
  - 校验支持 `anyOf`、`enum`、`required`、`maxItems`、`minLength`/`maxLength`、`pattern`、`format: uri`、`minimum`/`maximum` 与基本类型。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:40-105`。
  - 校验按字段路径报错，消息形如 `<path> 参数不符合工具约束`（例如 `arguments.repositoryId`）。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:107-109`。
  - 校验在 `tools/call` 内、工具执行之前完成，失败时返回 `isError=true` 且不触碰下游服务。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:84-85`、`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:160-161`。
  - 校验只遍历 schema 中声明的属性，未声明的额外字段被忽略而不是拒绝。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:68-79`。
  - JSON Schema 中的 `default` 不由校验器填充，实际默认值由工具实现按 fallback 取值。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:213-217`。
  - `format: uuid` 不单独校验，UUID 由 `UUID.fromString` 兜底（失败抛 `IllegalArgumentException`）。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:93-99`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:40`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:25-33`

### MCP-007 账户访问令牌认证
- 需求：MCP 调用必须使用账户访问令牌（Bearer）认证；令牌只代表账户身份，不代表仓库权限。
- 规则：
  - 只接受 `Authorization: Bearer <token>`；缺失或前缀不匹配时返回 401 `ACCESS_TOKEN_REQUIRED` 并带 `WWW-Authenticate: Bearer realm="analyzer-mcp"`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:32-35`。
  - 令牌格式为 `acp_` 加 43 位 base64url 随机串，否则直接判无效。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:43-45`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:72`。
  - 存储只保留 SHA-256 摘要与原始令牌前 12 位前缀，不保存明文。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:52-53`。
  - 创建时校验令牌名称 1–80 字符、有效期 1–365 天，否则 400 `TOKEN_INPUT_INVALID`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:39-41`。
  - 认证失败统一返回 401 `ACCESS_TOKEN_INVALID`（无效、已过期、已撤销或账户已停用），并带 `error="invalid_token"`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:104-106`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:40-46`。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:22-48`

### MCP-008 每次调用实时校验令牌与账户状态
- 需求：令牌的每一次使用都必须重新校验撤销、过期与账户状态，并刷新最近使用时间。
- 规则：
  - 每次请求按令牌摘要查库，逐项校验 `revokedAt` 为空、`expiresAt` 在当前时间之后。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:73-76`。
  - 账户必须存在且启用；强制改密账户返回 403 `PASSWORD_CHANGE_REQUIRED`；锁定账户返回 403 `ACCOUNT_LOCKED`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:89-97`。
  - 通过后更新 `last_used_at`，更新失败视为令牌无效。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:78`。
  - 账户身份只从令牌解析结果注入请求属性；每个请求独立解析，不缓存。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:37-39`、`backend/src/main/java/com/analyzercoder/security/SecurityContext.java:21-25`。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:71-87`

### MCP-009 令牌可访问的端点白名单
- 需求：访问令牌只能调用 MCP 工具相关端点，不得用于平台管理接口。
- 规则：
  - 携带 `Authorization` 但路径不在白名单时返回 403 `TOKEN_ENDPOINT_FORBIDDEN`「访问令牌仅可调用 MCP 工具接口」。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:28-30`。
  - 白名单仅两项：`GET /api/repositories/{id}/evidence-search` 与 `POST /api/repositories/{id}/contexts`（`/api/mcp` 自身单独放行）。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:12-15`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:50-53`。
  - 该白名单正是 stdio 适配器 `search_project` 与分支解析实际调用的两个 REST 端点。证据：`mcp-server/src/server.mjs:32-39`。
  - 测试：用 Bearer 令牌调用 `/api/accounts/{id}/access-tokens` 返回 403。证据：`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:172`。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:50-53`

### MCP-010 Origin 校验
- 需求：`/api/mcp` 请求携带 `Origin` 时必须与服务端公开地址一致，否则拒绝。
- 规则：
  - 仅对 `/api/mcp` 执行 Origin 校验。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:31`。
  - 无 `Origin` 头时跳过（适配非浏览器客户端）。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:56-57`。
  - 有 `Origin` 时要求 host、scheme、port 与请求一致，且不含 userInfo、path、query、fragment，否则 403 `MCP_ORIGIN_FORBIDDEN`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:58-75`。
  - 测试：`Origin: https://evil.test` 返回 403 且不执行任何工具。证据：`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:141`、`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:149`。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:55-75`

### MCP-011 不接受会话 Cookie 代替令牌
- 需求：`/api/mcp` 不能用网页会话 Cookie 冒充令牌认证。
- 规则：
  - `/api/mcp` 路径即使携带 `AC_SESSION` 且没有 Authorization 头，也必须返回 401 `ACCESS_TOKEN_REQUIRED`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:26-28`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:32-35`。
  - 令牌认证成功时，请求直接跳过会话与 CSRF 校验（不再要求 `AC_SESSION`）。证据：`backend/src/main/java/com/analyzercoder/security/SessionInterceptor.java:45-46`。
  - 拦截器注册顺序为先令牌后会话，因此令牌优先。证据：`backend/src/main/java/com/analyzercoder/security/WebSecurityConfig.java:20-24`。
  - 测试：仅有会话 Cookie 的 `POST /api/mcp` 返回 401，且 `verifyNoInteractions` 工具服务。证据：`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:74-75`。
- 证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:26-35`

### MCP-012 每个工具调用前的 READ 权限校验
- 需求：每个工具调用都必须对目标仓库实时校验 `READ` 权限；权限被撤销后同一令牌立即失效。
- 规则：
  - `search_project` 与 `resolve_project_context` 在读取任何数据前调用 `access.require(actor, repo, READ)`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:39-41`、`backend/src/test/java/com/analyzercoder/application/mcp/McpToolServiceTest.java:42-47`。
  - 所有 `codegraph_*` 工具（`list_codegraph_scopes` 除外，见 MCP-013）在解析上下文前调用同一校验。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:50-51`。
  - 分支上下文解析内部再次执行 `READ` 校验。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:349`。
  - 拒绝时抛 403 `FORBIDDEN`「无权限访问该仓库」，且不调用下游检索或 CLI。证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:37-44`、`backend/src/test/java/com/analyzercoder/application/mcp/McpToolServiceTest.java:45`。
  - 测试：用同一令牌先后操作两个仓库，权限由每次调用重新判定。证据：`backend/src/test/java/com/analyzercoder/interfaces/rest/McpControllerTest.java:119-122`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:39-41`

### MCP-013 可见项目过滤与超级管理员扩张
- 需求：`list_codegraph_scopes` 只返回令牌账户可见的项目；隐藏项目不泄漏。
- 规则：
  - 使用 `access.visibleRepositoryIds(actor)` 作为过滤集合。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:152-158`。
  - 普通账户取 `findVisibleRepositoryIds(accountId)`，超级管理员取 `findVisibleRepositoryIdsForAdmin()`。证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:56-60`。
  - 分支枚举仍走 `branches.list(actor, repoId)`，其中包含 `READ` 校验。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:165`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:77-78`。
  - 测试：不可见仓库不出现在结果中。证据：`backend/src/test/java/com/analyzercoder/application/mcp/McpCodeGraphToolsTest.java:73-74`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:149-160`

### MCP-014 list_codegraph_scopes 返回字段与分页
- 需求：发现类工具必须给出可供 AI 客户端按名称选择项目与分支的稳定结构，并支持分页。
- 规则：
  - 入参 `page`（默认 1，1–100000）、`pageSize`（默认 20，1–50）。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:150-151`。
  - 项目按名称小写升序排序后再切页。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:159-163`。
  - 返回 `projects[]`、`page`、`pageSize`、`totalProjects`、`hasMore`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:195-206`。
  - 项目项字段：`repositoryId`、`name`、`sourceType`、`branches[]`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:184-193`。
  - 分支项字段：`branchId`、`name`、`status`、`trackingStatus`、`contentVersion`、`commitSha`、`codegraphReady`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:166-181`。
  - `codegraphReady` 为真要求分支未归档、内容版本非空且存在 `PUBLISHED` 产物。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:173-181`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:149-207`

### MCP-015 contextId 的获取与复用
- 需求：首次调用用 `branchId` 解析出固定的阅读上下文，后续调用复用 `contextId`，保持同一内容版本。
- 规则：
  - `resolve_project_context` 必须提供 `branchId` 或 `contextId`，否则抛「解析上下文需要 branchId 或 contextId」。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:54-56`。
  - 图谱类工具在两者都缺失时抛「CodeGraph 查询需要 branchId 或 contextId」。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:54-55`。
  - 上下文解析结果直接序列化返回，包含 `contextId`、`repositoryId`、`branchId`、`branchName`、`contentVersion`、`commitSha`、`expiresAt`；内部内容路径被 `@JsonIgnore` 排除。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchReadContext.java:9-17`。
  - 图谱工具返回体固定包含 `context`、`artifactId`、`cliVersion`、`result`，便于客户端复用。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:137-146`。
  - `search_project` 带分支参数时返回 `{context, result}`，不带时返回原始检索结果。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:63-72`。
  - 上下文不会自动跟随分支更新；每次以 `contextId` 解析都会读到创建时固定的内容版本。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:350-376`。
  - stdio 适配器把该能力包装为 `resolve_project_context`，并在 `search_project` 中先解析再以 `X-Branch-Context` 头复用。证据：`mcp-server/src/server.mjs:46-57`、`mcp-server/src/server.mjs:32-39`、`mcp-server/test/server.test.mjs:95-99`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchReadContext.java:8-17`

### MCP-016 上下文过期与不匹配
- 需求：上下文过期、账户不匹配或分支不匹配时必须报明确错误码，客户端应重新解析。
- 规则：
  - 查询条件包含 `account_id` 与 `expires_at > CURRENT_TIMESTAMP`；查不到即 409 `CONTEXT_EXPIRED`「分支上下文不存在或已过期，请重新选择分支」。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:351-372`。
  - 同时传 `branchId` 与 `contextId` 但分支不一致时 409 `CONTEXT_MISMATCH`「分支与上下文不匹配」。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:374-375`。
  - 上下文有效期为创建后 1 小时。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:397`。
  - 前端与 README 都把这两个错误码列为「重新选择或解析分支」的处理项。证据：`frontend/src/features/mcp/McpGuide.vue:33`、`mcp-server/README.md:98`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347-377`

### MCP-017 未就绪分支可见但不可查询；ZIP 项目不入发现列表
- 需求：发现列表应展示未就绪分支以便用户判断，但查询必须失败且不回退；ZIP 的 `WORKSPACE` 分支同样参与发现。
- 规则：
  - 分支列表返回所有受管分支，未就绪者以 `codegraphReady=false` 体现，不隐藏。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:166-181`。
  - 查询未发布产物的分支时抛 `CODEGRAPH_ARTIFACT_NOT_AVAILABLE`「所选分支内容版本尚未发布 CodeGraph 产物」，不换分支、不换内容版本。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:57-60`、`backend/src/test/java/com/analyzercoder/application/mcp/McpCodeGraphToolsTest.java:106-109`。
  - 分支尚未准备（无可发布内容版本）时解析上下文报 409 `BRANCH_NOT_READY`，提示「不会使用其他分支的数据」。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:400-402`。
  - 发现列表过滤 `sourceType == ZIP` 的项目。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:154-158`。
  - README 明确 ZIP 项目通过 `WORKSPACE` 使用同一分支图谱协议。证据：`mcp-server/README.md`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:57-60`

### MCP-018 不接受服务器文件路径、任意内容版本 ID 或任意产物 ID
- 需求：外部客户端不得指定服务器文件路径、任意内容版本 ID 或产物路径；内容版本与产物一律由服务端从分支身份解析。
- 规则：
  - 12 个工具的 schema 中没有 `contentVersion`、`artifactPath` 或绝对路径参数，只有 `repositoryId` + `branchId`/`contextId` 与查询条件。证据：`backend/src/main/resources/mcp-tools.json:6-517`。
  - 内容版本 ID 一律来自服务端解析出的上下文：`graphs.latestContentVersion(repositoryId, context.contentVersion())`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:56-57`。
  - 产物路径只从数据库行读取，且必须位于配置的受管根目录内，否则 `CODEGRAPH_ARTIFACT_MISSING`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:187-190`。
  - 返回内容中不回显产物绝对路径（把项目路径替换为 `.`）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:227`、`backend/src/test/java/com/analyzercoder/application/mcp/McpCodeGraphToolsTest.java:83-84`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:56-57`

### MCP-019 参数净化与文件路径校验
- 需求：所有字符串查询参数必须净化，文件类参数必须是所选分支内的相对路径。
- 规则：
  - 通用净化：去除首尾空白；必填时不得为空；不得以 `-` 开头（防止被当作 CLI 选项）；长度不超过 500；不得包含 `\r`、`\n` 与 `& | < > ^ " % ! \`` 等字符，否则抛「CodeGraph 查询参数无效」。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:234-242`。
  - 文件路径额外要求：反斜杠统一为正斜杠；禁止以 `/` 开头、禁止 `../` 片段或结尾 `..`、禁止 `..`、禁止 `盘符:` 前缀，否则抛「文件路径必须位于所选分支内」。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:219-228`。
  - 数值参数超出上下限时抛「<字段> 超出允许范围」。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:213-217`。
  - `codegraph_affected` 的 `files` 必须是非空数组，否则抛「files 不能为空」。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:123-126`。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:219-242`

### MCP-020 CLI 调用受固定操作白名单、超时与输出上限约束
- 需求：MCP 触发的 CLI 查询必须限定在固定只读操作集合内，并受超时与输出体积上限约束。
- 规则：
  - 允许的操作集合为 `explore`、`node`、`query`、`callers`、`callees`、`impact`、`files`、`status`、`affected`；图谱工具名到操作的映射固定。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:197-207`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:62-129`。
  - 单次查询超时 30 秒，超时/失败统一映射为 `CODEGRAPH_QUERY_FAILED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:223`、`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:230-233`。
  - 输出超过 200000 字符抛 `CODEGRAPH_RESULT_TOO_LARGE`「CodeGraph 查询结果过大，请缩小范围」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:224-226`。
  - 进程错误输出截断到 1000 字符后才进入错误消息。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:374-376`。
  - CLI 调用前先校验产物可用性（`published`），确保命令只在已发布的受管内容版本上执行。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:210`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:194-234`

### MCP-021 工具结果结构与错误封装
- 需求：工具结果必须同时提供文本内容与结构化内容；错误必须以 `isError` 表达并给出稳定错误码。
- 规则：
  - 成功结果：`content[{type:"text", text: JSON 字符串}]` + `structuredContent` + `isError=false`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:91-98`。
  - `ApiSecurityException` 与 `IllegalArgumentException`：`isError=true`，文本为异常消息原文。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:99-112`。
  - `CodeGraphException`：`isError=true`，文本为 `<code>: <message>`，保留错误码。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:113-124`。
  - 其他 `RuntimeException`：`isError=true`，文本固定为 `MCP_INTERNAL_ERROR: 工具执行失败，请查看服务端日志`，异常细节只写服务端日志。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:125-140`。
  - `tools/call` 的结果整体包在 `{jsonrpc, id, result}` 中，HTTP 状态仍为 200。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:146-149`。
  - stdio 适配器把后端错误再包一层 `{content, structuredContent:{code,message}, isError:true}`。证据：`mcp-server/src/server.mjs:98-109`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:91-140`

### MCP-022 适配器的凭据与环境配置
- 需求：stdio 适配器必须通过环境变量获得后端地址与凭据，并在无令牌时明确拒绝图谱工具。
- 规则：
  - 适配器配置项：`ANALYZER_API_BASE`（默认 `http://127.0.0.1:8080`）、`ANALYZER_ACCESS_TOKEN`、`ANALYZER_SESSION_TOKEN`、`ANALYZER_CSRF_TOKEN`。证据：`mcp-server/src/api-client.mjs:53-59`。
  - 客户端必须提供 `baseUrl`；没有访问令牌时必须同时提供会话令牌与 CSRF 令牌，否则构造失败。证据：`mcp-server/src/api-client.mjs:11-20`。
  - 有访问令牌时发送 `Authorization: Bearer <token>`，否则发送 `Cookie: AC_SESSION=<session>`；仅在没有访问令牌且方法非安全时附加 `X-CSRF-Token`。证据：`mcp-server/src/api-client.mjs:26-31`。
  - 图谱类工具缺少访问令牌时直接返回 401 `ACCESS_TOKEN_REQUIRED`，不发起请求。证据：`mcp-server/src/server.mjs:82`。
  - 非 2xx 响应映射为 `AnalyzerApiError`，`code` 取响应体 `code`，缺省为 `HTTP_ERROR`；响应体非法 JSON 时为 `INVALID_JSON`。证据：`mcp-server/src/api-client.mjs:34-40`、`mcp-server/src/api-client.mjs:45-51`。
- 证据：`mcp-server/src/api-client.mjs:53-59`

### MCP-023 前端 MCP 接入页面与配置模板
- 需求：平台内提供 MCP 接入说明页，能生成可直接粘贴的客户端配置模板并管理访问令牌。
- 规则：
  - 路由 `/mcp` 渲染 `McpGuideView.vue`（内部组合 `McpGuide.vue`），标题「MCP 接入」。证据：`frontend/src/router/index.ts:21`、`frontend/src/views/McpGuideView.vue:1-9`。
  - 页面章节：准备仓库、创建访问令牌、配置客户端、搜索代码与知识、选择分支并查询图谱、常见问题。证据：`frontend/src/features/mcp/McpGuide.vue:47-87`。
  - 令牌管理内嵌账户令牌面板，令牌只显示一次。证据：`frontend/src/features/mcp/McpGuide.vue:53-58`。
  - 配置模板形如 `{mcpServers:{"analyzer-coder":{url:"<平台地址>/api/mcp",headers:{Authorization:"Bearer <令牌>"}}}}`，地址取自 `VITE_API_BASE_URL` 或当前 origin，可一键复制。证据：`frontend/src/features/mcp/McpConfigPanel.vue:4-16`、`frontend/src/features/mcp/McpConfigPanel.vue:24-26`。
  - 页面明确「此服务使用手动访问令牌，不提供 OAuth 登录跳转」。证据：`frontend/src/features/mcp/McpConfigPanel.vue:26`。
  - 排错清单列出 401/403、连接失败、`CODEGRAPH_ARTIFACT_NOT_AVAILABLE`、`CONTEXT_EXPIRED`/`CONTEXT_MISMATCH`、结果为空。证据：`frontend/src/features/mcp/McpGuide.vue:28-35`。
  - 分支工作区提供「复制 MCP 参数」，输出 `repositoryId` 与 `branchId`。证据: `frontend/src/features/branches/BranchIndexPanel.vue:54`、`frontend/src/features/branches/useBranchManagement.ts:152`。
- 证据：`frontend/src/features/mcp/McpGuide.vue:28-35`

### MCP-024 令牌管理接口
- 需求：账户可查看、创建、撤销自己的访问令牌；超级管理员可管理他人令牌。
- 规则：
  - `GET /api/accounts/{accountId}/access-tokens` 列出令牌（含 `prefix`、`expiresAt`、`lastUsedAt`、`revokedAt`），响应带 `Cache-Control: no-store`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountAccessTokenController.java:28-34`。
  - `POST` 创建令牌并仅此一次返回明文 `rawToken`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountAccessTokenController.java:36-50`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:60`。
  - `DELETE /api/accounts/{accountId}/access-tokens/{tokenId}` 撤销令牌，返回 `{revoked:true}`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountAccessTokenController.java:52-58`。
  - 非本人且非超级管理员时 403 `FORBIDDEN`「只能管理自己的访问令牌」；撤销不存在的令牌返回 404 `TOKEN_NOT_FOUND`。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:99-102`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:64-69`。
  - 创建与撤销写入审计日志（`ACCESS_TOKEN_CREATED`、`ACCESS_TOKEN_REVOKED`）。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:59`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:68`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/AccountAccessTokenController.java:19-60`

## 3 数据与状态

- 访问令牌存储字段：`id`、账户、名称、SHA-256 摘要、12 位前缀、创建时间、过期时间、`lastUsedAt`、`revokedAt`；对外视图不返回摘要。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:47-58`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:119-126`。
- 令牌状态判定只看三个事实：是否撤销、是否过期、账户是否可用（启用、非强制改密、未锁定）。证据：`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:73-97`。
- MCP 服务端无状态：不保存会话、事件流或工具调用状态，所有请求带 `no-store`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:22`、`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:149`。
- 阅读上下文（contextId）字段：`contextId`、`repositoryId`、`branchId`、`branchName`、`contentVersion`、`commitSha`、`expiresAt`；`contentPath` 标记 `@JsonIgnore` 不出现在工具响应中。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchReadContext.java:8-17`。
- 上下文的持久化状态：`branch_read_contexts`（含 `expires_at`）与 `branch_context_knowledge`（固定适用的知识修订）。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:404-421`。
- 分支发现在意三个状态：`trackingStatus`（`ACTIVE`/`ARCHIVED`）、`status`（准备状态，如 `READY`/`BUILDING`/`FAILED`）、`codegraphReady`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:166-181`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:66-75`。
- 图谱产物状态为 `PUBLISHED`（可查询）与 `RETIRED`（被新发布替换），MCP 只认 `PUBLISHED`。证据：`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:31-43`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:58`。
- 图谱构建任务状态与失败码：`QUEUED`、`RUNNING`、`CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELED`；失败码 `CODEGRAPH_TIMEOUT`、`CODEGRAPH_BUILD_FAILED`（MCP 不触发构建）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:70-88`。

## 4 接口清单

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| POST | `/api/mcp` | Streamable HTTP MCP：`initialize` / `ping` / `tools/list` / `tools/call`（通知类返回 202） | 账户访问令牌（Bearer） |
| GET | `/api/mcp` | 明确不提供服务端事件流，返回 405 与 `Allow: POST` | 账户访问令牌（Bearer） |
| POST | `/api/repositories/{repoId}/contexts` | 解析或复用阅读上下文（stdio 适配器与网页共用） | 账户访问令牌（白名单）或登录会话；仓库 READ |
| GET | `/api/repositories/{repoId}/evidence-search` | 代码与知识联合检索（stdio 适配器 `search_project`） | 账户访问令牌（白名单）或登录会话；仓库 READ |
| GET | `/api/accounts/{accountId}/access-tokens` | 列出访问令牌 | 本人或超级管理员；令牌调用被拒（403） |
| POST | `/api/accounts/{accountId}/access-tokens` | 创建访问令牌（仅一次返回明文） | 本人或超级管理员；令牌调用被拒（403） |
| DELETE | `/api/accounts/{accountId}/access-tokens/{tokenId}` | 撤销访问令牌 | 本人或超级管理员；令牌调用被拒（403） |

权限来源：`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:26-53`、`backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:30-69`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:349`、`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:39-44`。

### 4.1 错误码目录

传输与协议层（HTTP + JSON-RPC）：

| 错误码 | HTTP | 含义 | 来源 |
| --- | --- | --- | --- |
| `ACCESS_TOKEN_REQUIRED` | 401 | 未提供 Bearer 令牌（含仅有会话 Cookie 的情况） | `backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:32-35` |
| `ACCESS_TOKEN_INVALID` | 401 | 令牌格式错误、不存在、已过期、已撤销或账户不可用 | `backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:104-106` |
| `PASSWORD_CHANGE_REQUIRED` | 403 | 令牌账户必须先登录平台完成改密 | `backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:92-93` |
| `ACCOUNT_LOCKED` | 403 | 令牌账户已锁定 | `backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:94-95` |
| `TOKEN_ENDPOINT_FORBIDDEN` | 403 | 访问令牌被用于 MCP 工具接口以外的端点 | `backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:29-30` |
| `MCP_ORIGIN_FORBIDDEN` | 403 | `/api/mcp` 的 Origin 与服务地址不一致 | `backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:74` |
| `FORBIDDEN` | 403 | 令牌账户对目标仓库缺少 READ 权限 | `backend/src/main/java/com/analyzercoder/security/AccessControlService.java:42` |
| `TOKEN_INPUT_INVALID` | 400 | 令牌名称或有效期不合法（名称 1–80、有效期 1–365 天） | `backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:39-41` |
| `TOKEN_NOT_FOUND` | 404 | 待撤销的令牌不存在 | `backend/src/main/java/com/analyzercoder/security/AccessTokenService.java:67` |
| `-32700` | 400 | 请求体无法解析（Parse error） | `backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:152-155` |
| `-32600` | 400 | 非法请求 / 不支持的协议版本 / 缺少 id | `backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:49-59` |
| `-32601` | 200 | 未知方法（Method not found） | `backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:142-144` |
| `-32602` | 200 | 未知名工具（Unknown tool） | `backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:81-82` |
| `MCP_INTERNAL_ERROR` | 200 | 工具执行发生未预期异常，细节不返回客户端 | `backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:125-140` |
| `BAD_REQUEST` | 400 | 工具参数净化/语义校验失败（消息见下） | `backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:27-29` |
| `MCP_ADAPTER_ERROR` | — | stdio 适配器内的非 API 异常 | `mcp-server/src/server.mjs:101` |
| `MCP_BACKEND_ERROR` | — | 适配器收到的后端 JSON-RPC error | `mcp-server/src/server.mjs:87` |
| `HTTP_ERROR` / `INVALID_JSON` | — | 适配器对非 2xx 或无有效 code 的响应/非法 JSON 的兜底码 | `mcp-server/src/api-client.mjs:37`、`mcp-server/src/api-client.mjs:50` |

`BAD_REQUEST` 覆盖的具体消息（由 `IllegalArgumentException` 产生）：`未知 MCP 工具`、`解析上下文需要 branchId 或 contextId`、`CodeGraph 查询需要 branchId 或 contextId`、`name 或 file 至少提供一个`、`files 不能为空`、`文件路径必须位于所选分支内`、`CodeGraph 查询参数无效`、`<字段> 超出允许范围`、`检索词不能为空`、`<路径> 参数不符合工具约束`。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:37`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:55`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:60`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:55`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:82`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:125`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:215`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:226`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:240`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:108`。

分支与上下文：

| 错误码 | HTTP | 含义 | 来源 |
| --- | --- | --- | --- |
| `CONTEXT_EXPIRED` | 409 | contextId 不存在、不属于该账户或已过期（有效期 1 小时） | `backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:371-372` |
| `CONTEXT_MISMATCH` | 409 | 同时给出的 branchId 与 contextId 指向不同分支 | `backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:374-375` |
| `BRANCH_NOT_READY` | 409 | 分支尚未准备，无可发布内容版本（不会回退其他分支） | `backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:400-402` |
| `BRANCH_NOT_FOUND` | 404 | 分支不存在或非活动分支 | `backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:189` |
| `BRANCH_GRAPH_BUSY` | 409 | 仓库已有绑定其他版本的活动图谱任务 | `backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:74` |
| `BRANCH_CONTEXT_UNSUPPORTED` | 409 | 端点未接入分支上下文，拒绝静默回退到默认分支 | `backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:41-42` |

CodeGraph（在 MCP 中以 `isError=true` 文本 `<code>: <message>` 返回；在网页接口中按 `ApiExceptionHandler` 映射为 HTTP 状态）：

| 错误码 | 网页 HTTP | 含义 | 来源 |
| --- | --- | --- | --- |
| `CODEGRAPH_ARTIFACT_NOT_AVAILABLE` | 409 | 所选内容版本没有 `PUBLISHED` 产物 | `backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:179-181` |
| `CODEGRAPH_VERSION_MISMATCH` | 409 | 产物与请求内容版本不一致，或查询期间内容版本已更新 | `backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:184-185`、`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:242` |
| `CODEGRAPH_ARTIFACT_MISSING` | 409 | 产物目录不存在或超出受管目录 | `backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:189` |
| `CODEGRAPH_QUERY_FAILED` | 409 | 只读 CLI 查询执行失败 | `backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:231-232` |
| `CODEGRAPH_RESULT_TOO_LARGE` | 409 | 查询输出超过 200000 字符 | `backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:225-226` |
| `CODEGRAPH_DATABASE_NOT_AVAILABLE` | 409 | 产物中不存在可读取的 `codegraph.db` | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:25-26` |
| `CODEGRAPH_DATABASE_UNREADABLE` | 409 | 图数据库读取失败 | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:41-42` |
| `CODEGRAPH_DATABASE_LIMIT_EXCEEDED` | 409 | 节点超过 500000 或边超过 2000000 | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:57-58`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:83-84` |
| `CODEGRAPH_SYMBOL_NOT_FOUND` | 404 | 产物中找不到可定位的精确符号 | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:109-111` |
| `CODEGRAPH_CLI_OUTPUT_INVALID` | 422 | CLI 返回无法解析的 JSON | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:53-55` |
| `CODEGRAPH_IMPACT_SCHEMA_UNSUPPORTED` | 422 | impact 输出不含结构化 `affected` 列表 | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:87-89` |
| `CODEGRAPH_EXPORT_SCHEMA_UNSUPPORTED` | 422 | 图数据不含 `nodes` 与 `edges` | `backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:95-97` |
| `CODEGRAPH_IMPACT_QUERY_FAILED` | 503 | impact 查询执行失败 | `backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:159-160` |
| `CODEGRAPH_EXPORT_NOT_AVAILABLE` | 503 | 保留的映射分支，当前代码中未见抛出点（需人工确认） | `backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:63` |

任务失败码：`CODEGRAPH_TIMEOUT`（消息含「超时」）、`CODEGRAPH_BUILD_FAILED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:87`。

## 5 边界与非目标

- 全部 12 个工具都是只读：没有构建图谱、同步代码、修改知识或写文件的工具。证据：`backend/src/main/resources/mcp-tools.json:45-49`、`backend/src/main/resources/mcp-tools.json:511-515`。
- 图谱工具不触发分支准备或图谱构建，只读取已发布产物。证据：`mcp-server/README.md:9`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:57-60`。
- 平台采用手动访问令牌，不提供 OAuth 跳转；客户端必须能设置 `Authorization` 请求头。证据：`frontend/src/features/mcp/McpConfigPanel.vue:26`、`mcp-server/README.md:30`。
- 不提供服务端事件流（SSE）：`GET /api/mcp` 固定 405。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:39-42`。
- 服务端无会话状态：每个请求独立认证、独立解析上下文，不记忆上一次的工具或分支选择。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:22`。
- 工具不返回文件系统路径，也不接受文件系统路径；文件类参数限定为分支内相对路径。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:219-228`。
- `codegraph_affected` 返回的是受影响符号线索，不代表实际执行过的测试结果。证据：`mcp-server/README.md:88`、`backend/src/main/resources/mcp-tools.json:471`。
- 代码中未见针对 `/api/mcp` 的速率限制或并发配额实现；若部署层有网关限制，需人工确认。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/McpController.java:44-150`。

## 6 已知缺口

- Node 侧测试只实际调用了 12 个工具中的 2 个（`search_project`、`list_codegraph_scopes`）；`resolve_project_context` 与另外 9 个 `codegraph_*` 工具仅在清单断言中出现，未被调用级测试覆盖。证据：`mcp-server/test/server.test.mjs:22`、`mcp-server/test/server.test.mjs:57`、`mcp-server/test/server.test.mjs:92`、`mcp-server/test/server.test.mjs:119`。
- 后端 `McpCodeGraphToolsTest` 只有 2 个用例，覆盖 `list_codegraph_scopes` 与 `codegraph_search`；`codegraph_explore`/`node`/`callers`/`callees`/`impact`/`files`/`status`/`affected` 的参数拼装与净化没有专门的单元测试。证据：`backend/src/test/java/com/analyzercoder/application/mcp/McpCodeGraphToolsTest.java:73-84`、`backend/src/test/java/com/analyzercoder/application/mcp/McpCodeGraphToolsTest.java:106-130`。
- `McpToolServiceTest` 只覆盖 `search_project`；`resolve_project_context` 的分支必要性与错误分支没有独立测试。证据：`backend/src/test/java/com/analyzercoder/application/mcp/McpToolServiceTest.java:55-61`。
- `McpToolCatalog.validate` 不校验 `format: uuid`（只处理 `format: uri`），因此 UUID 合法性依赖 `UUID.fromString` 抛异常，错误码为 `BAD_REQUEST` 而非工具约束错误。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:93-99`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:40`。
- schema 未声明 `additionalProperties: false`，未知参数被静默忽略，客户端拼写错误不会得到提示。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:71-79`。
- `codegraph_node` 的 schema 只要求 `repositoryId`，「name 或 file 至少提供一个」由服务端运行期校验，客户端 schema 层面无法预知。证据：`backend/src/main/resources/mcp-tools.json:202-205`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:81-82`。
- `repositoryId` 的 `pattern` 强度不一致：`search_project` 使用严格 UUID 版本/变体校验，其余工具使用宽松的 36 位十六进制模式。证据：`backend/src/main/resources/mcp-tools.json:13`、`backend/src/main/resources/mcp-tools.json:30`。
- `codegraph_files` 与 `codegraph_status` 没有 `limit` 参数，输出规模仅靠 `CODEGRAPH_RESULT_TOO_LARGE` 兜底。证据：`backend/src/main/resources/mcp-tools.json:396-428`、`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:224-226`。
- `list_codegraph_scopes` 对每个分支单独查询一次产物（`latestContentVersion`），没有批量接口；项目/分支很多时可能产生大量查询。需人工确认是否需要批量化。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:173-181`。
- stdio 适配器的会话 Cookie 模式（`ANALYZER_SESSION_TOKEN` + `ANALYZER_CSRF_TOKEN`）能调用 `search_project` 与上下文解析，但图谱工具因后端白名单限制必然失败并返回 401；该模式与 README 中「图谱工具需提供访问令牌」的说明一致但易误用。证据：`mcp-server/src/api-client.mjs:26-31`、`mcp-server/src/server.mjs:82`。
- `mcp-server/README.md` 把 `codegraph_affected` 描述为「受影响测试线索」，而 `mcp-tools.json` 的标题是 `Analyze affected symbols`；两处表述不一致，需人工确认以哪一处为准。证据：`mcp-server/README.md:86`、`backend/src/main/resources/mcp-tools.json:470`。
- `ApiExceptionHandler` 保留了 `CODEGRAPH_EXPORT_NOT_AVAILABLE` 到 503 的映射，但当前代码中没有抛出该错误码的位置。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/ApiExceptionHandler.java:63`。
- 后端没有 `McpToolCatalog` 的独立单元测试文件；schema 校验分支（`anyOf`、`enum`、`maxItems`、`pattern`、`minimum`/`maximum`）主要依赖接口层测试间接覆盖。需人工确认是否需要补齐。证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolCatalog.java:40-105`。
