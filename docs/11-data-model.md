# 数据模型（Flyway 迁移 / 表结构 / 触发器 / 受管文件布局）
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

本文档描述数据库结构与其派生文件的持久化要求，范围包括：

- 9 个 Flyway 迁移脚本（`V1__init_schema.sql` 至 `V9__remove_cross_repository_projects.sql`）各自的意图与影响，以及已退役能力留下的结构变化与数据守卫。
- 现存表的清单、用途、关键字段、主外键关系、唯一约束与检查约束。
- 触发器与数据库函数。
- 数据库索引，尤其是用于检索与排序的索引。
- 由受管数据根目录（managed data root）派生的文件布局与配额。
- 快照删除的引用检查与软删除语义。

角色与权限（沿用账号与权限领域的定义）：数据模型本身不区分角色，所有可见性判断由查询条件与 `READ`/`MAINTAIN`/`MANAGE` 三级权限、所有者（owner）关系与 `SUPER_ADMIN` 角色共同实现。文中出现"可见"一词时均指该组合规则。

## 2 需求条目

### DATA-001 V1 单一初始化基线

- 需求：V1 是唯一的结构基线，由历史多个版本按顺序合并而成，只支持空库或明确重建后的数据库。
- 规则：
  - 第一句启用 `vector` 扩展（pgvector）。
  - 文件头注释明确"由原 V1 至 V14 按版本顺序合并，仅支持空库或明确重建后的数据库"。
  - 基线内保留了合并痕迹：`code_graph_edges` 被重命名为 `heuristic_call_edges`，`knowledge_cards.status` 重命名为 `publication_status`，`code_review_status` 重命名为 `source_version_status`，并新增 `review_status`/`reviewed_by`/`reviewed_at`。
  - 基线内还建后即删：`confirm_knowledge_code_version()` 与 `mark_repository_knowledge_stale()` 触发器在 V1 内先创建后删除（后者被 `knowledge_drift_events` 方案取代）。
  - 基线末尾附带了种子数据：`system_settings.externalModelEnabled=false`、内置向量模型 `local-hash-64`（64 维，`LOCAL_HASH`）并设为当前启用、`llm_provider_activation` 单例行。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1`、`backend/src/main/resources/db/migration/V1__init_schema.sql:6`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1204`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1276`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1066`

### DATA-002 V2 退役变更审查

- 需求：变更审查能力退出产品范围，按依赖顺序删除其表与触发函数。
- 规则：
  - 删除 `task_review_feedback`、`task_review_outcomes`、`task_reviews`。
  - 删除 `prevent_task_review_outcome_update()` 与 `prevent_terminal_task_review_update()`。
  - 这三个表与其不可变触发器在 V1 中曾被创建，因此 V1+V2 之后数据库不再有变更审查相关的表、视图或函数（属"结构性消失"，不是软删除）。
  - 当前代码中已无对应的 mapper 与接口。
- 证据：`backend/src/main/resources/db/migration/V2__remove_change_review.sql:2`、`backend/src/main/resources/db/migration/V2__remove_change_review.sql:6`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1482`

### DATA-003 V3 分支上下文与不可变分支快照

- 需求：引入独立于旧默认版本指针的分支存储，并保证分支快照及其代码片段不可被旧流程改写。
- 规则：
  - 迁移为附加式（additive），旧仓库指针在灰度期保持"仅 legacy 使用"。
  - 新建 `repository_branches`（含 `published_snapshot_id`、`generation`、`preparation_status`/`preparation_error`，唯一约束 `(repo_id,name)`）；已存在且未删除的仓库按 `md5(id || ':branch:' || COALESCE(default_branch,'WORKSPACE'))` 派生稳定分支标识回填。
  - `repository_branches.published_snapshot_id` 通过可延迟外键指向 `branch_snapshots(repo_id,branch_id,id)`，允许先插分支后插快照。
  - 新建 `branch_snapshots`（`commit_sha`、`content_path`，仓库+分支外键）、`branch_read_contexts`（绑定账号、仓库、分支、快照与过期时间）、`knowledge_branch_scopes`（`ALL_BRANCHES`/`SELECTED_BRANCHES` 且与 `branch_ids` 基数互为约束）、`knowledge_branch_scope_history`、`knowledge_branch_validations`、`branch_context_knowledge`。
  - 新增函数 `capture_branch_scope`、`initialize_branch_scope`、`protect_branch_chunk`、`cleanup_deleted_repository_branches` 及其触发器。
  - `protect_branch_chunk` 在删除或更新 `code_chunks` 时，若该片段属于某个分支快照且仓库未逻辑删除，则抛出 `Immutable branch snapshot chunks cannot be rewritten or deleted`；仓库逻辑删除后允许清理。
- 证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:1`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:13`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:27`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:110`、`backend/src/test/java/com/analyzercoder/infrastructure/persistence/BranchIsolationDatabaseTest.java:205`

### DATA-004 V4 分支图谱任务的目标绑定

- 需求：代码图谱任务需要记录其针对的分支与不可变快照。
- 规则：
  - 新建 `index_job_branch_targets`，主键即 `job_id`（外键指向 `index_jobs`，级联删除），并保存仓库、分支、快照。
  - `(repo_id,branch_id,snapshot_id)` 组合外键指向 `branch_snapshots`，快照删除时级联删除目标记录。
  - 新增索引 `idx_branch_graph_target_snapshot` 支撑按仓库+快照检索。
  - 当前没有接口直接暴露该表内容（见第 6 节）。
- 证据：`backend/src/main/resources/db/migration/V4__branch_graph_tasks.sql:1`、`backend/src/main/resources/db/migration/V4__branch_graph_tasks.sql:8`、`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:28`

### DATA-005 V5 分支准备任务表

- 需求：分支级代码操作以持久化任务承载，并保证同一分支同一操作同时只有一个活动任务。
- 规则：
  - 新建 `branch_preparation_jobs`，字段包含 `status`（`QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`）、`stage`、`attempt_token`、`target_commit`、`kind`（`SNAPSHOT`/`VECTORS`）、`target_snapshot`、`error`、时间戳。
  - 检查约束要求 `SNAPSHOT` 类型的目标快照为空、`VECTORS` 类型的目标快照非空。
  - 部分唯一索引 `branch_preparation_one_active` 约束 `(branch_id, kind)` 上仅一个 `QUEUED`/`RUNNING` 记录。
  - `account_id` 指向创建任务的账号；提交账号在任务执行时会重新校验是否仍启用且不处于强制改密状态。
- 证据：`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:1`、`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:17`、`backend/src/main/resources/db/migration/V5__branch_preparation_jobs.sql:19`、`backend/src/main/java/com/analyzercoder/application/branch/BranchPreparationJobs.java:401`

### DATA-006 V6 向量复用索引

- 需求：同仓库内相同内容与相同向量配置的向量可以复用。
- 规则：
  - 新增索引 `idx_chunk_embeddings_reuse`，列为 `(repo_id, content_hash, model, dimension, retrieval_capability)`。
  - 复用是仓库内局部行为，要求内容摘要与向量配置完全一致；跨仓库不复用。
- 证据：`backend/src/main/resources/db/migration/V6__branch_embedding_reuse.sql:1`、`backend/src/main/resources/db/migration/V6__branch_embedding_reuse.sql:2`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:920`

### DATA-007 V7 分支生命周期与来源凭据

- 需求：补齐分支生命周期（归档/恢复）、把 Markdown 来源与知识来源链接改为分支维度，并把默认版本问答与旧快照对齐到分支模型。
- 规则：
  - `repository_branches` 增加 `tracking_status`（`ACTIVE`/`ARCHIVED`）、`archived_at`、`archived_by`，并建部分索引 `idx_repository_branches_active`。
  - 该迁移为 V3 之后创建的仓库补种分支，并把旧 `repositories.current_snapshot_id` 对应的快照写入 `branch_snapshots`（主键复用旧快照标识，`ON CONFLICT(id) DO NOTHING`）。
  - 新增函数 `synchronize_default_repository_branch()` 与触发器 `repositories_default_branch_snapshot`，把默认分支的发布状态同步到分支表。
  - `qa_conversations` 增加 `branch_id`、`context_id`、`branch_name`、`commit_sha`，并按既有快照回填；新外键要求 `(repo_id,branch_id)` 存在于分支表，上下文外键在上下文删除时置空。
  - `repository_markdown_sources` 增加非空 `branch_id`，唯一约束从 `(repo_id,file_path)` 改为 `(repo_id,branch_id,file_path)`：同一 Markdown 路径在不同分支是独立来源。
  - `knowledge_card_markdown_source_links` 增加非空 `source_branch_id` 与外键，来源索引重建为 `(repo_id,source_branch_id,source_path,generated_at DESC)`。
  - 新建 `repository_project_drafts`（草稿生命周期 `DRAFT`/`SOURCE_CONFIGURED`/`IMPORTING`/`READY`/`FAILED`），并给 `repository_import_jobs` 增加 `project_draft_id`。
  - 迁移中包含列注释作为语义约定：归档只停止新上下文和新任务、不删除历史证据；问答分支允许为空；Markdown 来源分支身份稳定。
- 证据：`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:3`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:36`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:64`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:88`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:115`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:126`

### DATA-008 V8 分支代码操作与内容发布标记

- 需求：把"内容索引完成"和"最近同步时间"变成快照级事实，并把分支任务种类扩展到代码操作，同时收窄默认分支触发器的触发条件。
- 规则：
  - `branch_snapshots` 增加 `content_indexed_at`（注释：该不可变快照的内容索引发布标记）；`repository_branches` 增加 `last_synced_at`，并按已发布快照回填。
  - 回填 `content_indexed_at` 的条件是"该快照已存在代码片段"。
  - 分支任务种类扩展为 `SNAPSHOT`/`SYNC`/`CONTENT`/`GRAPH`/`VECTORS`/`PREPARE`，目标快照约束改为"`SNAPSHOT`/`SYNC`/`PREPARE` 可空，其余必须非空"。
  - 新增索引 `branch_job_snapshot`；注释明确 `repositories.default_branch` 只是初始阅读偏好，同步与索引属于各分支。
  - 重建触发器 `repositories_default_branch_snapshot`，把触发列从 `current_snapshot_id,current_snapshot_path,current_commit,default_branch` 收窄为前三列：仅改阅读偏好不再发布或创建分支代码。
  - 新增 `initialize_branch_content_state()` 触发器：插入分支快照时若已存在对应代码片段，则补写 `content_indexed_at`（适配旧版先写块后发布的顺序）。
- 证据：`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:2`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:10`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:16`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:18`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:21`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:27`

### DATA-009 V9 退役跨仓工程与数据守卫

- 需求：跨仓工程项目退出产品范围，迁移必须在存在跨仓数据时硬失败，不允许静默扩大或缩小知识范围。
- 规则：
  - 迁移先对 `engineering_projects`、`engineering_project_repositories`、`engineering_project_contracts`、`knowledge_cards`、`knowledge_card_revisions` 加 `ACCESS EXCLUSIVE` 锁。
  - 守卫一：三张工程表中任一存在数据即抛 `Cross-repository project data must be exported/migrated before V9`。
  - 守卫二：`knowledge_cards` 或 `knowledge_card_revisions` 的 `scope_payload` 中 `repositoryIds`/`serviceNames`/`contractIds` 任一数组非空即抛 `Cross-repository knowledge scopes must be migrated before V9`。
  - 通过守卫后，先禁用 `trg_knowledge_card_revision` 触发器，再从两张表的 `scope_payload` 中移除上述三个键，然后重新启用触发器（避免结构清理被记录成一次知识修订）。
  - 重建检查约束：`scope_payload` 必须是对象且三个新键存在且类型正确，并显式禁止上述三个已退役键出现；默认值收敛为 `{"pathPatterns":[],"symbols":[],"modules":[]}`。
  - 最后按依赖顺序删除 `engineering_project_contracts`、`engineering_project_repositories`、`engineering_projects`。
  - 结果：V1+V9 之后不存在任何工程项目相关表与列。
- 证据：`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:2`、`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:10`、`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:23`、`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:31`、`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:46`、`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:63`

### DATA-010 账号与会话域表

- 需求：账号、会话、验证码、失败计数、成员权限、治理锁与访问令牌各表职责单一。
- 规则：
  - `accounts`：`account_role` 为真实角色列，`role` 是由 `account_role` 生成并存储的兼容列；`account_version` 为乐观锁且有 `> 0` 检查；用户名唯一性基于 `LOWER(BTRIM(username))`。
  - `accounts.last_repository_id` 外键指向仓库并在仓库删除时置空（该外键在 `repositories` 建表后追加，属于循环引用拆解）。
  - `login_sessions` 以 `token_hash` 为主键，保存 `csrf_token`、空闲与绝对过期时间，账号删除时级联删除。
  - `login_captcha_challenges`（答案摘要 + 过期/消费时间）、`login_failure_counters`（规范化用户名为主键的连续失败计数）为登录风控的持久化状态。
  - `repository_permissions` 主键 `(account_id, repo_id)`，`permission_level` 检查约束只允许 `READ`/`MAINTAIN`/`MANAGE`；表注释明确不包含由 `owner_account_id` 表达的 OWNER。
  - `repository_governance_locks` 每仓库一行，保存治理操作乐观锁版本。
  - `account_access_tokens` 保存令牌摘要（唯一）、前缀、过期与撤销时间，并有 `expires_at > created_at` 检查。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:12`、`backend/src/main/resources/db/migration/V1__init_schema.sql:52`、`backend/src/main/resources/db/migration/V1__init_schema.sql:113`、`backend/src/main/resources/db/migration/V1__init_schema.sql:168`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1859`

### DATA-011 仓库与来源域表

- 需求：一个仓库固定一个代码来源与默认分支，只维护一个"当前已发布版本"指针。
- 规则：
  - `repositories` 关键字段：`path`（唯一，未删除行）、`source_type`（`LOCAL_GIT`/`REMOTE_GIT`/`GITLAB`/`ZIP`）、`default_branch`、`current_commit`、`worktree_digest`、`worktree_dirty`、`current_snapshot_id`、`current_snapshot_path`、`snapshot_created_at`、`codegraph_path`、`owner_account_id`、`ownership_version`、`repository_status`、`repository_version`（`> 0` 检查）、`deleted_at`。
  - 唯一约束：`uq_repositories_normalized_path`（全表 `path` 唯一）与 `uq_repositories_owner_normalized_name`（同所有者下规范化名称唯一，仅未删除行参与）。
  - `git_credentials`：类型限 `GIT_HTTP_TOKEN`/`GITLAB_PAT`，状态限 `ACTIVE`/`DISABLED`/`INVALID`，保存密文、IV、摘要与算法（默认 `AES-256-GCM`）、掩码、服务地址、过期时间、逻辑禁用时间与创建/更新账号。
  - `repository_credential_bindings`：主键 `(repository_id, usage_type)`，`usage_type` 目前只允许 `CLONE`；凭据删除为 `RESTRICT`，即被绑定的凭据不可直接删除。
  - `repository_import_jobs`：状态限 `QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`/`CANCELED`，含 `cancel_requested`、结果仓库外键与项目草稿外键。
  - `repository_project_drafts`：草稿态建仓记录，`result_repository_id` 在来源校验导入成功后回填。
  - `repository_deletion_tombstones`：逻辑删除后的物理清理任务，含 `cleanup_status`、`retry_count`、`last_error_code`、`cleanup_updated_at`。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:55`、`backend/src/main/resources/db/migration/V1__init_schema.sql:107`、`backend/src/main/resources/db/migration/V1__init_schema.sql:199`、`backend/src/main/resources/db/migration/V1__init_schema.sql:251`、`backend/src/main/resources/db/migration/V1__init_schema.sql:266`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:126`

### DATA-012 分支与快照域表

- 需求：分支有稳定身份与生命周期，快照是不可变的内容版本身份。
- 规则：
  - `repository_branches`：主键 `id`；`(repo_id,name)` 唯一并提供 `(repo_id,id)` 组合唯一；`generation` 用于乐观并发；`preparation_status` 限 `PENDING`/`BUILDING`/`READY`/`FAILED`；`tracking_status` 限 `ACTIVE`/`ARCHIVED`；`published_snapshot_id` 通过可延迟外键指向同仓库同分支的快照。
  - `branch_snapshots`：保存 `commit_sha`、`content_path`、`content_indexed_at`；`(repo_id,branch_id)`、`(repo_id,branch_id,id)`、`(branch_id,id)` 三组唯一约束支撑组合外键。
  - 分支归档要求 `MANAGE`，且分支上不能存在 `QUEUED`/`RUNNING` 的准备工作（否则 409 `BRANCH_HAS_ACTIVE_TASK`）；恢复要求 `MANAGE`。
  - 同一分支名归档后不允许重新跟踪，必须先恢复，返回 409 `BRANCH_ARCHIVED`（避免继承错误的历史身份）。
  - `repository_branches.last_synced_at` 记录最近一次同步时间。
- 证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:2`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:17`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:100`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:122`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:149`

### DATA-013 代码片段与索引域表

- 需求：代码/资产片段是检索与 Agent 上下文的最小持久化单元，并以快照与内容摘要为一致性依据。
- 规则：
  - `code_chunks` 关键字段：`snapshot_id`、`commit_sha`、`file_path`、`symbol_id`/`symbol_name`/`symbol_kind`、`language`、`chunk_type`、`asset_type`（检查约束 `CODE`/`DOCUMENT`/`RULE`/`TASK`/`CONFIG`）、`start_line`/`end_line`、`content`、`content_hash`。
  - 片段仓储在删除与重定位（rebase）时显式排除属于分支快照的片段：`deleteByRepositoryId`、`deleteByPaths`、`rebaseUnchanged`、`latestIndexedCommit` 都带 `NOT EXISTS(SELECT 1 FROM branch_snapshots b WHERE b.id=code_chunks.snapshot_id)` 条件。
  - `index_jobs` 保存任务类型、状态、阶段、执行模式、回退原因、失败码、心跳与固定超时；部分唯一索引保证每仓库一个活动任务。
  - 分支内容索引在事务内先对快照行加 `FOR UPDATE` 并复查是否已索引，避免重复任务写入重复片段。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:375`、`backend/src/main/resources/mappers/CodeChunkMapper.xml:42`、`backend/src/main/resources/db/migration/V1__init_schema.sql:348`、`backend/src/main/java/com/analyzercoder/application/branch/BranchContentIndexService.java:96`

### DATA-014 向量域表

- 需求：代码片段与知识卡片各有向量表，显式记录生成能力，维度必须与向量长度一致。
- 规则：
  - `chunk_embeddings`：主键即 `chunk_id`（片段删除级联删除），保存 `model`、`dimension`、`embedding vector`、`content_hash`、`retrieval_capability`（`CHARACTER_HASH` 或 `SEMANTIC_EMBEDDING`，非空）。
  - `knowledge_card_embeddings`：主键即 `card_id`，额外保存 `revision`，`model` 默认 `local-hash-64`，能力列与检查约束同上。
  - 两表的维度检查为 1 到 4096，并有 `vector_dims(embedding) = dimension` 检查，保证维度与向量长度一致。
  - 能力列的取值来源与语义：`local-hash-64` 或配置为 `LOCAL_HASH` 的模型为 `CHARACTER_HASH`，否则为 `SEMANTIC_EMBEDDING`；注释明确"只有 `SEMANTIC_EMBEDDING` 才表示外部模型语义向量"。
  - 补齐向量的判定条件是"缺少向量，或内容摘要变化，或模型/维度/能力与当前启用模型不一致"。
  - 基线刻意不为向量列建 HNSW 索引：注释说明灵活维度向量使用精确余弦扫描。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:421`、`backend/src/main/resources/db/migration/V1__init_schema.sql:675`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1218`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:428`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1084`

### DATA-015 知识与修订域表

- 需求：知识卡片以"当前内容 + 修订历史"两表保存，并把发布状态、来源版本状态、人工评审状态三种状态彼此独立。
- 规则：
  - `knowledge_cards`：`publication_status`（`DRAFT`/`PUBLISHED`/`ARCHIVED`）、`source_version_status`（`UNVERIFIED`/`CURRENT`/`SUSPECT`/`STALE`）、`review_status`（`UNREVIEWED`/`APPROVED`/`CHANGES_REQUESTED`）、`verified_commit`、`source_version_checked_at`、`reviewed_by`/`reviewed_at` 分别独立存储。
  - 工程知识字段：`knowledge_kind`（11 种取值）、`severity`（`INFO`/`WARNING`/`CRITICAL`）、`enforcement`（`REFERENCE`/`ADVISORY`/`REQUIRED`）、`owner_account_id`、`scope_payload`、`obligations_payload`、`last_verified_snapshot_id`、`verification_note`。
  - `scope_payload` 与 `obligations_payload` 有 JSONB 结构检查：前者要求 `pathPatterns`/`symbols`/`modules` 为数组且禁止出现已退役的跨仓键；后者要求 `requiredTests`/`requiredApproverAccountIds`/`instructions`/`prohibitedPathPatterns` 为数组且 `knowledgeUpdateRequired` 为布尔。
  - `knowledge_card_revisions` 主键 `(card_id, revision)`，保存该修订的正文、标签、`publication_status` 与工程知识字段快照。
  - `knowledge_code_refs`：主键 `(card_id, revision, position)`，保存关联的仓库、快照、片段、文件、符号、行号与内容摘要；片段删除时 `chunk_id` 置空（位置事实仍保留）。
  - `knowledge_drift_events`：记录来源版本的自动漂移与人工复核，含前后快照/提交、前后状态、触发类型（`AUTOMATIC_DIFF`/`MANUAL_CONFIRM_CURRENT`/`MANUAL_MARK_STALE`）与结构化原因数组；`AUTOMATIC_DIFF` 上按 `(card_id, card_revision, to_snapshot_id, trigger_type)` 建部分唯一索引防重。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:600`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1291`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1394`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1601`、`backend/src/main/resources/db/migration/V1__init_schema.sql:701`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1545`

### DATA-016 Markdown 来源域表

- 需求：仓库 Markdown 来源按分支保存稳定身份，知识修订另行保存生成时的精确版本凭据。
- 规则：
  - `repository_markdown_sources`：`(repo_id,branch_id,file_path)` 唯一；`content_hash` 必须是 64 位小写十六进制；`asset_type` 限 `DOCUMENT`/`RULE`/`TASK`；`line_count`、`byte_size` 必须为正；`file_path` 非空且禁止 `..` 路径段。
  - `knowledge_card_markdown_source_links`：主键 `(card_id, revision)`（每个修订最多一条来源凭据），保存生成时的来源快照、路径、内容摘要与生成时间；`source_id` 在来源行删除时置空，但快照/路径/摘要事实保留。
  - 路径与哈希的检查约束在两表上一致，避免写入越界路径或非法摘要。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1102`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:90`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1139`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1151`

### DATA-017 附件域表

- 需求：知识附件以内容寻址路径保存，大小与类型受限，修订通过关联表引用附件。
- 规则：
  - `knowledge_attachments`：`size_bytes > 0`、`sha256` 为 64 位摘要、`storage_path` 记录受管路径、`scan_status` 默认 `READY`，`(repo_id,id)` 唯一。
  - `knowledge_card_attachment_refs`：主键 `(card_id, revision, attachment_id)`，附件外键为 `RESTRICT`（被知识引用的附件不可删除），并保存显示顺序。
  - 应用层限制：图片扩展名（png/jpg/jpeg/webp/gif）单文件上限 10 MiB；文档类扩展名（pdf/txt/md/csv/json/docx/xlsx/pptx）上限 50 MiB；其他类型拒绝；单次上传同时按流式累计校验大小并计算 SHA-256。
  - 修订级限制：每个知识修订最多 20 个附件；同一修订的附件字节总和不得超过 200 MiB（在保存修订关联时逐个累加校验）。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:736`、`backend/src/main/resources/db/migration/V1__init_schema.sql:765`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:27`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:52`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:138`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:149`、`backend/src/main/resources/mappers/KnowledgeAttachmentMapper.xml:27`

### DATA-018 问答会话域表

- 需求：问答以"线程 + 轮次"组织，并把引用证据与回答快照独立保存。
- 规则：
  - `qa_conversations`：`thread_id` 默认 `gen_random_uuid()`，`turn_no` 从 1 开始（`> 0` 检查），`(thread_id, turn_no)` 唯一；`status` 限 `RUNNING`/`COMPLETED`/`STOPPED`/`FAILED`；`evidence_status` 限 7 种取值（`CITATION_COMPLETE`/`CITATION_INCOMPLETE`/`SUPPORTED`/`DEGRADED`/`MODEL_OUTPUT_REJECTED`/`INSUFFICIENT`/`UNKNOWN`）；`answer_payload` 为可原样恢复的回答快照。
  - 幂等：`(account_id, repo_id, client_request_id)` 在请求标识非空时唯一。
  - 分支维度：`branch_id`、`context_id`（上下文删除时置空）、`branch_name`、`commit_sha`；`branch_id` 允许为空以兼容旧默认版本问答。
  - `qa_citations`：保存来源类型（`CODE`/`KNOWLEDGE`）、片段外键、知识卡片外键、文件/符号/行号、证据摘要、排序与引用快照；片段或知识删除时对应外键置空，但引用文本事实保留。
  - 会话删除分两种入口：按线程删除（可额外要求分支上下文）与按仓库级联删除。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:503`、`backend/src/main/resources/db/migration/V1__init_schema.sql:555`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:64`、`backend/src/main/resources/db/migration/V1__init_schema.sql:567`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:424`

### DATA-019 图谱产物与启发式边域表

- 需求：外部 CodeGraph CLI 的产物与索引期字符串匹配得到的启发式调用候选必须分开存储。
- 规则：
  - `heuristic_call_edges`（由 V1 的 `code_graph_edges` 重命名而来）：`(repo_id, snapshot_id, source_chunk_id, target_chunk_id, relation)` 唯一；表注释明确这是"符号名+左括号"字符串规则推断的候选，不是 CodeGraph CLI 关系。
  - `codegraph_artifacts`：保存 `cli_version`、`status`、`artifact_path`、`node_count`/`edge_count`、`created_at`/`published_at`。
  - 发布语义：发布前把同一快照旧的 `PUBLISHED` 行改为 `RETIRED`，再插入新行（同一事务）；另有 `retirePublished` 只退役不属于任何分支快照的旧产物。
  - 检索只使用已发布产物对应的快照（SQL 契约测试断言 `g.snapshot_id = r.current_snapshot_id` 且来源表为 `heuristic_call_edges`）。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:444`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1204`、`backend/src/main/resources/db/migration/V1__init_schema.sql:471`、`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:4`、`backend/src/test/java/com/analyzercoder/infrastructure/persistence/CurrentSnapshotSqlContractTest.java:65`

### DATA-020 任务域表

- 需求：索引任务与分支准备任务分别建表，索引任务另有可选的分支快照目标表。
- 规则：
  - `index_jobs`：类型/状态/阶段/执行模式/回退原因/失败码/心跳/固定超时/起止时间；`uq_index_jobs_one_active_per_repository` 为部分唯一索引；`idx_index_jobs_running_timeout` 仅覆盖运行中与取消中的行。
  - `branch_preparation_jobs`：见 DATA-005，V8 扩展种类与目标快照约束。
  - `index_job_branch_targets`：见 DATA-004。
  - `repository_import_jobs` 与 `repository_deletion_tombstones` 属于仓库领域，不属于任务中心展示范围。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:348`、`backend/src/main/resources/db/migration/V1__init_schema.sql:372`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1268`、`backend/src/main/resources/mappers/IndexJobMapper.xml:47`

### DATA-021 模型配置与密钥域表

- 需求：问答 provider 配置只追加版本，向量模型配置可更新并单例启用，密钥单独加密存放。
- 规则：
  - `encrypted_secret_versions`：密文、IV、明文指纹摘要（`CHAR(64)`）、算法、创建账号与时间。
  - `llm_provider_configs`：`config_version` 唯一且由序列递增；协议仅 `OPENAI_COMPATIBLE`；超时/温度/输出上限有区间检查；`fingerprint` 为 `CHAR(64)`。
  - `llm_provider_runtime_states`：与配置一对一（`config_id` 主键），保存可用性、最近检测、连续失败、熔断状态与开启时间；“最近检测”外键在检测记录删除时置空。
  - `llm_provider_activation`：`singleton_id = 1` 单例、`active_config_id` 可为空、`activation_version` 并发控制；基线插入单例行。
  - `vector_model_configs`：`model` 全局唯一；协议限 `LOCAL_HASH`/`OPENAI_COMPATIBLE`；维度 1–4096；请求超时 3000–120000，默认 30000；密钥版本可空。
  - `vector_model_activation`：`singleton_id = 1` 且检查约束固定等于 1，`active_config_id` 非空（默认启用内置 `local-hash-64`）。
  - `system_settings`：`setting_key` 主键、`sensitive` 控制读取掩码、记录最后修改账号与时间；种子键 `externalModelEnabled=false`。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:800`、`backend/src/main/resources/db/migration/V1__init_schema.sql:858`、`backend/src/main/resources/db/migration/V1__init_schema.sql:900`、`backend/src/main/resources/db/migration/V1__init_schema.sql:928`、`backend/src/main/resources/db/migration/V1__init_schema.sql:819`、`backend/src/main/resources/db/migration/V1__init_schema.sql:845`、`backend/src/main/resources/db/migration/V1__init_schema.sql:781`

### DATA-022 审计域表

- 需求：安全、账号与仓库治理事件集中写入审计表，扩展信息以脱敏 JSON 保存。
- 规则：
  - `audit_events` 保存操作账号、目标账号、目标仓库、事件类型、结果、请求追踪标识、来源地址与脱敏扩展信息；三个外键在目标被删除时置空，保证审计记录不随业务数据消失。
  - 排序索引为 `(created_at DESC, id)`，按操作人检索的索引为 `(actor_account_id, created_at DESC)`。
  - 本表字段与写入语义的完整需求见账号与权限文档，本文档只记录其数据形态。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:316`、`backend/src/main/resources/db/migration/V1__init_schema.sql:341`

### DATA-023 阅读上下文域表

- 需求：阅读上下文（`contextId`）把一次读取会话绑定到确定的仓库、分支与不可变快照。
- 规则：
  - `branch_read_contexts`：保存账号、仓库、分支、快照与非空过期时间；`(repo_id,branch_id,snapshot_id)` 组合外键指向分支快照，级联删除；`idx_branch_context_expiry` 支撑按过期时间清理。
  - `branch_context_knowledge`：`(context_id, card_id)` 主键，保存上下文创建时固化的知识卡片修订号；上下文删除时级联删除。
  - 上下文有效期 1 小时（应用层生成），过期清理由后台 worker 每 3600000 ms 执行一次 `DELETE WHERE expires_at <= CURRENT_TIMESTAMP`。
  - 解析上下文时校验归属账号、过期时间与分支一致性。
- 证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:30`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:75`、`backend/src/main/java/com/analyzercoder/application/branch/BranchCodeOperationsService.java:184`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:38`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:350`

### DATA-024 触发器与数据库函数

- 需求：数据库层承担默认分支同步、分支身份初始化、快照保护、仓库清理级联与知识修订留档。
- 规则：
  - `synchronize_default_repository_branch()`（触发器 `repositories_default_branch_snapshot`，`AFTER INSERT OR UPDATE OF current_snapshot_id,current_snapshot_path,current_commit`）：确保默认分支存在，若已有当前快照则写入 `branch_snapshots`（`ON CONFLICT(id) DO NOTHING`）并把分支置为 `READY`、清除准备错误。
  - `initialize_branch_scope()`（`trg_initialize_branch_scope`，`knowledge_cards` 插入后）：为新卡片创建/获取默认分支并写入 `knowledge_branch_scopes` 与 `knowledge_branch_scope_history`。
  - `capture_branch_scope()`（`trg_capture_branch_scope`，`knowledge_cards.revision` 更新后）：把当前适用范围快照写入历史表（`ON CONFLICT DO NOTHING`）。
  - `protect_branch_chunk()`（`trg_protect_branch_chunk`，`code_chunks` 删除或更新前）：拒绝改写或删除属于分支快照的片段，除非仓库已逻辑删除。
  - `initialize_branch_content_state()`（`trg_initialize_branch_content_state`，`branch_snapshots` 插入前）：若该快照已有代码片段则补写 `content_indexed_at`。
  - `capture_knowledge_card_revision()`（`trg_knowledge_card_revision`，`knowledge_cards` 插入或指定列更新后）：把当前内容写入 `knowledge_card_revisions`，冲突时**更新**该修订行。
  - `cleanup_deleted_repository_branches()`（`trg_cleanup_deleted_repository_branches`，`repositories.repository_status` 变为 `DELETED` 后）：删除该仓库的全部分支。
- 证据：`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:36`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:93`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:82`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:110`、`backend/src/main/resources/db/migration/V8__branch_code_operations.sql:27`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1447`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:124`

### DATA-025 已退役的触发器与函数

- 需求：被移除能力留下的触发器与函数必须一并清理，且不改变仍在使用中的知识状态语义。
- 规则：
  - V1 内先建后删：`confirm_knowledge_code_version()`（原用于在知识编辑时记录确认依据的提交）与 `mark_repository_knowledge_stale()`（原用于仓库提交变化后按仓库维度标记知识待复核）；后者被 `knowledge_drift_events` 驱动的检查取代，不再由触发器写状态。
  - V2 删除变更审查的 `prevent_terminal_task_review_update()` 与 `prevent_task_review_outcome_update()`。
  - V9 在结构清理期间临时禁用再启用 `trg_knowledge_card_revision`，避免清理动作被记录成知识修订。
  - 当前不存在任何阻止 `knowledge_card_revisions` 行被更新的触发器：该表的更新语义是 `capture_knowledge_card_revision()` 的 `ON CONFLICT ... DO UPDATE`（同一修订号在卡片内容变更但修订号未变时会被覆盖）。"知识修订不可变保护"在当前实现中**不成立**，需人工确认是否为预期设计。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1276`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1542`、`backend/src/main/resources/db/migration/V2__remove_change_review.sql:6`、`backend/src/main/resources/db/migration/V9__remove_cross_repository_projects.sql:31`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1459`

### DATA-026 检索与排序关键索引

- 需求：检索与列表查询依赖一组固定的复合索引，向量检索刻意走精确扫描。
- 规则：
  - 片段检索：`idx_code_chunks_repo_file`、`idx_code_chunks_repo_symbol`、`idx_code_chunks_repo_created_at`、`idx_code_chunks_repo_language`、`idx_code_chunks_repo_snapshot`、`idx_code_chunks_repo_asset_type`、`idx_code_chunks_repo_path_line`。
  - 知识检索：`idx_knowledge_cards_repo_status`、`idx_knowledge_cards_engineering_policy`、`idx_knowledge_refs_current_lookup`（按仓库+文件+行号+内容摘要定位当前引用）、`idx_knowledge_code_refs_card`、`idx_knowledge_code_refs_repo_file`。
  - 图谱检索：`idx_heuristic_call_edges_source`/`_target`（按仓库+符号）；`idx_codegraph_artifacts_repo_snapshot`。
  - 向量检索：`idx_chunk_embeddings_repo`、`idx_knowledge_card_embeddings_repo`、`idx_chunk_embeddings_reuse`（复用查找）。
  - 顺序敏感列表：任务与历史列表按 `created_at DESC, id DESC` 排序，`idx_index_jobs_repo_created_at`、`idx_knowledge_card_revisions_repo_card`、`idx_knowledge_drift_card_created`、`idx_qa_conversations_account_repo_created` 等服务于该类查询。
  - 没有为 `vector` 列建立 HNSW/IVFFlat 索引，原因是 pgvector 的这类索引要求固定表达式维度，而系统支持可变维度。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:414`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1088`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1091`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1432`、`backend/src/main/resources/db/migration/V1__init_schema.sql:442`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1084`

### DATA-027 受管数据根目录布局与配额

- 需求：平台快照、导入暂存、知识附件与图谱产物全部落在由同一个受管根目录派生的路径下，并有文件数/字节数配额。
- 规则：
  - 根目录由 `APP_MANAGED_DATA_ROOT`（`app.repository.managed-data-root`）指定；容器部署必须挂载持久化卷。
  - 派生目录：快照根 `${managed-data-root}/repositories`（`app.repository.snapshot-root`）、导入暂存 `${managed-data-root}/staging/imports`（`app.repository.import-root`）。
  - 旧版受管快照路径为 `<snapshot-root>/<repoId>/current-<snapshotId>/content`，同目录下存在 `.staging-<snapshotId>` 与 `current-<snapshotId>`，发布使用原子目录移动并置为只读。
  - 分支快照路径为 `<snapshot-root>/<repoId>/branch-<snapshotId>/`，内有 `source.zip` 与 `content`。
  - 知识附件路径为 `<managed-data-root>/repositories/<repoId>/knowledge/objects/<sha 前 2 位>/<sha>`，上传暂存为 `<managed-data-root>/staging/knowledge/<uuid>`；路径必须仍在受管根目录内。
  - 图谱产物路径为 `<artifact-root>/<repoId>/codegraph/<snapshotId>/<artifactId>/project/.codegraph`，其中 `app.codegraph.artifact-root` 默认等于 `${managed-data-root}/repositories`。
  - 配额与上限：单快照最多 20000 个文件（`APP_REPOSITORY_SNAPSHOT_MAX_FILES`）、最多 2147483648 字节（2 GiB，`APP_REPOSITORY_SNAPSHOT_MAX_TOTAL_BYTES`）；源码预览单文件上限 2097152 字节（2 MiB）；代码索引纳入的单文件上限 524288 字节（512 KiB，硬编码默认值）。
  - 仓库路径与受管路径分别校验：仓库必须位于 `APP_REPOSITORY_ALLOWED_ROOTS` 白名单内，受管路径必须位于受管根目录内。
- 证据：`backend/src/main/resources/application.yml:66`、`backend/src/main/resources/application.yml:70`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:57`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:78`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:89`、`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:77`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/RepositoryPathPolicy.java:47`

### DATA-028 快照删除的引用检查

- 需求：删除分支快照前必须检查所有持久引用，只有零引用才允许清理，并连带清理派生物。
- 规则：
  - 查询与删除都需要 `MANAGE`；快照不存在返回 404 `SNAPSHOT_NOT_FOUND`。
  - 引用检查覆盖 6 类引用方：被分支发布指针引用（`repository_branches.published_snapshot_id`）、未过期的阅读上下文（`branch_read_contexts`）、任务引用（`branch_preparation_jobs.target_snapshot` 的活动任务 + `index_job_branch_targets`）、问答引用（`qa_conversations.snapshot_id`）、知识引用（`knowledge_code_refs` + `knowledge_branch_validations` + `knowledge_card_markdown_source_links.source_snapshot_id`）、Markdown 来源引用（`repository_markdown_sources.snapshot_id`）。
  - 任一计数非零即不可删除，删除接口返回 409 `SNAPSHOT_REFERENCED`。
  - 检查结果以结构化形式返回：是否可删除、内容路径以及 6 类引用各自的计数。
  - 删除流程先对快照行加 `FOR UPDATE`，删除顺序为：先删 `branch_snapshots` 身份行（解锁 V3 的片段保护触发器），再删该快照的 `codegraph_artifacts` 与 `code_chunks`，最后清理受管文件目录；影响行数不为 1 时返回 409 `SNAPSHOT_CHANGED`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:42`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:87`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:152`、`backend/src/main/java/com/analyzercoder/application/branch/BranchArtifactRetentionService.java:56`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:121`

### DATA-029 软删除与仓库物理清理

- 需求：仓库删除是两阶段过程：先逻辑删除并停止可见，再由后台任务清理派生数据与受管文件。
- 规则：
  - 逻辑删除会把状态置为 `DELETING` 并写入 `deleted_at`，同时插入 `repository_deletion_tombstones`；此时仓库对常规查询不可见（大量查询带 `deleted_at IS NULL`）。
  - 未删除行的唯一性约束只在 `deleted_at IS NULL` 时生效，因此删除后可重新登记同名或同路径仓库；`countByPath`、`countByOwnerAndNormalizedName` 等唯一性检查同样带 `deleted_at IS NULL`。
  - 清理任务以 `FOR UPDATE SKIP LOCKED` 认领 `PENDING`/`FAILED` 或超过 10 分钟未更新的 `RUNNING` 记录；失败时累加 `retry_count` 并记录错误码，可被重试。
  - 清理内容按依赖顺序删除问答、知识卡片、启发式边、图谱产物、向量、片段、索引任务、成员授权、治理锁，最后把仓库置为 `DELETED`、路径改写为 `[deleted]/<id>`、清空 `current_snapshot_id`/`current_snapshot_path`/`codegraph_path`/`worktree_digest`。
  - 清理还会删除受管文件目录 `<snapshot-root>/<repoId>`；仓库状态变为 `DELETED` 时由 V3 触发器删除其分支行。
  - 上下文过期清理是独立的定时任务，不做引用判断，直接按过期时间删除。
- 证据：`backend/src/main/java/com/analyzercoder/application/repository/RepositoryDeletionService.java:34`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:99`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:118`、`backend/src/main/resources/db/migration/V1__init_schema.sql:108`、`backend/src/main/resources/mappers/RepositoryMapper.xml:82`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:124`

## 3 数据与状态

### 表清单（按领域分组，共 47 张）

| 领域 | 表 |
| --- | --- |
| 账号与会话 | `accounts`、`login_sessions`、`login_captcha_challenges`、`login_failure_counters`、`repository_permissions`、`repository_governance_locks`、`account_access_tokens` |
| 仓库与来源 | `repositories`、`git_credentials`、`repository_credential_bindings`、`repository_import_jobs`、`repository_project_drafts`、`repository_deletion_tombstones` |
| 分支与快照 | `repository_branches`、`branch_snapshots` |
| 代码片段与索引 | `code_chunks`、`index_jobs` |
| 向量 | `chunk_embeddings`、`knowledge_card_embeddings`、`vector_model_configs`、`vector_model_activation` |
| 知识与修订 | `knowledge_cards`、`knowledge_card_revisions`、`knowledge_code_refs`、`knowledge_drift_events` |
| Markdown 来源 | `repository_markdown_sources`、`knowledge_card_markdown_source_links` |
| 附件 | `knowledge_attachments`、`knowledge_card_attachment_refs` |
| 问答会话 | `qa_conversations`、`qa_citations` |
| 图谱产物 | `codegraph_artifacts`、`heuristic_call_edges` |
| 任务 | `branch_preparation_jobs`、`index_job_branch_targets` |
| 模型配置 | `llm_provider_configs`、`llm_provider_runtime_states`、`llm_provider_activation`、`llm_connectivity_checks`、`encrypted_secret_versions`、`system_settings` |
| 审计 | `audit_events` |
| 上下文 | `branch_read_contexts`、`branch_context_knowledge`、`knowledge_branch_scopes`、`knowledge_branch_scope_history`、`knowledge_branch_validations` |

### 已退役结构（不再存在）

| 迁移 | 消失的对象 | 数据守卫 |
| --- | --- | --- |
| V2 | `task_reviews`、`task_review_outcomes`、`task_review_feedback` 及其两个不可变触发器函数 | 无（直接 `DROP TABLE IF EXISTS`） |
| V9 | `engineering_projects`、`engineering_project_repositories`、`engineering_project_contracts`，以及 `scope_payload` 中的 `repositoryIds`/`serviceNames`/`contractIds` | 存在跨仓工程行或跨仓知识范围时迁移硬失败，要求先导出/迁移 |

### 组合外键与循环引用

- `accounts.last_repository_id` → `repositories(id)` 在 `repositories` 建表后追加，避免建表顺序死锁。
- `repository_branches.published_snapshot_id` → `branch_snapshots(repo_id,branch_id,id)` 为 `DEFERRABLE INITIALLY DEFERRED`，允许"插入分支 → 插入快照 → 回填指针"的三步写入在同一事务内完成。
- `branch_snapshots` 与 `repository_branches` 之间通过 `(repo_id,branch_id)` 组合外键互为约束，保证快照不会挂到其他仓库的分支上。

## 4 接口清单

数据模型主要通过以下接口被读写（完整清单见各领域文档）：

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/repositories` 与 `/api/repositories/page` | 列出/分页查询未删除仓库 | 登录用户（按可见性过滤） |
| POST | `/api/repositories` | 登记本地仓库 | 登录用户 |
| PATCH | `/api/repositories/{repositoryId}` | 修改名称/说明/默认分支（乐观锁版本） | `MANAGE` |
| DELETE | `/api/repositories/{repositoryId}` | 逻辑删除仓库（写入清理任务） | 所有者或超级管理员 |
| GET/POST | `/api/repositories/{repositoryId}/branches` | 列出/跟踪分支 | `READ` / `MAINTAIN` |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/archive` | 归档分支 | `MANAGE` |
| POST | `/api/repositories/{repositoryId}/branches/{branchId}/restore` | 恢复分支 | `MANAGE` |
| POST | `/api/repositories/{repositoryId}/contexts` | 创建/解析阅读上下文 | `READ` |
| GET | `/api/repositories/{repositoryId}/branches/{branchId}/snapshots/{snapshotId}/retention` | 快照引用检查 | `MANAGE` |
| DELETE | `/api/repositories/{repositoryId}/branches/{branchId}/snapshots/{snapshotId}` | 删除无引用快照 | `MANAGE` |
| GET | `/api/repositories/{repoId}/knowledge/{cardId}/history` | 知识修订历史 | `READ` |
| POST | `/api/repositories/{repoId}/knowledge/{cardId}/history/{revision}/restore` | 恢复到历史修订 | `MAINTAIN` |
| POST | `/api/repositories/{repositoryId}/knowledge/attachments` | 上传知识附件 | `MAINTAIN` |
| GET | `/api/repositories/{repositoryId}/knowledge/attachments/{attachmentId}` | 读取知识附件 | `READ` |
| GET/POST/PUT | `/api/settings/llm/vector-models[/{id}]` | 向量模型配置读写 | 超级管理员 |
| GET/PUT | `/api/settings` | 系统键值配置 | 超级管理员 |

## 5 边界与非目标

- 不覆盖知识正文的编辑规则、评审与发布流程语义（见知识与问答相关文档），仅描述其表结构约束。
- 不覆盖账号、会话、权限、审计的字段语义（见账号与权限文档）。
- 不覆盖检索算法、排序权重与问答生成逻辑，仅描述其依赖的索引与列。
- 数据库只保留一个"当前已发布版本"指针与若干不可变分支快照；不提供跨仓库工程、接口契约与变更审查的存储（已由 V2/V9 移除）。
- 迁移脚本不允许修改历史版本文件；结构演进必须追加新版本脚本。

## 6 已知缺口

1. **仓库物理清理引用了不存在的表**：`RepositoryGovernanceMapper.xml` 的 `deleteRepositoryCredentials` 执行 `DELETE FROM repository_credentials`，但当前 schema 中不存在该表（凭据表名为 `git_credentials`，相关索引名为 `idx_repository_credentials_repo`）。该语句会抛错，被 `RepositoryDeletionWorker` 捕获后记为 `cleanup_status='FAILED'` 并不断重试，仓库无法收敛到 `DELETED`。
   - 证据：`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:116`、`backend/src/main/resources/db/migration/V1__init_schema.sql:199`、`backend/src/main/resources/db/migration/V1__init_schema.sql:244`、`backend/src/main/java/com/analyzercoder/worker/RepositoryDeletionWorker.java:26`
2. **知识修订无不可变保护**：`knowledge_card_revisions` 没有阻止 `UPDATE` 的触发器，`capture_knowledge_card_revision()` 使用 `ON CONFLICT (card_id, revision) DO UPDATE`，因此当卡片内容变化但 `revision` 未变时历史行会被覆盖。这是否为预期设计与"知识修订不可变"的既有表述冲突，需人工确认。
   - 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1459`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1472`
3. **`repository_deletion_tombstones` 无外键**：该表只有 `repository_id` 主键列，没有指向 `repositories` 的外键，仓库行被硬删除后会留下孤儿墓碑记录。
   - 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:294`
4. **`code_chunks` 与 `repositories` 的外键未定义级联**：`repo_id` 外键没有 `ON DELETE` 子句（默认 `NO ACTION`），而 `chunk_embeddings`、`heuristic_call_edges`、`codegraph_artifacts` 使用 `ON DELETE CASCADE`；仓库硬删除路径依赖显式清理语句，删除顺序契约需人工确认。
   - 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:377`、`backend/src/main/resources/db/migration/V1__init_schema.sql:446`
5. **时间类型不统一**：多数表使用 `TIMESTAMP`，而验证码、任务、模型配置等使用 `TIMESTAMPTZ`，跨表比较与前端展示的时区语义需人工确认是否需要统一。
   - 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:28`、`backend/src/main/resources/db/migration/V1__init_schema.sql:141`、`backend/src/main/resources/db/migration/V1__init_schema.sql:279`
6. **`snapshot-max-files` / `snapshot-max-total-bytes` 默认值仅在代码中**：`application.yml` 给出同样默认值，但 `FileSystemRepositorySnapshotAdapter` 与 `GitBranchSnapshotFactory` 各自声明默认值，若只改配置项需要同时确认两处读取路径。
   - 证据：`backend/src/main/resources/application.yml:75`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:41`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/GitBranchSnapshotFactory.java:31`
7. **`app.codegraph.artifact-root` 与快照根目录共用同一父目录**：默认都指向 `${managed-data-root}/repositories`，因此图谱产物与代码快照混在同一仓库目录树下，快照目录清理（`deleteRepository`）会连同图谱产物一起删除；是否需要隔离需人工确认。
   - 证据：`backend/src/main/resources/application.yml:94`、`backend/src/main/resources/application.yml:72`、`backend/src/main/java/com/analyzercoder/infrastructure/repository/FileSystemRepositorySnapshotAdapter.java:110`
8. **仓库生命周期状态取值无数据库约束**：`repositories.repository_status` 是自由文本列（默认 `READY`），没有检查约束；`DELETING`/`DELETED` 等取值只由 SQL 字面量与 V3 触发器条件隐含，拼写错误不会被数据库拒绝。是否需要补约束需人工确认。
   - 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:74`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:131`、`backend/src/main/resources/mappers/RepositoryGovernanceMapper.xml:119`
