# 任务中心与系统配置（任务中心 / 后台任务 / 模型配置）
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色
本领域包含三部分已实现能力：

1. **任务中心**：索引任务（index job）的分页查询、详情、取消与重试，以及分支准备任务（branch preparation job）的查询与索引状态视图。
2. **后台任务执行**：索引任务与分支准备任务的调度、超时、并发与分支级锁，以及崩溃恢复行为。
3. **模型配置**：问答模型的 provider 配置（版本化新增 + 就地更新）、向量模型配置（维度、能力、启用/激活）、连通性检测（connectivity check）任务，以及 API Key 的加密存储与主密钥约束。

角色与权限（沿用账号与权限领域定义）：角色只有 `SUPER_ADMIN` 与普通用户（`NORMAL`）；仓库权限级别只有 `READ` < `MAINTAIN` < `MANAGE`；成员记录中的所有者由 `ownerAccountId` 表达，按 `MANAGE` 处理。任务中心分页查询、任务列表、模型配置与连通性检测均为管理员专属；任务详情、取消、重试按仓库权限判定；分支任务提交需 `MAINTAIN`，查询需 `READ`，分支归档/恢复需 `MANAGE`。

## 2 需求条目
### TSK-001 任务中心分页查询与可见范围

- 需求：任务中心提供索引任务的全局分页查询，并限定可见仓库范围。
- 规则：
  - 仅超级管理员可调用；普通用户被拒（普通用户调用被拒绝并返回无权限错误）。
  - 参数只有 `pageNum`（默认 1）与 `pageSize`（默认 20）；页码 ≥ 1，每页 1 到 100。
  - 只返回未逻辑删除仓库（`repositories.deleted_at IS NULL`）的任务；超级管理员可见全部，否则仅 `owner_account_id` 为本人或存在本人成员权限的仓库。
  - 排序固定 `created_at DESC, id DESC`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:64,69`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobPageService.java:20`、`backend/src/main/resources/mappers/IndexJobMapper.xml:47`、`backend/src/test/java/com/analyzercoder/interfaces/rest/IndexControllerTest.java:23`
### TSK-002 查询筛选能力与计数语义

- 需求：接口只支持分页，不提供任何筛选参数；界面的状态计数是页内计数。
- 规则：
  - 接口只接收 `pageNum`、`pageSize`，没有仓库、类型、状态过滤。
  - 前端"成功 / 已取消"计数基于**当前页**数据统计，翻页后数值变化。
  - 前端每页默认 15，切换页大小后回到第 1 页。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:65`、`frontend/src/features/indexing/useIndexJobs.ts:11,16,42`
### TSK-003 单任务详情与仓库维度查询

- 需求：可按任务标识查询详情，也可按仓库查询任务集合与最近一次状态。
- 规则：
  - `GET /api/index-jobs/{jobId}` 与 `GET /api/repositories/{id}/index-jobs` 需该仓库 `READ`。
  - `GET /api/repositories/{id}/index/status` 返回该仓库最近一次任务，仓库或任务不存在时报业务错误。
  - `GET /api/index-jobs`（列表）仍为管理员专属，并在应用层按可见仓库二次过滤。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:57,73,81,93`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobService.java:52,70`
### TSK-004 任务类型与启动入口（含旧版兼容接口）

- 需求：索引任务有 4 种类型，不同入口允许的类型不同。
- 规则：
  - 类型：`FULL`、`INCREMENTAL`、`CODEGRAPH`、`KNOWLEDGE_DRIFT`。
  - `POST /api/repositories/{id}/index` 只允许 `FULL`/`INCREMENTAL`，缺省按 `FULL`；该接口同时是旧版默认版本索引入口，完成后会写 `branch_snapshots.content_indexed_at` 以兼容分支模型。
  - 代码图谱任务由 `POST /api/repositories/{repoId}/codegraph/build`（需 `MAINTAIN`）创建；有分支阅读上下文时改为创建绑定该快照的任务。
  - 知识失效检查没有直接 HTTP 启动入口，只能由代码图谱成功后的自动后继或仓库准备流程创建。
  - 向量构建不是独立类型：旧版索引任务内部串行执行 `build_embeddings` 步骤。
- 证据：`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJobType.java:4`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:50`、`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:42`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:177,185`、`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftTaskService.java:19`
### TSK-005 任务状态机与阶段

- 需求：任务状态受领域约束，阶段以自由文本暴露进度。
- 规则：
  - 状态：`QUEUED`、`RUNNING`、`CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELED`；新任务固定 `QUEUED` + 阶段 `queued`。
  - 领取执行由 SQL 原子完成（`FOR UPDATE SKIP LOCKED`），FULL/INCREMENTAL 初始阶段 `scan_repository`。
  - 成功可从 `RUNNING` 或 `CANCEL_REQUESTED` 进入；失败同理；终态不被改写，重试创建新任务。
  - 每仓库同时只允许一个活动任务（数据库部分唯一索引）。
  - 观察到的阶段值包括 `scan_repository`、`write_chunks`、`build_embeddings`、`cancel_requested`、`canceled`、`timed_out`、`codegraph_failed` 及 `full:completed:<块数>:vectors-ready`、`codegraph_published:<snapshotId>`、`knowledge_drift_completed:<snapshotId>:ready|degraded` 等终态摘要。
- 证据：`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJobStatus.java:4`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:24,196,217`、`backend/src/main/resources/mappers/IndexJobMapper.xml:64`、`backend/src/main/resources/db/migration/V1__init_schema.sql:372`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:160,202`
### TSK-006 取消任务的适用状态

- 需求：只有排队中或运行中的任务可以提交取消请求。
- 规则：
  - 需要任务所属仓库 `MAINTAIN`。
  - 领域层只接受 `QUEUED`（直接转 `CANCELED`）与 `RUNNING`（转 `CANCEL_REQUESTED`）；对 `CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELED` 再次取消会抛错。
  - 运行中的任务在安全检查点（写块前、写块后、向量构建后、CodeGraph 心跳点）收敛为 `CANCELED`。
  - 前端仅在 `QUEUED`/`RUNNING` 时显示取消按钮，并提示将在下一个安全检查点停止。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:101`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:137,172`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:116,155,197,270`、`frontend/src/features/indexing/UnifiedIndexJobDetail.vue:10,71`
### TSK-007 重试任务的适用状态

- 需求：只有失败的任务可以重试，且重试不改写原任务。
- 规则：
  - 需要任务所属仓库 `MAINTAIN`。
  - 只有 `FAILED` 可重试；其他状态抛"只有失败的任务可以重试"。
  - 原任务带分支目标（`index_job_branch_targets`）时，重试为同一仓库新建 `CODEGRAPH` 任务并沿用该分支快照；分支目标不存在时抛错。
  - 若该仓库已存在活动任务（`QUEUED`/`RUNNING`/`CANCEL_REQUESTED`），重试不创建新任务而是直接返回该活动任务。
  - 分支准备任务不提供重试接口，只能重新提交对应操作。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:111`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobService.java:88,100`、`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:53`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:42`
### TSK-008 执行模式与回退原因

- 需求：实际执行模式与请求类型分离记录，增量请求可能回退为全量并记录稳定原因码。
- 规则：
  - `execution_mode` 仅允许 `FULL`/`INCREMENTAL` 且允许为空；只能在 `RUNNING` 时写入。
  - 回退判定顺序：缺索引基线 `BASELINE_MISSING`、工作区脏 `DIRTY_WORKTREE`、Git 差异失败 `GIT_DIFF_FAILED`、变更比例超阈值 `CHANGE_RATIO_EXCEEDED`。
  - 阈值：变更文件数 / 当前文件总数 > 0.35 即回退全量。
- 证据：`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:32,245`、`backend/src/main/java/com/analyzercoder/domain/indexing/IndexJob.java:113`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1361`
### TSK-009 失败代码、错误信息与快照绑定

- 需求：失败任务同时保留稳定失败代码与人类可读信息；任务详情的快照绑定是已知缺口。
- 规则：
  - 通用失败码 `TASK_FAILED`；CodeGraph 为 `CODEGRAPH_TIMEOUT`/`CODEGRAPH_BUILD_FAILED`；知识失效检查为 `KNOWLEDGE_DRIFT_TIMEOUT`/`KNOWLEDGE_DRIFT_FAILED`。
  - 超时收敛由 SQL 直接写入固定错误文本；处理器侧错误信息截断到 500 字符。
  - 前端在 CodeGraph 失败且错误信息不可解码时提示配置 `app.codegraph.executable`。
  - `IndexJobResponse` 不含快照字段，因此任务中心无法展示"任务针对哪个快照"（见第 6 节）。
- 证据：`backend/src/main/resources/mappers/IndexJobMapper.xml:86`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:87`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:123`、`frontend/src/features/indexing/UnifiedIndexJobDetail.vue:12`
### TSK-010 后台调度线程池与轮询间隔

- 需求：多个后台 worker 以固定延迟轮询各自任务，互不占用同一调度线程。
- 规则：
  - 调度总开关 `app.workers.enabled`（缺省开启）；调度池默认 4（`APP_WORKER_POOL_SIZE`），注释要求文件扫描、外部模型调用与 CodeGraph 不占用其他任务的调度线程。
  - 索引任务 5000 ms（`app.indexing.poll-interval-ms`），单实例内 `synchronized` 串行。
  - CodeGraph 2000 ms（`app.codegraph.poll-interval-ms`），每轮先收敛超时再领取新任务；知识失效检查 3000 ms（`app.knowledge.drift-poll-interval-ms`）同样先收敛。
  - 分支准备任务 2000 ms（`app.repository.branch-poll-interval-ms`），固定线程池并发执行，并发度 `app.repository.branch-concurrency` 默认 2 并夹在 1 到 8 之间。
  - 其他：仓库导入 2000 ms、仓库删除清理 5000 ms、分支阅读上下文过期清理 3600000 ms。
- 证据：`backend/src/main/java/com/analyzercoder/config/WorkerConfig.java:8`、`backend/src/main/resources/application.yml:2`、`backend/src/main/java/com/analyzercoder/worker/IndexJobWorker.java:17`、`backend/src/main/java/com/analyzercoder/worker/CodeGraphJobWorker.java:16`、`backend/src/main/java/com/analyzercoder/worker/KnowledgeDriftJobWorker.java:16`、`backend/src/main/java/com/analyzercoder/worker/BranchPreparationWorker.java:20,28`
### TSK-011 任务固定超时

- 需求：CodeGraph 与知识失效检查有固定时限，超时由数据库收敛为失败。
- 规则：
  - 领取时写 `timeout_at = 当前时间 + 固定秒数`；心跳只表示存活，不延长截止时间。
  - 时限：CodeGraph 默认 12 分钟（`app.codegraph.task-timeout-minutes`）；知识失效检查默认 5 分钟（`app.knowledge.drift-task-timeout-minutes`）。
  - 收敛条件 `status IN ('RUNNING','CANCEL_REQUESTED') AND timeout_at < now`，按类型写对应失败码与固定错误文本。
  - 处理器心跳点发现已过截止时间会主动抛"CodeGraph 后台任务执行超时"。
  - FULL/INCREMENTAL 领取时不写 `timeout_at`，不受该机制约束。
- 证据：`backend/src/main/resources/mappers/IndexJobMapper.xml:70,86`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:30,113`、`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftJobProcessor.java:26`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:111`
### TSK-012 崩溃与中断恢复

- 需求：各任务表的崩溃恢复行为均已确定，但实现方式不同。
- 规则：
  - 分支准备任务：领取条件同时接受 `QUEUED` 与"`RUNNING` 且 `updated_at` 早于当前 1 小时"，崩溃后被重新领取；`attempt_token` 使旧 worker 的后续写入失败并抛"准备任务已被接管"。
  - 连通性检测：服务启动时把遗留 `QUEUED`/`RUNNING` 置为 `FAILED`/`UNAVAILABLE`，错误码 `DEPENDENCY_INTERRUPTED`。
  - CodeGraph 与知识失效检查：依靠固定 `timeout_at` 在后续轮询中被收敛。
  - FULL/INCREMENTAL：没有陈旧 `RUNNING` 回收，也没有超时（见第 6 节）。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:194,423`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:104`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:84`、`backend/src/main/resources/mappers/IndexJobMapper.xml:64`
### TSK-013 分支准备任务模型与并发约束

- 需求：分支代码操作以分支准备任务承载，种类、状态、阶段与目标快照分别记录。
- 规则：
  - 种类（`kind`）：`SNAPSHOT`（旧版准备，兼容保留）、`SYNC`、`CONTENT`、`GRAPH`、`VECTORS`、`PREPARE`。
  - 状态：`QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`；阶段默认 `QUEUED`，执行中经过 `RESOLVING`、`SNAPSHOT`、`PUBLISHING`、`INDEXING`、`GRAPH`、`EMBEDDING`、`COMPLETED` 等检查点。
  - 目标快照约束：`SNAPSHOT`/`SYNC`/`PREPARE` 可空，`CONTENT`/`GRAPH`/`VECTORS` 必须非空。
  - 同分支同 `kind` 只允许一个 `QUEUED`/`RUNNING` 任务（部分唯一索引）；重复提交复用既有活动任务；若活动任务的目标快照不同则返回 409 `BRANCH_VECTOR_BUSY`。
  - 提交时对分支行加锁（`FOR UPDATE OF b`）并要求分支 `tracking_status='ACTIVE'` 且仓库未删除；失败统一写"任务失败，请检查分支、仓库权限、凭据或向量模型配置后重试"。
- 证据：`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:12`、`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:19`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:134,181,307`、`frontend/src/features/indexing/BranchTasksPanel.vue:28,30`
### TSK-014 分支任务提交与查询接口

- 需求：分支操作提供三类提交入口与两类查询入口，其中一类提交入口为旧版兼容。
- 规则：
  - `POST /branches/{branchId}/prepare` 提交 `SNAPSHOT`（旧版兼容）；`POST /branches/{branchId}/code-jobs` 按 `kind` 分派，`SYNC`/`PREPARE` 不接受 `contextId`（否则报"同步操作不能指定历史阅读上下文"），其他操作必须提供 `contextId`（否则报"索引操作需要已同步的分支快照"）。
  - `POST /branch-vector-jobs` 提交 `VECTORS`，必须提供 `contextId`，并要求目标快照已完成内容索引（`content_indexed_at` 非空且存在代码片段），否则报"请先构建此分支快照的内容索引"。
  - 提交需 `MAINTAIN` 并返回 202；查询需 `READ`。
  - `GET /branch-preparation-jobs` 只返回每 `(branch_id, kind)` 最新一条（`DISTINCT ON`）；`GET /branch-preparation-jobs/history` 返回完整历史分页，可按 `branchId` 过滤，页码默认 1、每页默认 15。
  - 前端分支任务面板只列出 `LOCAL_GIT`/`REMOTE_GIT`/`GITLAB` 来源的项目，并按项目与分支筛选，每 2500 ms 静默刷新。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:81,90,96,184`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:52`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:57,77,101,115`、`frontend/src/features/indexing/BranchTasksPanel.vue:24,39,50`
### TSK-015 分支级锁与阅读上下文（contextId）

- 需求：执行期按分支串行化任务，并把代码操作固定到不可变快照。
- 规则：
  - 领取任务时按分支 UUID 计算 64 位键并 `pg_try_advisory_lock`；同一分支同一时刻只允许一个执行中的任务，不同分支可并发；执行结束（含异常）必须释放锁。
  - 拿到锁后置 `RUNNING` 并写入随机 `attempt_token`，条件不满足则本轮放弃。
  - 阅读上下文由 `branch_read_contexts` 表达，绑定账号、仓库、分支、快照，有效期 1 小时；解析校验归属账号与过期时间，过期返回 409 `CONTEXT_EXPIRED`，分支不一致返回 409 `CONTEXT_MISMATCH`。
  - 未提供 `contextId` 时按已发布快照新建上下文；分支尚未准备返回 409 `BRANCH_NOT_READY`（"不会使用其他分支的数据"），同时把该分支范围内已发布且评审通过的知识固化进 `branch_context_knowledge`。
  - 分支发布用 `generation` 乐观并发：不匹配返回 409 `BRANCH_BUSY`，被新任务取代返回 409 `BRANCH_BUILD_SUPERSEDED`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:202,317,391`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347,379,404,412`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:74`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:30`
### TSK-016 分支索引状态与向量就绪判定

- 需求：按分支与快照分别报告内容索引、图谱与向量三类就绪状态。
- 规则：
  - `GET /branch-index-statuses` 返回仓库全部分支状态；`GET /branches/{branchId}/index-status?contextId=...` 返回指定快照状态；均需 `READ`。
  - 内容就绪 = `branch_snapshots.content_indexed_at` 非空；图谱就绪 = 存在 `PUBLISHED` 产物；向量就绪 = 该快照每个片段都有与当前启用向量模型的模型名、维度、检索能力一致的向量。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:205,213,227`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchCodeOperationsController.java:36`
### TSK-017 自动后继任务

- 需求：任务成功后按固定顺序自动排队后继任务，后继排队失败不影响已完成任务。
- 规则：
  - 内容索引成功后，若当前快照已变化则不排队 CodeGraph；若该快照尚无已发布产物则创建 `CODEGRAPH` 任务；排队异常只记告警。
  - CodeGraph 成功后，仅对非分支目标的任务自动排队知识失效检查；排队异常只记告警。
  - 代码图谱单一活动约束：同类型活动任务直接返回；存在其他类型活动任务时报"仓库已有活动任务，请等待完成后再构建 CodeGraph"；有分支目标且活动任务快照不同则返回 409 `BRANCH_GRAPH_BUSY`。
- 证据：`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobProcessor.java:221`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:93`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphTaskService.java:19`、`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:65`
### TSK-018 任务中心前端可达性与轮询

- 需求：任务中心在导航上只对管理员可见，路由不设管理员守卫；页面按分区渲染并对管理员轮询。
- 规则：
  - `/indexing` 路由没有 `admin` 元信息，登录用户均可访问；`/settings`、`/accounts`、`/audit` 才有管理员守卫。
  - 导航"任务中心"入口只在超级管理员分组渲染。
  - 页面默认分区为"分支任务"；仅当是管理员且查询参数为 `jobs`/`vectors` 时才进入对应分区；"其他任务"与"当前向量索引"页签仅对管理员渲染，非管理员不加载索引任务列表。
  - 索引任务列表每 2000 ms 静默刷新；当前页为空且页码 > 1 时自动回退一页；选中任务消失时自动改选第一条。
  - 取消成功提示"取消请求已提交"；重试成功提示"已创建新的重试任务"并重置到第 1 页选中新任务。
  - 状态标签：排队中/运行中/取消中/成功/失败/已取消；详情展示类型、实际模式、回退原因、阶段与全部时间字段。
- 证据：`frontend/src/router/index.ts:24,55`、`frontend/src/components/workspaceNavigation.ts:61`、`frontend/src/views/UnifiedIndexJobsView.vue:17,64,73`、`frontend/src/features/indexing/useIndexJobs.ts:29,45,47`、`frontend/src/features/indexing/TaskStatusTag.vue:7`、`frontend/src/features/indexing/UnifiedIndexJobDetail.vue:56`
### TSK-019 当前向量索引视图

- 需求：提供按仓库查看当前向量索引覆盖情况的只读视图，作为任务中心的一个分区。
- 规则：
  - 摘要、代码片段分页、知识卡片分页三个接口均需 `READ`。
  - 支持按关键字 `q`、向量状态（`EMBEDDED`/`MISSING`）、分块类型（`FILE`/`SYMBOL`/`DOC_SECTION`/`TEST_CASE`/`CONFIG`）筛选。
  - 摘要包含片段与知识卡片的已向量化/缺失计数、当前模型名与维度、检索能力与最近更新时间。
  - 前端每页默认 15，默认来源为代码分块；切换来源、状态或分块类型时回到第 1 页。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/VectorIndexController.java:22,33,39,52,64`、`frontend/src/api/vectorIndex.ts:78`、`frontend/src/features/indexing/useCurrentVectorIndex.ts:15,92`
### CFG-001 Provider 配置的版本化新增

- 需求：新增问答模型配置即创建一个新的不可变配置版本。
- 规则：
  - `POST /api/settings/llm/providers` 仅管理员可调用。
  - 每次新增从序列 `llm_provider_config_version_seq` 取递增版本号并生成新配置标识，同时插入一条运行状态记录（可用性 `UNTESTED`、熔断 `CLOSED`）。
  - 配置指纹 = 名称、协议、地址、模型、连接超时、请求超时、最大输出、温度、流式开关与密钥摘要拼接后的 SHA-256。
  - 表注释将该表定义为"LLM Provider 不可变配置版本"。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:45`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:126,844`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:32,53`、`backend/src/main/resources/db/migration/V1__init_schema.sql:881`
### CFG-002 Provider 配置的就地更新与运行状态重置

- 需求：已保存配置可就地更新，更新后必须重新检测。
- 规则：
  - `PUT /api/settings/llm/providers/{configId}` 仅管理员可调用；不存在返回 404 `LLM_CONFIG_NOT_FOUND`。
  - 更新重写配置字段、刷新指纹与创建者、把 `created_at` 置为当前时间。
  - 更新会重置运行状态：可用性 `UNTESTED`、连续失败清零、熔断关闭、清空最近检测与错误码。
  - 前端提示"模型备案已更新，请重新检测"。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:52`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:56,65`、`frontend/src/views/SystemSettingsView.vue:121`
### CFG-003 Provider 字段校验与默认值

- 需求：Provider 配置字段有明确取值范围与默认值，数据库层有同区间约束。
- 规则：
  - 协议只支持 `OPENAI_COMPATIBLE`（缺省取该值）；名称 1–100 字符；模型标识 1–200 字符。
  - 连接超时默认 5000（1000–10000）；请求超时默认 60000（3000–120000）；最大输出默认 2048（1–32768）；温度默认 0.2（0–2）；流式开关缺省为真。
  - 服务地址必须通过端点策略校验。
  - 数据库检查约束：`chk_llm_provider_type`、`chk_llm_connect_timeout`、`chk_llm_request_timeout`、`chk_llm_max_output_tokens`、`chk_llm_temperature`。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:585`、`backend/src/main/resources/db/migration/V1__init_schema.sql:874`、`backend/src/main/java/com/analyzercoder/application/llm/LlmEndpointPolicy.java:41`
### CFG-004 Provider 列表与旧版兼容接口

- 需求：提供版本列表，并保留旧版单配置读写接口。
- 规则：
  - `GET /providers` 与 `GET /provider/versions` 返回同一份版本列表（版本倒序）；`GET /provider` 返回最高版本配置，无配置时返回可用性 `UNCONFIGURED` 的空视图。
  - `PUT /provider` 是旧版写入接口，行为等同"新增版本"（每次生成新配置行与版本号），不是就地更新。
  - 前端使用 `providers` 系列接口，不使用旧版单配置接口。
  - 供问答使用的 `askModels()` 返回全部版本，并为每条计算 `available = (availability == AVAILABLE && breakerState == CLOSED)`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:27,33,39,99`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:95,111,647`、`frontend/src/api/llmSettings.ts:116`
### CFG-005 API Key 加密存储与主密钥约束

- 需求：API Key 以 AES-256-GCM 加密入库，主密钥必须满足长度要求。
- 规则：
  - 主密钥取自 `APP_LLM_MASTER_KEY`（`app.llm.master-key`）；为空或长度 < 24 时启动失败（"APP_LLM_MASTER_KEY 必须至少包含 24 个字符"）。
  - 使用本地开发默认密钥 `analyzer-coder-local-development-key` 时只记录告警，不阻止启动。
  - 加密密钥为 `SHA-256("encrypt:" + 主密钥)`，摘要密钥为 `SHA-256("digest:" + 主密钥)` 作 HMAC-SHA256；每条密钥使用 12 字节随机 IV；算法记录为 `AES-256-GCM`。
  - 密文、IV、明文指纹摘要与算法存入 `encrypted_secret_versions`，配置行只保存 `secret_version_id`；更换主密钥后解密失败报"无法解密模型密钥"。
  - Git 凭据使用独立命名空间（`git-credential-encrypt:`/`git-credential-digest:`），主密钥缺省回落到 LLM 主密钥（`app.credentials.master-key`）。
- 证据：`backend/src/main/resources/application.yml:51,97`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSecretCipher.java:26,35,39`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:817`、`backend/src/main/java/com/analyzercoder/application/repository/CredentialSecretCipher.java:22`
### CFG-006 密钥更新动作语义（KEEP / REPLACE / CLEAR）

- 需求：保存配置时以显式动作描述密钥变更，避免误覆盖。
- 规则：
  - `secretAction` 取 `KEEP`、`REPLACE`、`CLEAR`，缺省 `KEEP`；其他值报"secretAction 必须为 KEEP、REPLACE 或 CLEAR"。
  - `KEEP` 沿用现有密钥版本；首次保存且无密钥时报错要求提供密钥或明确清除。
  - `REPLACE` 的新密钥不得为空且不超过 5000 字符，随后加密并写入新密钥版本。
  - `CLEAR` 把密钥版本置空，摘要按字面量 `none` 参与指纹计算；未保存的候选配置不允许 `KEEP`。
  - 前端规则：填写了密钥则按 `REPLACE` 提交；编辑已配置密钥且未填写时保留 `KEEP`；新建未填写时按 `CLEAR` 提交。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:525,548,569`、`frontend/src/views/SystemSettingsView.vue:112`
### CFG-007 模型端点策略与 APP_LLM_ALLOW_INSECURE_LOCAL

- 需求：模型服务地址默认只允许 HTTPS，且禁止解析到受保护网络。
- 规则：
  - 地址必须含主机名，不得含用户凭据、查询参数或片段；端口必须合法（非 0、≤ 65535）；末尾斜杠被去除；默认必须 `https`。
  - `APP_LLM_ALLOW_INSECURE_LOCAL`（缺省 false）为真时，允许 `localhost`、`*.localhost`、`127.0.0.1`、`::1` 使用 `http` 并允许其解析到回环地址。
  - 其他本机、链路本地、站点本地、组播、CGNAT（100.64.0.0/10）、169.254/16 及 IPv6 唯一本地/链路本地地址一律拒绝（`LLM_NETWORK_BLOCKED`）；DNS 失败为 `LLM_DNS_FAILED`。
  - HTTP 客户端不跟随重定向。
- 证据：`backend/src/main/resources/application.yml:99`、`backend/src/main/java/com/analyzercoder/application/llm/LlmEndpointPolicy.java:22,41,56,77`、`backend/src/main/java/com/analyzercoder/application/llm/OpenAiCompatibleClient.java:55`
### CFG-008 连通性检测的创建、查询、取消与单飞复用

- 需求：检测以异步任务创建，同一账号同一配置指纹同时只存在一个在途检测。
- 规则：
  - `POST /connectivity-checks` 仅管理员可调用；请求体提供 `configId`（已保存配置）或 `candidate`（未保存候选）之一。
  - 新建先写 `QUEUED`（阶段 `VALIDATE_CONFIG`），再提交到固定 2 线程的守护线程池。
  - 单飞键为"账号 + 配置指纹"；命中在途检测时直接返回既有检测；并发竞争失败时把新检测置为 `CANCELED` 并写阶段 `SINGLE_FLIGHT_REUSED`（错误码 `LLM_CHECK_REUSED`）。
  - 查询与取消都只允许发起者，否则返回 404 `LLM_CHECK_NOT_FOUND`；取消会中断执行线程并置为 `CANCELED`/`UNTESTED`/`LLM_CHECK_CANCELED`，对已终态记录取消不改变状态（更新条件限定 `QUEUED`/`RUNNING`）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:106,114,121`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:56,326,374,383`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:97`
### CFG-009 检测阶段与结果分类

- 需求：检测按固定阶段推进并持久化每阶段结果，最终给出可用性分类。
- 规则：
  - 阶段顺序：`VALIDATE_CONFIG` → `RESOLVE_AND_AUTHORIZE_TARGET` → `CONNECT_TLS` → `AUTHENTICATE` → `GENERATE_MINIMAL`，开启流式时追加 `STREAM_FIRST_TOKEN`，收尾阶段 `CLASSIFY_AND_PERSIST`。
  - 鉴权阶段请求 `/models`；返回 404 或 405 时跳过状态码校验；2xx 且响应给出模型列表时校验目标模型存在；基础生成返回空内容视为 `LLM_PROTOCOL_INVALID`。
  - 全阶段成功 → `AVAILABLE`；仅流式首 token 失败 → `DEGRADED`（摘要"基础生成可用，但流式输出检测失败"）；其他失败 → `UNAVAILABLE`。
  - 记录保存总耗时、连接耗时、首 token 耗时与脱敏错误摘要（截断 500 字符）；检测完成后若记录由本流程收敛且对应已保存配置，则把结果写入该配置运行状态。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/OpenAiCompatibleClient.java:32,80,98,131`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:426,449,462`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:89,117`
### CFG-010 检测超时与中断恢复

- 需求：检测有独立于模型请求超时的总时限，服务重启后遗留检测被标记为中断失败。
- 规则：
  - 总超时来自 `APP_LLM_CONNECTIVITY_TIMEOUT_SECONDS`（缺省 15 秒），夹在 5 到 30 秒之间；与配置自身的请求超时不同。
  - 截止时间以纳秒传入 HTTP 客户端作为每次请求预算；取消标记在阶段之间检查。
  - 启动时把仍为 `QUEUED`/`RUNNING` 的检测统一置为 `FAILED`/`UNAVAILABLE`/阶段 `INTERRUPTED`/错误码 `DEPENDENCY_INTERRUPTED`。
  - 未预期运行时异常按 `FAILED`/`DEPENDENCY_UNEXPECTED` 收敛；服务关闭时取消全部控制位并关闭线程池。
- 证据：`backend/src/main/resources/application.yml:101`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:80,84,89,432,489`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:104`
### CFG-011 模型可用性与熔断阈值

- 需求：运行期连续失败达到阈值后熔断该模型，问答降级。
- 规则：
  - 阈值来自 `APP_LLM_BREAKER_FAILURE_THRESHOLD`（缺省 3），夹在 1 到 10 之间。
  - 每次调用失败使连续失败 +1；达到阈值时可用性置 `UNAVAILABLE`、熔断置 `OPEN` 并记录开启时间。
  - 调用成功清零连续失败并记录最近成功时间，仅在熔断 `CLOSED` 时更新。
  - 检测得到 `AVAILABLE` 会清零连续失败并关闭熔断；`DEGRADED`/`UNAVAILABLE` 只累加连续失败，**不**开启熔断（熔断仅由运行期调用失败触发）。
  - 选择问答模型时：可用性非 `AVAILABLE` 返回 409 `LLM_MODEL_UNAVAILABLE`；熔断非 `CLOSED` 返回 409 `LLM_BREAKER_OPEN`。
- 证据：`backend/src/main/resources/application.yml:103`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:117,129,134`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:408,421`
### CFG-012 向量模型配置

- 需求：向量模型以独立配置管理，区分本地字符哈希与外部语义向量。
- 规则：
  - 协议只支持 `LOCAL_HASH` 与 `OPENAI_COMPATIBLE`（缺省 `LOCAL_HASH`）。
  - `LOCAL_HASH` 固定 64 维（否则 `VECTOR_DIMENSION_INCOMPATIBLE`）；外部模型维度 1–4096。
  - 外部模型必须提供 `baseUrl`（经端点策略校验）与 API Key；本地哈希不保存密钥。
  - 请求超时缺省 30000（3000–120000）；`vector_model_configs.model` 全表唯一。
  - 检索能力由协议推导：`LOCAL_HASH` → `CHARACTER_HASH`（标签"字符相似度"，限制"基于字符哈希投影与余弦距离""不理解同义词、业务含义或代码语义"）；外部 → `SEMANTIC_EMBEDDING`（标签"语义检索"）。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:671,764,772`、`backend/src/main/resources/db/migration/V1__init_schema.sql:819,825`、`frontend/src/api/llmSettings.ts:47`
### CFG-013 向量模型连通性检测

- 需求：向量模型提供同步检测接口，返回可用性、维度、检索能力与耗时。
- 规则：
  - `POST /vector-models/{id}/check` 仅管理员可调用；不存在返回 404 `VECTOR_MODEL_NOT_FOUND`。
  - `LOCAL_HASH` 直接判定可用（不发起外部调用）。
  - 外部模型调用 OpenAI 兼容 `/embeddings`，以 `"connection probe"` 为探测输入，并校验返回向量长度与配置维度一致。
  - 失败返回 `available=false` 及错误码与脱敏摘要；前端提示"检测通过：<能力标签>，<维度> 维，<耗时> ms"。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:92`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:242,250,265`、`frontend/src/views/SystemSettingsView.vue:226`
### CFG-014 向量模型启用与激活版本

- 需求：系统只有唯一的启用向量模型，切换前必须先通过检测并用激活版本号做乐观并发控制。
- 规则：
  - `POST /vector-models/{id}/activate` 仅管理员可调用，必须携带客户端 `expectedActivationVersion`。
  - 启用前先执行同步检测；未通过时返回 409（错误码取检测错误码，缺省 `VECTOR_MODEL_UNAVAILABLE`）。
  - 更新条件为 `activation_version = expectedActivationVersion`；影响 0 行返回 409 `VECTOR_MODEL_ACTIVATION_CONFLICT`。
  - 成功使 `activation_version` 自增并记录启用人与时间；数据库以 `singleton_id = 1` 保证单例。
  - 内置种子模型 `local-hash-64`（`LOCAL_HASH`，64 维）初始化即启用；前端提示"已切换到 <名称>，后续检索会重建不匹配的向量"，只对非当前启用模型显示"切换使用"。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:222,235`、`backend/src/main/resources/mappers/LlmSettingsMapper.xml:177`、`backend/src/main/resources/db/migration/V1__init_schema.sql:845,1071`、`frontend/src/views/SystemSettingsView.vue:216,326`
### CFG-015 向量模型编辑限制

- 需求：当前启用的向量模型不允许就地编辑。
- 规则：
  - `PUT /vector-models/{id}` 对当前启用配置返回 409 `VECTOR_MODEL_ACTIVE`（"请先切换当前向量模型再编辑"）；不存在返回 404 `VECTOR_MODEL_NOT_FOUND`。
  - 外部向量模型更新的密钥动作只接受 `KEEP`（现有密钥必须存在）或 `REPLACE`（必须提供非空密钥），否则报 `VECTOR_MODEL_INVALID`。
  - 前端对当前启用模型禁用"编辑"按钮。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:198,204,712`、`frontend/src/views/SystemSettingsView.vue:327`
### CFG-016 系统键值配置

- 需求：提供管理员可读写的系统级键值配置，敏感项读取时掩码。
- 规则：
  - `GET /api/settings` 与 `PUT /api/settings` 均需管理员权限。
  - `sensitive` 为真的项读取时统一返回 `******`；写入为按主键 upsert 并记录最后修改账号与时间。
  - 种子值只有 `externalModelEnabled = false`，且代码中没有任何位置读取该键（见第 6 节）。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:233,243`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1145,1153`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:403`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1066`
### CFG-017 模型配置前端界面

- 需求：模型配置页按"问答模型 / 向量模型"两个分区管理配置。
- 规则：
  - 挂载时并行加载 provider 列表与向量模型列表。
  - 问答模型卡片展示协议、可用性标签、名称、模型标识、服务地址、最近成功时间、输出上限，并提供"检测""编辑"。
  - 向量模型卡片展示运行方式、向量维度、数据外发（本地为"无"，外部为"索引文本"）、备案时间、检索能力标签与限制，并提供"检测""切换使用""编辑"。
  - 表单取值范围与后端校验一致（连接超时 1000–10000、请求超时 3000–120000、最大输出 1–32768、温度 0–2；本地哈希维度固定 64 且禁用编辑）。
- 证据：`frontend/src/views/SystemSettingsView.vue:63,270,310,350,365`、`frontend/src/api/llmSettings.ts:115`

## 3 数据与状态
### 索引任务（`index_jobs`）

| 字段 | 说明 |
| --- | --- |
| `job_type` | `FULL` / `INCREMENTAL` / `CODEGRAPH` / `KNOWLEDGE_DRIFT` |
| `status` | `QUEUED` / `RUNNING` / `CANCEL_REQUESTED` / `SUCCEEDED` / `FAILED` / `CANCELED` |
| `current_step` | 自由文本阶段，终态含结果摘要 |
| `execution_mode`、`fallback_reason` | 实际模式与回退原因码，仅允许 `FULL`/`INCREMENTAL` |
| `failure_code`、`error_message` | 稳定失败码与人类可读信息 |
| `started_at`、`heartbeat_at`、`timeout_at`、`finished_at` | 时间线；只有按类型领取的任务写 `timeout_at` |

约束与索引：`uq_index_jobs_one_active_per_repository`（每仓库一个活动任务）、`idx_index_jobs_running_timeout`（超时收敛）、`index_job_branch_targets`（分支快照目标）。
### 分支准备任务（`branch_preparation_jobs`）

| 字段 | 说明 |
| --- | --- |
| `kind` | `SNAPSHOT` / `SYNC` / `CONTENT` / `GRAPH` / `VECTORS` / `PREPARE` |
| `status` | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` |
| `stage` | 检查点名称，默认 `QUEUED` |
| `attempt_token` | 执行权令牌，用于识别被接管的旧 worker |
| `target_commit`、`target_snapshot`、`error` | 目标提交、目标快照与失败文本 |

约束与索引：`branch_preparation_one_active`、`branch_preparation_queue`、`branch_job_snapshot`。
### 模型配置状态

- `llm_provider_configs`（不可变版本 + 唯一 `config_version`）与 `llm_provider_runtime_states`（可用性 `UNTESTED`/`AVAILABLE`/`DEGRADED`/`UNAVAILABLE`、熔断 `CLOSED`/`OPEN`、连续失败数、最近成功/失败时间、最近错误码）。
- `llm_connectivity_checks`：状态 `QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`/`CANCELED`，阶段明细以 JSON 数组保存。
- `vector_model_configs` + `vector_model_activation`（`singleton_id = 1` 单例，`activation_version` 乐观锁）；`llm_provider_activation` 同样使用 `singleton_id = 1`。
- `encrypted_secret_versions`：密文、IV、HMAC 摘要与算法。
### 前端可观察状态

- 索引任务状态：排队中 / 运行中 / 取消中 / 成功 / 失败 / 已取消；类型标签：全量内容索引 / 增量内容索引 / 代码图谱构建 / 知识失效检查。
- 分支任务状态：排队中 / 执行中 / 已完成 / 失败；种类标签：同步代码 / 内容索引 / 代码图谱 / 一键准备 / 向量索引 / 旧版准备；阶段标签见 `frontend/src/features/indexing/BranchTasksPanel.vue:30`。

## 4 接口清单

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| POST | `/api/repositories/{id}/index` | 启动 FULL/INCREMENTAL 索引任务（旧版入口） | `MAINTAIN` |
| GET | `/api/repositories/{id}/index/status` | 仓库最近一次索引任务 | `READ` |
| GET | `/api/index-jobs/page` | 任务中心分页查询 | 超级管理员 |
| GET | `/api/index-jobs` | 任务列表（按可见仓库过滤） | 超级管理员 |
| GET | `/api/index-jobs/{jobId}` | 单任务详情 | 任务所属仓库 `READ` |
| GET | `/api/repositories/{id}/index-jobs` | 仓库任务列表 | `READ` |
| POST | `/api/index-jobs/{jobId}/cancel` | 取消任务 | 任务所属仓库 `MAINTAIN` |
| POST | `/api/index-jobs/{jobId}/retries` | 重试失败任务 | 任务所属仓库 `MAINTAIN` |
| POST | `/api/repositories/{repoId}/codegraph/build` | 创建代码图谱任务 | `MAINTAIN` |
| POST | `/api/repositories/{id}/branches/{branchId}/prepare` | 旧版分支准备（`SNAPSHOT`） | `MAINTAIN` |
| POST | `/api/repositories/{id}/branches/{branchId}/code-jobs` | 分支同步或固定快照索引任务 | `MAINTAIN` |
| POST | `/api/repositories/{id}/branch-vector-jobs` | 分支向量索引任务 | `MAINTAIN` |
| GET | `/api/repositories/{id}/branch-preparation-jobs` | 每分支每类型最新任务 | `READ` |
| GET | `/api/repositories/{id}/branch-preparation-jobs/history` | 分支任务历史分页 | `READ` |
| GET | `/api/repositories/{id}/branch-index-statuses` | 分支索引状态列表 | `READ` |
| GET | `/api/repositories/{id}/branches/{branchId}/index-status` | 指定快照的索引状态 | `READ` |
| POST | `/api/repositories/{id}/contexts` | 解析/创建阅读上下文 | `READ` |
| GET | `/api/repositories/{id}/branches/{branchId}/snapshots/{snapshotId}/retention` | 查询快照引用 | `MANAGE` |
| DELETE | `/api/repositories/{id}/branches/{branchId}/snapshots/{snapshotId}` | 删除无引用快照 | `MANAGE` |
| GET | `/api/repositories/{id}/vector-index/summary` | 当前向量索引摘要 | `READ` |
| GET | `/api/repositories/{id}/vector-index/chunks` | 片段向量覆盖分页 | `READ` |
| GET | `/api/repositories/{id}/vector-index/knowledge` | 知识卡片向量覆盖分页 | `READ` |
| GET | `/api/settings/llm/provider` | 最高版本 provider（旧版） | 超级管理员 |
| GET | `/api/settings/llm/provider/versions` | provider 版本列表（旧版） | 超级管理员 |
| PUT | `/api/settings/llm/provider` | 写入新 provider 版本（旧版） | 超级管理员 |
| GET | `/api/settings/llm/providers` | provider 列表 | 超级管理员 |
| POST | `/api/settings/llm/providers` | 新增 provider 版本 | 超级管理员 |
| PUT | `/api/settings/llm/providers/{configId}` | 就地更新 provider | 超级管理员 |
| GET | `/api/settings/llm/vector-models` | 向量模型列表（含启用状态） | 超级管理员 |
| POST | `/api/settings/llm/vector-models` | 新增向量模型 | 超级管理员 |
| PUT | `/api/settings/llm/vector-models/{id}` | 更新向量模型 | 超级管理员 |
| POST | `/api/settings/llm/vector-models/{id}/activate` | 启用向量模型 | 超级管理员 |
| POST | `/api/settings/llm/vector-models/{id}/check` | 向量模型连通性检测 | 超级管理员 |
| POST | `/api/settings/llm/connectivity-checks` | 创建连通性检测 | 超级管理员 |
| GET | `/api/settings/llm/connectivity-checks/{checkId}` | 查询连通性检测 | 超级管理员（发起者） |
| POST | `/api/settings/llm/connectivity-checks/{checkId}/cancel` | 取消连通性检测 | 超级管理员（发起者） |
| GET | `/api/settings` | 读取系统键值配置 | 超级管理员 |
| PUT | `/api/settings` | 写入系统键值配置 | 超级管理员 |

## 5 边界与非目标

- 不覆盖仓库远程导入任务（`repository_import_jobs`）、仓库删除清理任务（`repository_deletion_tombstones`）与账号/会话/审计日志（见账号与权限文档）。
- 不覆盖问答模型的实际生成细节（提示词脱敏、检索排序、证据引用），仅覆盖配置与连通性检测。
- 不提供任务优先级、全局暂停、批量重跑，以及分支准备任务的重试接口。
- 不提供"重新嵌入全部数据"接口：切换向量模型后由后续任务按需补齐缺失向量。
- 连通性检测不是定时探测：只有显式创建检测才更新运行状态。
- 系统键值配置未接入任何业务判断，不是功能开关的集中入口。

## 6 已知缺口

1. **FULL/INCREMENTAL 索引任务缺少崩溃恢复与超时**：`claimNextQueued` 只领取 `QUEUED` 且不写 `timeout_at`，`expireTimedOut` 仅处理 `CODEGRAPH`/`KNOWLEDGE_DRIFT`。执行期间 worker 崩溃会留下永久 `RUNNING` 记录，部分唯一索引阻止该仓库新建任务，而 `start` 会返回该活动任务而非报错。
   - 证据：`backend/src/main/resources/mappers/IndexJobMapper.xml:64,86`、`backend/src/main/java/com/analyzercoder/application/indexing/IndexJobService.java:26`
2. **任务详情不暴露快照绑定**：`index_job_branch_targets` 已记录分支与快照，但 `IndexJobResponse` 未包含该字段。
   - 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:123`、`backend/src/main/resources/db/migration/V4__branch_graph_tasks.sql:1`
3. **连通性检测取消无前端入口**：后端提供取消接口，但 `frontend/src/api/llmSettings.ts` 未封装，界面也无按钮。
   - 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/LlmSettingsController.java:121`、`frontend/src/api/llmSettings.ts:115`
4. **系统键值配置无业务消费方**：`GET/PUT /api/settings` 可读写 `system_settings`，但代码中没有任何位置读取具体键值（含种子键 `externalModelEnabled`）。
   - 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1066`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1145`
5. **界面状态计数是页内计数**：任务中心的"成功 / 已取消"取自当前页集合，且接口不支持状态筛选。
   - 证据：`frontend/src/features/indexing/useIndexJobs.ts:16`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:65`
6. **分支任务失败原因不可区分**：所有异常写入同一段固定文本，无法区分分支缺失、凭据失效或向量模型配置问题。
   - 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:307`
7. **超时收敛依赖 worker 开启**：`app.workers.enabled=false` 时超时记录不会被收敛；该开关的运维语义需人工确认。
   - 证据：`backend/src/main/java/com/analyzercoder/config/WorkerConfig.java:10`
8. **分支任务阶段取值未约束**：`stage` 为自由文本，数据库未约束取值，前端仅做映射；新增阶段会直接显示原始字符串，是否需要约束需人工确认。
   - 证据：`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:7`、`frontend/src/features/indexing/BranchTasksPanel.vue:30`
