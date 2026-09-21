# 证据问答与代码图谱
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 功能范围与角色

本领域覆盖两件事：

- 证据问答（evidence QA）：围绕某个仓库的当前快照做联合检索（代码片段 + 已发布团队知识），生成回答，给出可回溯的引用证据，并保存为可恢复的会话（thread / conversation）。
- 代码图谱（CodeGraph）：把 CodeGraph CLI 产出的图谱产物（artifact）绑定到某个分支快照，对外提供 `latest`、`impact`、`explore` 三类读取能力，并在前端 `/atlas` 页面做只读可视化。

角色与权限模型：

- 账户角色只有两种：`SUPER_ADMIN`、`NORMAL`（普通用户）。证据来源：`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4-7`。
- 仓库权限级别只有三级：`READ` < `MAINTAIN` < `MANAGE`，用 `ordinal()` 比较大小。证据来源：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4-11`。
- 成员记录中的所有者（owner）关系按 `MANAGE` 处理；仅所有者动作走 `requireOwner`。证据来源：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:30-34`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:46-54`。
- 超级管理员在 `canAccess` 直接放行，并在 `visibleRepositoryIds` 中扩展为全部可见仓库。证据来源：`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:23-25`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:56-60`。
- 问答与历史记录按账户隔离：所有历史查询都带 `account_id` 条件。证据来源：`backend/src/main/resources/mappers/IntelligenceMapper.xml:182-227`。

术语约定：证据问答（evidence QA）；本地证据模式（LOCAL_EVIDENCE_MODE）；引用校验；会话（thread / conversation）；代码图谱（CodeGraph）；图谱产物（artifact）；阅读上下文（contextId）；账户访问令牌（access token）。

## 2 需求条目

### QA-001 问答请求与响应
- 需求：接口 `POST /api/repositories/{repoId}/ask` 接收一个问题并返回一轮完整回答（含引用、证据状态、检索诊断）。
- 规则：
  - 请求体字段为 `question`（必填、`@NotBlank`）、`clientRequestId`、`threadId`、`modelConfigId`，全部为可选除 `question` 外。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:257-258`。
  - 调用前必须对该仓库具备 `READ`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:77`。
  - 响应包含 `conversationId`、`threadId`、`turnNo`、`repositoryId`、`branchId`、`branchName`、`commitSha`、`title`、`question`、`answer`、`snapshotId`、`citations`、`provider`、`evidenceStatus`、`fallbackReason`、`citationAssessment`、`retrieval`、`createdAt`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1671-1689`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:72-95`

### QA-002 客户端幂等请求标识
- 需求：携带相同 `clientRequestId` 的重复提问不重复生成回答，直接返回已保存的那一轮。
- 规则：
  - 命中 `(repo_id, account_id, client_request_id)` 唯一约束的既有记录时，从 `answer_payload` 还原整轮回答返回。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:160-169`、`backend/src/main/resources/db/migration/V1__init_schema.sql:555-557`。
  - 若该标识已用于其他分支快照，抛 `IllegalArgumentException`（HTTP 400），提示发起新请求。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:165-167`。
  - 前端在重试同一问题时复用原 `clientRequestId`，发送成功后清空。证据：`frontend/src/features/ask/useAskConversation.ts:50`、`frontend/src/features/ask/useAskConversation.ts:66`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:160-169`

### QA-003 可用问答模型列表
- 需求：前端可查询当前可用于问答的模型配置列表，并据此让用户选择回答方式。
- 规则：
  - 接口 `GET /api/repositories/{repoId}/ask/models`，需要仓库 `READ`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:97-102`。
  - 返回字段为 `id`、`name`、`model`、`availability`、`breakerState`、`available`；`available` 当且仅当 `availability=AVAILABLE` 且 `breaker_state=CLOSED`。证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:111-124`。
  - 前端把不可用模型置灰，空值选项显示为“本地证据 · 无需问答模型”。证据：`frontend/src/views/AskView.vue:300-310`。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:111-124`

### QA-004 本地证据模式（未指定模型）
- 需求：未指定 `modelConfigId` 时不调用任何外部模型，返回基于检索证据的确定性回答。
- 规则：
  - 仅当检索到证据且 `modelConfigId == null` 时走该分支：`provider = "deterministic-local"`，`evidenceStatus = "DEGRADED"`，`fallbackReason = "LOCAL_EVIDENCE_MODE"`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:285-293`。
  - 确定性回答最多列出前 5 条证据，逐条编号 `[S1]`…`[S5]` 并标注「代码」或「知识」来源，行号存在时附加「第 N 行附近」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:831-848`。
  - 该分支的引用列表取前 `min(5, 证据数)` 条。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:290`。
  - 无证据时不进入该分支，另有 `INSUFFICIENT`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:246-251`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:285-293`

### QA-005 外部模型回答与拒绝降级
- 需求：指定 `modelConfigId` 时调用外部模型生成回答；模型输出未通过引用校验时必须降级，不得原样返回。
- 规则：
  - 模型调用入参为 `modelConfigId` 与拼装后的提示词；提示词对历史与证据都有长度上限（见 QA-009）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:253-256`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:850-894`。
  - 校验通过时采用模型回答，`provider` 为「配置名/模型名」，`evidenceStatus` 为 `CITATION_COMPLETE` 或 `CITATION_INCOMPLETE`，`fallbackReason` 为 `null`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:260-273`。
  - 校验不通过时答案替换为确定性回答并追加未通过原因，`evidenceStatus = "MODEL_OUTPUT_REJECTED"`，`fallbackReason = "CITATION_VALIDATION_FAILED"`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:274-284`。
  - 模型调用失败（连接类异常）时 `generate` 返回空，降级为确定性回答，`evidenceStatus = "DEGRADED"`，`fallbackReason = "MODEL_UNAVAILABLE"`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:285-289`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:420-423`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:253-293`

### QA-006 外部模型超时与熔断阈值
- 需求：外部模型调用受连接/请求超时约束；连续失败达到阈值后熔断，后续调用被拒绝而不是继续尝试。
- 规则：
  - 单次连接超时默认 5000 ms、允许 1000–10000 ms；请求超时默认 60000 ms、允许 3000–120000 ms。证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:604-606`。
  - 熔断阈值由 `app.llm.breaker-failure-threshold` 配置，默认 3，取值被收敛到 1–10。证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:73-74`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:80-81`。
  - 每次运行时失败将 `consecutive_failures+1`，达到阈值即把 `availability` 置为 `UNAVAILABLE`、`breaker_state` 置为 `OPEN`；成功则清零。证据：`backend/src/main/resources/mappers/LlmSettingsMapper.xml:129-143`。
  - 熔断打开（或配置不存在、未通过连接检测）时，`generate` 抛 `ApiSecurityException`，请求整体失败：`LLM_MODEL_REQUIRED`(400)、`LLM_MODEL_NOT_FOUND`(404)、`LLM_MODEL_UNAVAILABLE`(409)、`LLM_BREAKER_OPEN`(409)。证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:400-413`。
- 证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:400-424`

### QA-007 事实段引用编号
- 需求：外部模型回答中的每个仓库事实段必须标注一个或多个 `[S编号]`，编号对应当轮证据序号。
- 规则：
  - 提示词固定要求「每个仓库事实句末必须标注一个或多个 [S编号]」，且历史对话只用于理解指代、不得作为仓库事实。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:854-858`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:892`。
  - 提示词对证据块总量设 14000 字符预算，单条证据正文最多 2400 字符，历史最多取最近 4 轮、单轮问题 500 字符、回答 900 字符，当前问题最多 1200 字符。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:860-891`。
  - 出站提示词会脱敏私钥、AWS 访问密钥与 `api_key/secret/password/token` 形式凭据，并截断到 24000 字符内。证据：`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:910-926`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:850-894`

### QA-008 引用校验只验证机械覆盖，不验证语义蕴含
- 需求：回答的引用编号必须存在且被引用，事实段需有引用覆盖；系统不判断证据是否在语义上支持回答，并且必须把这一限制显式暴露给用户。
- 规则：
  - 引用正则只识别 `[S…]` 形式；编号必须是纯数字且落在 `1..证据数` 内，否则进入 `invalidReferences`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:13`、`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:27-35`。
  - 出现非法引用即判定 `valid=false`，原因「模型引用了不存在的证据」；一条引用都没有也判定 `valid=false`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:64-69`。
  - `complete` 的定义是「没有未引用的事实段」（`uncitedBlockCount == 0`）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:70`。
  - `entailmentVerified` 在所有分支都写死为 `false`，含义是「未做语义蕴含校验」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:55-62`、`backend/src/main/java/com/analyzercoder/application/intelligence/CitationAssessment.java:6-12`。
  - 校验范围：标题行（`#` 开头）、分隔线、去掉引用后无实义字符的块不计入事实段；连续的 Markdown 列表行各算一个事实段。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:73-97`。
  - 结论必须写成：引用格式正确不等于事实被支持。前端固定展示「仅检查引用编号与段落覆盖，未验证证据是否在语义上支持回答。」证据：`frontend/src/features/ask/AskConversationPanel.vue:107`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:10`、`backend/src/main/java/com/analyzercoder/application/intelligence/CitationAssessment.java:5`

### QA-009 证据状态枚举与无引用段落标记
- 需求：每轮回答必须带一个证据状态枚举值，并单独暴露未引用事实段的数量与非法引用清单。
- 规则：
  - 实现实际写入的状态值：`INSUFFICIENT`、`CITATION_COMPLETE`、`CITATION_INCOMPLETE`、`MODEL_OUTPUT_REJECTED`、`DEGRADED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:248`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:264`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:280`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:287`。
  - 数据库约束额外允许历史值 `SUPPORTED` 与 `UNKNOWN`；`SUPPORTED` 仅为历史行的可读性保留。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1181-1195`。
  - 无引用段落通过 `CitationAssessment.uncitedBlockCount` 标记，非法引用通过 `invalidReferences` 列出。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CitationAssessment.java:6-12`。
  - 前端把状态映射为中文标签，并对未引用段数与非法引用单独提示。证据：`frontend/src/features/ask/AskConversationPanel.vue:49-58`、`frontend/src/features/ask/AskConversationPanel.vue:93-108`。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1181-1195`

### QA-010 检索与证据组装
- 需求：回答的准确性依赖混合检索结果，检索需可诊断（哪些通道可用、是否降级）。
- 规则：
  - 每轮问答使用的证据上限为 10 条。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:202-203`。
  - 多轮提问会把最近 3 轮问题（每轮最多 600 字符）拼进检索词。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:436-444`。
  - 每条证据携带 `sourceType`（`CODE`/`KNOWLEDGE`）、标题、文件路径/符号/行列、内容、分数、`similarityKind`、命中通道，知识类证据额外带 `sourceScope` 与关联代码引用；检索诊断包含 `snapshotId`、`vectorModel`、`retrievalCapability`、启用通道、不可用通道及原因、通道指标、召回数、耗时、`degraded` 与降级原因。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1627-1647`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1585-1608`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1585-1609`

### QA-011 会话线程与轮次
- 需求：同一会话内的提问按轮次顺序累积，形成 thread / conversation 结构。
- 规则：
  - 首轮以新生成的 `conversationId` 同时作为 `threadId`；指定 `threadId` 时必须是当前账户在本仓库（分支）下存在的会话，否则报「问答会话不存在」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:171-184`。
  - 续问时先对线程加锁（`FOR UPDATE`），再读取历史轮次。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:185`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:220-224`。
  - `turnNo` 取线程内 `MAX(turn_no)+1`，并由唯一约束 `(thread_id, turn_no)` 兜底。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:225-227`、`backend/src/main/resources/db/migration/V1__init_schema.sql:560-561`。
  - 首轮标题取问题前 30 个码点、折叠空白；空问题标题为「未命名问题」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1515-1524`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:171-199`

### QA-012 会话历史保存的字段
- 需求：每轮回答必须持久化足以原样恢复的证据快照、分支身份与代码版本。
- 规则：
  - 落库字段：`id`、`thread_id`、`turn_no`、`repo_id`、`account_id`、`client_request_id`、`title`、`question`、`answer`、`snapshot_id`、`branch_id`、`context_id`、`branch_name`、`commit_sha`、`provider`、`evidence_status`、`fallback_reason`、`answer_payload`（JSONB 完整快照）、`status='COMPLETED'`、`finished_at`。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:158-168`。
  - 引用快照单独写入 `qa_citations`，含来源类型、`chunk_id`、`knowledge_card_id`、文件路径、符号、行列、内容 SHA-256、排序与完整引用 JSON；引用随会话级联删除。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:282-285`、`backend/src/main/resources/db/migration/V1__init_schema.sql:567-569`。
  - 分支字段为后加：`branch_id`、`context_id`、`branch_name`、`commit_sha`，其中旧的默认版本问答允许 `branch_id` 为空。证据：`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:64-79`、`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:117-118`。
  - `snapshotId` 优先取本轮证据携带的快照，其次取检索诊断快照。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:234-239`。
  - 知识类证据保存 `knowledge_card_id`，并按知识检索行的 `revision` 关联知识修订。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:722-745`。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:158-168`

### QA-013 历史列表、详情、重命名与删除
- 需求：用户可以分页查看自己的会话列表、打开某一会话的全部轮次、重命名和删除。
- 规则：
  - 列表分页：`limit` 默认 50 且收敛到 1–100，`offset` 默认 0 且不小于 0；按 `updated_at` 倒序返回线程级聚合（最新一轮标题/问题/状态 + 引用数 + 轮次数）；详情返回该线程全部轮次快照。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:344-359`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:228-246`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:365-387`。
  - 重命名 `PATCH …/qa/records/{conversationId}`，标题经 `clean(title, 1, 80)` 校验；无匹配记录时报「问答会话不存在」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:395-416`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1210-1216`。
  - 删除 `DELETE …/qa/records/{conversationId}` 返回 204，删除整个线程；无匹配记录时报「问答会话不存在」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:143-153`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:423-434`。
  - 以上四个操作都只要求仓库 `READ`（列表/详情/重命名/删除四处 `require(..., READ)`）。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:110`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:135`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:344-434`

### QA-014 按分支隔离会话
- 需求：分支维度的问答历史互不串用；跨分支的续问必须被拒绝，并给出明确提示。
- 规则：
  - 请求带分支阅读上下文（`X-Branch-Context`）时，线程查找、轮次读取、重命名、删除与列表全部追加 `branch_id` 条件。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:192-202`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:211-219`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:247-265`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:270-281`。
  - 续问时若历史轮次存在其他快照，报「会话属于其他代码版本，请在当前分支新建会话」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:192-195`。
  - 未携带上下文时走旧逻辑（仅按仓库 + 账户），保留对旧默认版本会话的兼容。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:177-191`。
  - 前端按 `branchContext.identity` 变化重新加载历史并作废当前会话视图。证据：`frontend/src/views/AskView.vue:66`、`frontend/src/views/AskView.vue:267`。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:192-202`

### QA-015 恢复历史时保留原引用快照
- 需求：打开历史会话时展示的是当时保存的引用与状态，而不是按当前快照重新检索的结果。
- 规则：
  - 每轮回答从 `answer_payload` 反序列化恢复，包括 `citations`、`provider`、`evidenceStatus`、`fallbackReason`、`citationAssessment`、`retrieval`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1433-1466`。
  - `answer_payload` 为空的历史行按列字段降级构造，引用列表为空，检索诊断标记为 `LEGACY_RECORD`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1410-1432`。
  - 快照、分支名与提交号在 payload 缺失时回退到行字段。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1447-1451`。
  - 前端恢复后按轮次排序展示、清空本地待发送状态。证据：`frontend/src/features/ask/useAskConversation.ts:81-92`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1410-1470`

### QA-016 历史引用跳转的版本校验
- 需求：从历史引用的代码位置跳转到源码视图时，必须校验目标快照，不得用当前源码冒充历史内容。
- 规则：
  - 联合检索页发现 `route.query.snapshotId` 与当前快照不一致时，直接提示「该证据来自历史快照，当前源码不能代表当时的内容」，不加载文件内容。证据：`frontend/src/views/ChunksM0View.vue:137-142`。
  - 读取文件后若返回的 `snapshotId` 与页面快照不一致，提示「代码快照已更新，请重新加载页面后查看源码」。证据：`frontend/src/views/ChunksM0View.vue:214-217`。
  - 检索结果按快照过滤：只有 `KNOWLEDGE` 与快照一致的代码证据进入当前列表，其余计为 `staleHits`。证据：`frontend/src/views/ChunksM0View.vue:245-252`。
  - 图谱页同样校验 `route.query.snapshotId` 与当前分支快照、以及图谱返回快照。证据：`frontend/src/views/CodeAtlasView.vue:117`、`frontend/src/views/CodeAtlasView.vue:131-132`。
- 证据：`frontend/src/views/ChunksM0View.vue:137-142`
### GRAPH-001 图谱构建任务触发与权限
- 需求：只有具备 `MAINTAIN` 的账户可以触发 CodeGraph 构建；接口立即返回任务，不同步阻塞。
- 规则：
  - `POST /api/repositories/{repoId}/codegraph/build` 要求 `MAINTAIN`，成功返回 202 与任务信息。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:42-52`。
  - 携带分支上下文时把任务绑定到该分支快照（写入 `index_job_branch_targets`）。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:43-51`、`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:80-86`。
  - 未携带上下文时按仓库当前快照建任务，任务类型为 `CODEGRAPH`，初始状态 `QUEUED`，`startedAt`/`heartbeatAt` 为空。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphTaskService.java:36`、`backend/src/test/java/com/analyzercoder/application/intelligence/CodeGraphTaskServiceTest.java:20-23`。
  - 前端只在具备构建能力时显示构建按钮，否则提示「当前账号没有构建权限」。证据：`frontend/src/features/graph/GraphImpactPanel.vue:235-237`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:42-52`

### GRAPH-002 构建任务去重与冲突
- 需求：同一仓库不允许并发图谱构建；同一分支快照的重复请求复用活动任务。
- 规则：
  - 已有活动的 `CODEGRAPH` 任务时复用该任务；已有其他类型的活动任务时抛「仓库已有活动任务，请等待完成后再构建 CodeGraph」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphTaskService.java:19-37`。
  - 分支路径下：活动任务绑定的快照与本次相同则复用；否则报 `BRANCH_GRAPH_BUSY`（409，「仓库已有其他版本的活动任务」）。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:65-75`。
  - 活动状态集合为 `QUEUED`、`RUNNING`、`CANCEL_REQUESTED`。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:67`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphTaskService.java:19-37`

### GRAPH-003 后台任务状态、心跳与超时
- 需求：构建必须在 Worker 中执行、持续上报心跳、可按请求取消、超时后判失败。
- 规则：
  - Worker 认领 `CODEGRAPH` 队列任务，初始步骤 `prepare_codegraph`，总时限由 `app.codegraph.task-timeout-minutes` 决定（默认 35 分钟，最小 1 分钟）。
  - 每个 checkpoint 都会更新心跳并检查：已请求取消则抛 `BuildCanceledException`；任务已失败则按错误消息中止；超过 `timeoutAt` 则抛「CodeGraph 后台任务执行超时」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:107-116`。
  - 成功时任务落为 `SUCCEEDED`，步骤写成 `codegraph_published:<snapshotId>`；失败时 `failureCode` 为 `CODEGRAPH_TIMEOUT`（消息含「超时」）或 `CODEGRAPH_BUILD_FAILED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:76`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:86-88`。
  - 非分支构建成功后自动排队知识失效检查。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:77`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:93-105`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:52-91`

### GRAPH-004 在最新分支工作区增量构建
- 需求：分支图谱直接复用固定工作区，避免每个提交重复复制和全量初始化。
- 规则：
  - 分支构建直接在固定受管分支内容目录生成 `.codegraph`，不再二次复制全部源码；首次使用 `init`，后续提交及重建使用增量 `index`。兼容的仓库级旧链路仍复制到独立产物目录。
  - 复制时跳过已有 `.codegraph` 目录与符号链接；越界路径抛「快照路径越界」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:267-294`。
  - 构建命令根据 `.codegraph` 是否存在选择 `init <project>` 或 `index <project>`，超时为 `timeoutMinutes * 60` 秒；随后执行 `--version` 读取 CLI 版本。
  - 节点数为 0 或未生成 `.codegraph` 目录时拒绝发布。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:94-101`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:22-26`

### GRAPH-005 产物发布与单快照唯一发布约束
- 需求：一个分支快照最多只有一个处于 `PUBLISHED` 的产物；发布必须是原子的。
- 规则：
  - 发布前把同仓库全体 `PUBLISHED` 且不属于任何分支快照的产物置为 `RETIRED`（旧的默认版本指针）。证据：`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:27-30`。
  - `publish` 在单个事务内先 `retireSnapshot`（把该快照既有 `PUBLISHED` 置 `RETIRED`）再插入新行，插入恒为 `PUBLISHED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphArtifactPublisher.java:17-21`、`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:4-5`、`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:31-34`。
  - 构建期间仓库快照被切换时拒绝发布旧版本产物（非分支构建）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:103-107`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphArtifactPublisher.java:17-21`

### GRAPH-006 产物查询 latest
- 需求：可以查询某仓库（或某分支快照）最近的图谱产物元数据。
- 规则：
  - `GET /api/repositories/{repoId}/codegraph/latest` 需要 `READ`；带分支上下文时按该快照查。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:54-64`。
  - 查询按 `created_at DESC LIMIT 1`，不限制状态；无记录时返回 `null`。证据：`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:35-39`。
  - 返回字段为 `id`、`repositoryId`、`snapshotId`、`cliVersion`、`status`、`artifactPath`、`nodeCount`、`edgeCount`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphService.java:228-236`。
  - 前端只在返回快照与当前文件快照一致时认为图谱就绪。证据：`frontend/src/features/graph/GraphImpactPanel.vue:105-113`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:54-64`

### GRAPH-007 图谱查询的产物可用性校验
- 需求：任何图谱读取都必须确认存在 `PUBLISHED` 产物、产物指向当前快照、且产物位于受管目录内。
- 规则：
  - 无 `PUBLISHED` 产物：`CODEGRAPH_ARTIFACT_NOT_AVAILABLE`（「当前 Snapshot 尚未发布 CodeGraph 产物」）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:177-182`。
  - 产物快照与请求快照不一致：`CODEGRAPH_VERSION_MISMATCH`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:183-186`。
  - 产物目录不存在或不在 `artifact-root` 下：`CODEGRAPH_ARTIFACT_MISSING`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:187-190`。
  - `explore` 在读取后重新比对快照，查询期间快照更新则报 `CODEGRAPH_VERSION_MISMATCH`「查询期间快照已更新，请刷新图谱」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:236-245`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:177-192`

### GRAPH-008 impact 影响分析
- 需求：给定符号与深度，返回从该符号出发的真实依赖传播结果，包含节点、边、可解释路径与覆盖度。
- 规则：
  - `GET /api/repositories/{repoId}/codegraph/impact?symbol=&depth=` 需要 `READ`，`depth` 默认 3，服务端收敛到 1–5。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:66-80`、`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:144`。
  - 查询通过 CLI `impact -p <project> -d <depth> -j <symbol>` 执行，超时 120 秒，并读取同一产物的 SQLite 图数据合成路径。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:146-168`。
  - 返回结构：`nodes`、`edges`、`paths`、`relationSource`（`CODEGRAPH_SQLITE`）、`graphArtifactId`、`snapshotId`、`cliVersion`、`affectedNodeCount`、`maxDepthReached`、`coverage`、`limitations`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:21-38`。
  - `paths` 中 `nodeIds` 按「变更焦点到受影响节点」排序，`edgeIds` 保留依赖方向。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:406-413`。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:66-80`

### GRAPH-009 impact 不虚构连线与覆盖度标注
- 需求：结果只能包含产物中真实存在的边；无法映射的记录必须以限制码暴露，而不是补造连线。
- 规则：
  - 只保留真实导出边可达的节点，路径由真实边反推；无法映射到节点的 `affected` 记录不生成边。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:129-157`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:319-355`。
  - `coverage.complete` 当且仅当「无未映射的 affected 记录」且「CLI 上报的节点数/边数与实际表示一致」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:159-181`。
  - 固定限制码 `CODEGRAPH_STATIC_ANALYSIS_ONLY`；另有 `CODEGRAPH_DUPLICATE_SYMBOL_DEFINITIONS:n`、`CODEGRAPH_AFFECTED_NODE_UNMAPPED:n`、`CODEGRAPH_NODE_COUNT_MISMATCH:…`、`CODEGRAPH_EDGE_COUNT_MISMATCH:…`、`CODEGRAPH_DYNAMIC_RESOURCE_REFERENCES_PRESENT`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:183-207`。
  - 前端对每个限制码给出中文说明，并声明「页面没有补造连线」。证据：`frontend/src/features/graph/GraphImpactPanel.vue:81-87`。
  - 无产物时前端拒绝分析并提示「当前快照尚未发布代码图谱，不能生成真实关系路径」。证据：`frontend/src/features/graph/GraphImpactPanel.vue:136-139`。
- 证据：`backend/src/test/java/com/analyzercoder/application/intelligence/CodeGraphPropagationTest.java:70-96`

### GRAPH-010 explore 模块与符号投影
- 需求：`explore` 提供有界、绑定快照的交互式视图，支持模块总览与按模块/关键词的符号级视图。
- 规则：
  - `GET /api/repositories/{repoId}/codegraph/explore?module=&query=` 需要 `READ`；`module` 与 `query` 默认空串，任一长度超过 500 抛「查询范围过长」。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:82-98`。
  - 无 `module` 且无 `query` 时为模块总览（`level = "MODULE"`），节点按模块路径聚合；否则为符号视图（`level = "SYMBOL"`）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:61-77`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:116-124`。
  - 模块归属由文件路径推导；`source_file` 为空的节点被忽略。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:44-60`。
  - 只聚合两端都在可见节点集合内的边，`contains` 关系不计入。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:94-104`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:259-263`。
  - 前端 `getCodeAtlas` 调用该接口并携带分支上下文头。证据：`frontend/src/api/codeAtlas.ts:13-18`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:14`

### GRAPH-011 结果规模上限与截断提示
- 需求：图谱读取必须有节点/边/输出规模上限，并在截断时显式提示，不得静默返回不全结果。
- 规则：
  - `explore` 节点上限为模块视图 120、符号视图 240，边上限 1200；`totalNodes`/`totalEdges` 仍报截断前的数量。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:91`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:105-115`。
  - `partial = true` 表示发生了截断。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:124`。
  - 图数据库读取上限为 500000 节点、2000000 边，超限报 `CODEGRAPH_DATABASE_LIMIT_EXCEEDED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:16-17`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:56-59`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:82-85`。
  - 单次只读查询输出超过 200000 字符报 `CODEGRAPH_RESULT_TOO_LARGE`「CodeGraph 查询结果过大，请缩小范围」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:223-227`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:91`

### GRAPH-012 图谱读取器对 CLI 版本与产物格式的约束
- 需求：只读取受支持的 CodeGraph 产物格式；格式不符时必须报明确错误码。
- 规则：
  - 读取器面向 CodeGraph 1.6 及之后版本的产物。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:14`。
  - 要求产物目录下存在 `codegraph.db`，且路径必须落在产物目录内，否则 `CODEGRAPH_DATABASE_NOT_AVAILABLE`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:21-27`。
  - 以只读方式打开（`?mode=ro` 且 `PRAGMA query_only = ON`），失败报 `CODEGRAPH_DATABASE_UNREADABLE`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:32-43`。
  - 固定读取表结构：`nodes(id,name,qualified_name,kind,file_path,start_line,end_line)` 与 `edges(source,target,kind,line)`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:46-96`。
  - `impact` 输出必须含 `affected` 数组，否则 `CODEGRAPH_IMPACT_SCHEMA_UNSUPPORTED`；图数据必须含 `nodes` 与 `edges`（或 `links`），否则 `CODEGRAPH_EXPORT_SCHEMA_UNSUPPORTED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:86-98`。
  - 查询符号在产物中找不到精确匹配（符号名或限定名）时报 `CODEGRAPH_SYMBOL_NOT_FOUND`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:102-112`。
  - CLI 统计解析采用「数字 + nodes/edges」文本模式；解析不到即抛错，不返回 0。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:382-389`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphDatabaseReader.java:14`

### GRAPH-013 只读操作白名单
- 需求：平台对 CLI 的调用必须限定在固定只读操作集合内，并统一加超时。
- 规则：
  - 允许的操作：`explore`、`node`、`query`、`callers`、`callees`、`impact`、`files`、`status`、`affected`；其他操作抛「不支持的 CodeGraph 只读操作」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:194-209`。
  - `status` 特殊处理为 `status -j <project>`，其余为 `<operation> -p <project> <args>`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:212-221`。
  - 单次只读查询超时 30 秒，失败统一映射为 `CODEGRAPH_QUERY_FAILED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:222-233`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:194-234`

### GRAPH-014 无图谱或产物缺失时的明确错误与不回退
- 需求：任一分支缺少快照或产物时，必须报明确错误，绝不回退到其他分支或其他版本的数据。
- 规则：
  - 分支未准备（无可发布快照）：`BRANCH_NOT_READY`（409，「分支尚未准备，不会使用其他分支的数据」）。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:400-402`。
  - 分支已准备但无产物：`CODEGRAPH_ARTIFACT_NOT_AVAILABLE`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:177-182`。
  - 带 `X-Branch-Context` 但端点未接入分支上下文时报 `BRANCH_CONTEXT_UNSUPPORTED`（409，「不会回退到默认分支」）。证据：`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:37-43`。
  - 前端对 Git 类项目在分支上下文未就绪时直接阻断图谱与问答读取。证据：`frontend/src/features/branches/useBranchReadScope.ts:9-16`、`frontend/src/views/CodeAtlasView.vue:116`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:400-402`、`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:177-190`

### GRAPH-015 分支阅读上下文绑定
- 需求：分支维度的图谱读取通过阅读上下文（contextId）固定快照，且上下文绑定账户与一小时有效期。
- 规则：
  - 请求头 `X-Branch-Context` 携带 contextId，解析为不可变的 `BranchReadContext`（含仓库、分支、快照、提交、内容路径、过期时间）。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchRequestContext.java:19-25`、`backend/src/main/java/com/analyzercoder/application/branch/BranchReadContext.java:8-17`。
  - 由 `branchId` 新建上下文时有效期为 1 小时，并写入该分支适用的已发布知识修订。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:396-397`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:412-421`。
  - 上下文不存在或已过期：`CONTEXT_EXPIRED`；`branchId` 与 `contextId` 不匹配：`CONTEXT_MISMATCH`。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:371-376`。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347-377`

### GRAPH-016 启发式符号图与 CodeGraph 产物相互独立
- 需求：索引阶段的符号串匹配关系与 CodeGraph CLI 产物必须分开存储、分开标注来源，避免被误认为真实调用图。
- 规则：
  - 启发式关系存放在 `heuristic_call_edges` 表，原 `code_graph_edges` 已重命名以与 CLI 产物区分。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1200-1206`。
  - 启发式边来自「内容包含『符号名 + (』」的字符串匹配，受 `LIMIT 500` 约束。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1273-1301`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:287-292`。
  - `GET /api/repositories/{repoId}/graph` 返回的 `relationSource` 为 `HEURISTIC_CALL_REFERENCE`，算法标注 `SYMBOL_TOKEN_FOLLOWED_BY_PARENTHESIS`，并附三条限制说明（非 CLI 结果、无法识别重载/动态分派/反射/别名/跨语言、结果绑定当前已发布快照）。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1016-1027`。
  - 该接口的 `depth` 收敛到 1–5，`direction` 取值影响遍历方向（`UPSTREAM`/`DOWNSTREAM` 之外视为双向）；风险等级按去重边数分为 `HIGH`(>20)/`MEDIUM`(>5)/`LOW`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:978-1020`。
- 证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1200-1206`

### GRAPH-017 图谱可视化页面（/atlas）
- 需求：前端提供 `/atlas` 只读图谱页，支持模块聚合与符号视图、3D 与平面两种渲染、以及可回退的渲染降级。
- 规则：
  - 路由 `/atlas` 对应 `CodeAtlasView.vue`，标题「代码图谱」。证据：`frontend/src/router/index.ts:26`。
  - 视图切换为「3D 空间」与「平面阅读」，默认 3D；模块视图支持双击展开进入符号视图。证据：`frontend/src/views/CodeAtlasView.vue:18`、`frontend/src/views/CodeAtlasView.vue:163`、`frontend/src/views/CodeAtlasView.vue:192`。
  - 3D 初始化失败时自动切换到平面模式并提示「3D 初始化失败，已切换到平面模式」；WebGL 上下文丢失时降级到兼容渲染并提示。证据：`frontend/src/views/CodeAtlasView.vue:25-35`、`frontend/src/features/graph/CodeAtlas3D.vue:358-361`。
  - 用户可手动「重新检测 WebGL」或「使用兼容 3D」；两种渲染都不可用时抛 `unavailable` 事件继续回退。证据：`frontend/src/views/CodeAtlasView.vue:36-41`。
  - 选中节点后展示关联关系（全部/入向/出向）与源码摘录；源码读取后校验快照一致性。证据：`frontend/src/views/CodeAtlasView.vue:238-240`、`frontend/src/views/CodeAtlasView.vue:151-161`。
- 证据：`frontend/src/views/CodeAtlasView.vue:1`

### GRAPH-018 关系方向语义与只读约束
- 需求：可视化必须明确连线来自静态分析、不代表运行时轨迹；页面不得提供任何修改代码或图谱的操作。
- 规则：
  - 页面固定文案：「连线来自静态解析，不代表实际运行轨迹」「区域表示模块归属，空间高度不代表架构层级」。证据：`frontend/src/views/CodeAtlasView.vue:200-202`、`frontend/src/features/graph/atlasLayout3d.ts:4-5`。
  - 关系方向用入向/出向图例与箭头表达，选中节点时高亮关联边、淡化无关节点。证据：`frontend/src/views/CodeAtlasView.vue:210`、`frontend/src/views/CodeAtlasView.vue:217-219`。
  - 页面只读：所有交互为选择、展开、缩放、平移、打开源码；唯一的写操作是图谱构建（由 `canBuildGraph` 能力控制）。证据：`frontend/src/views/CodeAtlasView.vue:235-244`。
  - 3D 动画与脉冲数量有上限（选中时仅前 80 条边加脉冲），并遵循 `prefers-reduced-motion`。证据：`frontend/src/features/graph/CodeAtlas3D.vue:173-177`、`frontend/src/features/graph/CodeAtlas3D.vue:363`。
  - 前端 `graph()` 实际调用 `/codegraph/impact`，`direction` 参数被忽略并固定为 `BOTH`。证据：`frontend/src/api/intelligence.ts:477-481`。
- 证据：`frontend/src/views/CodeAtlasView.vue:200-202`

## 3 数据与状态

问答数据：

- `qa_conversations`：一轮问答 = 一行；`answer_payload` 保存完整回答快照；唯一约束 `(account_id, repo_id, client_request_id)` 与 `(thread_id, turn_no)`；`evidence_status` 受 CHECK 约束；`turn_no > 0`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:503-565`。
- `qa_citations`：一轮回答的多条引用，随会话级联删除，含 `evidence_hash` 与 `citation_payload`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:567-598`。
- 分支字段后加：`branch_id`、`context_id`、`branch_name`、`commit_sha`，并对 `branch_id`、`context_id` 建外键。证据：`backend/src/main/resources/db/migration/V7__branch_lifecycle_and_provenance.sql:64-81`。
- 状态值集合（实现写入）：`INSUFFICIENT`、`CITATION_COMPLETE`、`CITATION_INCOMPLETE`、`MODEL_OUTPUT_REJECTED`、`DEGRADED`；历史兼容值 `SUPPORTED`、`UNKNOWN`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1181-1195`。
- `fallbackReason` 取值：`NO_EVIDENCE`、`LOCAL_EVIDENCE_MODE`、`MODEL_UNAVAILABLE`、`CITATION_VALIDATION_FAILED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:249`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:281`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:288-289`。
- 引用校验结果 `CitationAssessment` 的字段定义与「`entailmentVerified` 恒为 false」见 QA-008。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CitationAssessment.java:6-20`。

图谱数据：

- `codegraph_artifacts`：`id`、`repo_id`、`snapshot_id`、`cli_version`、`status`、`artifact_path`、`node_count`、`edge_count`、`created_at`、`published_at`；索引 `(repo_id, snapshot_id, created_at DESC)`。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:471-497`。
- 状态取值：写入恒为 `PUBLISHED`，被替换时置 `RETIRED`。证据：`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:4-5`、`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:31-34`。
- `heuristic_call_edges`：索引阶段生成的启发式关系，与 CLI 产物分离。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:1200-1206`。
- `index_job_branch_targets`：把 `CODEGRAPH` 任务绑定到 `(repo_id, branch_id, snapshot_id)`。证据：`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:28-41`、`backend/src/main/java/com/analyzercoder/application/branch/BranchGraphTasks.java:80-86`。
- 图谱构建任务状态：`QUEUED`、`RUNNING`、`CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELED`；失败码 `CODEGRAPH_TIMEOUT`、`CODEGRAPH_BUILD_FAILED`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphJobProcessor.java:70-88`。
- 产物存储根目录由 `app.codegraph.artifact-root` 决定，默认位于受管数据根目录下；CLI 可执行文件与超时分别由 `app.codegraph.executable`、`app.codegraph.timeout-minutes`、`app.codegraph.task-timeout-minutes` 配置。证据：`backend/src/main/resources/application.yml:85-94`。
- `branch_read_contexts` 上下文有效期 1 小时，绑定账户；`branch_context_knowledge` 固定该上下文适用的知识修订。证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:396-421`。

## 4 接口清单

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| POST | `/api/repositories/{repoId}/ask` | 证据问答，返回一轮回答与引用 | READ |
| GET | `/api/repositories/{repoId}/ask/models` | 可用问答模型列表 | READ |
| GET | `/api/repositories/{repoId}/qa/records` | 会话列表（limit/offset） | READ |
| GET | `/api/repositories/{repoId}/qa/records/{conversationId}` | 会话详情（全部轮次与引用快照） | READ |
| PATCH | `/api/repositories/{repoId}/qa/records/{conversationId}` | 会话重命名（标题 1–80 字符） | READ |
| DELETE | `/api/repositories/{repoId}/qa/records/{conversationId}` | 删除会话（204） | READ |
| GET | `/api/repositories/{repoId}/evidence-search` | 代码 + 知识联合检索（问答取证通道） | READ |
| GET | `/api/repositories/{repoId}/hybrid-search` | 代码混合检索（limit 1–100） | READ |
| POST | `/api/repositories/{repoId}/contexts` | 解析或复用分支阅读上下文（contextId） | READ |
| GET | `/api/repositories/{repoId}/chunks/{chunkId}/graph-target` | 由代码片段解析图谱目标符号 | READ |
| GET | `/api/repositories/{repoId}/graph` | 启发式符号关系图（非 CLI 产物） | READ |
| POST | `/api/repositories/{repoId}/codegraph/build` | 提交 CodeGraph 构建任务（202） | MAINTAIN |
| GET | `/api/repositories/{repoId}/codegraph/latest` | 查询最近图谱产物元数据 | READ |
| GET | `/api/repositories/{repoId}/codegraph/impact` | 符号影响分析 | READ |
| GET | `/api/repositories/{repoId}/codegraph/explore` | 模块/符号图谱投影 | READ |
| GET | `/api/repositories/{repoId}/files` | 仓库文件清单（图谱页源码入口） | READ |
| GET | `/api/repositories/{repoId}/files/content` | 文件内容 | READ |
| GET | `/api/index-jobs/{jobId}` | 查询图谱构建任务状态 | READ（任务所属仓库） |
| POST | `/api/index-jobs/{jobId}/cancel` | 取消图谱构建任务 | MAINTAIN（任务所属仓库） |

上表权限来源：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:52-172`、`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeGraphController.java:42-98`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IndexController.java:73-109`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryCodeBrowserController.java:36-66`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:108-115`。

分支上下文支持范围由拦截器白名单决定：GET 支持 `/branch-overview`、`/hybrid-search`、`/evidence-search`、`/code-evidence-context`、`/files*`、`/codegraph/(explore|latest|impact)`、`/qa/records*`、`/knowledge`、`/knowledge/markdown-sources`、`/chunks/{id}/graph-target`；POST 支持 `/ask`、`/codegraph/build`、`/knowledge`、`/knowledge/markdown-sources/(generate|generate-pending)`；PATCH/DELETE 仅 `/qa/records/{id}`。证据：`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:13-32`。

## 5 边界与非目标

- 引用校验不是事实校验：`entailmentVerified` 恒为 `false`，系统只验证编号合法性与段落覆盖，不判断证据是否支持结论。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/AnswerCitationValidator.java:10`、`frontend/src/features/ask/AskConversationPanel.vue:107`。
- 历史对话不作为仓库事实证据：提示词明确历史只用于理解指代。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:854-858`。
- 问答不触发索引或图谱构建：检索路径中明确「索引构建属于后台任务，查询不得重建语料」。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:557-558`。
- 图谱是静态分析结果：不支持反射、动态分派、别名与跨语言调用的可靠识别。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1023-1026`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphPropagation.java:184`。
- 图谱页面不修改代码、不执行测试；图谱读取只走固定只读操作白名单，「受影响测试」类结果只是关联线索而非实际运行结果。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/ManagedCodeGraphService.java:194-207`、`backend/src/main/resources/mcp-tools.json:469-471`、`frontend/src/features/mcp/McpGuide.vue:81`。
- 向量检索在分支上未就绪时不阻塞问答，而是记录 `BRANCH_VECTOR_NOT_READY` 并退回关键词检索。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:586-592`。
- 知识只有 `PUBLISHED` 且与分支适用范围匹配时才进入分支检索；草稿仅在具备 `MAINTAIN` 时对 `/api/repositories/{repoId}/knowledge` 可见。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:179-187`。

## 6 已知缺口

- 前端证据状态联合类型包含 `SUPPORTED`，但当前后端不再写入该值，仅历史行可读到；界面把它标注为「历史引用格式已校验」。证据：`frontend/src/api/intelligence.ts:166-173`、`frontend/src/features/ask/AskConversationPanel.vue:53`。
- 外部模型熔断后的行为与配置注释不一致：配置注释写「熔断后降级为本地证据模式」，但实现是在熔断打开时抛 `LLM_BREAKER_OPEN`(409)，该轮提问整体失败而非降级。证据：`backend/src/main/resources/application.yml:102-103`、`backend/src/main/java/com/analyzercoder/application/llm/LlmSettingsService.java:411-413`。
- `GET /api/repositories/{repoId}/graph`（启发式关系图）已实现，但前端没有任何调用点；前端「调用图谱」与影响分析走的是 `/codegraph/impact`。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:165-174`、`frontend/src/api/intelligence.ts:477-481`。
- `direction` 参数在前端被忽略（形参命名为 `_direction`，固定传 `BOTH`）。证据：`frontend/src/api/intelligence.ts:477-481`。
- `/codegraph/latest` 使用不带状态过滤的 `findLatest`，可能返回已 `RETIRED` 的产物行；前端只校验 `snapshotId` 而不校验 `status`，会把该行显示为「当前快照图谱已发布」。需人工确认是否需要在接口层过滤状态。证据：`backend/src/main/resources/mappers/CodeGraphArtifactMapper.xml:35-39`、`frontend/src/features/graph/GraphImpactPanel.vue:110`、`frontend/src/features/graph/GraphImpactPanel.vue:229-232`。
- 提问长度没有服务端上限（只有 `@NotBlank`）；提示词会截断到 1200 字符，但检索词由未截断的问题与历史拼装。证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:257-258`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:436-444`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:867`。
- `qa_conversations.status`、`stop_requested`、`started_at`、`finished_at` 等列存在，但当前实现恒写 `COMPLETED`，没有流式生成或停止生成的能力。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:166`、`backend/src/main/resources/db/migration/V1__init_schema.sql:518-521`。
- 图谱 CLI 契约测试 `ManagedCodeGraphCliContractTest` 带类级 `@EnabledOnOs(OS.LINUX)`，在 Windows 上整类跳过，本地无法覆盖真实 CLI 解析路径。证据：`backend/src/test/java/com/analyzercoder/application/intelligence/ManagedCodeGraphCliContractTest.java:24`。
- `GET /api/repositories/{repoId}/codegraph/impact` 与 `/explore` 无分页参数，模块/符号视图的截断只能靠 `partial` 与节点/边上限感知。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:91`、`backend/src/main/java/com/analyzercoder/application/intelligence/CodeGraphExplorer.java:107`。
