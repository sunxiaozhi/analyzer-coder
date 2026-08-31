# 代码智库系统设计文档

版本：v3.1
日期：2026-07-31
状态：当前实现设计基线
实现基准：1.0.0 发布候选工作区（基于 Git commit `61155a43101e39c0c1c92858b8a851151959fb01`）

> 本文解释当前代码如何运行。历史设计中尚未出现于当前路由、Controller、Service、Mapper 或数据库迁移的能力统一列入第 18 节，不作为现状架构。

## 1. 设计目标与约束

1. 代码快照是源码浏览、索引、检索、问答和图分析的事实边界。
2. PostgreSQL/pgvector 保存业务数据和可变维度向量；受管文件系统保存仓库副本、快照、CodeGraph 产物和附件。
3. CodeGraph 提供静态结构关系；LLM 只处理检索证据，不生成结构事实。
4. 普通账号只能访问 OWNER 或被授权仓库；超级管理员拥有全局管理入口。
5. 当前系统是单实例、单数据库、后台轮询 Worker 架构。
6. 当前没有 QuickStart、Onboarding、Backup/Restore、SSE QA 和任意多仓检索服务；跨仓只用于受治理的 Task Review 知识匹配。

## 2. 技术栈与运行拓扑

### 2.1 后端

- Java 17、Spring Boot 3.5。
- Spring MVC Controller + 应用服务 + 领域端口 + MyBatis Mapper。
- PostgreSQL 17 与 pgvector；Flyway 按版本执行初始化、多轮问答和可变向量维度迁移。
- PageHelper 提供分页。
- `HttpClient` 调用 OpenAI-compatible completion/embedding。
- `ProcessBuilder` 调用 Git 和 CodeGraph CLI。
- Spring `@Scheduled` 驱动内容索引和仓库删除 Worker。

### 2.2 前端

- Vue 3.5、TypeScript、Vite、Composition API、`<script setup>`。
- Pinia 管理登录、当前仓库和工作区页签。
- Vue Router 管理 8 个业务路由和登录页。
- Element Plus + Lucide 图标。
- highlight.js 负责源码高亮；marked + DOMPurify 负责 Markdown 预览。

### 2.3 运行拓扑

```text
Browser
  → Vue SPA
  → Spring MVC /api
      → Security Interceptor
      → Application Services
      → MyBatis Mappers → PostgreSQL + pgvector
      → Managed File System
      → Git CLI / CodeGraph CLI
      → OpenAI-compatible Provider（可选）
```

开发 `compose.yaml` 只启动绑定本机的 pgvector PostgreSQL；生产 `compose.prod.yaml` 编排 PostgreSQL、后端与前端/Nginx，也提供 systemd + 宿主机 Nginx 模板。

## 3. 后端模块边界

### 3.1 身份与权限

- `AuthController`：验证码、登录、当前会话、改密、退出。
- `AccountController`：账号分页、创建、编辑、重置、解锁和审计。
- `AccountPreferenceController`：当前仓库偏好。
- `AuthService`：密码规则、登录失败、会话、账号和审计事务。
- `CaptchaService`：失败计数与算术验证码。
- `SessionInterceptor`：公共路径、Cookie 会话、CSRF 和首次改密限制。
- `AccessControlService`：仓库可见范围、permission 与 capabilities。

### 3.2 仓库

- `RepositoryController`：本地 Git 登记、查询、重扫、编辑和删除入口。
- `RepositorySourceImportController`：远程 Git/GitLab 和 ZIP 同步导入。
- `RegisterRepositoryService`：仓库登记与快照发布。
- `RepositorySourceImportService`：clone、ZIP 解压、受管工作副本移动。
- `RepositoryEditingService`：名称、描述、默认分支和版本冲突。
- `RepositoryGovernanceService`：成员授权、撤权、所有权转移和逻辑删除。
- `RepositoryDeletionService/Worker`：墓碑领取和受管数据幂等清理。
- `RepositoryCodeBrowserService`：当前快照文件列表、路径保护、编码和预览限制。

### 3.3 索引、检索与智能能力

- `IndexJobService`：创建、查询、取消和重试任务。
- `IndexJobProcessor/Worker`：扫描快照、切分 chunk、替换内容、准备向量。
- `VectorIndexQueryService`：当前代码/知识向量汇总与分页明细。
- `IntelligenceService`：混合检索、问答、向量补建、基础图和知识卡片。
- `RetrievalQueryAnalyzer/RetrievalRanker`：查询分析与多通道融合。
- `AnswerCitationValidator`：校验模型答案中的 `[S编号]`。
- `CodeGraphService/ManagedCodeGraphService`：CLI 构建、产物登记和影响查询。
- `CodeGraphTaskService`：只把 CodeGraph 构建请求加入 `index_jobs`。
- `CodeGraphJobWorker/CodeGraphJobProcessor`：领取图谱任务，维护心跳、超时和取消，并执行复制、构建与发布。

### 3.4 模型与知识

- `LlmSettingsService`：问答模型、向量模型、连接检测、激活、外部生成和熔断。
- `OpenAiCompatibleClient`：非流式 completion、流式能力探针和 embedding。
- `LlmEndpointPolicy`：问答/向量端点规范化和地址限制。
- `LlmSecretCipher`：模型密钥加密与摘要。
- `KnowledgeAttachmentService`：附件校验、对象存储和下载。
- `KnowledgeCardHistoryService`：修订查询与恢复为新草稿。
- `MarkdownRenderingService`：服务端 Markdown 安全渲染。

## 4. 数据模型

### 4.1 身份与治理表

| 表 | 当前用途 |
| --- | --- |
| `accounts` | 账号、角色、状态、密码、失败次数、当前仓库和版本 |
| `login_sessions` | Token 摘要、CSRF、空闲和绝对有效期 |
| `login_captcha_challenges` | 验证码摘要和一次性使用状态 |
| `login_failure_counters` | 用户名维度失败计数 |
| `repository_permissions` | 非 OWNER 的 READ/MAINTAIN/MANAGE |
| `repository_governance_locks` | 仓库治理互斥记录 |
| `audit_events` | 登录、账号和仓库治理审计 |

### 4.2 仓库与任务表

| 表 | 当前用途 |
| --- | --- |
| `repositories` | 来源、分支、commit、快照、OWNER、状态和乐观锁版本 |
| `repository_deletion_tombstones` | 逻辑删除后的物理清理队列 |
| `index_jobs` | FULL/INCREMENTAL/CODEGRAPH 任务和重试关系 |
| `git_credentials` | 可复用的加密 Git HTTPS Token/GitLab PAT，按创建人隔离 |
| `repository_credential_bindings` | 仓库与凭据的用途绑定 |

当前没有独立 `code_snapshots` 表；快照标识和路径直接保存在 `repositories.current_snapshot_*`。

### 4.3 索引与图表

| 表 | 当前用途 |
| --- | --- |
| `code_chunks` | 当前仓库 chunk，绑定 snapshot、commit、文件、符号和行号 |
| `chunk_embeddings` | 代码 chunk 的可变维度 `vector`、维度、模型和摘要 |
| `code_graph_edges` | CodeGraph 导入的 source/target/relation |
| `codegraph_artifacts` | 仓库快照的 CLI 产物记录 |
| `knowledge_card_embeddings` | 已发布知识当前修订的可变维度 `vector` |

不同模型维度可同时存储；距离查询按当前模型和维度过滤，避免跨维度运算。

### 4.4 问答与知识表

| 表 | 当前用途 |
| --- | --- |
| `qa_conversations` | 多轮会话中的问答轮次记录 |
| `qa_citations` | 回答引用的代码/知识元数据 |
| `qa_conversations`、`qa_citations` | 多轮会话、轮次记录与完整证据快照 |
| `knowledge_cards` | 当前卡片修订和状态 |
| `knowledge_card_revisions` | 不可变修订历史 |
| `knowledge_code_refs` | 卡片与 chunk 的代码引用 |
| `knowledge_attachments` | 附件对象元数据和扫描状态 |
| `knowledge_card_attachment_refs` | 卡片修订与附件关系 |

### 4.5 模型配置表

- `system_settings`：兼容保留的系统键值设置；问答模型选择不再读取 `externalModelEnabled`。
- `encrypted_secret_versions`：问答/向量 API Key 密文、IV、摘要和算法。
- `llm_provider_configs`：多个问答模型备案。
- `llm_provider_runtime_states`：可用性、最后成功/失败和熔断状态。
- `llm_provider_activation`：旧版全局问答模型激活记录，数据库兼容保留，当前问答链路不再读取。
- `llm_connectivity_checks`：异步连接检测及阶段结果。
- `vector_model_configs/vector_model_activation`：向量模型备案和唯一激活项。

## 5. 身份与安全设计

### 5.1 登录链路

```text
POST /api/auth/login
  → 若失败计数 ≥ 3，校验 captchaId/captchaAnswer
  → 查账号、启用和 lockedUntil
  → PBKDF2 校验密码
  → 失败计数 +1；第 5 次设置锁定时间
  → 成功清零失败、创建随机会话、返回 CSRF
  → Set-Cookie AC_SESSION
```

会话 Token 只把 SHA-256 摘要存入数据库。Cookie 为 HttpOnly、SameSite=Lax、Path=/，Max-Age 与绝对会话时长一致；生产环境由 `APP_SESSION_COOKIE_SECURE=true` 设置 Secure。反向代理部署使用 framework forwarded-header 策略恢复客户端地址和原始协议。

### 5.2 请求鉴权

`SessionInterceptor` 仅放行健康检查、登录、验证码和错误页。修改请求必须携带 CSRF。首次改密账号只能访问 `/me`、改密和退出。

仓库接口使用 `AccessControlService.require` 或 `requireOwner`。超级管理员绕过仓库 permission；普通 OWNER 始终拥有最高仓库能力。

### 5.3 安全加固状态

已完成：敏感启动默认值移除、随机临时重置密码、旧会话撤销、强制首次改密、可配置 Secure Cookie、受信代理 Forwarded 头处理、数据库端口本机绑定和生产容器非 root 运行。

剩余安全债务：

1. 远程 Git 仍允许 HTTP，缺少完整 SSRF、重定向和 DNS 重绑定防护。
2. ZIP 只有路径、数量、大小、压缩比和部分魔数校验，没有恶意内容扫描。
3. 知识发布没有 MANAGE 级二次校验。
4. 生产密钥托管、证书签发轮换、日志集中化和备份恢复由部署环境负责，尚未接入平台能力。

## 6. 仓库生命周期

### 6.1 本地 Git

```text
POST /api/repositories
  → 校验账号与路径
  → Git inspector 读取分支、commit、工作区摘要
  → 文件系统适配器复制只读快照
  → 同事务登记仓库、OWNER 和 current snapshot 元数据
```

允许根由 `APP_REPOSITORY_ALLOWED_ROOTS` 配置。平台不执行 pull、checkout 或 reset。

### 6.2 远程 Git/GitLab

`POST /api/repository-imports/remote` 接收 name、url、branch、sourceType 和可选 credentialId。REMOTE_GIT 与 GITLAB 走同一 shallow clone 流程；凭据由 `/api/repository-credentials` 创建、更新和检测，Token 加密保存并通过临时 GIT_ASKPASS 注入。导入在请求线程内同步完成。

### 6.3 ZIP

`POST /api/repository-imports/zip` 上传 ZIP，解压到 staging，阻止规范化路径越界，限制条目、单文件和总解压量。解压后初始化 Git、创建导入提交，再移动到受管 worktree。

### 6.4 重扫与编辑

- `POST /repositories/{id}/rescan` 同步比较当前版本并按需发布新快照。
- `PATCH /repositories/{id}` 使用 repositoryVersion 乐观锁更新名称、描述和默认分支。
- 当前没有独立候选快照任务与进度 API。

### 6.5 删除

```text
DELETE /repositories/{id}
  → OWNER 校验与 ownershipVersion
  → repositories.status = DELETING
  → 写 deletion tombstone
  → 查询立即隐藏
  → RepositoryDeletionWorker 领取墓碑
  → 删除受管派生数据和目录
  → COMPLETE 或 FAILED 后重试
```

LOCAL_GIT 原始目录不在清理范围。

### 6.6 一键准备与项目画像

- `GET /api/repositories/{id}/profile` 返回当前准备状态、四阶段轨道和事实型项目画像，要求 READ。
- `POST /api/repositories/{id}/prepare` 幂等推进准备流程，要求 MAINTAIN：远程来源执行同步，本地/ZIP 执行重扫；快照变化后启动增量索引，无 chunk 时启动全量索引；内容就绪后构建当前快照 CodeGraph。
- 内容索引继续由 `IndexJobWorker` 后台执行，前端轮询子任务并再次调用 prepare 推进下一阶段；关闭页面不会取消已入队任务。
- 项目画像由 `RepositoryPreparationService` 聚合当前快照文件、`VectorIndexQueryService` 汇总和当前 `codegraph_artifacts`，不调用模型，不生成推测性架构结论。
- 当前尚无独立 preparation job 表；CodeGraph 阶段复用 `index_jobs`，由独立 Worker 异步执行。

### 6.7 项目资产与 Context Pack

`RepositoryAssetClassifier` 在扫描阶段按稳定路径和文件名将内容标为：

- `CODE`：可执行源码；
- `DOCUMENT`：README、设计、架构和一般说明；
- `RULE`：AGENTS/CLAUDE/CODEX 指令、贡献规范和 rules 目录；
- `TASK`：task、todo、roadmap、checklist 和 gate；
- `CONFIG`：构建、部署和运行配置。

`code_chunks.asset_type` 保存分类，V1 基线直接定义字段、约束和查询索引。项目画像按类型聚合并返回关键资产。

`ProjectContextPackService` 接收任务描述，优先召回规则和任务，再组合代码、文档与配置；结果受条目数和字符预算约束。每个条目携带当前 repository/snapshot/commit、路径、行号、chunkId 和 contentHash，供人或 Agent 校验来源。该服务只读，不包含自动执行和知识写回。

### 6.8 项目级架构地图

`ProjectArchitectureMapService` 从当前发布快照提取模块级架构，不依赖模型或尚未发布的工作区内容：

1. 以一级目录、monorepo 分组目录以及 `src` 下的常见分层目录归并模块。
2. 解析 Java package/import、TypeScript/JavaScript/Vue 的相对 import/require，以及 Python import。
3. 输出 `CONTAINS` 和 `DEPENDS_ON` 关系，并为依赖边保存有限来源样例。
4. 在受限数量和大小的代码、配置文件中识别 JDBC、Redis、MongoDB、Kafka、RabbitMQ、Elasticsearch、对象存储和 HTTP 客户端/端点，形成 `RESOURCE` 节点及 `CONNECTS_TO` 关系。
5. 资源定位信息只保留协议、类型或主机标识，统一去除凭据、路径、查询参数、片段和占位符，避免配置秘密进入架构响应。
6. 检测模块依赖环、domain/application 向外层实现的可疑依赖、领域模块直接连接运行资源，以及外部明文 HTTP。
7. 返回分析文件数、总代码文件数、大小/数量/编码跳过数和局限说明。动态生成的端点、依赖注入装配和真实运行调用仍需后续运行时数据补充。

接口保持只读并要求 READ。前端 `ProjectArchitectureMap` 在项目总览展示模块与运行资源画布、静态及运行连接方向、选中节点连接和风险详情；节点可切换为一跳聚焦视图，连接来源样例可直接打开当前快照文件。选中节点详情拆分为独立展示组件，路由导航仍由总览视图处理。
`ProjectArchitectureSymbolService` 先用架构地图校验模块，再以仓库、当前快照和模块范围查询轻量符号记录。分层模块复用架构层名规则，查询结果不携带代码正文，按符号与文件去重并受 200 条上限约束。前端请求状态封装在 `useModuleSymbols`，`ModuleSymbolDrawer` 只负责筛选、源码定位和发出调用图导航事件。


## 7. 源码浏览与预览

1. 列表从 `currentSnapshotPath` 递归读取普通文件，忽略符号链接。
2. 请求路径先统一分隔符、normalize，再验证不能绝对、不能 `..`、不能越出根目录。
3. 文件大于 `browser-max-file-bytes`（默认 2 MiB）拒绝。
4. 含 NUL 字节拒绝为二进制。
5. UTF-8 严格解码；BAT/CMD 失败时用 GB18030；其他编码失败。
6. 后端返回 snapshotId、路径、名称、语言、大小、行数和正文。
7. 前端 `RepositoryFileTree` 负责目录导航，`RepositoryFilePreview` 选择 Markdown 或代码预览。
8. `MarkdownPreview` 使用 marked + DOMPurify；`CodePreview` 使用 highlight.js，注册 batch/DOS 高亮。

## 8. 任务与内容索引

### 8.1 任务状态机

```text
QUEUED → RUNNING → SUCCEEDED
              ├→ FAILED
              └→ CANCEL_REQUESTED → CANCELED
```

`IndexJobService` 对同仓库任一非终态任务实行粗粒度互斥；因此内容索引和 CodeGraph 当前也互斥。retry 创建新任务并保存 retryOf。

### 8.2 内容索引处理

```text
IndexJobWorker
  → claimNextQueued
  → 校验仓库 currentSnapshot
  → RepositoryScannerPort.scan
  → 声明/Markdown 标题识别
  → 最多 120 行、20 行重叠切分
  → replaceRepositoryChunks
  → prepareRepositoryEmbeddings
  → SUCCEEDED(vectors-ready | vectors-degraded)
```

当前替换以仓库为粒度，没有独立内容索引版本头。`INCREMENTAL` 与 FULL 进入同一处理器，没有 diff 分支。

### 8.3 向量准备

`IntelligenceService` 根据激活模型、维度和检索能力查找缺失向量。LOCAL_HASH 生成确定性 64 维字符哈希投影，仅用于字符相似度；外部模型调用 `/embeddings` 并校验返回长度等于备案维度，才具备语义检索标识。外部失败时退回关键词和结构通道。

### 8.4 当前向量索引查询

- summary：当前仓库快照、commit、chunk/知识数量、缺失数量、模型、维度、检索能力和更新时间。
- chunks：按查询、EMBEDDED/MISSING、chunkType 分页。
- knowledge：按查询和 EMBEDDED/MISSING 分页。

页面只消费全局当前仓库，不提供内部仓库下拉，也不展示历史任务产物。

## 9. 混合检索

### 9.1 通道

- `CODE_KEYWORD`：代码正文、路径和符号关键词。
- `HEURISTIC_CALL_REFERENCE`：索引阶段由 `symbol(` 字符模式构建的启发式关系，不等同于 CodeGraph CLI。
- `CODE_CHARACTER_SIMILARITY`：LOCAL_HASH 代码字符相似度。
- `CODE_SEMANTIC`：外部 embedding 代码语义向量 cosine。
- `KNOWLEDGE_KEYWORD`：问答场景下的已发布知识。
- `KNOWLEDGE_CHARACTER_SIMILARITY`：LOCAL_HASH 知识字符相似度。
- `KNOWLEDGE_SEMANTIC`：外部 embedding 知识语义向量。

`hybrid-search` 不包含知识；`unifiedSearch` 用于问答并包含知识。

`hybrid-search` 返回 `hits + retrieval`。`retrieval` 记录查询时的当前快照、实际向量模型和能力类型、启用/不可用通道、各通道召回数量与耗时、融合召回数、总耗时及降级原因。问答将同一诊断写入每轮 `answer_payload`，因此历史回答保留当时的检索状态，而不是用当前配置反推。

### 9.2 融合与降级

`RetrievalRanker` 在内部对各通道赋权、去重；命中项返回 `score`、`lexicalScore`、`similarityScore`、`similarityKind` 和 `channels`。各通道独立记录成功或故障；向量补建/查询异常时关键词与结构通道继续工作，`retrieval.unavailableChannels` 同时返回稳定原因和安全截断的错误摘要。

## 10. 知识问答

### 10.1 当前调用链

```text
POST /repositories/{repoId}/ask
  → READ 鉴权
  → 校验请求携带的 modelConfigId
  → unifiedSearch(limit=10)
  → 无证据：INSUFFICIENT，不调用模型
  → 有证据：LlmSettingsService.generate(modelConfigId, prompt)
      → selected config AVAILABLE
      → breaker CLOSED
      → 凭据与私钥内容脱敏
      → Prompt 长度保护
      → OpenAI-compatible 非流式 completion
  → AnswerCitationValidator
      → 全事实段有合法引用：CITATION_COMPLETE
      → 部分事实段无引用：CITATION_INCOMPLETE
      → 非法：MODEL_OUTPUT_REJECTED + deterministic-local
      → 未调用/失败：DEGRADED + deterministic-local
  → insert qa_conversations + qa_citations
  → JSON Answer
```

### 10.2 证据 Prompt

Prompt 约束模型只能使用编号证据。总证据预算约 14000 字符，单项最多 2400 字符，要求中文回答并在事实句后标 `[S编号]`。api key、secret、password、token、访问密钥和私钥内容会被替换为脱敏占位符，而不是直接取消整次模型调用。

### 10.3 当前历史边界

每次请求生成或通过 clientRequestId 幂等复用一个 conversationId，并通过 threadId/turnNo 组织为多轮会话；历史按账号和当前仓库隔离，支持整段读取、重命名和删除。最近问题参与检索消歧，最近问答参与模型 Prompt，每轮仍持久化完整回答与引用快照；产品问答继续使用非流式 `generate`。

## 11. CodeGraph 与影响分析

`CodeGraphTaskService` 只创建 CODEGRAPH 排队任务并立即返回。`CodeGraphJobWorker` 排他领取任务，写入固定超时截止时间，并在快照复制和 CLI 等待期间持续更新心跳、检查取消。`ManagedCodeGraphService` 在独立目录运行 CLI，校验产物与当前快照仍一致后，通过 `CodeGraphArtifactPublisher` 的单一事务切换 `codegraph_artifacts` 发布状态；任何前置失败都不会退役旧产物。

影响分析以符号字符串为中心，深度 1–5。后端基础图方法支持 BOTH/UPSTREAM/DOWNSTREAM；当前 `/codegraph/impact` 和前端主要使用双向。风险按边数量分 LOW/MEDIUM/HIGH，限制固定说明反射和动态分派不完整。

当前没有符号候选、定义位置、模块聚合、图截断、历史产物选择和列表替代视图。

## 12. 知识卡片

### 12.1 卡片与修订

创建/更新先校验标题、正文、类型、状态、附件和代码引用，更新时保存历史修订。历史恢复通过复制旧修订内容形成新草稿，不改写旧记录。

当前类型：业务规则、技术决策、接口约定、模块说明。状态分为三条正交轴：发布状态 DRAFT/PUBLISHED/ARCHIVED，人工评审状态 UNREVIEWED/APPROVED/CHANGES_REQUESTED，来源版本状态 UNVERIFIED/CURRENT/STALE。保存正文只生成草稿和未评审修订；人工评审与发布是两个独立的 MANAGE 操作。检索只接纳已发布、人工通过且来源未过期的卡片。

### 12.2 Markdown 与引用

服务端 `MarkdownRenderingService` 生成消毒 HTML；前端预览再次使用 DOMPurify。代码引用绑定 chunk、快照、路径、符号、行号和摘要。知识引用可跳源码和图谱。

### 12.3 附件

附件先写 staging，检查扩展名、大小和部分魔数，再按 SHA-256 移动到仓库对象目录。下载使用仓库 READ 再鉴权。当前没有外部杀毒/内容扫描器和异步上传任务。

### 12.4 权限现状

Controller 创建/更新统一要求 MAINTAIN，服务层直接接受 CardInput.status。因此 MAINTAIN 当前可以发布，这是与目标治理模型的已知偏差。

## 13. 模型配置

### 13.1 问答模型

备案记录包含名称、OpenAI-compatible Base URL、模型、超时、最大 Token、Temperature、流式检测开关和密钥版本。密钥使用主密钥派生的加密服务保存；DTO 只暴露 `secretConfigured`。

保存或编辑后 runtime state 回到 UNTESTED。检测为 AVAILABLE 后即可在知识问答页面选择；问答模型不再需要全局激活。

### 13.2 连接检测

```text
POST connectivity-checks
  → 单飞键去重
  → 后台 executor
  → VALIDATE_CONFIG
  → RESOLVE_AND_AUTHORIZE_TARGET
  → CONNECT_TLS
  → AUTHENTICATE
  → GENERATE_MINIMAL
  → STREAM_FIRST_TOKEN（启用时）
  → CLASSIFY_AND_PERSIST
```

检测可取消，应用重启把中断检测置为失败。只有 runtime state 为 AVAILABLE 且熔断器为 CLOSED 的配置会在知识问答页面作为可用项。

### 13.3 运行期门控

`generate` 按请求中的模型配置 ID 查询配置，检查 AVAILABLE 和 breaker CLOSED，脱敏 Prompt 后调用模型。连续连接失败达到阈值后更新熔断状态；全局 `externalModelEnabled` 和问答模型激活记录不再参与运行期选择。

### 13.4 向量模型

LOCAL_HASH 不需要端点或密钥并固定为 64 维；OPENAI_COMPATIBLE 需要 Base URL、模型、Key 和 1–4096 的备案维度。模型卡片可独立执行 embedding 检测，切换前服务端再次检测。向量模型与问答模型使用不同选择机制。

## 14. REST API 基线

| 域 | 主要接口 |
| --- | --- |
| Auth | `/api/auth/captcha|login|me|change-password|logout` |
| Preferences | `/api/auth/preferences/current-repository` |
| Accounts | `/api/accounts`、`/page`、`/{id}`、reset、unlock、audit |
| Repositories | `/api/repositories`、page、detail、rescan、edit、delete |
| Imports | `/api/repository-imports/remote|zip` |
| Governance | `/api/repositories/{id}/governance/*` |
| Project memory | `/api/repositories/{id}/profile|prepare|context-pack|architecture-map` |
| Files/chunks | `/files`、`/files/content`、`/chunks` |
| Tasks | `/api/index-jobs/*`、`/repositories/{id}/index` |
| Vector index | `/repositories/{id}/vector-index/summary|chunks|knowledge` |
| Search/QA | `/hybrid-search`、`/ask`、`/graph-target` |
| CodeGraph | `/codegraph/build|latest|impact` |
| Knowledge | `/knowledge`、history、restore、attachments |
| Settings | `/api/settings`、`/api/settings/llm/*` |

当前错误响应为：

```json
{ "code": "STABLE_OR_GENERIC_CODE", "message": "用户可读说明", "timestamp": "..." }
```

`ApiExceptionHandler` 把安全异常映射指定状态；参数错误为 400；状态/数据冲突为 409；未处理异常为 500。当前没有 category、retryable、suggestedAction 和统一 requestId 字段。

## 15. 前端架构

### 15.1 路由与壳层

`WorkspaceShell` 负责导航、全局仓库、账号信息、工作区页签和 KeepAlive。正常登录完成后进入 `/ask`，直接访问根路径时重定向 `/overview`；管理员菜单由 `auth.isAdmin` 追加，路由守卫再次限制 admin 页面。

当前路由组件：

- 项目总览：`ProjectOverviewView`，组合结构、资产和 Context Pack 三个 feature 组件
- 仓库：`RepositoriesM0View`
- 索引：`UnifiedIndexJobsView`
- 源码检索：`ChunksM0View`
- 问答：`AskView`
- 图：`GraphView`
- 知识：`KnowledgeView`
- 账号：`AccountsView`
- 设置：`SystemSettingsView`

路由不可达的旧原型页面已删除；功能存在性只以 `router/index.ts` 中的真实路由及其 API 行为为准。

### 15.2 状态所有权

- `authStore`：当前账号、CSRF、恢复和退出；不持久化密钥。
- `repositoryStore`：可见仓库、服务端当前仓库偏好和最近任务。
- `workspaceTabs`：路由页签元数据，持久化到 localStorage。
- 问答消息：页面 composable 内存。
- 列表、表单和对话框：各 route/feature 组件局部状态。

### 15.3 组件边界现状

仓库、账号、问答、知识和索引已拆出部分 feature 组件。`SystemSettingsView` 和 `ChunksM0View` 仍同时包含编排、API 状态和大量展示，是当前维护性债务；后续拆分不得改变本文接口行为。

仓库准备与画像使用 `RepositoryPreparationDrawer`；准备轨道是流程状态主视觉，语言、一级目录和关键入口均来自当前快照统计。

## 16. UI 视觉与交互实现

1. 基础画布 `#f5f5f7`，内容和侧栏为白色，交互主色 `#0066cc`。
2. 桌面侧栏 204px，顶部栏 58px，工作区页签 44px，主内容紧凑留白。
3. 工作区页签为白色容器，激活项蓝底白字，支持水平滚动和管理菜单。
4. 页面使用细边框、6–10px 小圆角；模型/知识卡片和页签存在少量轻阴影。
5. 代码预览采用深色表面和独立行号列；Markdown 预览为浅色文档排版。
6. 响应式在 760px 以下折叠侧栏为顶部横向导航，部分多栏页面退化为单栏；源码预览在小屏隐藏右侧内容面。
7. UI 文案统一使用“源码检索”“调用图谱”“知识卡片”“系统设置”。
8. 未交付页面不进入导航。

根目录 `DESIGN.md` 是视觉参考来源之一，但包含面向 Apple 营销站的分析，不能覆盖本系统的数据密度、表格、代码和管理交互；实际 UI 权威以 `05-functional-ui-spec.md` 和当前样式为准。

## 17. 配置与运维边界

关键配置：

- `APP_DATASOURCE_URL/USERNAME/PASSWORD`
- `APP_INITIAL_ADMIN_USERNAME/PASSWORD`
- `APP_SESSION_IDLE_MINUTES/APP_SESSION_MAX_HOURS/APP_LOGIN_LOCK_MINUTES`
- `APP_SESSION_COOKIE_SECURE/APP_FORWARD_HEADERS_STRATEGY`
- `APP_REPOSITORY_ALLOWED_ROOTS/APP_MANAGED_DATA_ROOT`
- `APP_REPOSITORY_SNAPSHOT_MAX_FILES/MAX_TOTAL_BYTES/BROWSER_MAX_FILE_BYTES`
- `APP_CODEGRAPH_EXECUTABLE/APP_CODEGRAPH_TIMEOUT_MINUTES`
- `APP_LLM_MASTER_KEY/APP_LLM_ALLOW_INSECURE_LOCAL`
- `APP_CREDENTIAL_MASTER_KEY`
- `APP_LLM_CONNECTIVITY_TIMEOUT_SECONDS/APP_LLM_BREAKER_FAILURE_THRESHOLD`

数据库密码、初始管理员、仓库根、受管数据根和模型主密钥没有代码默认值，缺失时启动失败。生产模板进一步启用 Secure Cookie 和受信代理头处理。

## 18. 未实现设计与演进边界

以下能力不得从历史设计复制为当前实现：

1. QuickStartApplicationService 和 `/quick-start` API。
2. OnboardingApplicationService、路径、进度、笔记和投影表。
3. 问答 SSE 事件流、停止/断线恢复和流式 message 状态机。
4. 任意多仓检索、跨仓图聚合和版本化内容索引指针。
5. 仓库级外发策略、SSH 凭据和 GitLab 项目 API/Webhook。
6. 备份、恢复、维护模式、RPO/RTO。
7. 统一任务对导入、附件、删除、备份的完整覆盖。
8. CodeGraph 符号候选、结构证据、聚合与截断协议。

新增这些能力时，必须同步修改需求、设计、UI、迁移、接口、权限、错误和测试追踪，不得只新增页面或表结构。

## 19. 验证与测试

默认验证分成三层：`mvn test` 执行无外部依赖的单元/组件测试；`APP_RUN_POSTGRES_IT=true mvn -pl backend -Dtest='*IT' test` 在 PostgreSQL 17 + pgvector 上执行 Flyway、真实 MyBatis SQL、快照切换和仓库导入到问答链路；`npm test && npm run build` 执行关键路由测试、Vue 类型检查和生产构建。Linux CI 默认启动健康的 pgvector 服务并执行三层验证，另在 Linux 上以假可执行文件检查 CodeGraph CLI 参数和产物契约。

### 19.1 Task Review 的确定性 CI 投影

`CiCheckService` 不调用模型，也不重新读取可能已经变化的工作区。它读取已经完成并持久化的不可变 Task Review，先校验请求 Head 与审查 Head 完全一致，再把显式测试/审批回报投影成 `deterministic-ci-v1` 结果。

阻断集合固定为结构化禁止路径、直接证据产生的测试/审批义务、直接证据关联的 CRITICAL + REQUIRED 失效知识，以及经当前知识卡真实状态核对后仍缺失的必需知识新修订。图谱单独推断、检索候选、模型总结、未知项和 partial 标记只生成 Advisory。证据地址仅作为回报元数据，不替代状态判断；相同测试或审批存在冲突回报时采用较差状态。

知识同步不是调用方传入的布尔声明：服务端重新读取当前知识卡，只有修订号更高且同时为 `PUBLISHED + APPROVED + CURRENT` 才视为已处理。这样 CI 结果可以重复计算，也不会因模型措辞或调用方自报“已更新”发生漂移。

### 19.2 跨仓工程事实与知识匹配

`EngineeringProjectService` 管理工程项目、成员服务身份和双端契约证据。项目读取要求账号能够读取全部成员仓库，写操作要求对全部旧成员和新成员具有 MANAGE；仓库删除前也会检查活动工程项目。知识仍归属于原仓库，工程项目只提供受治理的适用边界，不复制知识正文。

契约不是自然语言声明。保存时，服务从双方当前 Snapshot 的内容 Chunk 精确读取登记路径，将全部 `startLine:contentHash` 排序后计算 SHA-256；Task Review 匹配时重新计算双方指纹，任一端缺失或变化都会把契约事实降为非当前。Snapshot UUID 保留为登记审计信息，但当前性由实际 Chunk 指纹决定。

`TaskReviewService` 先按创建者权限读取目标仓库可见的工程拓扑，再加载其中来源仓库的已发布知识。`KnowledgeScopeMatcher` 对跨仓字段使用显式 OR（仓库、服务、契约任一命中），并与仓库内路径/符号/模块范围做 AND；契约还要求目标变化路径精确等于登记证据路径。匹配原因使用 `REPOSITORY/SERVICE/CONTRACT`，来源使用 `PLATFORM_FACT`，与 Git、代码、CodeGraph、知识修订证据分开。

该链路不提供任意多仓搜索，也不从模型、README、向量或同项目成员关系自动生成服务/契约。当前数据库没有仓库级发布质量结果，因此 V13 准入只验证可证明的 Snapshot、权限和代码证据；真实 PostgreSQL 升级、多人权限和多仓数据规模仍属于部署验收。

### 19.3 追加式开发结果与反馈

`TaskReviewOutcomeService` 只接受已经完成且包含真实变化的不可变 Task Review。`task_review_outcomes` 保存一次具名回报的最终 Commit、摘要、测试/审批 JSON、请求摘要哈希和时间；`task_review_feedback` 逐条保存误报、漏报或知识更新判断。两张表禁止 UPDATE，允许随仓库生命周期级联删除；修正通过新的 clientRequestId 追加，不提供覆盖接口。

幂等边界是 `review_id + reported_by + client_request_id`。服务先对规范化请求序列化并计算 SHA-256，唯一冲突后只有哈希完全相同才返回原结果，否则返回稳定冲突。最终 Commit 只有与审查 Head 字符串完全相同时才标记精确绑定；其他 Commit 保留为报告人声明，不执行不可证明的祖先关系推断。

误报验证会回到原审查的结构化集合，目标不存在则拒绝；漏报因为描述的是缺失项，允许新增目标键但要求说明。知识更新判断必须绑定原审查中的适用/失效知识。查询时按原审查计算必须测试和审批的回报覆盖，反馈不调用知识服务、不改卡片状态，也不参与既有 CI 结果判定。

页面和 MCP 都使用同一 REST 服务。页面在审查证据主线后展示不可变结果账本和回报表单；MCP `report_task_outcome` 转发相同结构化字段、Session 与 CSRF，不创建第二套规则。

完成定义：接口、权限、持久化、失败路径、UI 状态和自动化测试同时与 `01-requirements.md` 当前基线一致。
