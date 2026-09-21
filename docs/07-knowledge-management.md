# 知识管理需求
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

### 1.1 范围

知识管理域负责把仓库代码之外的工程共识沉淀为可治理的**知识卡（knowledge card）**，并让这些知识参与到检索、问答、代码证据上下文与分支阅读流程中。已实现的能力边界：

- 知识卡内容模型：标题、Markdown 正文、卡片类型、工程知识类型、执行级别、严重级别、适用范围、义务、负责人、标签（`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1744-1803`）。
- 生命周期：草稿到人工评审到发布再到归档的状态机，以及**修订（revision）**历史与恢复（`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:176-231`、`backend/src/main/java/com/analyzercoder/interfaces/rest/KnowledgeCardHistoryController.java:31-50`）。
- 适用范围（scope：路径、符号、模块）与分支范围（项目共享知识 / 分支专属知识 / 指定分支范围）。
- 知识卡与代码片段的绑定（`chunkId` + `contentVersion` + 路径 + 符号 + 行号 + 内容哈希）。
- 分支知识验证与来源漂移（source drift）复核。
- Markdown 来源（markdown source）发现与知识卡生成。
- 附件（attachment）上传、下载与正文引用渲染。

非本域职责（由其他文档覆盖，本文件只在交叉约束处引用）：代码索引与 CodeGraph、向量模型配置、问答会话与引用校验、仓库与分支生命周期管理。

### 1.2 角色与权限级别

权限级别只有三级，按序包含：`READ` < `MAINTAIN` < `MANAGE`（`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4-11`，`includes` 使用 `ordinal()` 比较）。此外存在两种正交身份：

- **所有者（owner）关系**：仓库访问记录中的 `ownerAccountId` 表示所有者，`canAccess` 对所有者直接放行，`requireOwner` 是仅所有者或超级管理员可执行的动作（`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:19-54`）。
- **角色**：`SUPER_ADMIN` 在 `canAccess` 中直接放行；其余为普通用户（NORMAL）（`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:23-25`）。

知识域的实际授权点：

| 动作 | 所需权限 | 证据 |
| --- | --- | --- |
| 列出知识卡、读取来源漂移证据、读取修订历史、读取 Markdown 来源、读取分支范围与验证列表 | READ | `IntelligenceController.java:176-188`、`KnowledgeDriftController.java:44-47`、`KnowledgeCardHistoryController.java:34-37`、`MarkdownKnowledgeSourceController.java:41`、`BranchKnowledgeService.java:33-34,61,164-165` |
| 创建知识卡、编辑知识卡、上传附件、来源复核、生成 Markdown 来源、批量生成 | MAINTAIN | `IntelligenceController.java:195,209`、`KnowledgeAttachmentController.java:43`、`KnowledgeDriftController.java:61`、`MarkdownKnowledgeSourceController.java:51,66` |
| 人工评审、发布/撤回/归档、分支范围与分支验证 | MANAGE | `IntelligenceController.java:219,229`、`BranchKnowledgeService.java:81,164-165` |
| 恢复历史修订 | MAINTAIN | `KnowledgeCardHistoryController.java:48` |

## 2 需求条目

### KNO-001 知识卡字段模型
- 需求：知识卡必须同时保存展示字段与工程治理字段，治理字段使用受控枚举，不接受自由文本分类。
- 规则：
  - 展示字段：标题、卡片类型、Markdown 正文、标签。
  - 治理字段：`knowledgeKind`、`severity`、`enforcement`、`ownerAccountId`、`scope`、`obligations`、`lastVerifiedContentVersion`、`verificationNote`。
  - 状态字段：`publicationStatus`、`reviewStatus`、`sourceVersionStatus`、`revision`、`verifiedCommit`。
  - 知识类型枚举固定为 11 项：REFERENCE、BUSINESS_RULE、ARCH_DECISION、API_CONTRACT、DATA_CONSTRAINT、TEST_OBLIGATION、SECURITY_POLICY、RUNBOOK、INCIDENT_LESSON、OWNERSHIP、TECH_DEBT（`backend/src/main/java/com/analyzercoder/domain/knowledge/KnowledgeKind.java:4-16`）。
  - 执行级别固定为 REFERENCE、ADVISORY、REQUIRED（`backend/src/main/java/com/analyzercoder/domain/knowledge/KnowledgeEnforcement.java:4-8`）。
  - 严重级别固定为 INFO、WARNING、CRITICAL（`backend/src/main/java/com/analyzercoder/domain/knowledge/KnowledgeSeverity.java:4-8`）。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1374-1406`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:4-31`

### KNO-002 知识卡正文与字段长度约束
- 需求：创建或编辑知识卡时必须校验字段存在性与长度，超限或为空即拒绝。
- 规则：
  - 标题长度 1 到 200 个字符，创建/编辑前去除两端空白。
  - 卡片类型长度 1 到 40 个字符。
  - Markdown 正文长度 1 到 600000 个字符。
  - 标签去除空值、去重，最多保留 20 个。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1166-1180`

### KNO-003 工程知识策略校验（枚举、范围与义务上限）
- 需求：知识类型、严重级别、执行级别与适用范围、义务必须经过统一策略校验，REST、持久化与后续任务审查共用同一规则。
- 规则：
  - 三个枚举缺省时分别回落为 REFERENCE、INFO、REFERENCE；无法识别时抛出"类型/严重程度/执行级别无效"。
  - 适用路径最多 50 项、单项不超过 300 字符，并经过 `RepositoryGlobMatcher.normalizePattern` 规范化。
  - 适用符号最多 50 项、适用模块最多 20 项，单项不超过 200 字符。
  - 必需测试最多 50 项（单项 500 字符）、审批人最多 20 个、开发要求最多 50 项（单项 2000 字符）、禁止修改路径最多 50 项（单项 300 字符）。
  - 执行级别为 REFERENCE 时不得携带任何义务，否则拒绝。
- 证据：`backend/src/main/java/com/analyzercoder/application/knowledge/EngineeringKnowledgePolicy.java:18-54,79-147`、`backend/src/test/java/com/analyzercoder/application/knowledge/EngineeringKnowledgePolicyTest.java:19-123`

### KNO-004 义务（obligations）模型
- 需求：约束类知识必须能够声明命中后需要执行的测试、审批、禁止路径与知识同步要求。
- 规则：
  - 五字段：`requiredTests`、`requiredApproverAccountIds`、`instructions`、`prohibitedPathPatterns`、`knowledgeUpdateRequired`。
  - 三字段构造器保留用于兼容历史 JSON，新规则必须使用五字段显式保存。
  - `isEmpty()` 在五个字段全部为空/为假时成立。
  - 数据库层以 JSONB 约束五个字段的类型，并设置完整默认值。
- 证据：`backend/src/main/java/com/analyzercoder/domain/knowledge/KnowledgeObligations.java:8-40`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1601-1623`

### KNO-005 创建知识卡必然生成草稿
- 需求：新建知识卡一律以草稿、未评审状态落库，并立即固化第 1 号修订。
- 规则：
  - 插入时显式写入 `publication_status='DRAFT'`、`review_status='UNREVIEWED'`、`revision` 默认 1。
  - 创建后依次关联附件、关联代码引用，然后刷新来源版本状态。
  - 请求携带分支上下文时，创建出的知识卡会被绑定到该分支的分支范围。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:322-333`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1056-1079`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:190-201`

### KNO-006 编辑知识卡生成新修订并重置治理状态
- 需求：任何内容编辑都必须生成新的修订，并把知识重置为待治理状态；旧修订内容不得被覆盖。
- 规则：
  - 编辑将 `revision` 加 1，并把 `publication_status` 重置为 DRAFT、`review_status` 重置为 UNREVIEWED、评审人与评审时间清空。
  - 同时把 `source_version_status` 重置为 UNVERIFIED、`verified_commit` 清空、`last_verified_content_version` 与 `verification_note` 清空。
  - 编辑后重新关联附件与代码引用，并重新计算来源版本状态。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:334-345`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1081-1106`

### KNO-007 修订历史由数据库触发器强制留档
- 需求：修订历史必须由数据库层强制产生，不能依赖应用层是否记得写历史表。
- 规则：
  - 触发器在 `knowledge_cards` 插入后，或标题、卡片类型、正文、标签、发布状态、知识类型、严重级别、执行级别、负责人、适用范围、义务、最近验证内容版本、验证说明、修订号任一更新后，写入 `knowledge_card_revisions`。
  - 历史行按 `(card_id, revision)` 冲突时**更新同一修订的字段**，不新增重复修订。
  - 历史表保存的发布状态是该修订保存时的发布状态，表达"历史事实"而非当前状态。
  - 留档是强制的，但**不是防篡改的不可变日志**：同一次写入按 `ON CONFLICT (card_id, revision) DO UPDATE` 覆盖既有历史行（`V1__init_schema.sql:1459-1467`），且 `knowledge_card_revisions` 自身没有 UPDATE/DELETE 保护触发器。因此在不递增 `revision` 的情况下修改上述受控列（例如仅切换发布状态）会改写该修订的历史行。详见 [11-data-model.md](11-data-model.md) 的已知缺口。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1447-1476,1319`

### KNO-008 来源版本状态与人工评审状态相互独立
- 需求：系统必须把"来源代码是否仍然匹配"和"人是否认可这条知识"拆成两个独立状态，任何一个都不得隐含另一个。
- 规则：
  - `source_version_status` 取值 UNVERIFIED、CURRENT、SUSPECT、STALE，表示未验证、当前、可疑或已过期，且不表示人工认可内容。
  - `review_status` 取值 UNREVIEWED、APPROVED、CHANGES_REQUESTED，与来源版本及发布状态独立。
  - 状态注释在数据库层显式声明这两个语义边界。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1306-1318,1394-1396`

### KNO-009 人工评审通过或要求修改
- 需求：人工评审必须作为独立动作记录，且不自动改变发布状态。
- 规则：
  - 请求状态只接受 APPROVED 与 CHANGES_REQUESTED，其余值统一报"人工评审状态无效"。
  - 评审写入 `review_status`、`reviewed_by`、`reviewed_at`。
  - 评审成功后发布状态保持不变。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:41,1108-1116`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:367-371`、`backend/src/test/java/com/analyzercoder/application/intelligence/KnowledgeStateWorkflowTest.java:65-83`

### KNO-010 发布前置条件
- 需求：发布必须同时满足人工评审通过与来源版本可用，两个条件缺一不可。
- 规则：
  - 发布状态只接受 DRAFT、PUBLISHED、ARCHIVED。
  - 目标状态为 PUBLISHED 时，`review_status` 必须为 APPROVED，否则报"知识卡片尚未通过人工评审，不能发布"。
  - 目标状态为 PUBLISHED 时，`source_version_status` 不得为 SUSPECT 或 STALE，否则报"知识来源版本已过期，复核内容后才能发布"。
  - 执行级别为 REQUIRED 时额外要求：必须有负责人、适用范围不得为空、评审必须通过、来源版本必须为 CURRENT。
  - 发布成功后触发知识向量补齐；补齐失败不阻断发布，已发布知识仍可经关键词通道检索。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:42-43,1118-1143,1265-1271`、`backend/src/main/java/com/analyzercoder/application/knowledge/EngineeringKnowledgePolicy.java:56-77`、`backend/src/test/java/com/analyzercoder/application/intelligence/KnowledgeStateWorkflowTest.java:51-121`

### KNO-011 归档与撤回
- 需求：已发布知识必须能够被撤回为草稿或归档，且归档不等于删除历史。
- 规则：
  - 发布状态可设置为 ARCHIVED，归档后该知识不再出现在分支验证与健康统计的适用范围中（查询显式排除 ARCHIVED）。
  - 发布状态可回退为 DRAFT（撤回），用于重新治理。
  - 归档或撤回都会通过修订触发器留下历史记录。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1118-1143`、`backend/src/main/java/com/analyzercoder/application/branch/BranchKnowledgeService.java:42`、`backend/src/main/java/com/analyzercoder/application/branch/BranchOverviewService.java:177`

### KNO-012 历史查询与恢复为草稿
- 需求：知识卡必须可查看全部历史修订，并可把任一历史修订恢复为新的草稿修订。
- 规则：
  - 历史查询按修订号倒序返回该卡片的全部修订，含标题、类型、正文、标签、治理字段、发布状态、修改人与修改时间。
  - 恢复动作要求目标修订存在，否则报"知识卡片历史修订不存在"；知识卡不存在时报"知识卡片不存在"。
  - 恢复会把目标修订的内容与治理字段写回当前卡片，同时把发布状态重置为 DRAFT、评审状态重置为 UNREVIEWED、来源版本重置为 UNVERIFIED，并把修订号加 1。
  - 恢复不覆盖历史记录；恢复时会把被恢复修订所关联的附件重建到新修订上。
- 证据：`backend/src/main/resources/mappers/KnowledgeHistoryMapper.xml:54-78`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeCardHistoryService.java:63-116`

### KNO-013 项目共享知识与分支专属知识
- 需求：每条知识卡必须声明其适用分支范围，范围为二选一模式，不允许同时"共享且限定"。
- 规则：
  - 模式 `ALL_BRANCHES` 表示项目共享知识，`branch_ids` 必须为空；模式 `SELECTED_BRANCHES` 表示指定分支范围，`branch_ids` 必须非空。
  - 指定分支数量上限 30；空模式、空列表（SELECTED_BRANCHES 但无分支）或 ALL_BRANCHES 携带分支列表均报"知识适用分支范围无效"。
  - 每个分支必须属于当前仓库，否则报"适用分支不属于当前仓库"。
  - 新建知识卡时触发器自动把范围初始化为该项目默认分支的 SELECTED_BRANCHES 单分支。
  - 数据库约束在表层面再次保证模式与分支列表的一致性。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchKnowledgeService.java:73-96`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:41-47,93-106`、`backend/src/test/java/com/analyzercoder/application/branch/BranchKnowledgeServiceTest.java:52-70`

### KNO-014 分支范围编辑生成新修订并保留证据
- 需求：修改分支范围必须是一次可审计的修订变更，且不得丢失代码引用与附件。
- 规则：
  - 修改范围需要 MANAGE 权限。
  - 修改以乐观校验为前提：请求携带的期望修订号必须等于当前修订号，否则报 409 `KNOWLEDGE_REVISION_CONFLICT`（"知识已更新，请刷新后重试"）。
  - 修改范围会把修订号加 1，并把上一修订的代码引用与附件关联原样复制到新修订。
  - 修改范围不会复制分支验证结论。
  - 每次修订都在 `knowledge_card_revisions.branch_id` 中保留与卡片一致的分支身份。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchKnowledgeService.java:73-136`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:52-91`、`backend/src/test/java/com/analyzercoder/application/branch/BranchKnowledgeServiceTest.java:32-50`

### KNO-015 适用范围载荷与匹配规则
- 需求：适用范围必须支持路径 glob、符号与模块三种载荷，路径匹配使用受控、跨操作系统一致的 glob 语义。
- 规则：
  - 范围载荷为 `pathPatterns`、`symbols`、`modules` 三个字符串数组，空范围以全空数组表示。
  - 路径 glob 支持 `*`（不跨 `/`）、`**`（跨目录，`**/` 可匹配零层目录）、`?`（单个非 `/` 字符）。
  - 匹配在仓库相对路径上执行，反斜杠统一转为正斜杠，去除前缀 `./`，且区分大小写。
  - 正则元字符按字面量处理，不解释为正则。
  - 拒绝绝对路径、Windows 盘符路径、控制字符、含 `..` 段的路径；模式长度上限 300，路径长度上限 4096。
  - `matches` 内部按模式缓存编译结果。
- 证据：`backend/src/main/java/com/analyzercoder/application/knowledge/RepositoryGlobMatcher.java:11-95`、`backend/src/test/java/com/analyzercoder/application/knowledge/RepositoryGlobMatcherTest.java:11-45`

### KNO-016 绑定文件不等于全仓适用
- 需求：把代码片段绑定到知识卡只证明该片段被引用，不表示知识在整个仓库或整条分支上适用；适用性必须单独由适用范围给出。
- 规则：
  - 代码证据上下文对同一条知识分别给出"直接绑定"与"范围命中"两类适用理由：直接绑定要求文件路径与绑定记录一致；路径命中要求文件路径匹配 `pathPatterns`；符号命中要求当前符号与 `symbols` 精确相等。
  - 只有当至少一类适用理由存在时，该知识才会出现在该文件的上下文中。
  - 命中理由分为 DIRECT_BINDING、PATH_SCOPE、SYMBOL_SCOPE 三类，并按"是否可信"排序后返回。
  - 无效的旧范围规则只被忽略，不会扩大适用结论。
- 证据：`backend/src/main/java/com/analyzercoder/application/code/CodeEvidenceContextService.java:89-166`

### KNO-017 知识可见范围与检索一致性
- 需求：非正式状态的知识不得进入正式检索与问答，草稿只对具备维护权限的账号可见。
- 规则：
  - 列表接口默认只返回 `publication_status='PUBLISHED'`、`review_status='APPROVED'`、`source_version_status` 不属于 SUSPECT/STALE 的知识；仅当账号具备 MAINTAIN 及以上权限时才包含草稿。
  - 仓库级检索通道（关键词与向量）在 `validKnowledge` 条件下取数：已发布、评审通过、来源版本不在 SUSPECT/STALE、绑定代码引用在当前内容版本中仍能按路径与起始行找到相同内容哈希、并且该知识的分支范围包含默认分支或为项目共享。
  - 统一检索与问答走包含知识的通道，混合搜索（hybrid-search）显式不包含知识通道。
  - 知识关键词通道权重 1.2，向量通道权重 1.05。
  - 任意绑定代码引用在当前内容版本中找不到同哈希片段时，该知识整体不进入检索结果。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:41-61,312-321`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:97,121,529-555,598-630,1048-1050`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:176-188`

### KNO-018 分支阅读下的知识可见范围
- 需求：在分支阅读上下文中，只有该分支在内容版本上验证过的知识修订才能参与检索，并且必须使用该分支的正确修订版本。
- 规则：
  - 分支知识检索取当前分支上下文固定的 `contextId` 所钉住的知识修订，不使用卡片当前修订。
  - 分支可见性要求分支范围包含该分支（或为项目共享），已发布且评审通过。
  - 附加条件：执行级别为 REFERENCE 且该修订没有任何代码引用时可直接进入；否则必须存在 `state='CURRENT'` 的分支验证记录。
  - 同时存在 REVIEW_REQUIRED 或 INVALID 的分支验证记录时，该修订被排除。
  - 分支上下文的条件不包含 `source_version_status` 检查，与仓库级 `validKnowledge` 不同：分支通道以"该分支该内容版本的验证结论"取代来源版本状态。
  - 检索结果附带来源范围说明：项目共享、分支专属加分支名、或指定分支加数量。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:458-469,524-581`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:532-548,603-619`

### KNO-019 知识卡与代码片段的绑定
- 需求：知识卡必须能把具体代码片段绑定为证据，绑定内容必须固化当时的版本信息与行号。
- 规则：
  - 绑定输入只接受 `chunkId`；服务端按当前仓库当前内容版本解析出 `content_version`、文件路径、符号名、起止行与内容哈希并落库。
  - 只接受属于当前仓库、当前内容版本的代码片段，否则报"关联代码不存在或不属于当前仓库"。
  - 同一请求内去重，每张知识卡单次最多绑定 30 处代码。
  - 绑定按位置序号保存，供界面按序展示。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1218-1246`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:377-401`、`frontend/src/features/knowledge/KnowledgeCodeReferenceSelector.vue:60-61`

### KNO-020 旧内容版本绑定不得套用当前行号
- 需求：读取绑定的代码引用时必须按绑定时的内容版本解析，绑定已失效时显式标记为失效，而不是把旧行号当作当前代码位置。
- 规则：
  - 读取绑定记录时按 `repo_id + file_path + start_line + content_version + content_hash` 左连接代码片段；连接不到即把该引用标记为 `stale=true`。
  - 失效时仍返回绑定时的路径、符号、起止行与内容哈希，供人工判断，不回落到当前行号。
  - 知识卡详情展示中对失效引用显示"代码已变化"标记。
  - 代码证据上下文额外给出 `currentContentVersion` 标志，判断该绑定内容版本是否等于仓库当前内容版本。
  - 可信度要求绑定全部不失效。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:387-397`、`backend/src/main/java/com/analyzercoder/application/code/CodeEvidenceContextService.java:94-115,147-151`、`frontend/src/features/knowledge/KnowledgeCardDetailDialog.vue:100`

### KNO-021 分支验证结论与必填说明
- 需求：每条适用知识在具体分支内容版本上的适用性必须由人工给出结论与说明。
- 规则：
  - 结论四选一：CURRENT（已验证）、UNVERIFIED（未验证）、REVIEW_REQUIRED（待复核）、INVALID（不适用）。
  - 说明必填，不得为空或全空白，长度不超过 2000 个字符；结论或说明非法时报"请填写有效的分支验证状态和说明"。
  - 保存需要 MANAGE 权限。
  - 未产生验证记录的适用知识在列表中按 UNVERIFIED 展示。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchKnowledgeService.java:164-170`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:62-74`、`frontend/src/views/KnowledgeView.vue:44-47,628`

### KNO-022 验证绑定知识修订、分支与内容版本
- 需求：验证结论必须唯一绑定"知识修订 × 分支 × 内容版本"三元组，同三元组重复提交为更新而非新增。
- 规则：
  - 主键为 `(card_id, revision, branch_id, content_version)`，同一三元组冲突时更新结论、说明、检查人与检查时间。
  - 提交前校验该卡片修订仍然存在且其分支范围覆盖当前分支，否则报 409 `KNOWLEDGE_SCOPE_MISMATCH`（"知识修订已变化或不适用于该分支"）。
  - 知识修订变化、或分支内容版本变化后，旧结论不再被检索条件采用，必须重新验证。
  - Markdown 来源同步到新分支内容版本时自动为新内容版本写入占位结论：来源内容哈希未变写 UNVERIFIED 并说明"仍需独立确认适用性"，来源内容已变写 REVIEW_REQUIRED 并说明"请复核知识修订"。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchKnowledgeService.java:171-196`、`backend/src/main/resources/db/migration/V3__branch_contexts.sql:62-74`、`backend/src/main/resources/mappers/MarkdownKnowledgeSourceMapper.xml:192-209`

### KNO-023 分支验证列表、展示与筛选
- 需求：必须能按分支查看待验证知识清单，并在知识列表界面按验证状态筛选。
- 规则：
  - 分支验证列表返回该分支适用的全部非归档知识修订，携带结论与说明，无记录时结论回落为 UNVERIFIED，按标题与标识排序。
  - 列表读取需要 READ 权限，并要求有效的分支阅读上下文。
  - 知识列表界面在存在分支上下文时提供验证状态筛选（全部/已验证/未验证/待复核/不适用），并按卡片当前修订匹配验证结论。
  - 分支详情界面可仅针对单条知识修订打开验证面板；无匹配知识时提示检查适用分支。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchKnowledgeService.java:32-58`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:194-201`、`frontend/src/views/KnowledgeView.vue:43-47,89,541-544`、`frontend/src/features/knowledge/KnowledgeBranchValidationPanel.vue:17-32,56-67`

### KNO-024 Markdown 来源发现与身份
- 需求：仓库中的 Markdown 文档必须被自动发现为知识来源，来源身份在同一仓库同一分支的同一路径上保持稳定，同路径跨分支互不覆盖。
- 规则：
  - 只有语言为 markdown 且资产类型属于 DOCUMENT、RULE、TASK 的文件被视为来源。
  - 索引（全量或增量）都会以完整扫描清单同步来源，未变化的来源也会推进到新内容版本令牌，并删除当前分支清单之外的来源行。
  - 来源按 `(repo_id, branch_id, file_path)` 唯一；唯一约束在分支化迁移中从仓库路径唯一改为分支路径唯一。
  - 来源保存完整 Markdown 原文、完整原文 SHA-256、标题、资产类型、行数与字节数。
  - 标题优先取第一个 Markdown 标题，无标题时取文件名去扩展名，超长截断到 200 字符，仍为空时使用"未命名 Markdown"。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/MarkdownKnowledgeSourceService.java:61-150,457-495`、`backend/src/main/resources/mappers/MarkdownKnowledgeSourceMapper.xml:4-59`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1102-1137`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:83-99,119-120`

### KNO-025 来源状态：待生成、已生成、已过期
- 需求：每个 Markdown 来源必须显示其是否已生成知识卡、生成时的内容是否与当前内容一致。
- 规则：
  - 状态三选一：无关联知识卡为 PENDING（待生成）；关联卡片且生成的来源内容哈希等于当前来源哈希为 CURRENT（已生成）；否则为 STALE（已过期）。
  - 列表返回总数、待生成数、已生成数、已过期数，并按"待生成、已过期、已生成"的顺序再按路径排序。
  - 列表可限定在某个分支与内容版本，也可读取仓库默认分支当前内容版本。
  - 来源状态比较使用完整原文 SHA-256，因此同一内容跨内容版本仍为 CURRENT。
- 证据：`backend/src/main/resources/mappers/MarkdownKnowledgeSourceMapper.xml:61-119`、`backend/src/main/java/com/analyzercoder/application/intelligence/MarkdownKnowledgeSourceService.java:152-177,422-429`、`backend/src/test/java/com/analyzercoder/application/intelligence/MarkdownKnowledgeSourceServiceTest.java:267-300`

### KNO-026 单条来源生成知识卡
- 需求：可按路径把某一条 Markdown 来源的精确版本生成或同步为知识卡。
- 规则：
  - 请求必须携带来源路径、期望内容版本与期望内容哈希（64 位十六进制）。
  - 乐观校验失败（期望内容版本不等于当前内容版本或来源内容版本、或期望哈希不等于来源哈希）时返回 409 `MARKDOWN_SOURCE_CHANGED`（"Markdown 来源已变化，请刷新后重试"）。
  - 待生成来源创建新知识卡草稿；已生成来源直接返回既有卡片，不产生新修订；已过期来源更新同一条知识卡并产生新的草稿修订。
  - 生成内容超过 600000 个字符时返回 409 `MARKDOWN_SOURCE_TOO_LARGE`，不生成卡片。
  - 生成时自动关联该 Markdown 文件在当前内容版本中的代码片段，最多 30 处。
  - 卡片类型按资产类型与路径推断：RULE 为"项目规则"、TASK 为"任务说明"、README 为"项目说明"、ADR 或架构设计类路径为"架构设计"，其余为"项目文档"。
  - 同步已过期来源时继承既有卡片的知识类型、严重级别、执行级别、负责人、适用范围、义务与附件；新建时一律为 REFERENCE/INFO/REFERENCE 且无负责人。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/MarkdownKnowledgeSourceService.java:179-218,297-378,497-531`、`backend/src/main/java/com/analyzercoder/interfaces/rest/MarkdownKnowledgeSourceController.java:46-59,80-84`、`backend/src/test/java/com/analyzercoder/application/intelligence/MarkdownKnowledgeSourceServiceTest.java:88-265`

### KNO-027 批量生成待处理来源
- 需求：必须能按内容版本一次性批量生成待处理的 Markdown 来源，并返回剩余数量。
- 规则：
  - 请求必须携带期望内容版本且必须等于当前内容版本，否则返回 409 `MARKDOWN_SOURCE_CHANGED`。
  - 只处理状态为 PENDING 的来源，逐条重新加锁并再次校验来源版本与状态；每次处理上限 100 条，超过部分留待下一批。
  - 返回已生成数量与剩余待生成数量。
  - 已生成与已过期来源不会被批量动作修改。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/MarkdownKnowledgeSourceService.java:38-39,220-295`、`backend/src/main/java/com/analyzercoder/interfaces/rest/MarkdownKnowledgeSourceController.java:61-71`、`frontend/src/views/KnowledgeView.vue:367-400`

### KNO-028 生成知识与来源的溯源关系
- 需求：每条由 Markdown 生成的知识修订必须留下精确来源凭据，包括来源标识、所属分支、生成时内容版本、路径与完整原文哈希。
- 规则：
  - 溯源按 `(card_id, revision)` 唯一，随知识修订级联删除；来源行删除后来源标识允许为空，其余路径与哈希凭据保留。
  - 路径与哈希在数据库层校验（哈希须为 64 位十六进制，路径不得为空白或含 `..` 段）。
  - 列表与详情通过来源侧的最新溯源记录展示关联卡片与卡片修订。
  - 来源内容未变化时，同步流程会重新校准关联卡片的来源版本状态、验证时间、最近验证内容版本与验证说明。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1139-1168`、`backend/src/main/resources/mappers/MarkdownKnowledgeSourceMapper.xml:82-91,173-190,211-298`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:101-113`

### KNO-029 来源漂移检测的触发与指纹依据
- 需求：知识来源失效检查必须由后台任务在索引完成后执行，并以真实 Git 差异与绑定内容哈希为依据，不得在 HTTP 请求线程内扫描。
- 规则：
  - 检查任务类型为 `KNOWLEDGE_DRIFT`，由 CodeGraph 发布成功后自动排队；同一仓库已有活动任务时复用同类任务，若已有其他类型活动任务则拒绝并提示等待。
  - 工作线程按固定间隔轮询，先处理超时再领取任务；任务默认超时 5 分钟（可配置），过期任务标记为 `KNOWLEDGE_DRIFT_TIMEOUT`。
  - 检查只针对 `source_version_status='CURRENT'` 且 `verified_commit` 非空的卡片，并跳过已验证提交号等于当前提交的卡片。
  - 检查按已验证提交分组，对每组执行一次提交区间真实差异分析；无法形成完整 Git 事实时不猜测受影响知识，计入降级失败并可在后续重试。
  - 检查期间项目内容版本发生切换时任务失败，不产出结论。
- 证据：`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftTaskService.java:19-38`、`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftJobProcessor.java:22-88`、`backend/src/main/java/com/analyzercoder/worker/KnowledgeDriftJobWorker.java:16-20`、`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftService.java:63-116`、`backend/src/main/resources/mappers/KnowledgeDriftMapper.xml:47-52`

### KNO-030 漂移事件的证据内容
- 需求：漂移结论必须携带可复核的结构化证据，覆盖代码引用、路径范围与符号范围三类命中。
- 规则：
  - 命中类型固定为 CODE_REFERENCE_HASH_CHANGED、PATH_SCOPE_MATCHED、SYMBOL_SCOPE_MATCHED、MANUAL_CONFIRMATION、MANUAL_STALE_DECISION。
  - 每条证据包含命中规则、文件路径、起止行、变更类型与说明；每条知识最多保留 100 条证据。
  - 代码引用命中：仅当发生变更的路径包含该绑定路径，且当前内容版本中该路径下不存在相同内容哈希的片段时成立，说明为"知识绑定代码内容在当前内容版本中已不存在相同哈希"。
  - 路径范围命中：真实 Git 变更路径（含重命名的旧路径与新路径）匹配 `pathPatterns` 时成立。
  - 符号范围命中：变更符号解析结果不是文件级、且符号名或符号标识与 `symbols` 精确一致时成立。
  - 事件记录从内容版本/提交、到内容版本/提交、变更前状态、结果状态、触发类型、证据数组、说明与操作人。
  - 自动漂移事件按 `(card, revision, 目标内容版本, 触发类型)` 唯一，避免同一内容版本重复记录。
- 证据：`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftService.java:35,185-260,286-310,354-388,406-454`、`backend/src/main/resources/db/migration/V1__init_schema.sql:1545-1577`、`backend/src/test/java/com/analyzercoder/application/knowledge/KnowledgeDriftServiceTest.java:63-159`

### KNO-031 来源复核动作与说明
- 需求：人工必须能对漂移结论做出"确认仍然有效"或"标记已失效"的决定，并强制填写说明。
- 规则：
  - 动作只接受 CONFIRM_CURRENT 与 MARK_STALE，其他值报"source review action 无效"。
  - CONFIRM_CURRENT 把来源版本状态置为 CURRENT，并绑定当前提交与当前内容版本；MARK_STALE 置为 STALE，保留原提交与原最近验证内容版本。
  - 说明必填，长度 1 到 1000 个字符，写入 `verification_note`。
  - 复核写入一条审计事件，触发类型分别为 MANUAL_CONFIRM_CURRENT 与 MANUAL_MARK_STALE，并附带对应的手动证据。
- 证据：`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftService.java:118-177,390-396,414-437`、`backend/src/main/resources/mappers/KnowledgeDriftMapper.xml:74-91`、`backend/src/main/java/com/analyzercoder/interfaces/rest/KnowledgeDriftController.java:53-73`

### KNO-032 复核绑定知识修订的乐观校验
- 需求：来源复核必须绑定被复核的知识修订版本，修订已变化时拒绝写入并提示刷新。
- 规则：
  - 请求必须携带正整数 `expectedRevision`，否则报"expectedRevision 必须是正整数"。
  - 更新以修订号为条件；受影响行数不为 1 时抛出 `KNOWLEDGE_REVISION_CONFLICT`（"知识修订已变化，请刷新后重新核对"），且不写入审计事件。
  - 复核前要求仓库存在当前内容版本与当前提交，否则报 `CURRENT_CONTENT_VERSION_REQUIRED`。
  - 知识卡不存在时报 `KNOWLEDGE_CARD_NOT_FOUND`。
- 证据：`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftService.java:121-150,398-404`、`backend/src/main/resources/mappers/KnowledgeDriftMapper.xml:74-81`、`backend/src/test/java/com/analyzercoder/application/knowledge/KnowledgeDriftServiceTest.java:161-210`

### KNO-033 漂移证据的读取与前端呈现
- 需求：知识详情必须展示最近一次漂移或复核记录，并支持从证据跳转到对应代码位置。
- 规则：
  - 读取最近一次事件按创建时间与标识倒序取第一条；没有事件时返回 204 空响应。
  - 前端在打开知识详情时加载该事件，并展示触发类型的结构化证据。
  - 点击证据时跳转到检索页面，携带证据文件路径、起止行与事件目标内容版本。
  - 无文件路径可定位的证据给出"该记录没有可定位的代码文件"提示。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/KnowledgeDriftController.java:39-51`、`backend/src/main/resources/mappers/KnowledgeDriftMapper.xml:92-99`、`frontend/src/views/KnowledgeView.vue:207-242`、`frontend/src/features/knowledge/KnowledgeDriftPanel.vue:17-51`

### KNO-034 附件上传
- 需求：知识附件上传必须限制类型、大小、数量与存储位置，并保存内容摘要以便校验。
- 规则：
  - 上传要求 MAINTAIN 权限，附件必须归属明确仓库。
  - 允许的图片扩展名：png、jpg、jpeg、webp、gif；允许的文档扩展名：pdf、txt、md、csv、json、docx、xlsx、pptx；其他类型报"不支持此附件类型"。
  - 图片体积上限 10 MiB，其他附件上限 50 MiB；上传按流式读取二次校验，超限即拒绝。
  - 文件名只取基础名，长度不得超过 255 字符。
  - 内容必须与扩展名匹配：png、jpg/jpeg、gif、pdf 校验魔数，Office 三种格式校验 ZIP 头。
  - 内容按 SHA-256 计算摘要，落到受管根目录下 `repositories/<repoId>/knowledge/objects/<前两位>/<sha>`，相同摘要去重；临时文件写在 `staging/knowledge/<uuid>`，路径越界即拒绝。
  - 上传记录保存原始名、媒体类型、字节数、摘要、存储路径、上传账号与时间，扫描状态默认 READY。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:27-130`、`backend/src/main/java/com/analyzercoder/interfaces/rest/KnowledgeAttachmentController.java:37-45`、`backend/src/main/resources/db/migration/V1__init_schema.sql:736-763`

### KNO-035 附件下载
- 需求：附件下载必须按仓库鉴权，并以附件方式返回，禁止浏览器嗅探内容类型。
- 规则：
  - 下载要求 READ 权限。
  - 返回头包含 `Content-Disposition: attachment`（文件名按 UTF-8 编码）、记录中的媒体类型、字节数，以及 `X-Content-Type-Options: nosniff`。
  - 下载前校验存储路径位于受管根目录之内且为普通文件，否则报"附件文件不可用"。
  - 附件不存在时报"附件不存在"。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/KnowledgeAttachmentController.java:47-65`、`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:164-178`

### KNO-036 附件与知识修订的关联
- 需求：附件必须按知识修订关联，关联数量与总量受限，且只能关联本仓库附件。
- 规则：
  - 关联记录按 `(card_id, revision, attachment_id)` 唯一，带显示顺序，随知识修订级联删除；附件本身删除受限。
  - 单个修订最多 20 个附件，总字节数不得超过 200 MiB。
  - 请求指定的附件必须属于当前仓库，否则报"附件不存在或不属于当前仓库"。
  - 请求未给出附件列表时沿用上一修订的附件集合，用于保持既有证据。
  - 编辑知识卡、恢复历史修订、修改分支范围都会为新修订重建附件关联。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/KnowledgeAttachmentService.java:132-189`、`backend/src/main/resources/mappers/KnowledgeAttachmentMapper.xml:13-33`、`backend/src/main/resources/db/migration/V1__init_schema.sql:765-779`、`frontend/src/features/knowledge/KnowledgeCardContentSection.vue:87`

### KNO-037 正文中的附件引用渲染
- 需求：Markdown 正文必须能引用知识附件，且渲染结果需要清理脚本注入与不安全的链接目标。
- 规则：
  - 正文中的 `knowledge-attachment://<attachmentId>` 在渲染时转换为 `/api/repositories/<repoId>/knowledge/attachments/<attachmentId>`。
  - 渲染器支持表格与删除线扩展，默认转义原始 HTML。
  - 允许的元素为受控白名单（段落、换行、分隔线、强调、引用、代码、列表、标题、表格、链接、图片）。
  - 链接的 `href` 与图片的 `src` 必须匹配安全 URL 模式（http/https 或本仓库知识附件下载地址），链接强制附加 `rel=nofollow`。
  - 编辑器在插入附件时按 `![名称](knowledge-attachment://<id>)` 形式写入正文。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/MarkdownRenderingService.java:19-88`、`frontend/src/features/knowledge/KnowledgeCardEditorDialog.vue:171-173`、`frontend/src/features/knowledge/markdown.ts:7`

### KNO-038 前端知识管理界面
- 需求：知识管理界面必须提供卡片视图与 Markdown 来源视图两条工作流，并按权限收敛可用动作。
- 规则：
  - 未选择项目时以引导页拦截，要求在项目管理中选择仓库。
  - 卡片视图提供标题搜索、知识类型筛选、分支上下文下的验证状态筛选，以及新建、编辑、历史、分支范围、评审、发布/撤回/归档、查看详情、查看代码、查看图谱与来源复核动作。
  - Markdown 视图提供标题或路径搜索、状态筛选（待生成/已生成/已过期）、状态计数、单条生成与批量生成、查看原文与打开已生成卡片。
  - 生成动作按乐观校验失败（409）刷新列表并提示重新确认内容；来源复核冲突（`KNOWLEDGE_REVISION_CONFLICT`）刷新列表并提示重新核对。
  - 编辑与新建仅对具备维护权限的账号可用；评审、发布与分支范围设置仅对具备管理权限的账号可用；分支验证面板在无管理权限时只读并提示。
  - 编辑器按权限决定是否允许把新建卡片直接设为项目共享。
- 证据：`frontend/src/views/KnowledgeView.vue:80-81,84-99,112-142,179-201,341-400,424-490,590-610,674-679`、`frontend/src/features/knowledge/KnowledgeCardListItem.vue:89-96`、`frontend/src/features/knowledge/KnowledgeBranchScopeDialog.vue:46-61`、`frontend/src/features/knowledge/KnowledgeBranchValidationPanel.vue:70-81`、`frontend/src/views/KnowledgeView.spec.ts:56-75`

## 3 数据与状态

### 3.1 主要表

| 表 | 用途 | 关键约束 | 证据 |
| --- | --- | --- | --- |
| `knowledge_cards` | 知识卡当前内容与治理字段 | 主键 `id`；发布状态、来源版本状态、评审状态、知识类型、严重级别、执行级别均有 CHECK；范围与义务为 JSONB 且逐字段校验类型 | `backend/src/main/resources/db/migration/V1__init_schema.sql:600-640,1304-1313,1394-1442,1606-1626` |
| `knowledge_card_revisions` | 修订历史（仅保存知识内容历史） | 主键 `(card_id, revision)`；由触发器写入 | `backend/src/main/resources/db/migration/V1__init_schema.sql:642-669,1447-1476` |
| `knowledge_code_refs` | 知识修订与代码片段的绑定 | 主键 `(card_id, revision, position)`；外键指向修订；`chunk_id` 在片段删除后置空 | `backend/src/main/resources/db/migration/V1__init_schema.sql:701-734` |
| `knowledge_card_embeddings` | 知识卡当前修订的检索向量 | `card_id` 主键；维度 1 到 4096 且与向量维度一致；`retrieval_capability` 区分字符哈希与语义向量 | `backend/src/main/resources/db/migration/V1__init_schema.sql:675-699,1218-1250` |
| `knowledge_attachments` | 附件元数据 | `(repo_id, id)` 唯一；字节数大于 0 | `backend/src/main/resources/db/migration/V1__init_schema.sql:736-763` |
| `knowledge_card_attachment_refs` | 修订与附件关联 | 主键 `(card_id, revision, attachment_id)` | `backend/src/main/resources/db/migration/V1__init_schema.sql:765-779` |
| `knowledge_cards.branch_id` | 知识卡的唯一分支归属 | 与 `(repo_id,branch_id)` 分支外键一致 | `backend/src/main/resources/db/migration/V3__branch_contexts.sql` |
| `knowledge_card_revisions.branch_id` | 修订的分支归属 | 由修订触发器从卡片复制 | `backend/src/main/resources/db/migration/V3__branch_contexts.sql` |
| `knowledge_branch_validations` | 分支验证结论 | 主键 `(card_id, revision, branch_id, content_version)`；状态四值 CHECK | `backend/src/main/resources/db/migration/V3__branch_contexts.sql:62-74` |
| `branch_context_knowledge` | 分支阅读上下文钉住的知识修订 | 主键 `(context_id, card_id)` | `backend/src/main/resources/db/migration/V3__branch_contexts.sql:75-80` |
| `repository_markdown_sources` | Markdown 来源清单 | `(repo_id, branch_id, file_path)` 唯一；哈希格式与路径安全 CHECK | `backend/src/main/resources/db/migration/V1__init_schema.sql:1102-1137`、`V7__branch_lifecycle_and_provenance.sql:83-99` |
| `knowledge_card_markdown_source_links` | 知识修订与来源精确版本的溯源 | 主键 `(card_id, revision)`；分支外键 | `backend/src/main/resources/db/migration/V1__init_schema.sql:1139-1168`、`V7__branch_lifecycle_and_provenance.sql:101-113` |
| `knowledge_drift_events` | 漂移与复核审计 | 触发类型与前后状态 CHECK；证据必须为数组；自动事件按目标内容版本唯一 | `backend/src/main/resources/db/migration/V1__init_schema.sql:1545-1577` |

### 3.2 状态枚举

- 发布状态 `publication_status`：`DRAFT`、`PUBLISHED`、`ARCHIVED`（`V1__init_schema.sql:1304-1305`）。
- 人工评审状态 `review_status`：`UNREVIEWED`、`APPROVED`、`CHANGES_REQUESTED`（`V1__init_schema.sql:1308-1309`）。
- 来源版本状态 `source_version_status`：`UNVERIFIED`、`CURRENT`、`SUSPECT`、`STALE`（`V1__init_schema.sql:1394-1396`）。
- 漂移事件前置状态：`UNVERIFIED`、`CURRENT`、`SUSPECT`、`STALE`；结果状态：`CURRENT`、`SUSPECT`、`STALE`；触发类型：`AUTOMATIC_DIFF`、`MANUAL_CONFIRM_CURRENT`、`MANUAL_MARK_STALE`（`V1__init_schema.sql:1561-1566`）。
- 分支验证结论 `state`：`CURRENT`、`UNVERIFIED`、`REVIEW_REQUIRED`、`INVALID`（`V3__branch_contexts.sql:67`）。
- Markdown 来源状态（由查询派生，非持久列）：`PENDING`、`CURRENT`、`STALE`（`MarkdownKnowledgeSourceMapper.xml:71-75`）。
- 分支范围模式 `mode`：`ALL_BRANCHES`、`SELECTED_BRANCHES`（`V3__branch_contexts.sql:44`）。

### 3.3 状态流转

- 创建：`DRAFT` + `UNREVIEWED` + `UNVERIFIED`（有代码引用时创建后立即刷新为 `CURRENT`）。
- 编辑或恢复历史修订：回到 `DRAFT` + `UNREVIEWED` + `UNVERIFIED`，修订号加 1。
- 评审：`UNREVIEWED` 到 `APPROVED` 或 `CHANGES_REQUESTED`，可反复切换；发布状态不变。
- 发布：要求 `APPROVED` 且来源版本不在 `SUSPECT`/`STALE`；`REQUIRED` 级别另有负责人、范围与 `CURRENT` 要求。
- 撤回：`PUBLISHED` 到 `DRAFT`。
- 归档：任意状态到 `ARCHIVED`，归档后不参与分支验证与健康统计。
- 来源版本：代码引用写入后置 `CURRENT` 并绑定当前提交与内容版本；仓库当前提交变化时由触发器批量置 `STALE`；漂移命中时置 `SUSPECT`；复核后置 `CURRENT` 或 `STALE`。
- 分支验证：无记录视为 `UNVERIFIED`；人工可置四值之一；新内容版本同步时可自动写入 `UNVERIFIED` 或 `REVIEW_REQUIRED`。
- Markdown 来源：`PENDING` 生成后变 `CURRENT`；来源内容变化后变 `STALE`；`STALE` 同步后回到 `CURRENT`。

## 4 接口清单

| 方法 | 路径 | 用途 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/repositories/{repoId}/knowledge` | 列出知识卡，MAINTAIN 及以上可含草稿 | READ |
| POST | `/api/repositories/{repoId}/knowledge` | 创建知识卡草稿 | MAINTAIN |
| PUT | `/api/repositories/{repoId}/knowledge/{cardId}` | 编辑知识卡并生成新修订 | MAINTAIN |
| POST | `/api/repositories/{repoId}/knowledge/{cardId}/review` | 人工评审（APPROVED / CHANGES_REQUESTED） | MANAGE |
| POST | `/api/repositories/{repoId}/knowledge/{cardId}/publication` | 设置发布状态（DRAFT / PUBLISHED / ARCHIVED） | MANAGE |
| GET | `/api/repositories/{repoId}/knowledge/{cardId}/history` | 查询修订历史 | READ |
| POST | `/api/repositories/{repoId}/knowledge/{cardId}/history/{revision}/restore` | 恢复历史修订为新草稿 | MAINTAIN |
| POST | `/api/repositories/{repoId}/knowledge/attachments` | 上传附件 | MAINTAIN |
| GET | `/api/repositories/{repoId}/knowledge/attachments/{attachmentId}` | 下载附件 | READ |
| GET | `/api/repositories/{repoId}/knowledge/markdown-sources` | 列出 Markdown 来源与状态计数 | READ |
| POST | `/api/repositories/{repoId}/knowledge/markdown-sources/generate` | 单条来源生成或同步知识卡 | MAINTAIN |
| POST | `/api/repositories/{repoId}/knowledge/markdown-sources/generate-pending` | 批量生成待处理来源 | MAINTAIN |
| GET | `/api/repositories/{repoId}/knowledge/{cardId}/source-drift` | 读取最近一次漂移或复核事件（无事件返回 204） | READ |
| POST | `/api/repositories/{repoId}/knowledge/{cardId}/source-review` | 来源复核（CONFIRM_CURRENT / MARK_STALE） | MAINTAIN |
| GET | `/api/repositories/{repoId}/knowledge/branch-scopes` | 列出知识卡的分支范围 | READ |
| PUT | `/api/repositories/{repoId}/knowledge/{cardId}/branch-scope` | 设置分支范围（生成新修订） | MANAGE |
| GET | `/api/repositories/{repoId}/knowledge/branch-validations?contextId=` | 列出当前分支的适用知识与验证结论 | READ |
| POST | `/api/repositories/{repoId}/knowledge/{cardId}/branch-validation` | 写入分支验证结论与说明 | MANAGE |

接口证据：`IntelligenceController.java:176-231`、`KnowledgeCardHistoryController.java:20-50`、`KnowledgeAttachmentController.java:26-65`、`MarkdownKnowledgeSourceController.java:24-86`、`KnowledgeDriftController.java:24-76`、`RepositoryBranchController.java:141-201`。

## 5 边界与非目标

- 知识卡不是版本控制对象：没有分支合并、冲突解决或跨仓库迁移能力，分支范围只决定可见性，不复制知识内容。
- 适用范围只做匹配判定，不执行代码修改：`prohibitedPathPatterns`、`requiredTests`、`requiredApproverAccountIds` 等义务只作为声明数据保存，知识域内不触发实际审批或测试执行。
- 检索一致性的实现边界：知识参与统一检索与问答的证据通道，但混合搜索通道显式排除知识（`IntelligenceService.java:97`）。
- 漂移判定只基于 Git 差异与内容哈希，不做语义判断；无法形成完整 Git 事实时选择降级而不是猜测（`KnowledgeDriftService.java:107-113`）。
- 来源漂移检测不覆盖分支内容版本，只针对仓库当前内容版本与当前提交（`KnowledgeDriftJobProcessor.java:52-65`）。
- Markdown 来源只覆盖语言被识别为 markdown 且资产类型为 DOCUMENT/RULE/TASK 的文件；其他文件不进入来源清单。
- 附件不提供版本控制、在线预览或内容扫描实现：`scan_status` 默认 READY，没有真正的扫描流程。
- 附件与来源都按仓库隔离，不支持跨仓库引用。

## 6 已知缺口

1. **模块适用范围未被消费。** `KnowledgeScope.modules` 可以被写入、规范化和持久化，但全仓库没有任何匹配逻辑读取它；漂移检测只遍历 `pathPatterns` 与 `symbols`（`backend/src/main/java/com/analyzercoder/application/knowledge/KnowledgeDriftService.java:219,237`），代码证据上下文同样只用路径与符号（`backend/src/main/java/com/analyzercoder/application/code/CodeEvidenceContextService.java:121-143`）。因此"模块"目前只是声明字段，需人工确认是否有意保留为预留语义。
2. **不存在 `KnowledgeScopeMatcher`。** 实际匹配由 `RepositoryGlobMatcher` 与各调用点自行组合完成，路径匹配、符号匹配分别散落在漂移检测与代码证据上下文两处，规则重复；需人工确认是否要收敛为单一匹配组件。
3. **无删除知识卡的接口。** 只有发布状态到 `ARCHIVED` 的软退出，没有删除知识卡或删除来源的端点；`knowledge_card_revisions` 与 `knowledge_drift_events` 都随卡片级联删除，一旦提供删除接口就会丢失审计历史。
4. **草稿知识卡的列表过滤只按账号权限判定仓库级 MAINTAIN**（`IntelligenceController.java:179-184`），因此对具备 MAINTAIN 的账号，其他人的未发布草稿同样可见；需人工确认这是否是有意的协作语义。
5. **来源列表的 `branchId` 依赖分支化迁移后的结构。** `MarkdownSource` 的 `branchId`、来源唯一约束与索引都来自 `V7__branch_lifecycle_and_provenance.sql`，而 `V1__init_schema.sql` 中的 `repository_markdown_sources` 定义仍是无分支的旧版本；直接按 V1 建库的环境需要确认迁移链完整。
6. **前端类型存在后端不产出的可选字段。** `MarkdownKnowledgeSource` 声明了 `excerpt`、`cardTitle`、`cardStatus`（`frontend/src/api/intelligence.ts:377,382-383`），但后端 `MarkdownSource` 记录不包含这些字段，界面相应位置会回落为占位文案（`frontend/src/features/knowledge/MarkdownKnowledgeSourceList.vue:90,104`）。需人工确认是待实现还是残留声明。
7. **知识检索可用性取决于向量索引就绪。** 已发布知识的向量补齐在发布时执行，但失败被静默吞掉（`IntelligenceService.java:1265-1271`），只保留关键词通道；没有重试或状态提示接口，需人工确认运维预期。
8. **没有知识卡与仓库成员的一致性校验。** `ownerAccountId` 只要求非空（当执行级别为 REQUIRED 时），不校验该账号是否为仓库成员或是否可访问该仓库（`EngineeringKnowledgePolicy.java:65-67`）。
9. **附件的 `scan_status` 没有写入路径。** 表默认 `READY`，上传流程从不更新该字段（`KnowledgeAttachmentMapper.xml:4-8`），安全扫描是未实现能力。
10. **缺少对漂移证据的定位能力说明。** 只有部分证据（路径范围、符号范围、代码引用）携带文件路径与行号，手动复核事件不带文件位置，前端会提示无法定位（`frontend/src/views/KnowledgeView.vue:225-231`）；这是有意设计还是信息缺失需人工确认。
11. **未验证（UNVERIFIED）结论对分支检索等同于"未通过"。** 分支通道要求存在 `state='CURRENT'` 的验证记录，因此新内容版本同步自动写入的 UNVERIFIED 占位结论会让这些知识在分支检索中不可见（`MarkdownKnowledgeSourceMapper.xml:192-209`、`IntelligenceMapper.xml:462-468`），必须人工逐条确认；批量场景下的工作量需人工确认是否可接受。
