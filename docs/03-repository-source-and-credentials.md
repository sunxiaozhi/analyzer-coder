# 仓库接入与来源凭据
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

本文覆盖"把一份代码接入平台并形成项目（project）"这一领域：本地 Git、远程 Git / GitLab、ZIP 四种来源类型的接入路径，接入过程中产生的项目草稿（project draft），以及访问远程代码所需的仓库凭据（repository credential）。

对外标识与术语：

- 项目（project）：逻辑身份，接口层以 `repositoryId`（UUID，库表 `repositories.id`）为对外标识。一条 `repositories` 记录固定一个代码来源与一个默认分支，只维护"当前代码版本"。
- 来源类型：`LOCAL_GIT`、`REMOTE_GIT`、`GITLAB`、`ZIP` 四种取值。证据：`backend/src/main/java/com/analyzercoder/domain/repository/RepositorySourceType.java:4`
- 凭据类型：`GIT_HTTP_TOKEN`、`GITLAB_PAT` 两种取值。证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:20`

角色与权限模型（按当前实现，不使用四级权限的说法）：

- 权限级别只有三级：`READ` < `MAINTAIN` < `MANAGE`，用 `ordinal()` 比较。证据：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4`、`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:9`
- 所有者（owner）关系：由 `repositories.owner_account_id` 与成员记录中的 `ownerAccountId` 表达。账号等于 owner 时按 `MANAGE` 处理。证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:30`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:70`
- 仅所有者动作通过 `requireOwner` 判定，失败返回 403 `OWNER_REQUIRED`。证据：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:46`
- 账号角色只有 `SUPER_ADMIN` 与 `NORMAL`（普通用户）；超级管理员绕过仓库级权限检查并可见全部未删除仓库。证据：`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:23`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:56`
- 能力位（`capabilities`）随仓库响应返回，由 `AccessControlService.describe` 统一计算。字段定义见 `backend/src/main/java/com/analyzercoder/security/RepositoryAccess.java:13`，计算见 `backend/src/main/java/com/analyzercoder/security/AccessControlService.java:84`。

## 2 需求条目

### REP-001 项目逻辑身份与来源类型

- 需求：接入一份代码即创建一个项目；来源类型固定为 `LOCAL_GIT`、`REMOTE_GIT`、`GITLAB`、`ZIP` 之一，注册接口创建的是 `LOCAL_GIT` 项目。
- 规则：
  - 注册时校验同一所有者下名称唯一（名称 trim 后按小写规范化比较），冲突时拒绝。
  - 校验仓库路径尚未被接入平台（按规范化绝对路径计数）。
  - 新仓库初始 `repository_status='READY'`、`repository_version=1`。
  - 创建时记录 `owner_account_id`；创建后立即读取 Git 版本并发布受管快照；发布前后各读一次源状态，不一致则回滚并提示重试。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RegisterRepositoryService.java:60`、`backend/src/main/java/com/analyzercoder/application/repository/RegisterRepositoryService.java:159`、`backend/src/main/java/com/analyzercoder/infrastructure/persistence/model/RepositoryRow.java:60`、`backend/src/main/resources/mappers/RepositoryMapper.xml:107`

### REP-002 本地 Git 接入与允许根目录

- 需求：本地接入只接受位于 `APP_REPOSITORY_ALLOWED_ROOTS` 白名单内、且后端进程可读的目录；平台自己落地的受管目录必须位于 `APP_MANAGED_DATA_ROOT` 内。
- 规则：
  - 白名单由逗号或分号分隔，逐项解析为真实路径（`toRealPath`）；为空时应用启动失败。
  - 校验必须是目录且可读；否则 400「仓库路径必须是可读取的目录」。
  - 必须位于某个白名单根之下；否则 400「Repository path is outside configured allowed roots」。
  - 平台受管导入路径（远程/ZIP 导入的落地目录）改用受管数据根校验，越界报「Managed import path escaped managed data root」。
  - 本地路径还必须是 Git 工作区根目录（`rev-parse --show-toplevel` 与传入路径指向同一文件）。
- 证据：`backend/src/main/resources/application.yml:67`、`backend/src/main/resources/application.yml:70`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RepositoryPathPolicy.java:24`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RepositoryPathPolicy.java:47`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RepositoryPathPolicy.java:52`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RepositoryPathPolicy.java:57`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitCliLocalGitInspector.java:31`

### REP-003 快照容量上限与单文件上限

- 需求：发布受管快照时限制文件总数与总字节数，防止超大仓库拖垮平台；单文件预览与单文件索引入库分别设限。
- 规则：
  - 受管快照文件数上限 `app.repository.snapshot-max-files`，默认 20000。
  - 受管快照总字节上限 `app.repository.snapshot-max-total-bytes`，默认 2147483648（2 GiB）。
  - 快照复制拒绝符号链接与非普通文件；只复制 `git ls-files -co --exclude-standard` 列出的文件。
  - 远程/分支快照导出前先用 `git ls-tree -r -l` 预检文件数与字节数，超出即拒绝；导出后再次累计校验。
  - 源码预览读取的单文件上限 `app.repository.browser-max-file-bytes`，默认 2097152（2 MiB）。
  - 纳入内容索引的单文件上限 `app.indexing.max-file-bytes`，默认 524288（512 KiB），超出跳过。
- 证据：`backend/src/main/resources/application.yml:74`、`backend/src/main/resources/application.yml:78`、`backend/src/main/resources/application.yml:83`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:41`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:116`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:128`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:31`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:90`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:120`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCodeBrowserService.java:29`、`backend/src/main/java/com/analyzercoder/infrastructure/indexing/FileSystemRepositoryScanner.java:84`

### REP-004 仓库列表与分页

- 需求：提供分页列表与全量列表两种读取方式，只返回当前账号可见且未软删除的仓库。
- 规则：
  - 分页参数 `pageNum` 从 1 开始、`pageSize` 取值 1–100，越界报 400。
  - `query` 为空或纯空白时不做过滤；否则对名称、描述、默认分支、所有者用户名、所有者显示名做大小写不敏感的子串匹配。
  - 分页结果按 `created_at DESC, id DESC` 排序，返回统一分页结构（items/pageNum/pageSize/total/pages）。
  - 可见性：所有者账号、或存在 `repository_permissions` 成员授权；超级管理员可见全部。
  - 全量列表接口额外再按可见 id 集合过滤一次。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:83`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:103`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryPageService.java:20`、`backend/src/main/java/com/analyzercoder/application/common/PageResult.java:24`、`backend/src/main/resources/mappers/RepositoryMapper.xml:60`、`backend/src/main/resources/mappers/RepositoryAccessMapper.xml:29`

### REP-005 仓库详情与能力位

- 需求：单仓库详情返回来源、当前版本、所有关系、仓库状态与能力位。
- 规则：
  - 读取详情需要 `READ`。
  - 描述与乐观锁版本取自 `repositories.description`、`repositories.repository_version`。
  - `codeGraphPath`：当前快照存在已发布 CodeGraph 产物时取产物路径并置 `codeGraphDetected=true`；否则回退 `repositories.codegraph_path` 并置 `false`。
  - 能力位映射：`canRead` 恒为 true；`canEditRepository` = MANAGE；`canUpdate` = MAINTAIN；`canIndex` = MAINTAIN；`canBuildCodeGraph` = MAINTAIN；`canConfigure` = MANAGE；`canGrant`、`canManageCredential`、`canTransferOwnership`、`canDelete` = 所有者或超级管理员。
  - `relationship` 取值为 `SUPER_ADMIN`、`OWNER` 或权限级别名。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:95`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:185`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:210`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryEditingService.java:30`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:62`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:75`、`backend/src/main/java/com/analyzercoder/security/RepositoryAccess.java:13`

### REP-006 仓库资料编辑

- 需求：所有者或 MANAGE 成员可修改名称、描述与默认分支，并使用乐观锁避免覆盖他人修改。
- 规则：
  - 需要 `MANAGE`。
  - 名称必填且不超过 100 字符；规范化后在同一所有者名下唯一，冲突返回 409 `REPOSITORY_NAME_CONFLICT`。
  - 描述最长 500 字符，超出报 400。
  - `defaultBranch` 为 `null` 时保留原值；trim 后为空则写入 `NULL`；超过 255 字符报 400。
  - `version` 必须等于当前 `repository_version`，否则 409 `REPOSITORY_VERSION_CONFLICT`；成功后版本号加一。
  - 成功后写入审计事件 `REPOSITORY_UPDATED`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:162`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:199`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryEditingService.java:35`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryEditingService.java:49`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryEditingService.java:62`、`backend/src/main/resources/mappers/RepositoryMapper.xml:88`

### REP-007 仓库重扫

- 需求：重扫检查源目录当前版本并决定是否发布新的受管快照。
- 规则：
  - 需要 `MAINTAIN`；响应体为 `{changed, repository}`。
  - 仓库存在活动索引任务（`QUEUED`/`RUNNING`/`CANCEL_REQUESTED`）时拒绝，409 `CONFLICT`。
  - 版本判定要求分支、提交号、工作区摘要、`dirty` 全部相同且已有当前快照；相同时 `changed=false`，只刷新 `last_scanned_at`。
  - 版本变化时创建新受管快照、复核源未变化后发布，并在事务提交后删除上一版本目录。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:114`、`backend/src/main/java/com/analyzercoder/application/repository/RegisterRepositoryService.java:98`、`backend/src/main/java/com/analyzercoder/application/repository/RegisterRepositoryService.java:134`、`backend/src/main/java/com/analyzercoder/domain/repository/CodeRepository.java:71`、`backend/src/main/resources/mappers/IndexJobMapper.xml:57`

### REP-008 远程同步

- 需求：已接入的远程项目可按默认分支拉取远端更新，必要时自动触发增量索引。
- 规则：
  - 需要 `MAINTAIN`。
  - 仅 `REMOTE_GIT`/`GITLAB` 可用，否则报 400「只有远程 Git 或 GitLab 仓库可以拉取远端更新」。
  - `remote_url` 为空时以 409 拒绝，提示重新接入。
  - 拉取前对远程地址执行目标安全策略校验（见 REP-010）。
  - 使用该仓库已绑定的凭据（无绑定则匿名）：`git fetch --prune origin` 后 `git reset --hard origin/<默认分支|HEAD>`。
  - 拉取后执行一次重扫；有变化时启动 `INCREMENTAL` 索引任务并在响应中返回 `indexJobId`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:125`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryRemoteSyncService.java:39`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryRemoteSyncService.java:55`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:134`、`backend/src/main/java/com/analyzercoder/application/branch/BranchRemoteService.java:50`

### REP-009 远程 Git / GitLab 同步导入

- 需求：提供同步的远程导入入口，克隆完成后立即返回项目。
- 规则：
  - 请求体字段：`name`（必填）、`url`（必填）、`branch`（可选）、`sourceType`、`credentialId`（可选）、`projectDraftId`。
  - `sourceType` 必须是 `REMOTE_GIT` 或 `GITLAB`，否则 400。
  - 先执行远程目标安全策略校验（见 REP-010）。
  - 无凭据时执行 `git clone --depth 1 [--branch <branch>] <url> <target>`，超时 180 秒。
  - 有凭据时先按目标地址解析凭据（校验归属、状态 ACTIVE 与主机/端口匹配），再以临时 `GIT_ASKPASS` 方式执行克隆，执行结束清理临时脚本与输出文件。
  - 克隆失败时删除临时目录，并把 Git 输出改写为脱敏的中文提示（分支不存在、仓库不存在、认证失败、DNS 失败、超时、拒绝连接、证书失败、非法 Git 仓库、连接中断）。
  - 成功后注册为受管项目：写入 `source_type`、`remote_url`，`hideGitVersion=false`；存在凭据时自动写入 `CLONE` 用途绑定。
  - 本接口请求体里的 `projectDraftId` 当前未被使用（同步路径不做草稿关联）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:70`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:98`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:54`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:292`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:123`、`backend/src/main/resources/mappers/RepositoryMapper.xml:92`

### REP-010 远程目标地址与协议安全策略

- 需求：所有对外发起的远程仓库访问都必须先通过统一的目标地址策略，阻止本机探测、非法协议与凭据注入。
- 规则：
  - 仅接受 `https`（大小写不敏感），必须包含 host，且地址中不得内嵌用户名/密码。
  - 端口必须是缺省或 443；其它端口拒绝。
  - 主机名拒绝 `localhost`、`*.localhost`、`*.local`。
  - 解析该主机的全部地址，任一命中受保护网段即拒绝：任意本地地址、环回、链路本地、站点本地、组播；IPv4 额外拒绝 `0.0.0.0/8`、`10/8`、`127/8`、`>=224`、`169.254/16`、`172.16-31`、`192.168/16`、`100.64-127`、`192.0/16`、`198.18-19`、`198.51`、`203.0`；IPv6 额外拒绝 `fc00::/7`、`fe80::/10`、`::1`。
  - 域名无法解析时以 400 拒绝。
  - 执行 `ls-remote`、`fetch`、`clone` 时使用 `http.followRedirects=false`，并设置 `GIT_TERMINAL_PROMPT=0`。
  - 异步导入入口直接要求 HTTPS；同步导入入口的地址解析允许 `http`/`https`，但控制器在调用前已执行目标安全策略，因此外部可观察行为同样是 HTTPS-only。
- 证据：`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:14`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:21`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:26`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:30`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RemoteRepositoryTargetPolicy.java:49`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:64`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:251`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:26`

### REP-011 远程导入异步作业

- 需求：远程大仓库导入可交给后台作业执行，并提供查询与取消入口。
- 规则：
  - 提交时校验目标地址策略、凭据（归属 + ACTIVE + 主机匹配）与来源类型，然后插入 `QUEUED` 作业（`current_step='queued'`）并返回作业视图。
  - 若带 `projectDraftId`，草稿必须处于 `SOURCE_CONFIGURED` 或 `FAILED`，提交时转为 `IMPORTING`。
  - 列表接口返回当前账号的作业（超级管理员为全部），按创建时间倒序，最多 100 条。
  - 读取单条作业仅提交账号或超级管理员可见，否则 403 `FORBIDDEN`；作业不存在报 400。
  - 取消接口置 `cancel_requested=true`；仅 `QUEUED`/`RUNNING` 状态可请求，否则 409「任务当前不能取消」。
  - 后台 Worker 每 2 秒（`app.repository.import-poll-interval-ms`）串行认领一条 `QUEUED` 作业（`FOR UPDATE SKIP LOCKED`）：置 `RUNNING`/`validating` → 若已请求取消则置 `CANCELED` 并让草稿进入 `FAILED` → 否则 `current_step='cloning'` 执行导入 → 成功置 `SUCCEEDED`、回填 `result_repository_id` 并完成草稿；异常置 `FAILED`、`current_step='failed'`、错误信息截断 500 字符，并让草稿进入 `FAILED`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:38`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:51`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:58`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:64`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryImportJobService.java:52`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryImportJobService.java:104`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryImportJobService.java:138`、`backend/src/main/java/com/analyzercoder/worker/RepositoryImportJobWorker.java:17`、`backend/src/main/resources/mappers/RepositoryImportJobMapper.xml:7`、`backend/src/main/resources/mappers/RepositoryImportJobMapper.xml:8`、`backend/src/main/resources/mappers/RepositoryImportJobMapper.xml:16`、`backend/src/main/resources/db/migration/V1__init_schema.sql:266`

### REP-012 ZIP 上传导入与单版本模式

- 需求：支持以 ZIP 压缩包接入代码，并把该来源限定为"单版本"使用。
- 规则：
  - 请求为 `multipart/form-data`，表单字段 `name` 与文件部件 `file`。
  - 文件名必须以 `.zip` 结尾，否则 400「仅支持 ZIP 文件」。
  - 解压到导入暂存根下的随机目录；条目数超过 20000、单文件超过 20 MiB、累计超过 500 MiB、或条目路径越界（`..` 逃出根目录）均拒绝。
  - 解压后在工作副本内执行 `git init`、配置 `user.email`/`user.name`、`git add .`、`git commit --allow-empty -m "Imported ZIP snapshot"`。
  - 以受管方式注册，并把暂存目录原子移动到 `<受管数据根>/<repositoryId>/worktree`；路径越界时回滚已注册记录并清理暂存目录。
  - `hideGitVersion=true` 使 `default_branch` 与 `current_commit` 被清空，ZIP 项目不暴露 Git 版本信息；该仓库也不写入 `remote_url`，因此不能做远程分支发现与拉取。
  - ZIP 项目的前端界面走仓库级"单版本"操作（重扫 / 全量内容索引 / 构建代码图谱），不使用分支级任务。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:87`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:102`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:184`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:127`、`backend/src/main/resources/mappers/RepositoryMapper.xml:92`、`frontend/src/views/RepositoriesM0View.vue:212`、`frontend/src/features/repositories/SingleVersionOperations.vue:21`

### REP-013 项目草稿生命周期

- 需求：项目元数据先于代码获取持久化，草稿在来源成功落地后才与真实的项目身份绑定；失败可重试。
- 规则：
  - 创建：`name` 必填且 1–100 字符，`description` 最长 500 字符；初始状态 `DRAFT`，`version=1`。
  - 列表：按 `updated_at` 倒序返回当前账号的草稿；超级管理员返回全部。
  - 配置来源 `PATCH /{id}/source`：`sourceType` 必填，`sourceLocation` 1–2000 字符；仅当草稿属于该账号、`version` 匹配且状态属于 `DRAFT`/`SOURCE_CONFIGURED`/`FAILED` 时生效，更新为 `SOURCE_CONFIGURED`、`version+1`、清空 `error`；否则 409 `PROJECT_DRAFT_CONFLICT`。
  - 导入中：远程作业提交时把草稿置 `IMPORTING`（仅允许从 `SOURCE_CONFIGURED`/`FAILED` 进入）。
  - 完成 `POST /{id}/complete`：调用者必须对该 `repositoryId` 具备 `MANAGE`；草稿所有者必须与项目所有者一致，否则 409 `PROJECT_DRAFT_REPOSITORY_MISMATCH`；成功后把草稿 `description` 回填到项目并把项目版本号加一，草稿置 `READY`、写入 `result_repository_id`、`version+1`。
  - 失败重试语义：草稿只要仍处于 `SOURCE_CONFIGURED` 或 `FAILED` 就可重新配置来源或重新提交导入；失败写入 `FAILED` 与错误文本（截断 500 字符），且不会覆盖已 `READY` 的草稿。
  - 草稿内保存的 `credentialId` 仅做外键引用，不校验该凭据的归属与状态（需人工确认是否预期）。
  - 当前实现不提供删除草稿的接口。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryProjectDraftController.java:26`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:27`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:41`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:67`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:82`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:111`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:127`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:126`

### REP-014 凭据类型与字段校验

- 需求：凭据以"服务地址 + 用户名 + 令牌"描述一条 HTTPS 访问身份，保存前做统一字段校验。
- 规则：
  - 类型仅 `GIT_HTTP_TOKEN`、`GITLAB_PAT`，数据库同名校验约束。
  - 显示名称 trim 后 1–100 字符。
  - 服务地址必须是 host 非空、无内嵌用户信息的 `https` 地址；保存时只保留 `scheme://authority`（丢弃路径）。
  - 令牌在创建或替换时至少 8 个字符；更新时不传或传空白表示保留原令牌。
  - 用户名允许为空；为空时按类型取默认值：`GITLAB_PAT` 取 `oauth2`，其它取 `git`。
  - 校验（validate）要求所选凭据与目标地址的 host 与端口（缺省按 443）完全一致，否则报「所选凭据与远程仓库主机不匹配」。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:222`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:238`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:245`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:126`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:251`、`backend/src/main/resources/db/migration/V1__init_schema.sql:221`

### REP-015 凭据密钥加密与明文不可回读

- 需求：令牌以加密形式持久化，接口层永不回读明文，只有 Git 子进程调用边界可短暂获得明文。
- 规则：
  - 主密钥取 `app.credentials.master-key`（`APP_CREDENTIAL_MASTER_KEY`），未单独配置时回退 `APP_LLM_MASTER_KEY`；长度不足 24 字符时应用启动失败。
  - 加密算法 `AES/GCM/NoPadding`，12 字节随机 IV，密钥为 `SHA-256("git-credential-encrypt:" + masterKey)`。
  - 同时计算 `HmacSHA256` 摘要用于去重比对，摘要密钥为 `SHA-256("git-credential-digest:" + masterKey)`；算法名记录为 `AES-256-GCM`。
  - 对外视图 `CredentialView` 不包含密文与明文，只返回 `maskedValue`（4 个掩码字符加令牌末 4 位）。
  - 更换主密钥会使既有密文无法解密；当前实现没有密钥轮换或重加密路径（需人工确认运维约定）。
- 证据：`backend/src/main/resources/application.yml:51`、`backend/src/main/java/com/analyzercoder/application/repository/CredentialSecretCipher.java:22`、`backend/src/main/java/com/analyzercoder/application/repository/CredentialSecretCipher.java:26`、`backend/src/main/java/com/analyzercoder/application/repository/CredentialSecretCipher.java:30`、`backend/src/main/java/com/analyzercoder/application/repository/CredentialSecretCipher.java:47`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:284`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:329`

### REP-016 凭据生命周期与校验

- 需求：凭据可创建、更新、启用、停用、检测与删除，操作均限定在凭据所有者范围内并留下审计记录。
- 规则：
  - 全部接口都要求凭据属于当前账号或调用者为超级管理员，否则 403 `FORBIDDEN`「无权使用该 Git 凭据」；凭据不存在报 400。
  - 列表排除历史迁移数据（`legacy_repo_id` 非空），按「ACTIVE 优先、更新时间倒序」返回；普通账号只看自己创建的凭据。
  - 创建后状态为 `ACTIVE`，记录创建人与更新人；审计 `REPOSITORY_CREDENTIAL_CREATED`。
  - 更新时若替换令牌则 `credential_version` 加一；更新后状态强制回到 `ACTIVE`；审计 `REPOSITORY_CREDENTIAL_UPDATED`。
  - 启用/停用分别写入状态 `ACTIVE` / `DISABLED`，停用时记录 `disabled_at`；审计事件为 `..._ENABLED` / `..._DISABLED`。
  - 检测：先做目标地址策略校验，再校验主机/端口匹配，然后执行 `git ls-remote --exit-code <url> HEAD`（45 秒超时）；成功写 `ACTIVE` 与 `last_validated_at`，审计结果 `SUCCESS`；失败写 `INVALID`、脱敏错误（截断 240 字符），审计结果 `DENIED`，并把原始异常抛给调用方。
  - 删除：仍被仓库绑定时拒绝（409「凭据仍被 N 个仓库使用，请先更换或解绑」）。
  - 解析凭据时非 `ACTIVE` 状态直接拒绝（「所选 Git 凭据当前不可用」）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryCredentialController.java:29`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:42`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:64`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:88`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:119`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:150`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:165`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:210`、`backend/src/main/resources/mappers/RepositoryCredentialMapper.xml:9`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:119`

### REP-017 凭据与仓库的绑定

- 需求：远程项目通过"用途绑定"指向一条凭据，绑定与解绑都要求所有者身份，并在绑定前做一次真实连通性检测。
- 规则：
  - 绑定关系主键为 `(repository_id, usage_type)`，`usage_type` 只允许 `CLONE`；重复绑定按用途覆盖。
  - 三个接口（查询、绑定、解绑）都通过 `requireOwner` 鉴权，失败 403 `OWNER_REQUIRED`。
  - 目标项目必须存在且配置了 `remote_url`，否则 409「只有远程 Git 仓库可以绑定访问凭据」。
  - 绑定流程：解析远程地址 → 对所选凭据执行完整检测（归属、状态、主机匹配、真实 `ls-remote`）→ 写入绑定 → 审计 `REPOSITORY_CREDENTIAL_BOUND`。
  - 解绑：写入解绑并审计 `REPOSITORY_CREDENTIAL_UNBOUND`；未绑定时为幂等空操作。
  - 由导入流程内部写入的绑定不带检测步骤（导入本身已完成克隆验证）；凭据解析对未绑定仓库返回 `null`，即允许匿名访问。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryCredentialBindingController.java:27`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialBindingService.java:35`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialBindingService.java:40`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialBindingService.java:77`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:135`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryCredentialService.java:197`、`backend/src/main/resources/mappers/RepositoryCredentialMapper.xml:55`、`backend/src/main/resources/db/migration/V1__init_schema.sql:251`

### REP-018 凭据在 Git 操作中的使用

- 需求：凭据仅以最小暴露面注入 Git 子进程，执行结束后清理敏感环境与临时文件。
- 规则：
  - 有凭据时创建临时目录写入 `askpass` 脚本：Windows 为 `.cmd`，类 Unix 为 `.sh` 且权限设为仅属主可读写执行。
  - 通过环境变量 `GIT_ASKPASS`、`ANALYZER_GIT_USERNAME`、`ANALYZER_GIT_SECRET` 传递，命令行不出现令牌；同时设置 `GIT_TERMINAL_PROMPT=0`。
  - Git 输出写入临时文件，超过 2 MiB 直接判失败；执行结束后无论成败都删除输出文件与 askpass 目录，并强制结束仍在运行的进程。
  - 失败信息按模式映射为中文提示（认证失败、仓库不存在、域名解析失败、证书失败、其它）。
  - 每个 Git 调用都有显式超时：克隆 180 秒、拉取分支 180 秒、`ls-remote` 45 秒、`rev-parse` 30 秒。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:188`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:210`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:221`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:233`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:244`、`backend/src/main/java/com/analyzercoder/application/repository/GitCredentialExecutor.java:269`

### REP-019 仓库治理与仓库操作的权限交集

- 需求：仓库治理操作与仓库自身操作共用同一套授权判定；此处只记录与仓库接入/来源相关的交集部分。
- 规则：
  - 成员列表需要 `MAINTAIN`；候选账号列表、授权、撤销授权、所有权转移、删除申请都需要所有者身份（或超级管理员）。
  - 所有权转移前要求仓库状态属于 `READY`/`AUTH_ERROR`，并要求目标所有者名下无同名仓库。
  - 授权接口拒绝把所有者本人写入普通授权记录。
  - 删除申请在有活动写任务时拒绝（409「仓库存在运行中的写任务，暂不能删除」）。
  - 只有所有者（或超级管理员）能执行删除、转移、授权，以及凭据绑定；`MANAGE` 成员可编辑资料、归档/恢复分支、清理快照，但不能删除仓库或改授权。
  - 治理写操作使用 `ownership_version` 乐观锁，版本不匹配时报 409 请求刷新。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryGovernanceController.java:33`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:39`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:44`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:63`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:138`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:157`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:78`

### REP-020 仓库删除与软删除

- 需求：删除是"先软删除、后异步物理清理"的两段式流程，删除期间数据立即不可见。
- 规则：
  - 删除接口仅所有者或超级管理员可调用。
  - 有活动写任务时拒绝。
  - 软删除写入 `repository_status='DELETING'`、`deleted_at=now`、`ownership_version+1`，并插入删除墓碑（同一仓库重复请求为幂等空操作）。
  - 后台 Worker 默认每 5 秒认领墓碑（`PENDING`/`FAILED`，或 `RUNNING` 超过 10 分钟），先删除受管文件，再级联清理问答、知识卡、启发式调用边、CodeGraph 产物、向量、片段、索引任务、成员授权与治理锁，最后置 `repository_status='DELETED'`、`path='[deleted]/<id>'`、清空当前快照与 CodeGraph 指针，并把墓碑置 `COMPLETE`。
  - 清理失败时墓碑置 `FAILED`、`retry_count+1`，等待下一轮重试。
  - 状态变为 `DELETED` 时数据库触发器删除该仓库的全部分支记录。
  - 所有仓库读取路径都带 `deleted_at IS NULL` 条件，因此软删除后仓库立即从列表、详情与所有权限查询中消失。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:179`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryGovernanceService.java:138`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryDeletionService.java:35`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:84`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:99`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:118`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:128`、`backend/src/main/java/com/analyzercoder/worker/RepositoryDeletionWorker.java:17`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:124`、`backend/src/main/resources/mappers/RepositoryMapper.xml:52`

### REP-021 前端仓库接入与来源凭据界面

- 需求：前端提供项目分页列表、两步接入向导、凭据管理、草稿续接与单版本操作入口。
- 规则：
  - 管理页按 `pageSize=15` 分页、搜索 300ms 防抖，使用分页接口；页面右侧按来源渲染：Git 类项目渲染分支工作区，ZIP 项目渲染"单版本代码"操作区。
  - 接入向导来源类型为 `GITLAB`/`REMOTE_GIT`/`ZIP`/`LOCAL_GIT` 四选一；本地 Git 输入服务端路径，远程输入 HTTPS 地址与分支并可选凭据，ZIP 选择文件。
  - 三种来源的前端接入流程统一先建草稿、再 `PATCH .../source`：本地 Git 调 `POST /api/repositories` 后 `complete`；ZIP 调 `POST /api/repository-imports/zip` 后 `complete`；远程走 `POST /api/repository-imports/remote-jobs` 并轮询作业（最多 120 次、每次 1 秒），作业完成后由后端自动 `complete` 草稿。
  - 未完成草稿在列表上方最多展示 3 条并提供"继续接入"，复用同一草稿 id 重新配置。
  - 凭据对话框支持列表、新建、更新、检测、启用/停用、删除（删除前先查绑定关系提示）；仓库凭据绑定面板仅在远程来源且具备 `canManageCredential` 时渲染。
  - 编辑资料对话框只允许改名称、描述、默认分支，并回传 `version` 乐观锁；来源类型不可修改。
- 证据：`frontend/src/views/RepositoriesM0View.vue:31`、`frontend/src/views/RepositoriesM0View.vue:94`、`frontend/src/views/RepositoriesM0View.vue:112`、`frontend/src/views/RepositoriesM0View.vue:133`、`frontend/src/views/RepositoriesM0View.vue:194`、`frontend/src/views/RepositoriesM0View.vue:208`、`frontend/src/features/repositories/RepositoryFormDialog.vue:9`、`frontend/src/features/repositories/RepositoryCredentialManagerDialog.vue:36`、`frontend/src/features/repositories/RepositoryCredentialBindingPanel.vue:32`、`frontend/src/features/repositories/RepositoryEditDialog.vue:39`、`frontend/src/api/sourceImports.ts:22`、`frontend/src/api/projectDrafts.ts:30`、`frontend/src/api/repositoryCredentials.ts:32`

## 3 数据与状态

### 3.1 主要数据表

| 表 | 用途 | 关键约束 |
| --- | --- | --- |
| `repositories` | 项目逻辑身份、来源、当前版本与生命周期状态 | 路径唯一；`(owner_account_id, normalized_name)` 在未删除行上唯一；`repository_version > 0` |
| `repository_permissions` | 成员授权 | 主键 `(account_id, repo_id)`；`permission_level IN ('READ','MAINTAIN','MANAGE')`；不表达 owner |
| `git_credentials` | 加密凭据 | `credential_type IN ('GIT_HTTP_TOKEN','GITLAB_PAT')`；`status IN ('ACTIVE','DISABLED','INVALID')` |
| `repository_credential_bindings` | 仓库与凭据的用途绑定 | 主键 `(repository_id, usage_type)`；`usage_type` 只允许 `CLONE`；凭据外键 `ON DELETE RESTRICT` |
| `repository_import_jobs` | 远程导入异步作业 | `status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELED')` |
| `repository_project_drafts` | 项目草稿 | `lifecycle_status IN ('DRAFT','SOURCE_CONFIGURED','IMPORTING','READY','FAILED')`；`owner_account_id` 外键级联删除 |
| `repository_deletion_tombstones` | 删除后的物理清理任务 | `cleanup_status` 默认 `PENDING`，另有 `RUNNING`/`COMPLETE`/`FAILED`；`retry_count` |

证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:55`、`backend/src/main/resources/db/migration/V1__init_schema.sql:168`、`backend/src/main/resources/db/migration/V1__init_schema.sql:199`、`backend/src/main/resources/db/migration/V1__init_schema.sql:251`、`backend/src/main/resources/db/migration/V1__init_schema.sql:266`、`backend/src/main/resources/db/migration/V1__init_schema.sql:294`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:126`

### 3.2 状态枚举

- 项目来源类型：`LOCAL_GIT`、`REMOTE_GIT`、`GITLAB`、`ZIP`。证据：`backend/src/main/java/com/analyzercoder/domain/repository/RepositorySourceType.java:4`
- 仓库生命周期状态 `repository_status`：新库为 `READY`；删除申请后 `DELETING`；物理清理完成后 `DELETED`。实现中还会读取 `AUTH_ERROR`（所有权转移的前置条件），但当前代码没有任何写入 `AUTH_ERROR` 的路径。证据：`backend/src/main/java/com/analyzercoder/infrastructure/persistence/model/RepositoryRow.java:60`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:85`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:118`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:82`
- 凭据状态：`ACTIVE`、`DISABLED`、`INVALID`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:223`
- 导入作业状态：`QUEUED` → `RUNNING` → `SUCCEEDED` / `FAILED` / `CANCELED`；取消通过 `cancel_requested` 先请求、再由 Worker 落状态。证据：`backend/src/main/resources/mappers/RepositoryImportJobMapper.xml:8`、`backend/src/main/resources/mappers/RepositoryImportJobMapper.xml:16`
- 草稿状态：`DRAFT` → `SOURCE_CONFIGURED` → `IMPORTING` → `READY`；任一阶段失败可进入 `FAILED`，`FAILED` 可回到 `SOURCE_CONFIGURED` 或 `IMPORTING`。证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:54`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:74`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:91`
- 删除清理状态：`PENDING`/`RUNNING`/`COMPLETE`/`FAILED`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:298`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:100`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:125`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:129`

### 3.3 状态流转

- 项目：`READY` →（删除申请）`DELETING` →（清理完成）`DELETED`。软删除后所有查询因 `deleted_at IS NULL` 立即不可见。
- 草稿：新建 `DRAFT` → 配置来源 `SOURCE_CONFIGURED` → 异步提交 `IMPORTING` → 成功 `READY`；配置/导入失败 `FAILED`，`FAILED` 可重新配置或重新提交。
- 导入作业：提交 `QUEUED` → Worker 认领 `RUNNING`（`current_step` 依次为 `validating`、`cloning`、`completed`/`failed`/`canceled`）→ 终态。取消请求在作业被认领时生效。
- 凭据：创建与更新后为 `ACTIVE`，停用为 `DISABLED`，检测失败为 `INVALID`，检测成功回到 `ACTIVE`。

## 4 接口清单

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| POST | `/api/repositories` | 注册本地 Git 项目 | 已认证账号 |
| GET | `/api/repositories/page` | 项目分页列表与搜索 | 已认证账号（按可见性过滤） |
| GET | `/api/repositories` | 项目全量可见列表 | 已认证账号（按可见性过滤） |
| GET | `/api/repositories/{repositoryId}` | 项目详情与能力位 | READ |
| PATCH | `/api/repositories/{repositoryId}` | 编辑名称/描述/默认分支 | MANAGE |
| DELETE | `/api/repositories/{repositoryId}` | 申请删除（软删除） | 所有者（或超级管理员） |
| POST | `/api/repositories/{repositoryId}/rescan` | 重扫并发布新快照 | MAINTAIN |
| POST | `/api/repositories/{repositoryId}/sync` | 远程拉取并触发增量索引 | MAINTAIN |
| GET | `/api/repositories/{repositoryId}/profile` | 项目准备状态与画像 | READ |
| POST | `/api/repositories/{repositoryId}/prepare` | 项目级一键准备 | MAINTAIN |
| POST | `/api/repositories/{repositoryId}/prepare/stages/{stageKey}/retry` | 重试单个准备阶段 | MAINTAIN |
| GET | `/api/repositories/{repositoryId}/credential` | 查询仓库凭据绑定 | 所有者（或超级管理员） |
| PUT | `/api/repositories/{repositoryId}/credential` | 绑定/更换仓库凭据 | 所有者（或超级管理员） |
| DELETE | `/api/repositories/{repositoryId}/credential` | 解绑仓库凭据 | 所有者（或超级管理员） |
| POST | `/api/repository-imports/remote` | 同步远程导入 | 已认证账号 |
| POST | `/api/repository-imports/remote-jobs` | 提交远程导入异步作业 | 已认证账号 |
| GET | `/api/repository-imports/jobs` | 导入作业列表 | 已认证账号（限本人；超管全部） |
| GET | `/api/repository-imports/jobs/{id}` | 查询导入作业 | 提交账号或超级管理员 |
| POST | `/api/repository-imports/jobs/{id}/cancel` | 请求取消导入作业 | 提交账号或超级管理员 |
| POST | `/api/repository-imports/zip` | ZIP 上传导入 | 已认证账号 |
| GET | `/api/repository-project-drafts` | 项目草稿列表 | 已认证账号（限本人；超管全部） |
| POST | `/api/repository-project-drafts` | 创建项目草稿 | 已认证账号 |
| PATCH | `/api/repository-project-drafts/{id}/source` | 配置草稿来源 | 草稿所有者 |
| POST | `/api/repository-project-drafts/{id}/complete` | 草稿与项目绑定完成 | 草稿所有者 + 对目标项目 MANAGE |
| GET | `/api/repository-credentials` | 凭据列表 | 凭据所有者（或超级管理员） |
| POST | `/api/repository-credentials` | 创建凭据 | 已认证账号 |
| PUT | `/api/repository-credentials/{id}` | 更新凭据 | 凭据所有者（或超级管理员） |
| POST | `/api/repository-credentials/{id}/validate` | 检测凭据连通性 | 凭据所有者（或超级管理员） |
| POST | `/api/repository-credentials/{id}/enable` | 启用凭据 | 凭据所有者（或超级管理员） |
| POST | `/api/repository-credentials/{id}/disable` | 停用凭据 | 凭据所有者（或超级管理员） |
| DELETE | `/api/repository-credentials/{id}` | 删除凭据 | 凭据所有者（或超级管理员） |
| GET | `/api/repository-credentials/{id}/bindings` | 凭据的仓库绑定列表 | 凭据所有者（或超级管理员） |
| GET | `/api/repositories/{repositoryId}/governance/members` | 成员列表 | MAINTAIN |
| GET | `/api/repositories/{repositoryId}/governance/candidates` | 可授权账号候选 | 所有者（或超级管理员） |
| PUT | `/api/repositories/{repositoryId}/governance/members/{accountId}` | 授予/调整权限 | 所有者（或超级管理员） |
| DELETE | `/api/repositories/{repositoryId}/governance/members/{accountId}` | 撤销权限 | 所有者（或超级管理员） |
| POST | `/api/repositories/{repositoryId}/governance/transfer` | 转移所有权 | 所有者（或超级管理员） |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryController.java:73`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:38`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryProjectDraftController.java:26`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryCredentialController.java:29`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryCredentialBindingController.java:27`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryGovernanceController.java:33`

## 5 边界与非目标

- 不在本文范围：分支与快照发布、准备任务与阶段、阅读上下文与快照保留（见 `docs/04-branch-snapshot-and-preparation.md`）。
- 一个项目对应一个代码来源与一个默认分支；来源类型在接入后不可通过编辑接口修改（编辑接口不接收来源字段）。
- 只支持 HTTPS 远程仓库，不支持 SSH、`git://`、本地文件远程或内嵌凭据的 URL。
- 只支持单文件 ZIP 上传，不解析 `.tar.gz` 等其它打包格式，ZIP 内不保留原始提交历史（导入后为一条新提交）。
- 凭据只保存"服务地址 + 用户名 + 令牌"，不支持 SSH 私钥、证书文件、多因子或多凭据链。
- 平台不修改用户的本地工作区：快照与分支导出都只读 Git 对象，不执行 checkout 或 reset（远程同步的 `reset --hard` 只作用于平台自己克隆出的受管工作副本）。
- 删除仓库是异步物理清理，接口返回不代表文件已删除完成。

## 6 已知缺口

- `RepositoryGovernanceMapper.deleteRepositoryCredentials` 删除的是 `repository_credentials` 表，但现有迁移链（V1–V9）没有创建该表（V1 只创建了 `git_credentials` 与 `repository_credential_bindings`）。因此仓库删除的物理清理步骤会在此语句处失败，被记为墓碑 `FAILED` 并反复重试，`repository_status` 可能长期停留在 `DELETING`。需人工确认该语句的预期目标表。证据：`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:116`、`backend/src/main/resources/db/migration/V1__init_schema.sql:199`、`backend/src/main/resources/db/migration/V1__init_schema.sql:251`
- 仓库生命周期状态 `AUTH_ERROR` 只在所有权转移的前置条件与成员权限查询中被读取，没有任何代码写入该状态。证据：`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:82`
- 异步导入的取消只在 Worker 认领作业的那一刻被检查；对于已经处于 `RUNNING` 的作业，`cancel_requested` 不会再被读取，作业仍会走到 `SUCCEEDED` 或 `FAILED`，取消请求实际不生效。证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryImportJobService.java:113`、`backend/src/main/resources/mappers/RepositoryImportJobMapper.xml:16`
- 同步导入接口接收 `projectDraftId` 但未使用，因此通过该接口接入不会回填草稿状态。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:98`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositorySourceImportController.java:76`
- 同步导入与异步导入的地址协议校验不一致：`importRemote` 允许 `http`/`https`，`importRemoteQueued` 只允许 `https`。当前仅因控制器先行执行 HTTPS-only 策略而未暴露差异，属实现内不一致。证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:65`、`backend/src/main/java/com/analyzercoder/application/repository/RepositorySourceImportService.java:252`
- 草稿的 `credentialId` 在 `PATCH /{id}/source` 时不校验凭据归属与状态，其他账号的凭据 id 只要存在即可写入草稿；错误会在后续导入提交时才暴露。需人工确认是否预期。证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryProjectDraftService.java:41`、`backend/src/main/java/com/analyzercoder/application/repository/RepositoryImportJobService.java:61`
- 没有删除项目草稿的接口，`FAILED` 草稿只能被复用或长期保留。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryProjectDraftController.java:17`
- 前端没有导入作业列表与取消入口，也没有同步远程导入入口；异步导入只有 120 秒轮询，超时后仅提示稍后刷新。证据：`frontend/src/api/sourceImports.ts:21`、`frontend/src/views/RepositoriesM0View.vue:133`
- 前端未消费部分后端字段：`snapshotCreatedAt`、`codeGraphPath`、`codeGraphDetected`、`worktreeDigest`、`repositoryStatus` 与 `capabilities.canRead` 在界面中没有读取点。证据：`frontend/src/api/repositories.ts:119`、`frontend/src/api/repositories.ts:83`
- 测试覆盖缺口（据 `backend/src/test` 检索）：`RepositoryPathPolicy` 的白名单越界拒绝没有测试；项目分页、详情、资料编辑、删除申请、同步/异步远程导入、`RepositoryRemoteSyncService`、凭据 CRUD 全链路、草稿生命周期（除 `complete` 的 SQL 契约）均无测试；ZIP 的容量上限与 `hideGitVersion` 行为无断言。证据：`backend/src/test/java/com/analyzercoder/application/repository/RegisterRepositoryServiceTest.java:65`、`backend/src/test/java/com/analyzercoder/application/repository/RepositoryProjectDraftServiceTest.java:29`
- 集成测试类名以 `IT` 结尾且 `backend/pom.xml` 未配置 failsafe，`mvn test` 默认不会执行它们，其中部分是 ZIP 导入与凭据使用链路的唯一端到端证据。需人工确认 CI 是否单独运行这些用例。证据：`backend/src/test/java/com/analyzercoder/integration/RepositoryHttpWorkflowIT.java:27`、`backend/pom.xml:1`
