# 联合检索
> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

联合检索（unified retrieval）指在同一个请求内同时召回代码片段（chunk）与知识卡，融合排序后返回带来源标注、分数与通道信息的结果列表。证据问答（evidence QA）与 MCP 工具都建立在这一层之上。

## 1 功能范围与角色

- 范围：单一检索入口、查询分析、五个召回通道（代码关键词、字符向量 / 语义向量、知识关键词、知识向量、图谱相关通道）、候选去重与融合排序、结果字段与来源标注、快照与分支范围限定、知识可见性约束、降级与诊断、结果回到来源。
- 角色与权限级别：`READ`、`MAINTAIN`、`MANAGE` 三级，另有仓库所有者（owner）关系与超级管理员角色。检索只要求仓库 `READ`；分支知识清单接口在 `MAINTAIN` 时额外可见草稿。
- 消费者：网页代码检索工作台、证据问答、MCP 工具（`search_project`）。
- 非范围：不构建索引、不生成回答文本、不做重排模型训练；相关度分数不是正确率。

## 2 需求条目

### RET-001 单一联合检索入口

- 需求：代码与知识的联合检索只有一个入口，返回“证据列表 + 检索诊断”两段结构。
- 规则：
  - 路径为 `GET /api/repositories/{repoId}/evidence-search`，参数 `query` 必填、`limit` 默认 20。
  - 返回结构是 `{ evidence: [...], retrieval: {...} }`；`evidence` 可同时含 `CODE` 与 `KNOWLEDGE` 两种 `sourceType`。
  - 每条证据都带 `score`、`lexicalScore`、`similarityScore`、`similarityKind` 与 `channels`。
  - 权限要求为仓库 `READ`；存在 `X-Branch-Context` 时由分支上下文解析决定检索范围。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:59`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:108`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1579`、`frontend/src/api/intelligence.ts:437`

### RET-002 hybrid-search 的差异与前端未使用

- 需求：另有一个只返回代码命中的历史接口，仅供内部/集成测试使用，不作为前端入口。
- 规则：
  - `GET /api/repositories/{repoId}/hybrid-search` 返回 `{ hits: [...], retrieval: {...} }`，命中项固定为代码片段（不含 `sourceType`）。
  - 它与联合检索调用同一套召回与融合逻辑，但关闭知识通道：`includeKnowledge = false`。
  - `limit` 上限不同：`hybrid-search` 截断到 1–100，联合检索截断到 1–50。
  - 前端不存在任何 `hybrid-search` 调用点；前端只调用 `evidence-search`（经 `intelligenceApi.unifiedSearch` 与分支检索封装）。`hybrid-search` 的唯一非生产调用点是后端集成测试。
- 证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:46`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:96`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:120`、`frontend/src/api/intelligence.ts:438`、`frontend/src/api/branches.ts:58`、`backend/src/test/java/com/analyzercoder/integration/RepositoryHttpWorkflowIT.java:108`

### RET-003 请求参数与范围校验

- 需求：检索请求的 limit 与服务端能力边界一致，非法范围不产生越界查询。
- 规则：
  - 联合检索把 `limit` 夹紧到 1–50；`hybrid-search` 夹紧到 1–100。
  - 传入了 `X-Branch-Context` 时若上下文仓库与路径仓库不一致，直接抛错，不回退默认分支。
  - 未接入分支上下文的检索类路径若携带 `X-Branch-Context`，返回 `BRANCH_CONTEXT_UNSUPPORTED`，不会静默忽略该头。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:120`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:463`、`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:37`、`backend/src/main/java/com/analyzercoder/security/BranchContextInterceptor.java:40`

### RET-004 查询分析与术语抽取

- 需求：把自然语言问题归一化为关键词集合与归一化查询串，供各通道使用。
- 规则：
  - 归一化：NFKC → 小写 → 空白折叠为单空格 → 去首尾空白。
  - 术语提取上限 12 个；先按“汉字串 / 字母数字串”切分，再对长度 4 以上的连续汉字串做宽度 4、3、2 的 n-gram 补充。
  - 术语长度须在 2–80 之间，且不在停用词表中；停用词含英文冠词与 `什么/如何/怎么/是否/一个/这个/那个/请问/哪些/哪里`。
  - 额外按驼峰、下划线、点、美元符、斜杠、连字符切分出子词，子词长度不小于 2 且非停用词时也加入。
  - 术语集合是有序去重集合，通道 SQL 使用归一化查询串 + 术语集合 + 术语个数参与打分与归一。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalQueryAnalyzer.java:16`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalQueryAnalyzer.java:19`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalQueryAnalyzer.java:23`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalQueryAnalyzer.java:33`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalQueryAnalyzer.java:58`

### RET-005 空查询不算执行

- 需求：归一化后为空的查询不执行任何召回，直接返回空结果并给出原因。
- 规则：
  - 返回空证据列表，诊断中的 `degradationReasons` 为 `["EMPTY_QUERY"]`，其余诊断字段为空/零。
  - 该分支不查询数据库、不调用向量模型。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:92`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:115`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1596`

### RET-006 每通道候选上限

- 需求：每个通道的候选数量有独立上限，避免单通道挤占融合结果。
- 规则：
  - 单通道上限为 `min(40, max(16, limit * 4))`。
  - 该上限同时作为各通道 SQL 的 `LIMIT`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:40`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:466`

### RET-007 关键词通道（代码）

- 需求：以精确位置匹配召回代码片段，权重高于向量通道。
- 规则：
  - 通道名为 `CODE_KEYWORD`，权重 1.15，属于词法通道（读取 `lexical_score`）。
  - 打分构成：符号名与任一术语完全相等时取 0.98 与后续分数中的较大者；否则由“归一化查询串命中文件路径 +0.75 / 命中符号名 +0.95 / 命中正文 +0.65”与“术语命中（符号名等值 0.85、符号名包含 0.65、路径包含 0.5、正文包含 0.3）”的均值相加，再截断到 1.0。
  - 召回条件为归一化查询串或任一术语在路径、符号名、正文中出现（忽略大小写）；正文匹配忽略符号名为空的片段。
  - 排序为 `lexical_score` 降序，其次路径、起始行（空值优先）。
  - 范围限定见 RET-019。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:500`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:63`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:100`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:471`

### RET-008 字符向量 / 语义向量通道（代码）

- 需求：按向量距离召回代码片段；通道名随当前向量能力在“字符相似度”与“语义”之间切换。
- 规则：
  - 能力为 `SEMANTIC_EMBEDDING` 时通道名为 `CODE_SEMANTIC`，为 `CHARACTER_HASH` 时通道名为 `CODE_CHARACTER_SIMILARITY`；两者权重都是 1.0，均属向量通道（读取 `semantic_score`）。
  - 查询向量来自当前启用向量模型；模型返回空向量（`LOCAL_HASH` 情形）时改用内置 64 维字符哈希投影向量，本进程内生成，不写库。
  - 相似度定义为 `GREATEST(0, 1 - (embedding <=> 查询向量))`，即余弦距离转相似度。
  - 只召回与当前模型、当前维度匹配的向量记录，且要求向量记录的 `content_hash` 与片段当前 `content_hash` 一致。
  - 排序为向量距离升序，其次路径、起始行（空值优先）。
  - 分支模式（有 `X-Branch-Context`）下若该快照没有任何匹配向量的记录，不启用该通道，而是登记 `BRANCH_VECTOR_NOT_READY`。
  - 向量化查询本身抛异常时不启用向量通道，登记 `VECTOR_RETRIEVAL_FAILED`（见 RET-023）。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:567`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:592`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:586`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:104`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:512`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1526`

### RET-009 知识关键词通道

- 需求：在有效知识集合内按关键词召回知识卡，权重高于其它通道。
- 规则：
  - 通道名为 `KNOWLEDGE_KEYWORD`，权重 1.2，属词法通道；仅在联合检索（`includeKnowledge = true`）时参与。
  - 打分构成：归一化查询串命中标题 +0.95 / 正文 +0.65 / 标签 +0.8，加上各术语命中的均值（标题 0.7、标签 0.6、正文 0.35），截断到 1.0。
  - 召回条件为归一化查询串或任一术语出现在标题、正文或标签拼接串中（忽略大小写）。
  - 排序为 `lexical_score` 降序，其次更新时间降序。
  - 知识卡结果输出的 `contentHash` 是标题、正文与标签拼接串的 MD5，不是正文的 SHA-256。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:39`
  - 可见性约束见 RET-022。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:549`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:116`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:524`

### RET-010 知识向量通道

- 需求：在有效知识集合内按向量距离召回知识卡，连接条件必须包含知识修订号。
- 规则：
  - 能力为 `SEMANTIC_EMBEDDING` 时通道名为 `KNOWLEDGE_SEMANTIC`，为 `CHARACTER_HASH` 时通道名为 `KNOWLEDGE_CHARACTER_SIMILARITY`；权重均为 1.05，属向量通道。
  - 相似度定义与代码向量通道相同：`GREATEST(0, 1 - (embedding <=> 查询向量))`。
  - 连接条件严格要求向量记录的 `revision` 等于知识卡当前 `revision`，避免旧修订向量参与命中。
  - 只召回与当前模型、当前维度匹配的向量记录。
  - 排序为向量距离升序，其次知识卡更新时间降序。
  - 只在联合检索时启用；该通道失败只登记 `KNOWLEDGE_VECTOR_QUERY_FAILED`，不影响代码侧通道。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:600`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:621`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:148`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:152`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:564`

### RET-011 图谱相关通道（启发式调用关系）

- 需求：利用索引阶段生成的启发式调用候选，召回与被命中符号相关联的代码片段。
- 规则：
  - 通道名为 `HEURISTIC_CALL_REFERENCE`，权重 0.9，属词法通道；通道候选的固定 `lexical_score` 为 0.24。
  - 参与条件：当前是**默认模式**（没有 `X-Branch-Context`），且关键词通道已召回至少一个带符号名的片段；最多取前 8 个去重符号名作为种子。
  - 关系数据来自索引阶段写入的 `heuristic_call_edges`，该表按仓库当前快照过滤。
  - 召回规则：种子符号作为边的目标符号时返回边的源片段，作为源符号时返回边的目标片段；每条边的关系固定为 `CALLS`。
  - 该数据不是静态分析结果：接口层对图谱结果显式声明算法为 `SYMBOL_TOKEN_FOLLOWED_BY_PARENTHESIS`，并声明无法可靠识别重载、动态分派、反射、别名与跨语言调用。
  - 分支模式不启用该通道，因此分支检索没有图谱相关候选。
  - 通道失败登记 `HEURISTIC_RELATION_FAILED`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:513`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:519`、`backend/src/main/java/com/analyzercoder/infrastructure/persistence/mapper/GraphRetrievalMapper.java:21`、`backend/src/main/java/com/analyzercoder/infrastructure/persistence/mapper/GraphRetrievalMapper.java:26`、`backend/src/main/java/com/analyzercoder/infrastructure/persistence/mapper/GraphRetrievalMapper.java:28`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1016`

### RET-012 通道权重汇总

- 需求：各通道权重与参与条件是稳定契约，前端展示的通道名即来自该契约。
- 规则：
  - `KNOWLEDGE_KEYWORD` 1.2；`CODE_KEYWORD` 1.15；`KNOWLEDGE_SEMANTIC` / `KNOWLEDGE_CHARACTER_SIMILARITY` 1.05；`CODE_SEMANTIC` / `CODE_CHARACTER_SIMILARITY` 1.0；`HEURISTIC_CALL_REFERENCE` 0.9。
  - 知识通道只在联合检索启用；图谱相关通道只在默认模式启用；两个向量通道取决于当前向量模型是否可用。
  - 诊断中的 `enabledChannels` 只包含实际登记成功的通道，顺序即登记顺序。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:500`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:519`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:549`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:592`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:621`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:646`

### RET-013 候选去重键

- 需求：同一代码片段或同一知识卡在多通道命中时合并为一条结果，并记录全部命中通道。
- 规则：
  - 候选键为 `来源类型 + ":" + 记录标识`，代码用片段 id，知识用知识卡 id。
  - 合并时通道名按首次出现顺序去重收集；`lexicalScore` 与 `semanticScore` 分别取各通道的最大值。
  - 融合分数按通道在该通道候选列表中的名次累加，因此多通道命中会得到额外加成。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:692`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:20`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:28`、`backend/src/test/java/com/analyzercoder/application/intelligence/RetrievalRankerTest.java:27`

### RET-014 融合排序与分数归一

- 需求：用带权重的倒数排名融合（RRF）合并通道，再与置信度加权得到最终分数，并保证排序稳定。
- 规则：
  - RRF 常数 `RRF_K = 60`；单通道贡献为 `通道权重 / (60 + 该通道内名次)`，名次从 1 开始。
  - 置信度取 `max(lexicalScore, semanticScore)`；归一化 RRF 为 `min(1, rrfScore * 60)`。
  - 最终 `score = 置信度 * 0.72 + 归一化 RRF * 0.28`。
  - 排序为 `score` 降序；分数相同时按候选键升序，保证结果稳定。
  - 结果条数截断为 `max(1, limit)`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:15`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:30`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:87`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:40`

### RET-015 相关度门槛

- 需求：只有达到最低相关度的候选才进入结果，避免弱匹配被当成证据。
- 规则：
  - 词法分数门槛 0.16，语义分数门槛 0.34。
  - 保留条件是“词法分数达标或语义分数达标”，逐条过滤后再排序与截断。
  - 因此仅靠语义、且语义分数低于 0.34 的候选会被丢弃（有对应单元测试）。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:16`、`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:35`、`backend/src/test/java/com/analyzercoder/application/intelligence/RetrievalRankerTest.java:13`

### RET-016 代码结果字段

- 需求：代码命中提供足以定位与展示的全部字段，并标注来源与匹配性质。
- 规则字段：`sourceType = "CODE"`、`chunkId`、`snapshotId`、`filePath`、`symbolName`、`symbolKind`、`startLine`、`endLine`、`content`、`contentHash`、`score`、`lexicalScore`、`similarityScore`、`similarityKind`、`channels`。
- 规则：
  - 代码证据的 `title` 为符号名；符号名为空时退化为文件路径。
  - 代码证据不返回 `sourceScope`（为空），`codeReferences` 为空列表。
  - `similarityKind` 由通道名推导：含 `_SEMANTIC` → `SEMANTIC_EMBEDDING`；含 `_CHARACTER_SIMILARITY` → `CHARACTER_HASH`；否则 `NONE`。
  - JSON 字段名是 `similarityScore`，同时通过 `@JsonAlias` 兼容 `semanticScore` 输入。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:747`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:753`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1562`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1643`、`frontend/src/api/intelligence.ts:84`

### RET-017 知识结果字段

- 需求：知识命中除定位信息外，还要标明知识标识、适用范围与关联代码位置。
- 规则：
  - `sourceType = "KNOWLEDGE"`，`knowledgeCardId` 为知识卡 id，`chunkId` 与 `snapshotId` 为空。
  - `title` 为知识标题，`filePath` 为伪路径 `knowledge://<cardId>`，`symbolName`/`symbolKind`/`startLine`/`endLine` 为空。
  - `sourceScope` 为知识适用范围文案：默认模式不返回该字段；分支模式返回 `项目共享`、`分支专属 · <分支名>` 或 `指定分支 · <数量>`。
  - `codeReferences` 为该知识卡当前修订绑定的代码引用列表，含 `chunkId`、`snapshotId`、`filePath`、`symbolName`、`startLine`、`endLine`、`contentHash` 与 `stale` 标记。
  - 知识卡的 `contentHash` 是标题、正文与标签拼接串的 MD5，不是知识正文的 SHA-256。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:722`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1248`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:39`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:526`、`frontend/src/api/intelligence.ts:112`

### RET-018 分数的含义与边界

- 需求：必须明确检索分数不是正确率，也不是模型置信度。
- 规则：
  - `score` 是 RRF 与词法/向量置信度的固定加权组合，取值范围 0–1，用于相对排序，不表示“回答正确概率”。
  - `lexicalScore` 是位置匹配的加权和（截断到 1.0），`similarityScore` 是余弦相似度（截断到不小于 0），二者都是启发式度量。
  - `similarityKind` 用于区分“字符相似度”与“语义向量”，字符相似度不得表述为语义理解；向量能力为 `CHARACTER_HASH` 时通道名与结果字段均不含 `SEMANTIC`。
  - 前端不展示 `score`、`lexicalScore`、`similarityScore` 数值，只展示命中通道；检索结果列表以“命中 N 个代码片段、M 条知识”概括。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/RetrievalRanker.java:89`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:106`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1562`、`frontend/src/views/ChunksM0View.vue:87`

### RET-019 检索范围限定当前阅读上下文或默认快照

- 需求：检索必须落在单一快照上：有阅读上下文（`X-Branch-Context`）时用该上下文的快照，否则用仓库默认版本令牌。
- 规则：
  - 阅读上下文由请求头 `X-Branch-Context` 指定的 contextId 解析为 `BranchReadContext`，其快照、提交号、内容路径在上下文创建时即固定。
  - 默认模式的快照取 `repositories.current_snapshot_id`；该字段是仓库的默认版本令牌，在多分支下不等于当前阅读分支的快照。
  - 默认模式的代码通道在 SQL 中以子查询 `snapshot_id = (SELECT current_snapshot_id FROM repositories WHERE id = ?)` 限定；分支模式改为直接比较上下文快照 id。
  - 分支模式下若检索的仓库与上下文仓库不一致，直接报错，不会混用两个来源。
  - 检索结果中的代码证据 `snapshotId` 即被检索的快照，前端据此判断结果是否属于当前快照。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:476`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:89`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:497`、`backend/src/main/java/com/analyzercoder/interfaces/rest/BranchRequestContext.java:19`、`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:347`、`frontend/src/views/ChunksM0View.vue:247`

### RET-020 分支模式的知识版本固化

- 需求：分支检索使用的知识卡版本在阅读上下文建立时固化，避免同一上下文内知识版本漂移。
- 规则：
  - 创建阅读上下文时，把当时满足“已发布 + 已审核 + 分支范围命中”的知识卡及其修订号写入 `branch_context_knowledge`。
  - 分支模式的知识查询必须 JOIN 该固化表，且要求 `revision` 与固化记录一致。
  - 分支模式的知识追加适用范围与分支校验条件：`enforcement = 'REFERENCE'` 且无代码引用的知识可直接适用；其余需要该分支该快照下存在 `state = 'CURRENT'` 的分支校验记录；同时排除存在 `REVIEW_REQUIRED` 或 `INVALID` 校验记录的知识。
  - 默认模式不查该固化表，只按仓库默认分支范围与代码引用新鲜度判断。
- 证据：`backend/src/main/java/com/analyzercoder/application/branch/RepositoryBranchService.java:412`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:546`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:458`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:41`

### RET-021 召回阶段过滤而非截断后过滤

- 需求：范围限定必须发生在召回 SQL 的过滤条件里，而不是先召回再截断，否则候选池会被无关快照占满。
- 规则：
  - 各通道 SQL 都先按快照、模型、维度等条件过滤，再 `LIMIT` 通道上限；快照条件出现在 `WHERE` 子句中，位于 `LIMIT` 之前。
  - 因此 `limit` 的收紧只影响返回条数，不影响候选池的正确性。
  - 例外与补充：默认模式的前端在拿到结果后仍会再做一次客户端过滤——只保留 `sourceType = KNOWLEDGE` 或 `snapshotId` 与当前快照一致的代码证据，并把被丢弃的数量展示为“忽略旧快照 N 条”。这是对默认版本令牌可能在请求期间变更的兜底，不替代服务端召回阶段的过滤。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:497`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:509`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:517`、`frontend/src/views/ChunksM0View.vue:247`、`frontend/src/views/ChunksM0View.vue:92`

### RET-022 仅返回已发布且已审核的知识

- 需求：联合检索与知识向量统计只看到已经发布、已通过人工评审、来源版本未失效，且代码引用仍然有效的知识。
- 规则：
  - 默认模式（SQL 片段 `validKnowledge`，`backend/src/main/resources/mappers/IntelligenceMapper.xml:41` 至 `:61`）逐条要求：`c.publication_status='PUBLISHED'`（`:42`）、`c.review_status='APPROVED'`（`:47`）、`c.source_version_status NOT IN ('SUSPECT','STALE')`（`:48`）。
  - 默认模式还要求知识命中分支范围（`knowledge_branch_scopes`，`ALL_BRANCHES` 或包含仓库默认分支），并要求不存在“本修订引用了某个代码位置、但该位置在当前默认快照下已找不到同路径同行同摘要的片段”的情况（`:49`）；这一条通过 `knowledge_code_refs` 左连接 `code_chunks` 判定，是知识失效的兜底过滤。
  - 分支模式（SQL 片段 `validBranchKnowledge`，`backend/src/main/resources/mappers/IntelligenceMapper.xml:458` 至 `:469`）使用 `live.publication_status='PUBLISHED'`（`:459`）与 `live.review_status='APPROVED'`；不再单独判 `SUSPECT/STALE`，改由分支校验状态表达失效。
  - 向量覆盖统计使用同一约束：`VectorIndexQueryMapper` 的 `validKnowledge` 片段同样包含 `PUBLISHED`、`APPROVED`、`SUSPECT/STALE` 与代码引用新鲜度四项，位置 `backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:29` 至 `:53`。
  - 知识清单接口 `GET /api/repositories/{repoId}/knowledge` 在调用方不具备 `MAINTAIN` 时（`includeDraft = false`）追加同样的三项条件。
- 证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:41`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:42`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:47`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:48`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:458`、`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:29`、`backend/src/main/resources/mappers/IntelligenceMapper.xml:319`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:180`

### RET-023 通道不可用与降级诊断

- 需求：任一通道不可用都不使整体检索失败，但必须在诊断中如实呈现降级状态与原因。
- 规则：
  - 通道异常被捕获。代码类通道异常登记为 `CODE_KEYWORD_FAILED`、`CODE_VECTOR_QUERY_FAILED`、`HEURISTIC_RELATION_FAILED`；知识类通道异常登记为 `KNOWLEDGE_KEYWORD_FAILED`、`KNOWLEDGE_VECTOR_QUERY_FAILED`。
  - 向量化查询失败时统一登记 `CODE_VECTOR` / `KNOWLEDGE_VECTOR` + `VECTOR_RETRIEVAL_FAILED`，并尽力从模型配置读取当前模型名与能力；读取也失败时填 `unknown` / `UNKNOWN`。
  - 默认快照查询失败登记 `CURRENT_SNAPSHOT` + `SNAPSHOT_LOOKUP_FAILED`。
  - 分支模式下该快照没有匹配模型的向量时登记 `BRANCH_VECTOR_NOT_READY`，说明“使用关键词检索”。
  - 每条不可用记录含 `channel`、`reason`、`detail`；`detail` 为异常消息（为空时取异常类名），最长截断到 240 字符。
  - 只要存在任一不可用通道，诊断的 `degraded` 即为 `true`，`degradationReasons` 为 `通道:原因` 形式的列表。
  - 诊断还包含 `enabledChannels`、每通道 `recalledCount` 与 `durationMs`、返回候选数 `recalledCount`（融合后的条数）与整体 `durationMs`，以及本次使用的 `snapshotId`、`vectorModel`、`retrievalCapability`。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:503`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:631`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:669`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:648`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1585`、`backend/src/test/java/com/analyzercoder/application/intelligence/IntelligenceServiceMultiTurnTest.java:346`

### RET-024 前端展示检索范围与降级提示

- 需求：前端必须让用户看到当前检索范围（快照与检索能力）以及降级原因。
- 规则：
  - 检索完成后，结果区上方展示诊断条：快照前 8 位（不可用时显示“不可用”）、检索能力（`SEMANTIC_EMBEDDING` → 语义向量，`CHARACTER_HASH` → 字符相似度，其余 → 无向量能力），以及每个启用通道的中文名。
  - 降级时额外以醒目文本展示 `degradationReasons` 拼接串；为空时退化为不可用通道的 `reason` 列表。
  - 通道中文名映射只覆盖 `CODE_KEYWORD`、`CODE_SEMANTIC`、`CODE_CHARACTER_SIMILARITY`、`HEURISTIC_CALL_REFERENCE`；知识类通道名（`KNOWLEDGE_KEYWORD`、`KNOWLEDGE_SEMANTIC`、`KNOWLEDGE_CHARACTER_SIMILARITY`）未在映射表中，会原样展示英文标识。
  - 结果摘要区分代码与知识数量，并在存在被忽略的旧快照结果时追加“忽略旧快照 N 条”。
  - 结果条目展示标题（知识标题或文件名）、类型（知识或符号种类）、起始行、路径或伪路径、正文摘要与命中通道。
- 证据：`frontend/src/views/ChunksM0View.vue:417`、`frontend/src/views/ChunksM0View.vue:421`、`frontend/src/views/ChunksM0View.vue:291`、`frontend/src/views/ChunksM0View.vue:490`、`frontend/src/views/ChunksM0View.vue:495`

### RET-025 代码结果回到来源

- 需求：点击代码命中应定位到对应文件与行号，并保持当前快照范围。
- 规则：
  - 点击代码命中会打开文件证据抽屉并调用文件打开逻辑，携带 `filePath`、`startLine`、`endLine` 与 `symbolName`。
  - 文件内容按当前快照请求；返回文件的快照与页面快照不一致时拒绝展示，提示“代码快照已更新，请重新加载页面后查看源码”。
  - 路由参数 `path`/`startLine`/`endLine`/`symbol` 会驱动同样的定位行为；若当前快照中找不到该文件，提示“文件可能已删除或重命名”。
  - 若路由携带的 `snapshotId` 与当前快照不一致，提示该证据来自历史快照、当前源码不能代表当时内容。
  - 向量索引面板的代码行点击也会跳转到检索页并携带路径与行号。
- 证据：`frontend/src/views/ChunksM0View.vue:277`、`frontend/src/views/ChunksM0View.vue:214`、`frontend/src/views/ChunksM0View.vue:137`、`frontend/src/views/ChunksM0View.vue:144`、`frontend/src/features/indexing/CurrentVectorIndexPanel.vue:33`

### RET-026 知识结果回到来源

- 需求：点击知识命中应打开对应知识卡，并带上分支与阅读上下文。
- 规则：
  - 知识命中跳转到知识页并携带 `cardId`，同时带上当前 `branchId` 与 `contextId`。
  - 代码结果侧边的文件证据面板同样可以打开知识卡，或把绑定位置回传到文件打开逻辑。
  - 文件证据面板展示的知识来自另一个接口 `code-evidence-context`（确定性匹配：直接绑定、路径范围、符号范围、仓库范围），并在界面文案中明确“不把关键词相似结果冒充适用规则”，与联合检索的关键词召回形成对照。
  - 文件证据面板可跳转到代码图谱视图并携带路径、符号、快照与上下文。
- 证据：`frontend/src/views/ChunksM0View.vue:315`、`frontend/src/features/code/CodeEvidencePanel.vue:163`、`frontend/src/features/code/CodeEvidencePanel.vue:168`、`frontend/src/features/code/CodeEvidencePanel.vue:66`、`frontend/src/features/code/CodeEvidencePanel.vue:92`

### RET-027 证据问答复用联合检索

- 需求：证据问答必须以同一联合检索的结果作为证据集，不得另建召回路径。
- 规则：
  - 提问时把“最近 3 轮问题的截断文本 + 当前问题”拼成检索查询；单轮问题截断到 600 字符。
  - 调用联合检索取前 10 条证据，并把检索诊断原样保存在回答中。
  - 无证据时不调用模型，回答固定为“未找到达到相关度门槛的证据”，`evidenceStatus = INSUFFICIENT`、`fallbackReason = NO_EVIDENCE`。
  - 回答使用的快照优先取第一条证据的 `snapshotId`，否则取检索诊断的 `snapshotId`。
  - 分支模式下，同一线程的历史轮次快照必须与当前上下文快照一致，否则拒绝追问并要求新建会话。
  - 引用落库时保存正文的 SHA-256 摘要与名次，可原样恢复。
- 证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:436`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:203`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:246`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:234`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:192`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:817`

### RET-028 MCP 复用同一检索

- 需求：外部客户端通过 MCP 获取的检索结果与网页一致，不开放额外能力。
- 规则：
  - MCP 工具 `search_project` 直接调用服务层的联合检索，参数 `limit` 夹紧到 1–50，`query` 不能为空。
  - 可携带 `branchId` 或 `contextId` 解析阅读上下文；解析上下文需要显式传入二者之一。
  - 访问令牌只允许调用 `GET /api/repositories/{id}/evidence-search` 与 `POST /api/repositories/{id}/contexts`，其它路径返回 `TOKEN_ENDPOINT_FORBIDDEN`。
  - 权限仍按仓库 `READ` 校验。
- 证据：`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:36`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:62`、`backend/src/main/java/com/analyzercoder/application/mcp/McpToolService.java:69`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:50`、`mcp-server/src/server.mjs:37`

## 3 数据与状态

### 3.1 参与检索的数据

- `code_chunks`：代码片段来源，按 `snapshot_id` 与 `content_hash` 参与过滤。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:375`
- `chunk_embeddings` / `knowledge_card_embeddings`：向量来源，命中条件含模型、维度与摘要/修订一致性。证据：`backend/src/main/resources/db/migration/V1__init_schema.sql:421`
- `knowledge_cards` 与其分支范围、分支校验、代码引用表：知识可见性来源。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:41`
- `branch_context_knowledge`：分支模式下固化的知识修订清单。证据：`backend/src/main/resources/db/migration/V3__branch_contexts.sql:75`
- `heuristic_call_edges`：图谱相关通道的关系来源，按仓库当前快照过滤。证据：`backend/src/main/java/com/analyzercoder/infrastructure/persistence/mapper/GraphRetrievalMapper.java:28`
- `vector_model_configs` / `vector_model_activation`：决定通道名、查询向量与模型过滤条件。证据：`backend/src/main/resources/mappers/VectorIndexQueryMapper.xml:4`

### 3.2 状态字段

- 证据来源类型：`CODE`、`KNOWLEDGE`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:722`
- 相似度种类：`NONE`、`CHARACTER_HASH`、`SEMANTIC_EMBEDDING`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1562`
- 检索能力：`CHARACTER_HASH`、`SEMANTIC_EMBEDDING`、`UNKNOWN`（仅向量化失败且读不到配置时出现）。证据：`frontend/src/api/intelligence.ts:52`
- 通道名：`CODE_KEYWORD`、`CODE_SEMANTIC`、`CODE_CHARACTER_SIMILARITY`、`HEURISTIC_CALL_REFERENCE`、`KNOWLEDGE_KEYWORD`、`KNOWLEDGE_SEMANTIC`、`KNOWLEDGE_CHARACTER_SIMILARITY`，以及失败占位 `CODE_VECTOR`、`KNOWLEDGE_VECTOR`、`CURRENT_SNAPSHOT`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:568`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:639`、`frontend/src/views/ChunksM0View.vue:291`
- 降级原因码：`EMPTY_QUERY`、`SNAPSHOT_LOOKUP_FAILED`、`CODE_KEYWORD_FAILED`、`HEURISTIC_RELATION_FAILED`、`KNOWLEDGE_KEYWORD_FAILED`、`CODE_VECTOR_QUERY_FAILED`、`KNOWLEDGE_VECTOR_QUERY_FAILED`、`VECTOR_RETRIEVAL_FAILED`、`BRANCH_VECTOR_NOT_READY`。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:479`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:586`

## 4 接口清单

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| GET | `/api/repositories/{repoId}/evidence-search?query=&limit=` | 联合检索：代码 + 知识，返回证据与诊断 | 仓库 `READ` |
| GET | `/api/repositories/{repoId}/hybrid-search?query=&limit=` | 仅代码的混合检索（历史接口，前端未使用） | 仓库 `READ` |
| POST | `/api/repositories/{repoId}/ask` | 证据问答，内部调用联合检索取证据 | 仓库 `READ` |
| GET | `/api/repositories/{repoId}/code-evidence-context?filePath=&symbol=` | 按文件与符号做确定性适用知识匹配（非联合检索） | 仓库 `READ` |
| GET | `/api/repositories/{repoId}/knowledge` | 知识清单；无 `MAINTAIN` 时只返回已发布且已审核的有效知识 | 仓库 `READ`（`MAINTAIN` 时含草稿） |
| GET | `/api/repositories/{repoId}/chunks/{chunkId}/graph-target` | 由片段解析图谱焦点符号与文件行号 | 仓库 `READ` |
| GET | `/api/repositories/{repoId}/graph?symbol=&depth=&direction=` | 启发式调用关系图，`depth` 夹紧到 1–5 | 仓库 `READ` |
| POST | `/api/repositories/{repoId}/contexts` | 由 `branchId` 或 `contextId` 建立/解析阅读上下文 | 仓库 `READ`（访问令牌可用） |
| GET | `/api/mcp` | MCP 入口，工具 `search_project` / `resolve_project_context` 复用联合检索 | 仓库 `READ`（Bearer 访问令牌或会话） |

证据：`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:59`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:46`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:72`、`backend/src/main/java/com/analyzercoder/interfaces/rest/CodeEvidenceContextController.java:18`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:176`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:155`、`backend/src/main/java/com/analyzercoder/interfaces/rest/IntelligenceController.java:165`、`backend/src/main/java/com/analyzercoder/interfaces/rest/RepositoryBranchController.java:108`、`backend/src/main/java/com/analyzercoder/security/AccessTokenInterceptor.java:50`

权限级别与角色：`RepositoryPermission` 只有 `READ`、`MAINTAIN`、`MANAGE` 三级；仓库所有者（owner）按放行处理，另有仅所有者动作；角色为超级管理员与普通用户。证据：`backend/src/main/java/com/analyzercoder/security/RepositoryPermission.java:4`、`backend/src/main/java/com/analyzercoder/security/AccessControlService.java:19`

## 5 边界与非目标

- 分数是启发式排序值：`score`、`lexicalScore`、`similarityScore` 都不代表正确率、召回率或模型置信度。
- 检索是词法 + 向量 + 一张启发式关系表的组合：不包含学习型重排模型、查询改写模型或同义词词典。
- 关键词通道基于 `POSITION` 子串匹配，不做分词索引、不做词干化、不做拼写纠错；中文依赖 4/3/2 宽度 n-gram 补充。
- 向量检索为精确余弦扫描，未建立向量索引；候选池受每通道上限约束。
- 知识可见性是硬门槛：未发布、未通过评审、来源版本 `SUSPECT`/`STALE`、或代码引用已失效的知识都不会出现在结果中，即使关键词高度匹配。
- 分支模式下不启用图谱相关通道；图谱相关通道本身也不是静态分析结果，只是“符号名 + 左括号”的字符串匹配，无法识别重载、动态分派、反射、别名与跨语言调用。
- 默认模式的图谱相关通道与知识分支范围都锚定在仓库默认分支/默认版本令牌上；若用户正在阅读其它分支而未使用阅读上下文，结果不代表阅读分支。
- 前端不展示分数数值，只展示来源、位置、摘要与命中通道；因此分数仅供后端排序与诊断使用。
- 本域不负责索引构建与向量补建，检索路径不得写库（见 05 文档 IDX-026）。

## 6 已知缺口

- 前端通道中文名映射缺少三个知识类通道（`KNOWLEDGE_KEYWORD`、`KNOWLEDGE_SEMANTIC`、`KNOWLEDGE_CHARACTER_SIMILARITY`），诊断条会把它们原样显示为英文标识。证据：`frontend/src/views/ChunksM0View.vue:291`
- 诊断条只在检索成功返回后渲染；检索请求整体失败时只弹错误提示，用户看不到失败时的范围与降级信息。证据：`frontend/src/views/ChunksM0View.vue:256`
- 前端结果列表对默认模式做了客户端快照过滤（丢弃非当前快照的代码证据），但分支模式下该过滤仍会保留 `KNOWLEDGE` 证据而只按 `snapshotId` 比较代码证据；由于知识证据的 `snapshotId` 本来就为空，分支模式下的知识证据不会被该过滤影响，判断逻辑依赖 `sourceType` 特例，可读性较差。证据：`frontend/src/views/ChunksM0View.vue:247`
- `RetrievalDiagnostics.recalledCount` 语义是“融合后返回的候选数”，与每通道 `recalledCount`（该通道召回条数）同名不同义，容易误读；是否有意为之需人工确认。证据：`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1581`、`backend/src/main/java/com/analyzercoder/application/intelligence/IntelligenceService.java:1592`
- 默认模式的图谱相关通道依赖索引阶段生成的 `heuristic_call_edges`；该表按仓库当前快照过滤，因此当默认版本令牌变更而索引任务尚未运行时，该通道会因为没有当前快照的边而静默返回空，不登记为降级。证据：`backend/src/main/java/com/analyzercoder/infrastructure/persistence/mapper/GraphRetrievalMapper.java:40`
- 检索没有结果缓存，也没有对相同 `(repositoryId, query, snapshotId)` 的复用；每次请求都会重新向量化查询文本并执行全通道查询。是否需要缓存需人工确认。
- 关键词通道的打分公式与术语个数归一（除以 `termCount`）在术语很多时会把单术语命中权重稀释；实现如此，是否符合预期需人工确认。证据：`backend/src/main/resources/mappers/IntelligenceMapper.xml:85`
- 分支模式下该快照没有匹配向量时只登记 `BRANCH_VECTOR_NOT_READY` 并继续用关键词检索，没有提供“立即补建向量”的前端动作；补建需去分支任务页面另行提交（见 05 文档 IDX-022）。
