# 分支、快照与准备
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

本文覆盖"项目内按分支隔离代码版本"这一领域：分支的发现、跟踪与归档；不可变快照（snapshot，`snapshotId`）的发布与保留清理；阅读上下文（reading context，`contextId`）的创建、绑定与过期；以及把分支代码准备成可检索状态的准备任务（preparation）。

术语与对象关系：

- 分支（branch）：库表 `repository_branches`，稳定身份为 `branchId`，同一项目内按名称唯一。证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:2`
- 快照（snapshot）：库表 `branch_snapshots`，记录 `commit_sha` 与受管只读内容目录 `content_path`，发布后不可变。证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:17`
- 提交（commitSha）：Git 提交标识，发布快照时必须匹配 `[0-9a-fA-F]{40,64}`。证据：`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:75`
- 受管快照（managed snapshot）：平台在受管数据根下导出的只读代码副本，路径形如 `<snapshot-root>/<repositoryId>/branch-<snapshotId>/content`。证据：`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:78`
- 阅读上下文（reading context）：库表 `branch_read_contexts`，把"账号 + 项目 + 分支 + 快照"绑定为一个可过期的读取坐标，绝不是可变的全局分支开关。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchReadContext.java:8`
- 准备（preparation）：把某个快照推进到"可检索"的一串阶段与后台任务；分支级准备由 `branch_preparation_jobs` 驱动，项目级准备仍保留 `index_jobs` 路径。

权限模型（不使用四级权限的说法）：权限级别只有 `READ` < `MAINTAIN` < `MANAGE`；所有者（owner）关系由 `owner_account_id` 表达，账号等于 owner 时按 `MANAGE` 处理，仅所有者动作走 `requireOwner`（403 `OWNER_REQUIRED`）；角色只有 `SUPER_ADMIN` 与普通用户 `NORMAL`，超级管理员绕过仓库级权限。证据：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:30`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:46`、`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4`

## 2 需求条目

### BRN-001 分支身份与跟踪

- 需求：分支是项目内的稳定身份；远程分支必须先显式跟踪，平台不会自动跟踪或索引全部远程分支。
- 规则：
  - 跟踪需要 `MAINTAIN`；插入 `repository_branches` 时使用 `ON CONFLICT(repo_id,name) DO NOTHING`，重复跟踪返回既有分支（幂等）。
  - 同名分支已处于 `ARCHIVED` 时拒绝跟踪，返回 409 `BRANCH_ARCHIVED`，要求先显式恢复，避免继承错误的历史身份。
  - 项目创建时由数据库触发器/迁移按 `COALESCE(default_branch,'WORKSPACE')` 播种一个默认分支。证据见 BRN-009。
  - 分支的 `generation` 字段用于乐观并发控制，归档、恢复与准备都会使其加一。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:100`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:109`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:11`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:13`

### BRN-002 分支名称校验

- 需求：分支名进入 Git 命令与数据库前必须通过统一校验，防止参数注入与非法引用。
- 规则：拒绝空、纯空白、长度超过 200、以 `-` 或 `/` 开头、以 `/` 或 `.` 结尾、包含 `..`、`@{`、`//`、等于 `@`、包含 ASCII 控制字符（`<=32`、`127`）或 `~^:?*[\`、以及任一段以 `.` 开头或以 `.lock` 结尾的名称。违反时报 400「Git 分支名称无效」。
- 证据：`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:38`

### BRN-003 分支列表与就绪状态

- 需求：分支列表返回每个分支的已发布快照、提交、准备状态、错误与跟踪状态。
- 规则：
  - 需要 `READ`。
  - 按分支名升序返回；无已发布快照的分支 `snapshotId` 与 `commitSha` 为 `null`。
  - 字段：`id`、`name`、`snapshotId`（= `published_snapshot_id`）、`commitSha`、`status`（= `preparation_status`）、`error`（= `preparation_error`）、`generation`、`trackingStatus`、`archivedAt`。
  - `preparation_status` 取值 `PENDING`/`BUILDING`/`READY`/`FAILED`；`tracking_status` 取值 `ACTIVE`/`ARCHIVED`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:66`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:77`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:8`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:4`

### BRN-004 远程分支发现

- 需求：可列出远程仓库的分支清单，但发现本身不建立跟踪、不拉取代码、不创建任何快照。
- 规则：
  - 需要 `MAINTAIN`；项目必须配置 `remote_url`，否则 400「当前仓库未配置远程地址」；地址还需通过目标安全策略（HTTPS、443、非内网）。
  - 使用 `git ls-remote --heads` 且 `http.followRedirects=false`，超时 60 秒，命令中带 `--` 分隔符。
  - 最多返回 2000 条分支，超出报错要求清理无用分支；输出行必须形如 `<40-64 位十六进制> refs/heads/<名称>`，名称再经 BRN-002 校验；结果按名称升序。
  - 使用仓库已绑定凭据（无绑定则匿名）。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchRemoteService.java:34`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:23`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:39`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:56`、`backend/src/main/java/com/analyzercoder/application/branch/BranchRemoteService.java:50`

### BRN-005 分支归档与恢复

- 需求：归档只停止该分支的新上下文与新任务，不删除任何历史证据；恢复是显式动作。
- 规则：
  - 归档与恢复都需要 `MANAGE`。
  - 归档条件：分支处于 `ACTIVE` 且该分支没有 `QUEUED`/`RUNNING` 的准备任务；否则分别返回 409 `BRANCH_HAS_ACTIVE_TASK` 或 404 `BRANCH_NOT_FOUND`。
  - 归档写入 `tracking_status='ARCHIVED'`、`archived_at`、`archived_by`，并使 `generation` 加一。
  - 恢复要求当前状态为 `ARCHIVED`，写回 `ACTIVE` 并清空 `archived_at`/`archived_by`，`generation` 加一；否则 404 `BRANCH_NOT_FOUND`。
  - 归档分支的快照、片段、问答与知识证据全部保留（见 BRN-021 的引用检查）。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:122`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:136`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:149`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:115`

### BRN-006 快照发布与受管内容路径

- 需求：准备时把某个提交导出为只读受管副本，并登记一条不可变的 `branch_snapshots` 记录。
- 规则：
  - 导出使用 `git archive --format=zip <commit>`，绝不 checkout 或修改用户工作区。
  - 导出前用 `git ls-tree -r -l` 预检：包含符号链接（模式 `120000`）或子模块（`160000`）直接拒绝；文件数超过 `snapshot-max-files`（默认 20000）或累计字节超过 `snapshot-max-total-bytes`（默认 2 GiB）拒绝。
  - 解压时拒绝越界路径、反斜杠、冒号、绝对路径与任何名为 `.git` 的路径段；解压后再次校验文件数与字节数。
  - 导出完成后删除中间 `source.zip`，并把内容目录整棵置为不可写。
  - 发布事务内依次写入 `branch_snapshots`、按分支同步 Markdown 来源清单、批量写入 `code_chunks`，最后以 `generation` + `preparation_status='BUILDING'` 为条件更新 `published_snapshot_id` 与 `preparation_status='READY'`；条件不满足时报 409 `BRANCH_BUILD_SUPERSEDED`。
  - 单个快照的片段数上限为 100000，空内容报错「分支没有可索引的文本文件」。
  - 快照内容与片段不可改写或删除：数据库触发器禁止删除或更新属于有效快照的 `code_chunks`。
- 证据：`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:73`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:84`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:103`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:135`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:285`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:322`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:280`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:110`

### BRN-007 同一提交复用快照

- 需求：同步时若分支提交未变，复用已发布快照，不重复导出、不重复建索引。
- 规则：
  - 判定条件：分支已有 `published_snapshot_id` 且解析出的提交号与该快照的 `commitSha` 相等。
  - 复用时只更新 `preparation_status='READY'` 与 `last_synced_at`，并把该既有快照解析为阅读上下文返回。
  - 数据库层面另有一层保护：`branch_preparation_jobs` 对 `(branch_id, kind)` 在 `QUEUED`/`RUNNING` 上建唯一索引，避免同一分支同一类型重复入队。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:99`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:103`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:115`、`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:19`

### BRN-008 失败时保留上一次成功快照

- 需求：准备失败只改变分支的准备状态与错误信息，不得使分支失去上一次成功发布的快照。
- 规则：
  - 失败时仅更新 `preparation_status='FAILED'` 与固定的中文 `preparation_error`，且更新条件带 `generation`，因此不会覆盖被新任务接管的记录。
  - `published_snapshot_id` 在整个失败路径中不被修改。
  - 已创建但未发布的受管目录会被显式清理（`discardUnpublished`），且清理目标必须是该工厂生成的标准路径。
  - 分支级准备错误文案为「准备失败，请确认分支存在且仓库来源或凭据可用」；显式同步任务为「同步失败，请检查分支、代码源、权限或凭据」；后台作业记录为「任务失败，请检查分支、仓库权限、凭据或向量模型配置后重试」。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:336`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:338`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:145`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:166`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:313`

### BRN-009 项目当前快照指针与默认分支镜像

- 需求：仓库级 `repositories.current_snapshot_id` 与分支级 `published_snapshot_id` 是两条独立通道，默认分支由触发器"镜像"，但修改默认分支本身不发布任何代码。
- 规则：
  - `V3` 建立 `repository_branches`/`branch_snapshots`，并把既有 `repositories.current_snapshot_id` 与 `current_snapshot_path` 回填为默认分支的快照，同时把默认分支置 `READY`。
  - `V7` 引入触发器 `repositories_default_branch_snapshot`：`AFTER INSERT OR UPDATE OF current_snapshot_id,current_snapshot_path,current_commit,default_branch`，按 `COALESCE(default_branch,'WORKSPACE')` 确保分支存在，并在分支为 `ACTIVE` 时把仓库当前快照登记为该分支的快照、置 `READY`；已存在的快照 id 不覆盖（`ON CONFLICT(id) DO NOTHING`）。
  - `V8` 重建同一触发器，触发列中去掉 `default_branch`，即"只改默认分支"不再触发镜像；注释明确"仅改变阅读偏好不得发布或创建任何分支代码"。
  - 分支快照的发布不写入 `repositories.current_snapshot_id`（回归测试断言分支发布后该字段仍为 `NULL`），因此分支索引与仓库级（单版本）索引互不影响。
  - 分支快照的 `commit_sha` 取 `COALESCE(current_commit, worktree_digest, 'WORKSPACE')`，当来源隐藏 Git 版本（ZIP）时即退化为工作区摘要。
- 证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:13`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:36`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:59`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:20`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:22`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:47`、`backend/src/test/java/com/analyzercoder/application/branch/BranchCodeOperationsDatabaseTest.java:311`

### BRN-010 阅读上下文创建与绑定项

- 需求：读取接口必须携带明确的项目 + 分支 + 快照坐标，禁止隐式回退到默认分支。
- 规则：
  - 解析/创建入口为 `POST /api/repositories/{repositoryId}/contexts`，请求体 `{branchId, contextId}`；需要 `READ`。
  - 只传 `contextId` 时按 `id + repo_id + account_id + expires_at > now` 查询；任一不满足返回 409 `CONTEXT_EXPIRED`；若同时给了 `branchId` 且与上下文不一致，返回 409 `CONTEXT_MISMATCH`。
  - 只传 `branchId` 时要求该分支 `tracking_status='ACTIVE'` 且已有 `published_snapshot_id`，否则 409 `BRANCH_NOT_READY`（提示不会使用其他分支的数据）。
  - 创建时写入 `branch_read_contexts(account_id, repo_id, branch_id, snapshot_id, expires_at)`，有效期 3600 秒；上下文中不存储 `content_path` 与 `commitSha`，二者在解析时从 `branch_snapshots` 联表取得。
  - 创建上下文时同时快照该分支适用的知识卡（`PUBLISHED` 且 `APPROVED` 且分支范围匹配）到 `branch_context_knowledge`，使阅读过程中的知识集合固定。
  - 内部另有"不落库"的临时上下文（用于指定历史快照做索引），有效期同为 3600 秒，并要求快照所属分支为 `ACTIVE` 且项目未删除，否则 404 `BRANCH_SNAPSHOT_NOT_FOUND`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:108`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:371`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:397`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:412`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:164`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:30`

### BRN-011 上下文过期与清理

- 需求：过期上下文既不能使用，也不长期占用存储。
- 规则：
  - 所有上下文读取都要求 `expires_at > CURRENT_TIMESTAMP`，过期即 409 `CONTEXT_EXPIRED`。
  - 提供批量清理：删除所有 `expires_at <= CURRENT_TIMESTAMP` 的行。
  - `branch_read_contexts` 上有失效时间索引；问答记录通过外键 `ON DELETE SET NULL` 保留历史。
  - 上下文删除不删除快照，快照保留判定会把"仍有效的上下文"计为引用（见 BRN-020）。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:38`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:39`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:78`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:101`

### BRN-012 X-Branch-Context 请求头与拦截范围

- 需求：显式上下文绝不能被旧接口静默忽略；不支持的端点在收到该头时必须拒绝，而不是回退到默认分支。
- 规则：
  - 请求头名固定为 `X-Branch-Context`，值为 `contextId`。
  - 拦截器注册在 `/api/**` 上；仅当请求携带该头时才做判断。
  - 路径必须匹配 `^/api/repositories/<36 位 UUID>(/.*)$`，且"方法 + 剩余路径"命中支持清单才放行；否则抛 409 `BRANCH_CONTEXT_UNSUPPORTED`，消息为「该操作尚未接入分支上下文，不会回退到默认分支」。
  - GET 支持清单：`/branch-overview`、`/hybrid-search`、`/evidence-search`、`/code-evidence-context`、`/files`、`/files/content`、`/files/raw`、`/codegraph/explore`、`/codegraph/latest`、`/codegraph/impact`、`/qa/records`、`/qa/records/{id}`、`/knowledge`、`/knowledge/markdown-sources`、`/chunks/{id}/graph-target`。
  - POST 支持清单：`/ask`、`/codegraph/build`、`/knowledge`、`/knowledge/markdown-sources/generate`、`/knowledge/markdown-sources/generate-pending`。
  - PATCH 与 DELETE 只支持 `/qa/records/{id}`；其它方法（含 PUT）一律视为不支持。
  - 实际消费该头的控制器：`BranchOverviewController`（分支总览，无上下文时 400「分支总览需要明确的阅读上下文」）、`RepositoryCodeBrowserController`（文件树/内容/原图）、`CodeGraphController`、`CodeEvidenceContextController`、`MarkdownKnowledgeSourceController`、`IntelligenceController`（混合检索、证据检索、问答、问答历史、图谱目标、知识卡列表与创建）。
  - 因此 `GET /api/repositories/{id}/profile`、`/code-facts`、`/health-overview`、`/graph`、`PUT /knowledge/{cardId}` 等端点带上该头会被 409 拒绝，这是有意设计。
- 证据：`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:11`、`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:37`、`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:41`、`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:46`、`backend/src/main/java/com/analyzercoder/security/WebSecurityConfig.java:23`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchRequestContext.java:19`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchOverviewController.java:21`、`backend/src/test/java/com/analyzercoder/security/BranchContextInterceptorTest.java:34`

### BRN-013 分支索引状态字段

- 需求：为每个分支（或指定快照）给出内容索引、代码图谱与向量索引的就绪位。
- 规则：
  - 批量接口 `GET /branch-index-statuses` 需要 `READ`；指定快照接口 `GET /branches/{branchId}/index-status?contextId=` 先用上下文解析，因此同时要求 `READ` 与有效上下文。
  - `contentReady` = `branch_snapshots.content_indexed_at IS NOT NULL`。
  - `graphReady` = 该快照存在 `codegraph_artifacts.status='PUBLISHED'` 的产物。
  - `vectorsReady` = 该快照有片段，且不存在"缺少当前激活模型（model/dimension/retrieval_capability 三者匹配）向量"的片段；无激活模型配置时按 `local-hash-64`/64/`CHARACTER_HASH` 兜底。
  - `syncedAt`：若该快照就是已发布快照，取 `COALESCE(last_synced_at, snapshot.created_at)`，否则取快照自身创建时间。
  - MCP 项目列表另有一个 `codegraphReady` 字段，定义为 `archivedAt == null && 存在产物 && 产物 status == 'PUBLISHED'`，即归档分支即使有产物也报未就绪。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:205`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:227`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:233`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:240`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:177`

### BRN-014 分支任务类型与语义

- 需求：显式分支操作按语义拆分为同步与索引三类，加上一键准备与向量索引。
- 规则：
  - `POST /branches/{branchId}/code-jobs` 接受 `kind` 属于 `SYNC`、`CONTENT`、`GRAPH`、`PREPARE`，其它值报 400「不支持的分支操作」。
  - `SYNC`：只做拉取/解析提交并发布快照，不建内容索引、不建图谱。
  - `CONTENT`：必须携带 `contextId`，在指定快照上建内容索引（不拉 Git）；缺少时报 400「索引操作需要已同步的分支快照」。
  - `GRAPH`：必须携带 `contextId`，在指定快照上构建代码图谱；构建前置要求该仓库没有其它活动索引任务，否则 409 `BRANCH_GRAPH_BUSY`。
  - `PREPARE`：不允许携带 `contextId`，语义等于 `SYNC` + `CONTENT` + `GRAPH`；带上下文时报 400「同步操作不能指定历史阅读上下文」。
  - `POST /branches/{branchId}/prepare` 是等价的历史入口，固定以 `kind=SNAPSHOT` 入队（快照 + 内容索引 + 图谱一体化路径）。
  - `POST /branch-vector-jobs`（`kind=VECTORS`）必须携带 `contextId`，且要求该快照已完成内容索引（`content_indexed_at` 非空且存在片段），否则 400「请先构建此分支快照的内容索引」。
  - 所有提交入口都需要 `MAINTAIN`，成功返回 202 Accepted 与作业视图。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:52`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:81`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:96`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:101`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:111`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:115`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:327`、`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:74`

### BRN-015 准备任务的阶段与状态流转

- 需求：作业与阶段可被外部观察，阶段推进必须可追踪且可发现失控任务。
- 规则：
  - 作业状态：`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`。
  - `SYNC`/`CONTENT`/`GRAPH` 路径的阶段依次为 `RESOLVING` →（同步时）`SNAPSHOT` → `PUBLISHING` → `INDEXING` → `GRAPH` → `COMPLETED`，实际阶段取决于 kind 组合。
  - `SNAPSHOT` 路径的阶段依次为 `RESOLVING` →（快照阶段）`SNAPSHOT` → `INDEXING` → `PUBLISHING`，成功时写 `SUCCEEDED`/`COMPLETED`。
  - `VECTORS` 路径的阶段为 `RESOLVING` → `EMBEDDING` → `COMPLETED`。
  - 每个阶段更新都以 `id + attempt_token + status='RUNNING'` 为条件，条件不满足抛 409 `BRANCH_BUILD_SUPERSEDED`（「准备任务已被接管」），以此隔离丢失的 Worker。
  - 内容索引任务本身是幂等的：进入发布事务后再次检查 `content_indexed_at`，已索引则直接返回；且只有该快照仍是分支的已发布快照时才改写 Markdown 来源清单。
  - 数据库中 `kind` 允许 `SNAPSHOT`、`SYNC`、`CONTENT`、`GRAPH`、`VECTORS`、`PREPARE`；除 `SNAPSHOT`/`SYNC`/`PREPARE` 外都必须有 `target_snapshot`。
- 证据：`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:6`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:12`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:14`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:223`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:336`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:423`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:96`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:136`

### BRN-016 任务去重与并发控制

- 需求：同一分支同一类型的任务不重复执行；不同分支可并行，同一分支串行。
- 规则：
  - 入队前先锁分支行（`FOR UPDATE OF b`）并确认分支 `ACTIVE` 且项目未软删除，否则 404 `BRANCH_NOT_FOUND`。
  - 若该分支该类型已有 `QUEUED`/`RUNNING` 任务则不再插入，直接返回既有任务；若既有任务的目标快照与本次请求不同，返回 409 `BRANCH_VECTOR_BUSY`（「该分支已有其他快照的向量任务，请等待其完成」）。
  - 数据库层用部分唯一索引 `(branch_id, kind) WHERE status IN ('QUEUED','RUNNING')` 兜底。
  - Worker 由固定线程池驱动，并发度取 `app.repository.branch-concurrency`（默认 2，夹在 1–8 之间），轮询间隔 `app.repository.branch-poll-interval-ms`（默认 2000 毫秒）。
  - 认领时按分支 UUID 取 `pg_try_advisory_lock` 会话锁：取不到锁的分支本轮跳过，因此同分支串行、跨分支并行；Git 与文件系统工作期间不持有数据库事务。
  - 单次认领最多扫描 32 条候选；`QUEUED` 或"`RUNNING` 且超过 1 小时未更新"的任务都可被认领（超时回收）。
  - 认领成功后写入新的 `attempt_token`，后续所有阶段更新都以该 token 为栅栏。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:134`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:151`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:181`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:190`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:199`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:221`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:391`、`backend/src/main/java/com/analyzercoder/worker/BranchPreparationWorker.java:22`、`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:19`

### BRN-017 任务失败与重试

- 需求：失败任务留下可诊断的错误，并且可以重新提交；失败不会污染历史快照。
- 规则：
  - 执行异常时把作业置 `FAILED`/`stage='FAILED'` 并写固定错误文案，更新条件同样带 `attempt_token` 与 `RUNNING`，因此被接管的旧 Worker 不会覆盖新状态。
  - 失败后该分支该类型不再有活动任务，重新提交会创建新作业；失败作业保留在历史中。
  - 该准备路径失败不影响 `published_snapshot_id`（见 BRN-008）。
  - 分支作业本身没有取消接口；可取消的是仓库级 `index_jobs` 与远程导入作业。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:307`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:90`、`backend/src/test/java/com/analyzercoder/application/branch/BranchPreparationJobsTest.java:99`

### BRN-018 任务历史与分页

- 需求：既能看"每分支每类型的最新任务"，也能分页翻查完整历史。
- 规则：
  - `GET /branch-preparation-jobs` 需要 `READ`，按 `(branch_id, kind)` 取最新一条（`DISTINCT ON` + 创建时间/id 倒序）。
  - `GET /branch-preparation-jobs/history` 需要 `READ`，支持可选 `branchId` 过滤与 `pageNum`/`pageSize`（1–100，越界报 400），按 `created_at DESC, id DESC` 排序，返回统一分页结构。
  - 作业视图字段：`id`、`branchId`、`status`、`stage`、`error`、`kind`、`snapshotId`（= `target_snapshot`）。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:57`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:77`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:184`、`backend/src/test/java/com/analyzercoder/application/branch/BranchTaskHistoryTest.java:33`

### BRN-019 项目级准备与阶段重试

- 需求：单版本（非分支）项目保留一条"一键准备"路径，按阶段推进并可对单个阶段重试。
- 规则：
  - `POST /repositories/{id}/prepare` 需要 `MAINTAIN`：若已有活动索引任务直接返回其视图；否则远程来源先同步、其它来源先重扫；有变化则启动 `INCREMENTAL`，无片段则启动 `FULL`，存在缺向量且上次修复未降级则启动 `INCREMENTAL`，缺少已发布 CodeGraph 则启动 `CODEGRAPH`，缺少当前快照的知识失效检查则启动 `KNOWLEDGE_DRIFT`。
  - `GET /repositories/{id}/profile` 需要 `READ`，返回五个阶段 `snapshot`、`content`、`vectors`、`graph`、`knowledge_drift` 的状态与详情，整体状态取 `READY`/`DEGRADED`/`PROCESSING`/`ACTION_REQUIRED`/`NOT_READY`，进度按每阶段 20 分累加。
  - `POST /repositories/{id}/prepare/stages/{stageKey}/retry` 需要 `MAINTAIN`，`stageKey` 取 `snapshot`、`content`、`vectors`、`graph`、`knowledge_drift`（大小写不敏感）；`graph` 要求先有内容索引，`knowledge_drift` 要求当前快照已有 CodeGraph，未知阶段报 400。
  - 该路径操作的是仓库当前快照（`repositories.current_snapshot_id`），与分支快照解耦（见 BRN-009）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:133`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:142`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:151`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPreparationService.java:75`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPreparationService.java:127`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPreparationService.java:181`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPreparationService.java:246`

### BRN-020 快照保留检查

- 需求：删除快照前必须逐类检查持久引用，任何一类非零都不得清理。
- 规则：
  - 检查接口需要 `MANAGE`；快照必须属于指定的项目与分支，否则 404 `SNAPSHOT_NOT_FOUND`。
  - 检查六个维度并返回计数：发布指针（`repository_branches.published_snapshot_id`）、活动上下文（未过期的 `branch_read_contexts`）、任务引用（`branch_preparation_jobs.target_snapshot` 处于 `QUEUED`/`RUNNING`，加上 `index_job_branch_targets.snapshot_id`）、问答引用（`qa_conversations`）、知识引用（`knowledge_code_refs`、`knowledge_branch_validations`、`knowledge_card_markdown_source_links.source_snapshot_id`）、Markdown 来源（`repository_markdown_sources`）。
  - `removable` 仅当全部计数之和为 0。
  - 检查同时返回 `contentPath`，供调用方展示受管目录。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:42`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:87`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:97`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:117`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:134`、`backend/src/test/java/com/analyzercoder/application/branch/BranchArtifactRetentionServiceTest.java:49`

### BRN-021 快照删除

- 需求：删除快照是显式的垃圾回收动作，必须二次检查引用并清理全部派生数据。
- 规则：
  - 删除接口需要 `MANAGE`；先带 `FOR UPDATE OF s` 重新执行一次完整引用检查；若仍被引用则 409 `SNAPSHOT_REFERENCED`（「快照仍被分支、活动上下文、任务、问答或知识引用，不能清理」）。
  - 删除顺序：先删 `branch_snapshots` 行（删除不匹配时报 409 `SNAPSHOT_CHANGED`），再删该快照的 `codegraph_artifacts` 与 `code_chunks`——先移除不可变身份，正是为了让片段保护触发器放行这次显式清理。
  - 数据库层删除后，再删除受管内容目录；清理目标必须严格等于该快照工厂生成的标准路径，否则拒绝。
  - 需要 `BranchArtifactRetentionService` 可用（控制器以可选注入方式持有）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:121`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:131`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:48`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:60`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:76`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:33`

### BRN-022 ZIP 单版本与 Git 多分支的差异

- 需求：同一套分支/快照模型下，ZIP 来源表现为"单版本"，Git 来源表现为"多分支"。
- 规则：
  - ZIP 项目注册时清空 `default_branch` 与 `current_commit`，因此平台按 `COALESCE(default_branch,'WORKSPACE')` 播种的默认分支名为 `WORKSPACE`，镜像快照的 `commit_sha` 退化为工作区摘要。
  - ZIP 项目没有 `remote_url`，因此分支发现与远程拉取不可用（400「当前仓库未配置远程地址」）；分支同步回退到在受管工作副本内 `rev-parse refs/heads/<分支名>`。
  - 前端对 ZIP 项目渲染"单版本代码"操作区：更新代码版本走重扫、内容索引走项目级索引任务、图谱走项目级 CodeGraph 构建，不使用分支任务。
  - MCP 的项目列表会跳过 `sourceType == ZIP` 的项目，因此 ZIP 项目不出现在分支型 MCP 工具输出中。
  - Git 来源（含远程）支持分支发现、跟踪、按分支快照与分支级准备；单分支的本地/远程项目同样先有一个默认分支。
- 证据：`backend/src/main/resources/mappers/RepositoryMapper.xml:92`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:14`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:227`、`backend/src/main/java/com/analyzercoder/application/branch/BranchRemoteService.java:52`、`frontend/src/features/repositories/SingleVersionOperations.vue:21`、`frontend/src/features/repositories/SingleVersionOperations.vue:22`、`frontend/src/features/repositories/SingleVersionOperations.vue:23`、`backend/src/main/java/com/analyzercoder/application/mcp/McpCodeGraphTools.java:158`

### BRN-023 前端分支与准备界面

- 需求：前端提供分支列表/发现/跟踪/归档、分支快照与索引状态、准备操作、任务历史与阅读上下文切换。
- 规则：
  - 分支工作区装配分支管理、列表、详情与跟踪弹窗；分支表按"`ACTIVE` + `READY` + 有快照 + `contentReady`"判定可读，任务进行中禁用操作，并提供隐藏/显示已归档分支的开关。
  - 分支详情面板展示 `contentReady`/`graphReady`/`vectorsReady`，按就绪位门禁"打开代码""打开图谱"，并把重建内容索引/图谱/向量作为高级操作；"一键准备"提交 `kind=PREPARE`。
  - 任务历史组件按分支分页调用历史接口；任务中心另有分支任务页签，并发拉取分支列表与历史并以约 2.5 秒轮询。
  - 阅读上下文存储于 Pinia store：`localStorage` 键为 `analyzer-coder:branch:<repositoryId>`，选择分支时先清空上下文，仅当分支 `READY` 才 `POST /contexts`；恢复优先级为"路由指定 > localStorage > 默认分支 > 首个 READY > 首个 ACTIVE"。
  - 请求头构造：无 `contextId` 时不带 `X-Branch-Context`；带上后用于分支总览、文件树/内容、图谱、问答、检索等请求。
  - 切换项目、切换到 ZIP 项目、或取消跟踪当前分支时清空上下文。
  - 项目总览对分支项目先取上下文再请求 `branch-overview`，并对返回的 `snapshotId`/`commitSha` 做一致性校验；ZIP 项目改走 `profile` + `code-facts` + `health-overview`。
- 证据：`frontend/src/features/branches/BranchListTable.vue:24`、`frontend/src/features/branches/BranchIndexPanel.vue:16`、`frontend/src/features/branches/BranchIndexPanel.vue:41`、`frontend/src/features/branches/BranchTaskHistory.vue:17`、`frontend/src/features/indexing/BranchTasksPanel.vue:39`、`frontend/src/stores/branchContextStore.ts:9`、`frontend/src/stores/branchContextStore.ts:82`、`frontend/src/stores/branchContextStore.ts:54`、`frontend/src/api/branchContext.ts:1`、`frontend/src/features/overview/useProjectOverview.ts:49`

## 3 数据与状态

### 3.1 主要数据表

| 表 | 用途 | 关键约束 |
| --- | --- | --- |
| `repository_branches` | 分支稳定身份与生命周期 | `UNIQUE(repo_id,name)`、`UNIQUE(repo_id,id)`；`preparation_status IN ('PENDING','BUILDING','READY','FAILED')`；`tracking_status IN ('ACTIVE','ARCHIVED')`；`published_snapshot_id` 外键延迟校验 |
| `branch_snapshots` | 不可变快照 | `UNIQUE(repo_id,branch_id,id)`、`UNIQUE(branch_id,id)`；`content_path` 必填；`content_indexed_at` 记录内容索引发布时刻 |
| `branch_read_contexts` | 阅读上下文 | `account_id`/`repo_id`/`branch_id`/`snapshot_id` 外键；`expires_at` 必填并有失效索引 |
| `branch_context_knowledge` | 上下文绑定的知识集合 | 主键 `(context_id, card_id)`，随上下文级联删除 |
| `branch_preparation_jobs` | 分支准备任务 | `status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')`；`kind IN ('SNAPSHOT','SYNC','CONTENT','GRAPH','VECTORS','PREPARE')`；部分唯一索引保证同分支同类型只有一个活动任务；除 `SNAPSHOT`/`SYNC`/`PREPARE` 外必须有 `target_snapshot` |
| `index_job_branch_targets` | 代码图谱任务的分支目标 | 主键 `job_id`，外键指向快照 |
| `repository_project_drafts` | 项目草稿（与分支模型同期引入） | 见 `docs/03-repository-source-and-credentials.md` |
| `repository_markdown_sources` | 分支维度的 Markdown 来源 | 唯一键 `(repo_id,branch_id,file_path)` |

证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:2`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:17`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:30`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:75`、`backend/src/main/resources/db/migration/V4__branch_graph_tasks.sql:1`、`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:1`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:84`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:1`

### 3.2 状态枚举

- 分支准备状态 `preparation_status`：`PENDING`（初始）→ `BUILDING`（认领执行中）→ `READY`（发布成功）/ `FAILED`。证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:8`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:209`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:324`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:339`
- 分支跟踪状态 `tracking_status`：`ACTIVE` / `ARCHIVED`。证据：`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:4`
- 准备作业状态：`QUEUED` → `RUNNING` → `SUCCEEDED` / `FAILED`。证据：`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:6`
- 准备作业类型：`SNAPSHOT`、`SYNC`、`CONTENT`、`GRAPH`、`VECTORS`、`PREPARE`。证据：`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:13`
- 准备阶段（`stage`）：`QUEUED`（初始）、`RESOLVING`、`SNAPSHOT`、`PUBLISHING`、`INDEXING`、`GRAPH`、`EMBEDDING`、`COMPLETED`、`FAILED`。证据：`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:7`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:223`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:369`
- 项目级准备阶段键：`snapshot`、`content`、`vectors`、`graph`、`knowledge_drift`；阶段状态 `READY`、`RUNNING`、`PENDING`、`FAILED`、`DEGRADED`；整体状态 `READY`、`DEGRADED`、`PROCESSING`、`ACTION_REQUIRED`、`NOT_READY`。证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPreparationService.java:183`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPreparationService.java:252`、`frontend/src/api/repositories.ts:16`
- 知识分支校验状态（分支维度证据）：`CURRENT`、`UNVERIFIED`、`REVIEW_REQUIRED`、`INVALID`。证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:67`

### 3.3 状态流转

- 分支：`ACTIVE`（`PENDING` → `BUILDING` → `READY`/`FAILED`，可反复）→（归档）`ARCHIVED` →（恢复）`ACTIVE`。归档不改变已发布快照。
- 快照：`branch_snapshots` 行插入即不可变；`content_indexed_at` 由内容索引首次成功时写入一次；只有 BRN-021 的显式清理会删除该行。
- 阅读上下文：创建即生效（3600 秒）→ 到期后解析报 `CONTEXT_EXPIRED` → 由清理任务删除。
- 准备作业：`QUEUED` → `RUNNING`（持有 `attempt_token`）→ `SUCCEEDED` 或 `FAILED`；`RUNNING` 超过 1 小时可被重新认领（回收）。

## 4 接口清单

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/repositories/{repositoryId}/branches` | 分支列表与准备状态 | READ |
| GET | `/api/repositories/{repositoryId}/branches/discover` | 发现远程分支（不跟踪） | MAINTAIN |
| POST | `/api/repositories/{repositoryId}/branches` | 跟踪分支 | MAINTAIN |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/archive` | 归档分支 | MANAGE |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/restore` | 恢复分支 | MANAGE |
| POST | `/api/repositories/{repositoryId}/contexts` | 解析或创建阅读上下文 | READ |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/prepare` | 分支准备（kind=SNAPSHOT，202） | MAINTAIN |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/code-jobs` | 显式分支操作（202） | MAINTAIN |
| POST | `/api/repositories/{repositoryId}/branch-vector-jobs` | 构建分支向量索引（202） | MAINTAIN |
| GET | `/api/repositories/{repositoryId}/branch-preparation-jobs` | 每分支每类型的最新任务 | READ |
| GET | `/api/repositories/{repositoryId}/branch-preparation-jobs/history` | 任务历史分页 | READ |
| GET | `/api/repositories/{repositoryId}/branch-index-statuses` | 分支索引就绪状态批查询 | READ |
| GET | `/api/repositories/{repositoryId}/branches/{branchId}/index-status` | 指定快照的索引就绪状态 | READ（并需有效 contextId） |
| GET | `/api/repositories/{repositoryId}/branch-overview` | 分支总览（准备 + 代码事实 + 健康度） | READ + `X-Branch-Context` |
| GET | `/api/repositories/{repositoryId}/branches/{branchId}/snapshots/{snapshotId}/retention` | 快照引用检查 | MANAGE |
| DELETE | `/api/repositories/{repositoryId}/branches/{branchId}/snapshots/{snapshotId}` | 删除快照并清理派生数据 | MANAGE |
| GET | `/api/repositories/{repositoryId}/files`、`/files/content`、`/files/raw` | 按上下文读取分支快照文件 | READ + 可选 `X-Branch-Context` |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:47`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:53`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:59`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:65`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:73`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:81`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:90`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:96`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:108`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:121`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:131`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:184`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:36`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:42`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:52`

## 5 边界与非目标

- 不在本文范围：项目接入与来源凭据（见 `docs/03-repository-source-and-credentials.md`）、知识卡的分支范围与分支验证（`/knowledge/branch-scopes`、`/knowledge/{cardId}/branch-validation`）、问答历史接口。
- 分支数量与远程分支发现数量都没有面向产品的配额管理；发现上限 2000 条只是安全阈值。
- 分支级准备不做 Git 历史改写、不自动 rebase、不自动跟踪新远程分支。
- 归档不是删除：归档分支的快照与派生数据照旧参与引用检查，不会被自动清理。
- 阅读上下文不是全局状态：没有 ThreadLocal 分支切换、没有 SQL 改写、不修改仓库指针；不支持上下文的端点直接拒绝而不是回退。
- 分支级准备只有"重试 = 重新提交"这一种恢复方式，没有取消、没有优先级、没有跨分支调度限额。
- 快照内容是不可变的：已发布快照不能重新索引覆盖片段，只能在显式删除后重新发布（引用检查通过时）。

## 6 已知缺口

- 分支级准备作业没有取消接口，前端也没有取消入口；只有仓库级 `index_jobs` 与远程导入作业支持取消。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:190`、`frontend/src/api/branches.ts:47`
- 分支发现、跟踪、归档、恢复的服务实现没有任何测试覆盖（`RepositoryBranchService` 在测试中仅被 mock），因此 `BRANCH_ARCHIVED`、`BRANCH_HAS_ACTIVE_TASK`、`generation` 递增等规则缺少自动化保障。证据：`backend/src/test/java/com/analyzercoder/application/branch/BranchPreparationJobsTest.java:1`
- 并发锁语义（`pg_try_advisory_lock` 跨 Worker 互斥、`RUNNING` 超 1 小时回收、`BRANCH_BUILD_SUPERSEDED`）没有并发测试，现有测试为单线程成功路径。证据：`backend/src/test/java/com/analyzercoder/application/branch/BranchPreparationJobsTest.java:76`
- 快照保留检查只测到"问答引用"一个维度；`SNAPSHOT_NOT_FOUND`（404）、`SNAPSHOT_REFERENCED`（409）、`SNAPSHOT_CHANGED`（409）与 `deleteExpiredContexts()` 均无测试。证据：`backend/src/test/java/com/analyzercoder/application/branch/BranchArtifactRetentionServiceTest.java:49`
- 删除受管快照目录之前，若数据库删除事务成功但对文件系统目录的删除失败，当前实现不会把已删除的快照回滚，也不会记录待清理任务，受管目录会残留为孤儿目录。需人工确认运维清理约定。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:56`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:76`
- 阅读上下文的 3600 秒有效期是硬编码常量，没有配置项；前端也没有在上下文过期前主动续期或提示，只在请求失败时把错误写入 store。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:397`、`frontend/src/stores/branchContextStore.ts:88`
- 前端把 `contextId` 写入 URL 查询参数但从不读取该参数，路由上的上下文只是展示/书签；页面始终按 `branchId` 重新 `POST /contexts`。证据：`frontend/src/views/RepositoriesM0View.vue:155`、`frontend/src/stores/branchContextStore.ts:84`
- 前端没有快照保留与删除入口：`retention` 与删除快照两个接口在前端零引用，分支列表只展示当前已发布快照，无法查看或清理历史快照。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:121`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:131`
- 前端未调用旧式分支准备接口 `POST /branches/{branchId}/prepare`，统一走 `code-jobs`；该接口因此只有后端可用而无界面路径。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:81`、`frontend/src/api/branches.ts:47`
- 项目级阶段重试接口 `POST /repositories/{id}/prepare/stages/{stageKey}/retry` 已在 composable 中暴露，但总览页面没有解构它，界面上只提供整体准备，因此单阶段重试实际没有入口。证据：`frontend/src/features/overview/useProjectOverview.ts:121`、`frontend/src/features/overview/useProjectOverview.ts:187`
- ZIP 项目的分支播种名固定为 `WORKSPACE`（因 `default_branch` 被清空），而 ZIP 工作副本的实际 Git 分支名由本机 `git init` 默认值决定；分支级同步在该项目上是否会因分支名不匹配而失败，需人工确认（前端对 ZIP 不走分支操作，因此生产路径未暴露）。证据：`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:14`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:228`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:58`
- 自动化覆盖依赖环境变量：`BranchCodeOperationsDatabaseTest`、`BranchPreparationJobsTest`、`BranchIsolationDatabaseTest` 需对应 env 变量才执行，分支快照隔离、任务去重、`contentReady`/`graphReady`/`vectorsReady` 等最强证据在默认 `mvn test` 中会被跳过。需人工确认 CI 是否设置这些变量。证据：`backend/src/test/java/com/analyzercoder/application/branch/BranchCodeOperationsDatabaseTest.java:269`、`backend/src/test/java/com/analyzercoder/application/branch/BranchPreparationJobsTest.java:76`
