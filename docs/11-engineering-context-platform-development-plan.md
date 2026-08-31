# 工程上下文与变更治理平台开发需求

版本：v1.0  
日期：2026-08-30  
状态：REQ-003～REQ-022 已完成开发，待 PostgreSQL、真实仓库与真实任务验收
适用范围：`analyzer-coder` 当前代码库及后续演进  
文档性质：产品需求、技术设计、开发顺序和验收基线

## 1. 文档目的

本文件将项目后续方向固定为：

> 基于真实 Git 变更，匹配已审核的工程知识，输出测试、审批、知识失效和可核验证据，并同时服务开发者与 Coding Agent。

本文件是后续新增功能的开发基线。历史需求和设计文档继续描述已经交付的能力；当历史文档中的产品方向、菜单规划或功能含义与本文件冲突时，新功能以本文件为准。

后续开发必须按本文的需求编号、依赖关系和完成定义逐项推进，不再因页面展示效果临时改变产品主线。

## 2. 产品定位

### 2.1 产品解决的问题

系统不与 Coding Agent 竞争代码生成、修改和测试执行。系统负责 Coding Agent 难以长期稳定维护的组织级上下文：

1. 哪些业务规则适用于本次修改。
2. 哪些架构、接口、数据和安全约束必须遵守。
3. 哪些测试必须执行。
4. 哪些负责人必须确认。
5. 哪些结论来自 Git 和源码事实，哪些只是静态推断或模型建议。
6. 代码变化后，哪些知识可能已经过期。
7. 如何将同一份可信上下文提供给人、Agent、PR 和 CI。

### 2.2 产品不做什么

1. 不建设另一个代码编辑器或 Coding Agent。
2. 不把 README 阅读器作为项目总览核心。
3. 不把 Markdown 批量转卡片作为核心价值。
4. 不使用向量相似度直接产生强制规则。
5. 不使用大模型直接判断代码是否允许合并。
6. 不把检索排序分数展示成影响概率。
7. 不把影响节点数量直接展示成风险等级。
8. 不在单仓库闭环完成前开发跨仓库复杂治理。

## 3. 目标用户与权限

| 用户 | 权限 | 主要任务 |
| --- | --- | --- |
| 开发者 | `READ` | 获取任务上下文、审查工作区或 Commit、核对证据 |
| 知识维护者 | `MAINTAIN` | 创建和编辑知识、维护适用范围、处理知识失效 |
| 项目管理员 | `MANAGE` | 审核和发布知识、管理负责人和审批要求 |
| Coding Agent | 继承调用账号权限 | 获取上下文、提交变更审查、报告任务结果 |
| 系统管理员 | 系统级管理员 | 模型、账号、索引、审计和外部集成配置 |

## 4. 核心业务闭环

```text
维护者创建并审核工程知识
              ↓
开发者或 Agent 开始任务
              ↓
获取当前任务适用的规则和约束
              ↓
Agent 或开发者修改代码并产生 Git Diff
              ↓
平台解析真实文件、行号和符号变化
              ↓
匹配适用知识、测试和审批要求
              ↓
输出事实、推断、建议和未知项
              ↓
标记受影响知识为待复核
              ↓
负责人重新验证知识
```

P0 的完成标志不是页面上线，而是这条闭环可以在真实仓库和真实任务上重复运行。

## 5. 现有能力复用

后续设计必须优先复用当前实现：

| 当前能力 | 后续用途 |
| --- | --- |
| 仓库、Commit、Snapshot、Worktree Digest | 版本一致性和事实边界 |
| `GitDiffService` | 扩展为工作区和 Commit Range 变更引擎 |
| 代码 Chunk、符号、内容哈希 | 行号到符号映射及知识引用核验 |
| CodeGraph 发布产物 | P1 真实传播路径 |
| 知识卡审核、发布、修订历史 | 工程知识生命周期 |
| `knowledge_code_refs` | 知识与代码证据绑定 |
| `source_version_status` | 扩展为精确知识失效状态 |
| `ProjectContextPackService` | 升级为 Agent 任务上下文接口 |
| 仓库权限与审计 | 任务审查和知识复核权限 |
| 现有变更分析页 | 改造为变更审查工作台 |
| 质量评测目录和脚本 | 扩展 Task Review 指标 |

## 6. 统一真实性与证据模型

### 6.1 真实性类型

```text
GIT_FACT              Git 命令直接得到的文件和行变化
CODE_FACT             指定版本源码、符号和内容哈希
VERIFIED_KNOWLEDGE    已审核、已发布且来源有效的工程知识
GRAPH_INFERENCE       CodeGraph 或静态关系推断
RETRIEVAL_CANDIDATE   关键词或向量检索候选
MODEL_SUGGESTION      大模型总结和建议
UNKNOWN               当前证据无法确认
```

### 6.2 统一证据结构

```json
{
  "id": "evidence-id",
  "sourceType": "CODE_FACT",
  "repositoryId": "uuid",
  "snapshotId": "uuid",
  "commitSha": "sha",
  "filePath": "backend/src/main/java/example/RefundService.java",
  "symbolName": "approveRefund",
  "symbolKind": "METHOD",
  "startLine": 40,
  "endLine": 65,
  "contentHash": "sha256",
  "knowledgeCardId": null,
  "knowledgeRevision": null,
  "graphArtifactId": null,
  "detail": "修改行与方法声明范围重叠"
}
```

规则：

1. 每条确定性 Finding 至少包含一条证据。
2. `UNKNOWN` 可以没有代码证据，但必须有稳定原因代码。
3. 知识证据使用知识修订号，不伪造代码 Snapshot。
4. 模型只能引用已有 Finding 或 Evidence ID，不能创建新的文件或符号事实。
5. 所有页面用文字标注来源，颜色只用于辅助识别。

## 7. 需求总表

| 编号 | 优先级 | 功能 | 状态 | 依赖 |
| --- | --- | --- | --- | --- |
| REQ-001 | P0 | 工程知识结构化 | 开发完成，待 PostgreSQL 集成验收 | 无 |
| REQ-002 | P0 | 知识适用范围 | 已完成 | REQ-001 |
| REQ-003 | P0 | 真实 Git Diff 引擎 | 已完成 | 无 |
| REQ-004 | P0 | 改动符号识别 | 已完成 | REQ-003 |
| REQ-005 | P0 | 确定性知识匹配 | 已完成 | REQ-002、REQ-004 |
| REQ-006 | P0 | 任务审查领域与 API | 开发完成，待 PostgreSQL 集成验收 | REQ-005、REQ-009 |
| REQ-007 | P0 | 变更审查页面 | 开发完成，待登录态视觉验收 | REQ-006 |
| REQ-008 | P0 | 知识失效与重新验证 | 开发完成，待 PostgreSQL 集成验收 | REQ-003、REQ-005 |
| REQ-009 | P0 | 统一真实性与证据 | 已完成 | REQ-003、REQ-005 |
| REQ-010 | P0 | 真实质量评测 | 开发完成，待人工金标准复核与真实运行 | REQ-001～REQ-009 |
| REQ-011 | P1 | Agent 任务上下文接口 | 已完成 | P0 |
| REQ-012 | P1 | MCP 接入 | 已完成 | REQ-011 |
| REQ-013 | P1 | CodeGraph 真实传播路径 | 开发完成，待 Linux CLI 契约验收 | P0 |
| REQ-014 | P1 | 带引用的模型总结 | 已完成 | REQ-006、REQ-009 |
| REQ-015 | P1 | 工程健康项目总览 | 已完成 | P0 |
| REQ-016 | P1 | 代码与证据工作台 | 已完成 | REQ-009、REQ-013 |
| REQ-017 | P1 | 准备流程自动修复 | 已完成 | REQ-008、REQ-013 |
| REQ-018 | P1 | 导航与权限收敛 | 已完成 | REQ-007、REQ-015、REQ-016 |
| REQ-019 | P2 | PR/MR 集成 | 已完成 | P1 |
| REQ-020 | P2 | CI 确定性检查 | 开发完成，待 PostgreSQL 与真实流水线验收 | REQ-006、REQ-008、REQ-019 |
| REQ-021 | P2 | 跨仓库工程知识 | 开发完成，待 PostgreSQL 与真实多仓验收 | P1 真实验证通过 |
| REQ-022 | P2 | 开发结果与反馈 | 开发完成，待 PostgreSQL 与真实任务验收 | REQ-006 |

## 8. P0 详细需求

### REQ-001 工程知识结构化

#### 目标

把当前“标题、正文、标签”知识卡升级为可参与开发检查的工程知识对象。

#### 数据库

新增迁移 `V9__engineering_knowledge.sql`。

`knowledge_cards` 和 `knowledge_card_revisions` 同步增加：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `knowledge_kind` | `VARCHAR(40)` | `REFERENCE` | 知识类型 |
| `severity` | `VARCHAR(20)` | `INFO` | `INFO/WARNING/CRITICAL` |
| `enforcement` | `VARCHAR(20)` | `REFERENCE` | `REFERENCE/ADVISORY/REQUIRED` |
| `owner_account_id` | `UUID` | `NULL` | 知识负责人 |
| `scope_payload` | `JSONB` | 空 Scope | 适用范围 |
| `obligations_payload` | `JSONB` | 空 Obligations | 测试与审批要求 |
| `last_verified_snapshot_id` | `UUID` | `NULL` | 最近验证快照 |
| `verification_note` | `TEXT` | `NULL` | 最近验证说明 |

知识类型：

```text
REFERENCE
BUSINESS_RULE
ARCH_DECISION
API_CONTRACT
DATA_CONSTRAINT
TEST_OBLIGATION
SECURITY_POLICY
RUNBOOK
INCIDENT_LESSON
OWNERSHIP
TECH_DEBT
```

`source_version_status` 增加 `SUSPECT`：

```text
UNVERIFIED → CURRENT → SUSPECT → CURRENT/STALE
```

修订历史必须保存全部新字段；恢复历史后统一进入 `DRAFT + UNREVIEWED + UNVERIFIED`。

#### 后端

新增：

```text
domain/knowledge/KnowledgeKind.java
domain/knowledge/KnowledgeSeverity.java
domain/knowledge/KnowledgeEnforcement.java
domain/knowledge/KnowledgeScope.java
domain/knowledge/KnowledgeObligations.java
application/knowledge/EngineeringKnowledgePolicy.java
```

修改：

```text
KnowledgeCardRow
KnowledgeRevisionRow
IntelligenceService.CardInput
IntelligenceService.KnowledgeCard
IntelligenceMapper
KnowledgeHistoryMapper
```

当前知识 CRUD 暂时保留在 `IntelligenceService`，通过 `EngineeringKnowledgePolicy` 统一校验，P0 不进行大规模服务拆分。

#### 业务规则

1. `REFERENCE` 不产生强制义务。
2. `REQUIRED` 发布时必须有负责人和非空 Scope。
3. `REQUIRED` 必须是 `APPROVED + PUBLISHED + CURRENT` 才能参与正式审查。
4. `SUSPECT/STALE` 不产生强制义务。
5. P0 拒绝创建自动阻断规则；阻断只在 REQ-020 实现。

#### 前端

知识编辑器分为：

1. 基本内容。
2. 适用范围。
3. 开发要求。
4. 负责人和状态。
5. 代码证据。

高级字段默认折叠；普通参考知识仍可快速创建。

#### 验收

- [ ] 旧知识卡无损迁移（迁移脚本和契约测试已通过，待全新库与 V8 实库升级验证）。
- [x] 新字段完整进入修订历史。
- [x] 历史恢复不保留旧审核和来源确认。
- [x] 不符合规则的 `REQUIRED` 知识无法发布。
- [x] 前后端枚举一致。
- [x] JSON 字段有长度、数量和结构校验。

### REQ-002 知识适用范围

#### 目标

明确某条知识在什么代码范围内生效，防止所有知识对所有变更产生噪音。

#### Scope 模型

```json
{
  "pathPatterns": ["backend/src/**/refund/**"],
  "symbols": ["RefundService", "approveRefund"],
  "modules": ["backend"]
}
```

仓库由 `knowledge_cards.repo_id` 隐式限定，P0 不在 Scope 内保存跨仓库 ID。

#### 匹配来源

| Scope | 匹配方式 | 来源 |
| --- | --- | --- |
| 代码引用 | 绑定路径或内容哈希变化 | `CODE_FACT` |
| 路径 | 仓库相对路径 Glob | `GIT_FACT` |
| 符号 | 改动符号精确匹配 | `CODE_FACT` |
| 模块 | 当前架构地图模块匹配 | `GRAPH_INFERENCE` |

#### Glob 约束

1. 支持 `*`、`**`、`?`。
2. 统一使用 `/`。
3. 区分大小写。
4. 禁止绝对路径、盘符、`..` 和控制字符。
5. 单条规则最大 300 字符。
6. 每张卡最多 50 条路径、50 个符号和 20 个模块。

#### 实现

```text
application/knowledge/RepositoryGlobMatcher.java
application/review/KnowledgeScopeMatcher.java
application/review/KnowledgeMatchReason.java
```

#### 验收

- [x] Windows 与 Linux 得到一致匹配结果。
- [x] 每次命中返回规则、目标和证据。
- [x] 图谱不可用时模块匹配进入未知项。
- [x] 向量结果不能生成 `REQUIRED` 命中。
- [x] 非法路径规则无法保存。

### REQ-003 真实 Git Diff 引擎

#### 目标

用 Git 事实回答“这次究竟改了什么”，替代只依赖任务描述的候选检索。

#### 变更来源

```text
WORKTREE
SINGLE_COMMIT
COMMIT_RANGE
```

#### 输出

```json
{
  "source": "COMMIT_RANGE",
  "baseCommit": "sha",
  "headCommit": "sha",
  "worktreeDigest": null,
  "partial": false,
  "changes": [
    {
      "type": "MODIFIED",
      "oldPath": "path",
      "newPath": "path",
      "binary": false,
      "additions": 12,
      "deletions": 4,
      "hunks": [
        { "oldStart": 31, "oldCount": 4, "newStart": 31, "newCount": 12 }
      ]
    }
  ],
  "limitations": []
}
```

#### 架构

```text
infrastructure/git/ProcessGitClient.java
application/change/RepositoryChangeService.java
application/change/GitChangeRequest.java
application/change/RepositoryChange.java
```

现有 `GitDiffService` 改为委托新的 Git 客户端，保持增量索引行为兼容。

#### Git 命令

使用 `ProcessBuilder`，禁止 Shell 拼接：

```text
git rev-parse --verify
git diff --name-status -z -M -C
git diff --numstat -z
git diff --unified=0 --no-color --no-ext-diff
git ls-files --others --exclude-standard -z
```

#### 安全边界

1. Ref 最大 200 字符，禁止控制字符和以 `-` 开头。
2. 单次最多 5,000 个文件。
3. Patch 最大 5 MiB。
4. Git 命令 30 秒超时。
5. 所有路径必须是规范化仓库相对路径。
6. 超限时返回 `partial=true` 和限制说明，不能静默截断。
7. 不把完整 Patch 写入应用日志。

#### WORKTREE 一致性

分析开始和结束分别计算 Worktree Digest。发生变化时返回：

```text
WORKTREE_CHANGED_DURING_ANALYSIS
```

不得发布混合结果。

#### 验收

- [x] 与原始 Git 文件变化完全一致。
- [x] 正确处理新增、修改、删除、复制、重命名和二进制文件。
- [x] 包含未跟踪文件。
- [x] 路径越界和参数注入测试通过。
- [x] 非 Git 项目返回稳定的不支持错误。
- [x] 现有增量索引测试保持通过。

### REQ-004 改动符号识别

#### 目标

把 Diff 行号映射到类、接口、方法、函数、配置项或文件级对象。

#### 识别顺序

```text
CodeGraph 节点
→ 源码声明
→ Chunk 符号
→ FILE_LEVEL
```

#### 输出

```json
{
  "symbolId": "java:path:METHOD:approveRefund",
  "name": "approveRefund",
  "kind": "METHOD",
  "filePath": "backend/src/main/java/example/RefundService.java",
  "oldStartLine": 40,
  "newStartLine": 43,
  "changeType": "MODIFIED",
  "resolution": "SOURCE_DECLARATION",
  "provenance": []
}
```

`resolution`：

```text
CODEGRAPH
SOURCE_DECLARATION
CHUNK_SYMBOL
FILE_LEVEL
```

#### 实现

将 `IndexJobProcessor` 内的声明识别规则抽取为：

```text
application/code/CodeSymbolExtractor.java
application/review/ChangedSymbolResolver.java
```

索引和变更审查共用同一提取器。删除文件通过 `git show <base>:<path>` 读取 Base 内容；失败时降级为文件级。

#### 边界

1. 单文件最多读取 1 MiB。
2. 动态生成代码只做文件级识别。
3. 同名符号使用路径和类型区分。
4. P0 不引入完整 AST 框架。

#### 验收

- [x] 不生成仓库中不存在的符号。
- [x] 每个 Hunk 至少归属文件级对象。
- [x] 删除和重命名保留旧符号信息。
- [x] 超大文件和无法解析文件有明确降级原因。

### REQ-005 确定性知识匹配

#### 目标

将真实改动映射为适用知识、测试、审批和知识失效候选。

#### 服务

```text
application/review/TaskContextMatcher.java
application/review/TaskReviewFinding.java
application/review/KnowledgeMatch.java
application/review/KnowledgeMatchReason.java
```

#### 流程

```text
加载 PUBLISHED + APPROVED 知识
        ↓
按 CURRENT/SUSPECT/STALE 分组
        ↓
代码引用匹配
        ↓
路径匹配
        ↓
符号匹配
        ↓
模块匹配
        ↓
汇总测试、审批和未知项
```

#### 输出分组

```text
applicableKnowledge
referenceCandidates
requiredTests
requiredApprovals
staleKnowledge
unknowns
```

规则：

1. `CURRENT` 确定性命中进入 `applicableKnowledge`。
2. `SUSPECT/STALE` 命中进入 `staleKnowledge`，不产生强制义务。
3. 纯检索相似结果只能进入 `referenceCandidates`。
4. `REQUIRED` 知识确定性命中后产生测试和审批义务。
5. 证据不足时产生 `UNKNOWN`，不能直接判断违规。

#### 义务状态

测试：

```text
REQUIRED_NOT_REPORTED
REPORTED_PASSED
REPORTED_FAILED
NOT_APPLICABLE
```

审批：

```text
REQUIRED
APPROVED
REJECTED
NOT_APPLICABLE
```

P0 只产生 `REQUIRED_NOT_REPORTED` 和 `REQUIRED`；实际执行结果现由 REQ-022 以追加式回报保存，不改写原审查状态。

#### 验收

- [x] 同一知识只出现一次，可包含多个命中原因。
- [x] 相同测试和审批自动去重。
- [x] 不显示概率百分比。
- [x] 不使用模型判断规则是否满足。
- [x] 每个 Finding 都有证据或未知原因。

### REQ-006 任务审查领域与 API

#### 目标

提供人、前端、Agent、PR 和 CI 共用的唯一变更审查入口。

#### 数据库

新增迁移 `V10__task_reviews.sql`，创建 `task_reviews`：

| 字段 | 说明 |
| --- | --- |
| `id` | 审查 ID |
| `repo_id` | 仓库 |
| `created_by` | 发起账号 |
| `client_request_id` | 幂等请求 ID |
| `task` | 任务描述，可为空 |
| `change_source` | 变更来源 |
| `base_ref/head_ref` | 用户输入 Ref |
| `base_commit/head_commit` | 实际解析 Commit |
| `snapshot_id` | 分析时发布快照 |
| `worktree_digest` | 工作区一致性摘要 |
| `status` | `RUNNING/COMPLETED/FAILED` |
| `result_payload` | 完整不可变结果 JSON |
| `error_code/error_message` | 稳定错误 |
| `created_at/finished_at` | 时间 |

唯一索引：

```text
(created_by, repo_id, client_request_id)
```

#### 后端

```text
application/review/TaskReviewService.java
application/review/TaskReviewRequest.java
application/review/TaskReviewResult.java
interfaces/rest/TaskReviewController.java
infrastructure/persistence/mapper/TaskReviewMapper.java
resources/mappers/TaskReviewMapper.xml
```

#### API

```http
POST /api/repositories/{id}/task-reviews
GET  /api/repositories/{id}/task-reviews
GET  /api/repositories/{id}/task-reviews/{reviewId}
```

创建和查看需要 `READ` 权限；仓库内审查结果对拥有 READ 权限的成员可见。

#### 创建请求

```json
{
  "clientRequestId": "uuid",
  "task": "增加退款人工审批",
  "changeSource": "WORKTREE",
  "baseRef": "HEAD",
  "headRef": null,
  "modelConfigId": null
}
```

P0 保存 `modelConfigId` 但不调用模型。

#### 响应

```json
{
  "reviewId": "uuid",
  "status": "COMPLETED",
  "repositoryId": "uuid",
  "snapshotId": "uuid",
  "change": {},
  "changedSymbols": [],
  "applicableKnowledge": [],
  "referenceCandidates": [],
  "requiredTests": [],
  "requiredApprovals": [],
  "staleKnowledge": [],
  "unknowns": [],
  "summary": null,
  "createdAt": "timestamp"
}
```

#### 一致性

1. 当前快照不存在时拒绝审查。
2. 工作区变化时审查失败。
3. CodeGraph 快照不一致时排除图谱结果并返回未知项。
4. 已完成结果不可覆盖。
5. 相同幂等请求返回原结果。
6. 不在日志中输出完整 Patch 或源码内容。

#### 验收

- [x] POST 幂等。
- [x] 历史详情可完整恢复。
- [x] 失败结果有稳定错误码。
- [x] API 不依赖前端状态。
- [x] 并发切换 Snapshot 不产生混合结果。

### REQ-007 变更审查页面

#### 目标

让开发者在一个页面内完成真实变更、适用知识、测试、审批、知识失效和未知项核对。

#### 路由

保留 `/change-impact`，页面名称改为“变更审查”，避免旧链接失效。

页面内部模式：

```text
实际变更审查（默认）
需求影响预估（现有功能降级保留）
```

需求预估必须显示“未读取真实 Git Diff”，排序分数不能显示为概率。

#### 页面结构

```text
┌ 仓库 / Snapshot / Commit ──────────────────────┐
├ 实际变更审查 | 需求影响预估 ───────────────────┤
├ 任务描述 ──────────────────────────────────────┤
├ 工作区 / 单 Commit / Commit Range ─────────────┤
├ 开始审查 ──────────────────────────────────────┤
│                                                │
│  变更证据脊柱                    证据详情       │
│  ● 真实改动                     文件与符号     │
│  │                                             │
│  ● 适用知识                     知识原文       │
│  │                                             │
│  ● 测试与审批                   匹配原因       │
│  │                                             │
│  ● 知识失效                     负责人         │
│  │                                             │
│  ● 未知项                       限制和动作     │
└────────────────────────────────────────────────┘
```

#### 视觉基线

页面服务于代码审查，不做通用仪表盘：

| 用途 | 色值 |
| --- | --- |
| 页面背景 | `#F5F7F8` |
| 主文字 | `#1F2A33` |
| Git/代码事实 | `#2B6CB0` |
| 已验证知识 | `#1F7A5A` |
| 警告 | `#B96A19` |
| 未知 | `#76838F` |

正文继续使用 Inter/微软雅黑，路径、Commit、符号使用 SFMono/Consolas。不使用渐变、大面积彩色卡片和装饰性风险分数。“证据脊柱”是唯一视觉识别元素，表达审查顺序。

#### 前端文件

```text
frontend/src/api/taskReviews.ts
frontend/src/features/task-review/TaskReviewForm.vue
frontend/src/features/task-review/ChangeEvidenceSpine.vue
frontend/src/features/task-review/KnowledgeFindingList.vue
frontend/src/features/task-review/ObligationPanel.vue
frontend/src/features/task-review/ReviewEvidenceDrawer.vue
frontend/src/views/ChangeImpactView.vue
```

#### 交互

1. 点击代码证据进入代码页并定位行号。
2. 点击知识打开知识详情。
3. 点击未知项展示原因和处理动作。
4. 切换仓库后清空当前未提交表单和临时结果。
5. 历史结果只读。
6. WORKTREE 模式提示分析期间避免修改文件。
7. 窄屏转单栏，证据详情使用抽屉。

#### 验收

- [x] 所有状态有文字说明，不只依赖颜色。
- [x] 加载、空数据、失败和降级状态完整。
- [x] 证据能跳到代码或知识原文。
- [x] 不展示含义不明的候选百分比。
- [ ] 桌面和移动布局完成，待可用登录态下执行浏览器视觉验收。
- [x] 键盘焦点可见。

### REQ-008 知识失效与重新验证

#### 目标

代码变化后只标记真正受影响的知识，替换当前“Commit 变化即全仓库过期”的粗粒度逻辑。

#### 状态规则

```text
知识代码引用哈希变化 → SUSPECT
知识路径 Scope 与 Diff 相交 → SUSPECT
知识符号 Scope 与改动符号相交 → SUSPECT
只有 Commit 变化但 Scope 未受影响 → 保持 CURRENT
人工确认内容已错误 → STALE
```

V9 删除当前全仓库粗粒度失效触发器，新增：

```text
application/knowledge/KnowledgeDriftService.java
```

在新快照完成索引后执行 Drift 检查。

#### API

```http
POST /api/repositories/{id}/knowledge/{cardId}/source-review
```

```json
{
  "action": "CONFIRM_CURRENT",
  "expectedRevision": 4,
  "note": "已核对新的退款审批实现"
}
```

动作：

```text
CONFIRM_CURRENT
MARK_STALE
```

权限：`MAINTAIN`。

#### 验收

- [x] 非关联代码变化不影响知识。
- [x] 每次状态变化写审计。
- [x] 重新确认更新 Commit、Snapshot 和时间。
- [x] `SUSPECT` 不产生强制义务。
- [x] 页面能打开触发失效的 Diff 证据。

### REQ-009 统一真实性与证据

#### 目标

消除“知识证据使用代码 Snapshot 校验”“排序分数当真实性”等混乱。

#### 实现

```text
application/evidence/TruthSource.java
application/evidence/Provenance.java
frontend/src/components/evidence/TruthSourceBadge.vue
frontend/src/components/evidence/EvidenceLink.vue
frontend/src/components/evidence/ProvenanceSummary.vue
```

#### 规则

1. Git 和代码事实必须包含版本信息。
2. 知识事实必须包含卡片 ID、修订号和审核状态。
3. 图谱推断必须包含图谱产物和关系路径。
4. 检索候选必须显示检索通道，不能显示成概率。
5. 模型建议只能引用已有 Finding。
6. 修复变更分析把 `knowledge://` 证据当代码快照过滤的问题。

#### 验收

- [x] 后端 DTO、持久化结果和前端类型一致。
- [x] 每条 Finding 有来源。
- [x] 知识证据不再产生错误的混合快照告警。
- [x] 页面不扩大后端结论含义。

### REQ-010 真实质量评测

#### 目标

证明系统在真实任务中是否准确，不以单元测试或界面演示替代质量证据。

#### 数据集

```text
evaluation/task-review.jsonl
evaluation/knowledge-drift.jsonl
evaluation/task-review-results/
```

样本结构：

```json
{
  "id": "TR-001",
  "repositoryId": "uuid",
  "task": "修改退款审批",
  "baseRef": "base",
  "headRef": "head",
  "expectedFiles": [],
  "expectedSymbols": [],
  "expectedKnowledgeIds": [],
  "expectedRequiredTests": [],
  "expectedStaleKnowledgeIds": []
}
```

#### 指标

```text
diffFileExactness
symbolPrecision
symbolRecall
knowledgePrecision
knowledgeRecall
driftPrecision
driftRecall
provenanceCompleteness
fabricatedPathCount
p95LatencyMs
```

#### 首版门槛

```text
diffFileExactness = 1.0
fabricatedPathCount = 0
provenanceCompleteness = 1.0
exact-reference driftPrecision = 1.0
knowledgePrecision >= 0.90
```

知识召回率先建立真实基线，再确定正式阈值。

#### 验收

- [ ] 至少包含 10 个经人工复核的真实开发任务。
- [x] 结果文件齐全才能计算通过。
- [x] 误报和漏报分别统计。
- [x] 人工判断必须记录评审人和时间。
- [x] CI 可以重复运行评测程序。

## 9. P1 详细需求

### REQ-011 Agent 任务上下文接口

将现有 Context Pack 从“按关键词抓取 README、规则文件和代码”升级为“已验证工程知识优先”。

API：

```http
POST /api/repositories/{id}/task-context
```

返回结构化 JSON 和 Markdown，内容按以下顺序装配：

```text
CRITICAL REQUIRED
→ WARNING REQUIRED
→ VERIFIED_KNOWLEDGE
→ CODE_FACT
→ RETRIEVAL_CANDIDATE
```

支持最大条目数和字符/Token 预算。旧 `/context-pack` 保留一个版本周期，内部委托新服务。

验收：

- [x] Agent 获得任务适用规则、测试、审批、代码证据和未知项。
- [x] 只向 Agent 返回调用账号有权限的仓库内容。
- [x] 超出预算时优先保留强制规则和未知项。
- [x] Context Pack 绑定 Commit 和 Snapshot。

### REQ-012 MCP 接入

MCP 作为 HTTP API 的薄适配层，不重复业务逻辑。

工具：

```text
get_task_context
review_change
get_rules_for_symbol
get_required_tests
get_stale_knowledge
get_evidence
report_task_outcome
```

验收：

- [x] MCP 与 HTTP 权限一致。
- [x] 默认输出精简，可按 ID 获取证据详情。
- [x] MCP 不能绕过知识审核和来源状态。
- [x] 同一输入与 HTTP 得到相同业务结果。

### REQ-013 CodeGraph 真实传播路径

图谱接口返回稳定节点、文件、行号、深度、边和完整路径：

```json
{
  "nodes": [],
  "edges": [],
  "paths": [],
  "relationSource": "CODEGRAPH_CLI",
  "affectedNodeCount": 0,
  "maxDepthReached": 0,
  "coverage": {},
  "limitations": []
}
```

删除 `risk`，不再通过节点数量生成高、中、低风险。必须保留 CLI 返回的真实层级，禁止把所有节点连成星形关系。

验收：

- [x] 能解释 A 通过哪条路径影响 B。
- [x] 路径节点能定位代码。
- [x] CLI 与启发式来源明确区分。
- [x] 无图谱或图谱版本不一致时明确降级。

### REQ-014 带引用的模型总结

模型只接收已完成的 `TaskReviewResult`，输出强制 JSON：

```json
{
  "summary": "...",
  "findings": [
    { "text": "...", "evidenceIds": ["finding-id"] }
  ],
  "unknowns": []
}
```

运行时只验证 Evidence ID 存在和输出结构正确；语义支持率通过人工评测证明。模型失败不能影响确定性结果。

验收：

- [x] 模型不能创建新文件、新符号和新强制规则。
- [x] 非法引用时丢弃模型总结。
- [x] 模型不可用时 Task Review 仍完整。
- [x] UI 明确标记 `MODEL_SUGGESTION`。

### REQ-015 工程健康项目总览

页面职责：回答当前项目是否具备可信知识和可执行审查条件。

保留：

```text
项目名称、分支、Commit、Snapshot
准备状态
CodeGraph、向量、知识和代码类型统计
```

新增：

```text
CURRENT/SUSPECT/STALE 知识数量
无负责人 REQUIRED 知识
未审核知识
最近变更审查
当前阻塞项
开始变更审查主操作
```

删除 README 主阅读器，不恢复技术栈主展示。

验收：

- [x] 分支、Commit、Snapshot 和工作区状态在首屏可见。
- [x] CodeGraph、向量、可信知识和代码类型都使用当前持久化事实。
- [x] CURRENT/SUSPECT/STALE、未审核和无负责人的 REQUIRED 知识可见。
- [x] 最近审查和当前阻塞项在同一页面可见。
- [x] “开始变更审查”是唯一主操作，条件不足时明确禁用。
- [x] 页面不读取 README，也不恢复技术栈展示。

### REQ-016 代码与证据工作台

合并源码检索和调用图谱，提供：

```text
文件
检索
关系
知识引用
审查引用
```

检索使用混合检索接口并展示实际检索能力、Snapshot 和降级原因。关系作为当前文件或符号的上下文，不作为独立全屏图谱。

验收：

- [x] 问答、知识和审查可以统一跳到该工作台。
- [x] 文件、符号、知识和关系证据可相互导航。
- [x] 删除独立调用图谱主菜单。

### REQ-017 准备流程自动修复

状态机：

```text
SNAPSHOT
→ CHUNKS
→ EMBEDDINGS
→ CODEGRAPH
→ KNOWLEDGE_DRIFT
→ READY/DEGRADED
```

规则：

1. 缺失向量时自动启动修复。
2. 新 Snapshot 无图谱时自动排队 CodeGraph。
3. CodeGraph 成功后执行知识失效检查。
4. 任一步失败提供阶段级重试。
5. 不要求用户在仓库、索引和图谱页面之间手工串联。

### REQ-018 导航与权限收敛

普通开发者：

```text
项目总览
代码与证据
问项目
变更审查
```

维护者：

```text
知识治理
项目管理
```

管理员在“系统管理”内使用：

```text
索引任务
模型配置
账号权限
审计日志
```

完成后不再把后台运维能力与日常研发任务平铺在同一级菜单。

## 10. P2 详细需求

### REQ-019 PR/MR 集成

抽象：

```text
PullRequestProvider
GitHubPullRequestProvider
GitLabMergeRequestProvider
```

能力：

1. 获取 PR/MR 元数据和 Patch。
2. 转换成统一 `RepositoryChange`。
3. 执行 Task Review。
4. 使用隐藏 Marker 幂等更新评论。
5. 展示适用知识、测试、审批、知识失效和未知项。
6. 首版只提示，不提交失败状态。

### REQ-020 CI 确定性检查

只有以下确定性结果可以影响 CI：

```text
明确禁止路径被修改
必须测试未报告或失败
必要审批缺失
关键 REQUIRED 知识处于 SUSPECT/STALE
明确要求同步知识但未处理
```

模型建议、向量候选和单独的图谱推断不得阻断。

实现边界：CI 绑定已经完成的不可变 Task Review 和完整 Head Commit；测试/审批使用显式结构化回报，禁止路径使用仓库相对 Glob，知识同步由服务端核对更高修订是否处于 `PUBLISHED + APPROVED + CURRENT`。退出码 `1` 只表达确定性规则失败，平台/网络/鉴权错误使用 `2`。

### REQ-021 跨仓库工程知识

新增上层 `engineering_projects`，一个工程项目关联多个仓库。Scope 扩展 `repositoryIds/serviceNames/contractIds`。当前实现边界如下：

1. 项目至少包含两个具有当前 Snapshot 的仓库；仓库与项目内服务名必须唯一，服务身份只允许人工显式登记。
2. 契约必须登记两个不同成员仓库、双方当前内容索引证据路径及稳定内容指纹；Task Review 使用前重新验证双方指纹。
3. 项目写操作要求对全部成员仓库具有 MANAGE；读取完整项目及跨仓知识要求创建者能够读取涉及仓库。
4. 跨仓 Scope 必须显式命中仓库、服务或当前契约；路径、符号、模块仍作为附加 AND 条件。同项目、向量、模型或自由文本不产生关系。
5. 项目或拓扑仍被知识 Scope 引用时禁止破坏性变更；活动工程项目中的仓库禁止直接删除。
6. 当前没有独立、持久化的“仓库已达到发布质量门槛”事实，因此不以一次 Task Review 冒充质量通过。当前 Snapshot、权限和双端代码证据已在运行时强校验；质量指标由 REQ-010 评测和真实多仓验收核对。

### REQ-022 开发结果与反馈

新增 `task_review_outcomes` 与 `task_review_feedback`，形成审查后的追加式闭环：

1. 每条结果绑定已完成 Task Review、仓库、具名报告人和 clientRequestId，记录最终 Commit、结果摘要、实际测试/审批和报告时间；原审查与历史回报均不可覆盖。
2. 最终 Commit 与审查 Head 相同才标记精确绑定；其他 Commit 只标记为报告人声明，不伪造 Git 祖先验证。
3. 误报必须指向原审查已有对象；漏报允许记录缺失对象。知识更新判断必须指向原审查中的知识，取值为需要、不需要或未知。
4. 查询结果按原审查返回必须测试/审批的已回报和缺失覆盖；未回报不自动解释为成功或失败。
5. 反馈只用于评测和规则改进，不调用知识写服务、不改变发布/审核/来源状态，也不反向修改 CI 结论。
6. 页面与 MCP `report_task_outcome` 使用同一 HTTP API、READ 权限、Session/CSRF 和幂等校验。

## 11. 数据库迁移规划

| 迁移 | 内容 | 阶段 |
| --- | --- | --- |
| `V9__engineering_knowledge.sql` | 工程知识字段、SUSPECT、修订历史、删除粗粒度失效触发器 | REQ-001、REQ-008 |
| `V10__task_reviews.sql` | Task Review 持久化与索引 | REQ-006 |
| `V11__knowledge_drift_audit.sql` | 知识漂移审计与状态回写 | REQ-008 |
| `V12__ci_knowledge_obligations.sql` | 禁止路径与知识同步义务的卡片/修订迁移 | REQ-020 |
| `V13__engineering_projects.sql` | 工程项目、成员服务身份、双端契约证据与跨仓知识 Scope | REQ-021 |
| `V14__task_review_outcomes.sql` | 追加式任务结果、测试/审批回报和具名人工反馈 | REQ-022 |

迁移要求：

1. 全新数据库可从 V1 一次迁移到最新版本。
2. 已有 V8 数据库升级不丢失知识修订和引用。
3. 旧知识统一迁移为 `REFERENCE + INFO + REFERENCE`。
4. Flyway 迁移必须有 PostgreSQL 集成测试。

## 12. API 总表

| API | 方法 | 权限 | 阶段 |
| --- | --- | --- | --- |
| `/repositories/{id}/task-reviews` | POST/GET | READ | P0 |
| `/repositories/{id}/task-reviews/{reviewId}` | GET | READ | P0 |
| `/repositories/{id}/knowledge/{cardId}/source-review` | POST | MAINTAIN | P0 |
| `/repositories/{id}/task-context` | POST | READ | P1 |
| `/repositories/{id}/pull-request-reviews` | POST | MAINTAIN | P2 |
| `/repositories/{id}/task-reviews/{reviewId}/ci-check` | POST | READ | P2 |
| `/engineering-projects` | GET/POST | 全成员 READ / 全成员 MANAGE | P2 |
| `/engineering-projects/{id}` | GET/PUT/DELETE | 全成员 READ / 全成员 MANAGE | P2 |
| `/repositories/{id}/task-reviews/{reviewId}/outcomes` | POST/GET | READ | P2 |
| `/repositories/{id}/task-reviews/{reviewId}/outcomes/{outcomeId}` | GET | READ | P2 |
| `/provider-webhooks/github` | POST | GitHub HMAC-SHA256 | P2 |
| `/provider-webhooks/gitlab` | POST | GitLab Secret Token | P2 |

所有创建类 API 必须支持幂等 ID；所有读取结果必须重新检查仓库权限。

## 13. 前端信息架构

### 13.1 P0

现有菜单暂时保持，只改造“变更分析”为“变更审查”，避免同时进行大范围导航重构。

### 13.2 P1 完成后

主导航收敛为：

```text
项目总览
代码与证据
问项目
变更审查
```

知识治理、项目管理和系统管理按权限出现。

### 13.3 统一界面原则

1. 页面名称使用用户任务，不使用底层技术名。
2. 一个页面只有一个主任务。
3. 事实、知识、推断、建议和未知使用固定标签。
4. 所有失败状态说明原因和解决动作。
5. 不使用装饰性仪表盘、大面积渐变和无法解释的风险分数。
6. 代码证据必须能定位文件和行号。
7. 知识证据必须能定位卡片和修订号。

## 14. 开发顺序

必须按以下顺序推进：

```text
阶段 1：REQ-001 + REQ-002
工程知识模型、迁移、编辑器和适用范围

阶段 2：REQ-003
真实 Git Diff

阶段 3：REQ-004
改动符号识别

阶段 4：REQ-005 + REQ-009
确定性匹配和统一证据

阶段 5：REQ-006
Task Review 持久化与 API

阶段 6：REQ-007
变更审查页面

阶段 7：REQ-008
精准知识失效

阶段 8：REQ-010
真实评测与发布门槛

价值门槛 A：使用至少 10 个真实任务验收 P0

阶段 9：REQ-011～REQ-018
Agent、MCP、图谱、总览、代码证据和导航

价值门槛 B：Coding Agent 在真实任务中使用上下文和审查 API

阶段 10：REQ-019～REQ-022
PR、CI、跨仓库和反馈
```

某一阶段未通过验收，不进入依赖它的下一阶段。

## 15. 测试设计

### 15.1 后端单元测试

```text
EngineeringKnowledgePolicyTest
RepositoryGlobMatcherTest
RepositoryChangeServiceTest
GitPatchParserTest
CodeSymbolExtractorTest
ChangedSymbolResolverTest
TaskContextMatcherTest
TaskReviewServiceTest
KnowledgeDriftServiceTest
```

### 15.2 PostgreSQL 集成测试

1. V8 到 V9/V10 数据迁移。
2. 新知识字段和修订历史。
3. CURRENT/SUSPECT/STALE 查询语义。
4. Task Review 幂等和历史查询。
5. 仓库删除后的关联数据处理。
6. 权限和并发版本冲突。

### 15.3 Git 集成测试

使用临时真实 Git 仓库覆盖：

1. 新增、修改和删除。
2. 重命名和复制。
3. 二进制文件。
4. 未跟踪文件。
5. 脏工作区并发变化。
6. 非法 Ref、超时和超限。
7. Base 版本删除符号读取。

### 15.4 前端测试

1. 三种变更来源表单。
2. 真实性标签。
3. 代码与知识跳转。
4. 历史结果只读。
5. 空状态、失败和降级。
6. 仓库切换清理状态。
7. 移动布局和键盘焦点。

## 16. 统一完成定义

单项需求只有同时满足以下条件才能标记完成：

1. 数据库迁移在全新数据库和 V8 升级路径通过。
2. 产品行为、API 字段、错误码和权限实现完成。
3. 正常、失败、降级和版本切换路径有自动化测试。
4. 每条确定性结论有真实性类型和证据。
5. 不出现仓库中不存在的文件和符号。
6. 页面没有扩大后端结论含义。
7. 后端单元测试和 PostgreSQL 集成测试通过。
8. 前端测试、类型检查、Lint 和生产构建通过。
9. 相关文档和需求状态同步更新。
10. 不覆盖或破坏与本需求无关的用户代码和页面改动。

P0 整体完成还必须满足：

```text
diffFileExactness = 1.0
fabricatedPathCount = 0
provenanceCompleteness = 1.0
exact-reference driftPrecision = 1.0
knowledgePrecision >= 0.90
```

## 17. 冻结和暂缓清单

P0 完成前冻结：

- [ ] 项目总览视觉反复重构。
- [ ] 新增独立主菜单。
- [ ] 新建全屏图谱页面。
- [ ] 批量自动生成正式知识。
- [ ] README 转知识作为核心能力。
- [ ] 用节点数量计算风险。
- [ ] 用检索分数展示影响概率。
- [ ] 模型建议、不完整数据或单独图谱推断驱动的 PR/CI 阻断。
- [ ] 跨仓库复杂分析。
- [ ] 大模型直接判断规则违反。
- [ ] 与任务审查闭环无关的新管理功能。

## 18. 开发记录

| 日期 | 需求 | 状态 | 说明 |
| --- | --- | --- | --- |
| 2026-08-30 | 需求基线 | 已完成 | 固化 REQ-001～REQ-022 产品、技术、测试和验收设计 |
| 2026-08-30 | REQ-001 | 开发完成，待集成验收 | 工程知识模型、V9 迁移、统一校验策略、修订历史、知识编辑/详情/列表 UI 已实现；本机无 PostgreSQL 运行环境，尚未执行实库 Flyway 升级 |
| 2026-08-30 | REQ-002 | 已完成 | 实现安全跨平台 Glob、代码引用/路径/符号/模块确定性 Scope 匹配，以及带版本事实的命中和未知原因；待 REQ-005 接入审查主链路 |
| 2026-08-30 | REQ-003 | 已完成 | 实现工作区、单提交和提交范围的真实 Git Diff，输出类型、路径、二进制、行数和 Hunk；现有增量索引已委托新引擎 |
| 2026-08-30 | REQ-007 | 开发完成，待登录态视觉验收 | `/change-impact` 已重构为真实变更审查工作台，保留并明确降级需求影响预估；前端测试和生产构建通过，当前本地后端 8080 未运行，浏览器验收待补 |
| 2026-08-30 | REQ-008 | 开发完成，待 PostgreSQL 集成验收 | 移除 Commit 变化全仓库过期触发器，新增索引后逐卡片 Diff/哈希/Scope 漂移检查、审计、人工复核 API 和知识详情证据入口 |

后续每完成一项需求，必须在本节追加：

1. 实际实现内容。
2. 与原设计的差异及原因。
3. 数据库迁移。
4. 测试和构建结果。
5. 遗留限制。

### 18.1 REQ-001 实际实现记录

1. 实际实现：新增 11 种工程知识类型、3 级严重程度、3 级执行策略、负责人、Scope、Obligations、最近验证快照和验证说明；旧知识默认迁移为 `REFERENCE + INFO + REFERENCE`，不产生强制义务。
2. 规则实现：`EngineeringKnowledgePolicy` 统一校验枚举、路径安全、集合数量和单项长度；`REQUIRED` 发布前必须具备负责人、非空 Scope、人工审核和 `CURRENT` 来源状态。
3. 历史与真实性：所有新增字段进入知识修订触发器；历史恢复后强制回到 `DRAFT + UNREVIEWED + UNVERIFIED`，并清空最近验证快照和说明；`SUSPECT/STALE` 不进入正式检索和向量统计。
4. 数据库迁移：新增 `V9__engineering_knowledge.sql`；静态迁移契约和 MyBatis XML 解析通过，实库迁移因本机无 PostgreSQL/Docker 环境待 CI 或可用数据库验证。
5. 前端实现：知识编辑器采用“内容—范围—要求—负责人—代码证据”结构；高级字段默认折叠，普通参考知识保持快速创建；列表、详情和历史展示结构化字段及来源状态。
6. 验证结果：后端 `122` 项测试通过、`1` 项环境测试跳过；前端 `4` 项测试通过；Vue 类型检查和生产构建通过；`git diff --check` 通过。
7. 遗留限制：负责人和审批人当前以账号 UUID 编辑，后续应在权限收敛需求中替换为仓库成员选择器；PostgreSQL 全新库与 V8 升级验证通过前，不将 REQ-001 标记为完成。

### 18.2 REQ-002 实际实现记录

1. 实际实现：新增仓库相对路径 Glob 匹配器和知识 Scope 匹配器，支持代码引用、路径、符号、模块四种确定性命中；代码引用同时支持绑定路径与旧内容哈希变化。
2. 路径规则：统一将 `\\` 转为 `/`，支持 `*`、`**`、`?`，保持大小写敏感；保存和匹配阶段共同拒绝绝对路径、Windows 盘符、父目录穿越、控制字符及超过 300 字符的规则。
3. 真实性与证据：每条命中包含规则、实际目标、仓库、快照、提交、文件、符号/模块/知识块等可用事实；证据来源仅允许 `GIT_FACT`、`CODE_FACT`、`GRAPH_INFERENCE`，不存在向量检索生成正式命中的通道。
4. 未知状态：模块图谱不可用、历史脏规则、非法变更路径或非法代码引用均生成稳定原因代码，不猜测命中；非法变更路径不能仅凭符号、模块或哈希生成结果。
5. 数据库迁移：无。本需求复用 REQ-001 的 `scope_payload` 和代码引用数据，不新增持久化结构。
6. 验证结果：REQ-002 相关 `15` 项测试通过；后端全量 `132` 项测试通过、`1` 项环境测试跳过；前端 `4` 项测试和生产构建通过；`git diff --check` 通过。
7. 设计差异与限制：匹配器作为独立领域能力完成，尚未在生产审查请求中调用；这是因为真实 Diff、符号事实与审查编排分别由 REQ-003、REQ-004、REQ-005 提供，提前接入会退化为使用描述或检索结果伪造事实。

### 18.3 REQ-003 实际实现记录

1. 实际实现：新增 `GitChangeRequest`、`RepositoryChange`、`RepositoryChangeService` 和 `ProcessGitClient`，支持 `WORKTREE`、`SINGLE_COMMIT`、`COMMIT_RANGE`；输出解析后的新增、修改、删除、重命名、复制、二进制、增删行和零上下文 Hunk，不向业务层暴露或记录完整 Patch。
2. Git 真实性：所有提交参数先通过 `rev-parse --verify --end-of-options` 解析为对象 ID；单提交按第一父提交计算，根提交使用当前对象格式的空树；工作区同时覆盖暂存、未暂存和未跟踪文件。
3. 一致性与安全：Git 命令全部通过参数数组执行，不经过 Shell；清理继承的 `GIT_*` 环境变量，禁用外部 Diff、Textconv、Hooks、交互和可选锁；Ref 最大 200 字符并拒绝控制字符及 `-` 前缀，路径统一校验为仓库相对路径，命令超时为 30 秒。
4. 限制处理：单次最多返回 5,000 个文件，Patch 最多读取 5 MiB；超过限制时返回 `partial=true` 和稳定限制代码；非 Git 目录、子目录、非法 Ref、非法路径和命令异常均返回稳定错误代码，不静默降级。
5. 工作区 Digest：分析前后分别对 Git 状态、Raw Diff、受控文件和未跟踪文件内容计算 SHA-256；Digest 或 HEAD 变化时抛出 `WORKTREE_CHANGED_DURING_ANALYSIS`，不发布混合结果。
6. 兼容性：现有 `GitDiffService` 改为委托真实变更引擎；若结果为部分数据，增量索引拒绝使用并回退全量索引，原有删除、复制和重命名路径语义保持不变。
7. 数据库与界面：无数据库迁移、无新增页面；REQ-003 是 REQ-004 符号识别和 REQ-006 审查 API 的事实输入层，当前不单独制造用户入口。
8. 验证结果：REQ-003 及增量索引相关 `14` 项测试通过，其中真实临时 Git 仓库覆盖根提交、提交范围、工作区、复制、重命名、二进制和未跟踪文件；后端全量 `139` 项测试通过、`1` 项环境测试跳过；前端 `4` 项测试和生产构建通过；本需求 Java 文件 Spotless 检查通过。
9. 遗留限制：合并提交当前明确使用第一父提交并返回限制说明；未解决的合并冲突标记为部分结果，后续审查不会把它当作完整事实。

### 18.4 REQ-004 实际实现记录

1. 实际实现：新增共享的 `CodeSymbolExtractor` 和 `ChangedSymbolResolver`，将每个真实 Git Hunk 按 `CODEGRAPH → SOURCE_DECLARATION → CHUNK_SYMBOL → FILE_LEVEL` 映射为类、接口、方法、函数、配置项、文档段落或文件对象；结果包含符号 ID、名称、类型、路径、声明范围、旧/新 Hunk 行号、变更类型、识别方式和版本化来源。
2. 声明真实性：索引与变更审查共用同一轻量提取器，覆盖 Java/C#、Kotlin、JavaScript/TypeScript、Python、Go、Rust、PHP、Swift、Ruby、Shell、SQL、GraphQL、Markdown 和常见配置；只接受源码中实际出现且与 Hunk 相交的声明，不解析调用关系，也不根据任务描述或模型生成符号。
3. 旧版本与一致性：提交内容使用受限 `git show <commit>:<path>` 读取，删除文件和纯重命名可恢复 Base 符号及旧路径来源；工作区解析前后复核 REQ-003 的内容摘要，变化时拒绝发布混合结果。
4. CodeGraph 与 Chunk：增加版本严格匹配的 CodeGraph 符号查询扩展口，只有仓库、快照、提交、路径和行范围全部一致的节点才可采用；当前 CodeGraph 产物没有结构化逐符号查询能力，因此生产环境不会伪装图谱命中，而会进入源码声明；Chunk 回退新增当前快照、指定路径查询，并再次校验提交 ID。
5. 限制与安全：单文件上限为 1 MiB，单文件声明上限为 500；二进制、生成代码、非 UTF-8、符号链接、越界真实路径、读取失败和不支持的声明均以稳定原因降级到文件级，每个 Hunk 至少产生一个对象。
6. 数据库迁移与界面：无数据库迁移、无新增页面；新增的 Chunk 路径查询沿用仓库当前快照边界，供 REQ-005 和 REQ-006 的审查编排使用。
7. 验证结果：REQ-004、共享索引和 SQL 契约相关 `22` 项测试通过；后端全量 `148` 项测试通过、`1` 项环境测试跳过；真实临时 Git 仓库覆盖提交范围、删除、纯重命名、CodeGraph 版本拒绝、Chunk 回退和超大文件降级；本需求 Java 文件 Spotless 检查通过。
8. 设计差异与限制：未引入完整 AST；轻量规则不支持的语言或复杂多行声明会明确降级，不能作为不存在符号的证据。CodeGraph 逐符号提供器将在其本地 CLI/产物具备稳定、可验证的结构化接口后接入。

### 18.5 REQ-005 实际实现记录

1. 实际实现：新增 `TaskContextMatcher`、`KnowledgeMatch` 和 `TaskReviewFinding`，将 REQ-003 的文件变化、REQ-004 的改动符号与 REQ-002 的 Scope 匹配器组合为 `applicableKnowledge`、`referenceCandidates`、`requiredTests`、`requiredApprovals`、`staleKnowledge` 和 `unknowns` 六类稳定输出。
2. 正式知识边界：只接受同仓库、`PUBLISHED + APPROVED` 的知识候选；`CURRENT` 确定性命中进入正式适用知识，`SUSPECT/STALE` 确定性命中只进入陈旧知识，`UNVERIFIED` 命中产生未知项，不生成规则结论。
3. 确定性证据：正式命中只复用代码引用、Git 路径、源码符号和版本一致的模块事实；同一知识跨多个文件或规则命中时只输出一次并保留全部原因。文件级符号不会被当作真实方法名参与符号匹配。
4. 检索隔离：关键词或向量召回只能通过 `RetrievalReference` 进入参考候选，结果结构不包含概率字段；已经确定性命中的知识不重复出现在参考候选，检索结果不能产生测试或审批义务。
5. 义务与未知项：只有 `REQUIRED + CURRENT` 的确定性命中产生义务；相同测试命令和审批账号自动去重并保留所有来源知识与证据；Git 限制、符号识别降级、模块图谱不可用、快照/提交缺失和知识版本未验证均转换为带稳定原因的未知项，不判断违规。
6. 数据库迁移与界面：无数据库迁移、无新增页面；本需求实现无状态规则编排，知识持久化加载和不可变审查结果保存由 REQ-006 统一接入，避免提前形成第二套审查入口。
7. 验证结果：REQ-005 与 Scope 匹配相关 `11` 项测试通过；后端全量 `152` 项测试通过、`1` 项环境测试跳过；覆盖知识去重、多原因聚合、义务去重、陈旧隔离、纯检索候选、草稿/未审批排除和未知原因传播；本需求 Java 文件 Spotless 检查通过。
8. 设计差异与限制：P0 不执行模型判断，也不接收模型给出的规则满足状态；原审查中的测试与审批保持 `REQUIRED_NOT_REPORTED/REQUIRED`，REQ-022 通过独立不可变结果记录补充实际状态，不反向覆盖审查。

### 18.6 REQ-006 实际实现记录

1. 实际实现：新增 `TaskReviewRequest`、`TaskReviewResult`、`TaskReviewService`、`TaskReviewController` 和 MyBatis 持久化，提供 `POST/GET /api/repositories/{id}/task-reviews` 及详情接口；一次请求同步编排真实 Git Diff、符号识别、架构模块、工程知识、测试、审批、陈旧知识和未知项，API 不依赖前端内存状态。
2. 幂等与不可变：调用方必须提供 `clientRequestId`，数据库使用 `(created_by,repo_id,client_request_id)` 唯一索引和 `ON CONFLICT DO NOTHING`；同键同请求直接恢复已有结果，同键不同请求返回 `IDEMPOTENCY_KEY_CONFLICT`；`COMPLETED/FAILED` 终态由数据库触发器禁止覆盖。
3. 版本一致性：创建前要求当前发布快照，审查 Git Head 必须与快照提交一致；完成更新同时校验数据库当前快照 ID，快照切换时只保存 `SNAPSHOT_CHANGED_DURING_REVIEW` 失败状态，不写入结果 Payload；CodeGraph 返回不一致节点时排除并产生 `CODEGRAPH_VERSION_MISMATCH` 未知项。
4. 知识与检索：生产编排加载同仓库知识卡及代码引用，Scope 决策继续由 REQ-005 过滤 `PUBLISHED + APPROVED`；任务描述只执行数据库知识关键词召回并丢弃分数，作为参考候选，不调用 LLM。`modelConfigId` 仅随请求和结果保存，为后续模型摘要预留。
5. 权限与历史：创建、列表、详情均重新校验仓库 `READ` 权限；列表返回计数摘要，详情从不可变 JSON 完整恢复；失败记录持久化稳定错误码和截断后的安全信息，不记录完整 Patch 或源码。
6. 数据库迁移：新增 `V10__task_reviews.sql`，包括请求字段、解析后的 Commit、Snapshot、Worktree Digest、状态、完整 JSON、错误和时间字段，以及幂等唯一索引、历史索引、终态约束和不可变触发器；新增 `TaskReviewMapper.xml` 并通过 MyBatis 解析契约。
7. 验证结果：REQ-006 编排、API、快照、CodeGraph 与 SQL 契约相关 `25` 项测试通过；后端全量 `159` 项测试通过、`1` 项环境测试跳过；覆盖幂等重放、幂等冲突、JSON 恢复、READ 权限和快照切换拒绝发布；本需求 Java 文件 Spotless 检查通过。
8. 设计差异与限制：REQ-009 已完成统一来源模型并进入不可变审查结果 Payload；本机没有 PostgreSQL/Docker，V10 仍需在 CI 或可用 PostgreSQL 上完成迁移、唯一索引竞争和不可变触发器集成验收后再标记最终完成。

### 18.7 REQ-007 实际实现记录

1. 实际实现：保留 `/change-impact` 路由并将菜单、标签页和页面名称统一为“变更审查”；默认模式调用 REQ-006 的创建、列表和详情 API，按“真实改动—适用知识—测试与审批—知识失效—未知项”的证据脊柱展示完整审查结果。
2. 证据交互：文件、符号、命中规则、知识修订、负责人和未知原因集中显示在证据详情中；代码证据携带路径和声明行号跳转源码，知识证据携带卡片 ID 打开详情。历史审查属于旧 Snapshot 时拒绝跳转当前源码，避免用当前文件冒充历史证据。
3. 状态与版本：页面覆盖未选择仓库、无快照、加载、空历史、API 失败、审查失败、部分数据、未知项和完整结果；WORKTREE 明确提示分析期间不要修改文件，并在结果中展示摘要；仓库切换会清空表单、临时结果、错误、选择证据和历史上下文。
4. 历史与幂等：每次提交由浏览器生成新的 `clientRequestId`，历史列表只读取不可变结果；选择历史记录后以“历史审查 · 只读”展示，不复用为新请求或覆盖原记录。
5. 预估模式：原变更影响分析保留为次级“需求影响预估”，显式标注“未读取真实 Git Diff”；候选分数改为相对排序，不再展示百分比或暗示影响概率。
6. 视觉实现：依照前端设计规范采用审查卷宗与单一证据脊柱，固定使用 Git 蓝、知识绿、警告橙和未知灰，路径/Commit/符号使用等宽字体；没有渐变、通用指标卡或装饰性风险分数。窄屏为单栏，证据详情使用底部抽屉，所有可操作元素提供可见键盘焦点。
7. 测试结果：新增三种来源表单和证据脊柱测试，前端共 `7` 项测试通过；`vue-tsc --noEmit` 与 Vite 生产构建通过。浏览器已成功连接本地 Vite 页面，但本地 8080 后端未运行，鉴权恢复被代理为 500，因此登录态桌面/窄屏截图验收尚未执行。
8. 数据库与限制：无新增迁移，复用 V10 的 Task Review；页面不修改知识状态，不把参考候选扩大为义务。REQ-006 的 PostgreSQL 集成和可用登录态浏览器验收通过前，本需求保持“开发完成，待验收”。

### 18.8 REQ-008 实际实现记录

1. 实际实现：新增 `KnowledgeDriftService`，在当前快照代码片段写入、向量准备完成后执行来源漂移检查；只扫描状态为 `CURRENT`、存在验证提交且验证提交不同于当前提交的知识，并按验证 Commit 分组复用真实 `COMMIT_RANGE` Diff 和改动符号解析。
2. 精准规则：绑定代码引用仅在对应路径真实变化且当前快照不存在相同内容哈希时命中；路径 Scope 使用安全仓库 Glob 与真实旧/新路径匹配；符号 Scope 只接受非文件级降级的真实符号名称或 ID 精确命中。Commit 改变但三类规则均未命中的知识保持 `CURRENT`，仅刷新检查时间。
3. 降级边界：Git Diff、符号识别或知识结构数据无法形成完整事实时不猜测具体卡片受影响，索引完成信息标记 `drift-degraded` 供重试；部分 Diff 只使用已返回事实，同时报告降级，不把未观察到的路径扩大为命中。
4. 数据库迁移：新增 `V11__knowledge_drift_audit.sql`，明确删除 V7 遗留的 `trg_repository_knowledge_stale` 和 `mark_repository_knowledge_stale()`；新增 `knowledge_drift_events`，保存知识修订、旧/新 Snapshot 与 Commit、前后状态、触发类型、结构化原因、说明、操作者和时间，并对自动检查建立幂等唯一索引。
5. 人工复核：新增 `GET .../source-drift` 和 `POST .../source-review`；`CONFIRM_CURRENT` 与 `MARK_STALE` 均要求 `MAINTAIN` 权限、正整数 `expectedRevision` 和非空说明。确认当前会原子更新当前 Commit、Snapshot、状态和检查时间，修订冲突返回稳定 `KNOWLEDGE_REVISION_CONFLICT`，不会覆盖新修订。
6. 审查隔离：自动命中先把知识标为 `SUSPECT` 并写审计；REQ-005/REQ-006 已将 `SUSPECT/STALE` 隔离到陈旧知识，向量和关键词正式检索也排除这两种状态，因此不会产生强制测试或审批义务。
7. 前端闭环：知识详情新增来源漂移区，展示命中类型、规则或旧哈希、文件、行号、变更类型、旧/新 Commit 和复核说明；维护者可输入说明后确认当前或标记失效。点击 Drift 证据会把精确 Base/Head 和知识标题带到“变更审查”的 Commit Range 表单，不自动制造审查结果。
8. 验证结果：后端全量 `173` 项测试通过、`1` 项环境测试跳过，覆盖非关联变更、路径命中、引用哈希变化/未变化、乐观锁、权限、索引顺序、V11 和 MyBatis 契约；前端 `9` 项测试、类型检查和生产构建通过；`git diff --check` 通过。
9. 遗留限制：本机无 PostgreSQL/Docker，V11 的触发器删除、JSONB 审计、部分唯一索引和并发复核仍需在 CI 或可用 PostgreSQL 上完成实库验收；模块 Scope 漂移按需求暂不自动标记，模块证据仍由任务审查使用。

### 18.9 REQ-009 实际实现记录

1. 实际实现：新增 `TruthSource` 和 `Provenance`，统一区分 `GIT_FACT`、`CODE_FACT`、`VERIFIED_KNOWLEDGE`、`GRAPH_INFERENCE`、`RETRIEVAL_CANDIDATE`、`MODEL_SUGGESTION` 与 `UNKNOWN`；来源 ID 由完整事实字段稳定生成，不使用排序分数或模型措辞表达真实性。
2. 强制约束：Git/代码事实必须带 Snapshot、Commit 或 Worktree Digest；正式知识必须带卡片 ID、正修订号和人工审核状态；图谱推断必须带版本、图谱产物 ID 和关系路径；检索候选必须带通道；模型建议工厂拒绝没有 Finding ID 的输入。
3. 审查结果：正式知识命中同时携带已验证知识来源和实际 Git/代码/图谱来源；测试、审批和未知 Finding 均强制包含非空 `sources`。该字段随 REQ-006 的不可变结果 JSON 持久化，前端 `Provenance` 类型与后端字段逐项对应。
4. 检索隔离：参考候选以 `RETRIEVAL_CANDIDATE` 展示实际关键词或向量通道，只标注“用于排序候选”，不显示概率；确定性知识仍由 Scope 规则产生，前端没有把检索候选扩大为适用知识或义务。
5. 变更分析修复：代码候选和测试证据在进入 Snapshot/内容哈希校验前先校验来源类型，`KNOWLEDGE` 与 `knowledge://` 不再作为文件代码处理，也不会累计 `MIXED_SNAPSHOT_EVIDENCE_EXCLUDED` 或 `EVIDENCE_HASH_MISSING` 假告警；真实旧 Snapshot 代码仍被严格排除。
6. 前端实现：新增真实性徽标、证据链接和来源摘要组件，审查详情可直接区分事实、推断、检索与未知边界，并展示 Snapshot、知识修订、检索通道或图谱关系路径；颜色只辅助，所有类型均有文字标签。
7. 验证结果：后端全量 `178` 项测试通过、`1` 项环境测试跳过，覆盖来源类型不变量、每条 Finding 的来源、模型 Finding 引用约束以及知识候选不触发代码快照告警；前端 `10` 项测试、类型检查和生产构建通过。
8. 数据库与限制：本需求不新增迁移，统一来源随现有 JSON Payload 持久化。旧问答 Citation 和符号解析内部结构保持兼容，后续接入模型总结时必须通过 `MODEL_SUGGESTION.findingId` 引用既有结论，不能创造文件或符号事实。

### 18.10 REQ-010 实际实现记录

1. 实际实现：将现有检索、问答与影响预估评测升级为数据集版本 `2.0.0`，新增 `task-review.jsonl` 的 10 个仓库内开发任务、`knowledge-drift.jsonl` 的 8 个正反例，以及独立的任务审查结果归档目录；旧的 50 个检索、30 个问答和 20 个影响预估样本继续保留。
2. 指标与门槛：评测程序新增 `diffFileExactness`、符号精确率/召回率、知识精确率/召回率、漂移精确率/召回率、精确引用漂移精确率、来源完整率、编造路径数和跨任务 P95 延迟；首版门槛按需求设置为文件精确匹配 `1.0`、编造路径 `0`、来源完整率 `1.0`、精确引用漂移精确率 `1.0`、知识精确率不低于 `0.90`。
3. 完整性门禁：结果必须与全部五类数据集 ID 一一对应，缺失、重复或未知 ID 均拒绝评分；任务审查必须返回文件、符号、知识、测试、陈旧知识、Finding 来源和逐路径版本存在性检查，漂移命中必须携带完整来源。
4. 误报与漏报：报告对符号、知识、漂移和精确引用漂移分别输出 TP、FP、FN；文件范围逐任务输出 `falsePositiveFiles` 和 `missingFiles`，因此不会用单个召回率掩盖误报。
5. 真实性校验：来源完整性复用 REQ-009 的类型规则；Git/代码必须有版本，正式知识必须有卡片、修订和审核状态，图谱必须有产物与关系路径，检索必须有通道，模型建议引用的 Finding ID 必须真实存在。所有输出路径都必须在结果中显式记录对应版本存在性，缺项直接拒绝评分。
6. 人工评审边界：新增样本目前明确标记为 `PENDING_HUMAN_REVIEW`，没有伪造评审人和时间；结构校验返回 `valid=true, scoreable=false`，发布评分在 18 条样本由具名人工记录合法时间前必定拒绝执行。完成复核后才可将对应状态改为 `REVIEWED`。
7. CI 与测试：现有 Linux CI 继续执行数据集结构校验和评测程序测试；新增四个独立 Node 测试覆盖待人工数据拒绝评分、完整满分结果、误报/漏报失败与缺失结果失败，全部通过。发布标签仍要求 `evaluation/results/release.json`，且现在受版本 2 完整性与人工复核门禁约束。
8. 当前限制：10 个任务与 8 个漂移场景已绑定当前仓库真实路径并具备初始期望，但在人工领域评审签字前不能称为最终金标准，也不能声称达到质量门槛；这是有意保留的真实性阻断，不用单元测试生成的满分替代真实运行证据。

### 18.11 REQ-011 实际实现记录

1. 实际实现：新增 `POST /api/repositories/{id}/task-context` 和 `TaskContextService`，返回结构化 `entries`、必须测试、必须审批、未知项、Markdown 与预算使用情况；响应固定绑定仓库当前 Commit 和 Snapshot，不依赖前端临时状态。
2. 确定性知识边界：请求可携带一个已完成的 `taskReviewId`。只有审查任务文本一致、审查 Snapshot 等于当前 Snapshot，且知识当前修订仍与不可变审查结果一致时，知识正文才作为 `VERIFIED_KNOWLEDGE` 返回；旧 Snapshot、失败审查、任务不一致和知识修订变化均返回稳定错误或未知项，绝不混合版本。
3. 无审查降级：未提供 `taskReviewId` 时明确返回 `TASK_REVIEW_REQUIRED_FOR_DETERMINISTIC_KNOWLEDGE` 未知项，只输出当前版本代码事实和带通道的 `RETRIEVAL_CANDIDATE`；任务关键词不会直接产生适用知识、测试或审批义务。
4. 上下文顺序：候选按 `CRITICAL REQUIRED → WARNING REQUIRED → 其他 REQUIRED → UNKNOWN → VERIFIED_KNOWLEDGE → CODE_FACT → RETRIEVAL_CANDIDATE` 稳定排序。正式知识保留 REQ-009 的知识及 Git/代码/图谱来源，代码片段带 Snapshot、Commit、路径、行号和内容哈希，检索候选不携带义务。
5. 预算策略：支持 5～40 条、4,000～60,000 字符和 500～15,000 Token 估算预算；Token 预算按保守的 4 字符估算收紧字符上限，知识、代码和检索正文分别设置单项上限，按优先级整体选择，不输出破损 Markdown 片段。响应记录实际字符、估算 Token、省略数和是否截断。
6. 权限与兼容：新接口和旧 `/context-pack` 均在控制器强制仓库 `READ` 权限；旧 `ProjectContextPackService` 已变成新服务的兼容门面，不再维护 README/规则文件关键词抓取的第二套逻辑。前端旧总览仍可使用兼容响应，同时新增完整 `taskContext.ts` 类型和请求函数。
7. Agent 输出：正式知识条目内含测试与审批要求，响应再聚合为去重列表；审查未知、陈旧知识和审查后修订变化保留稳定代码与来源。Markdown 首部明确“来源类型不是概率，检索候选不能产生义务”。
8. 验证结果：后端全量 `182` 项测试通过、`1` 项环境测试跳过，覆盖正式知识优先、义务聚合、未知项保留、无审查降级、检索隔离、历史 Snapshot 拒绝、READ 权限和旧接口委托；前端 `10` 项测试、类型检查和生产构建通过，质量数据集与门禁测试继续通过。

### 18.12 REQ-012 实际实现记录

1. 实际实现：新增独立 `mcp-server` Node 模块，使用官方 TypeScript SDK v2 的 `McpServer.registerTool` 与 stdio 传输注册 `review_change`、`get_task_context`、`get_rules_for_symbol`、`get_required_tests`、`get_stale_knowledge`、`get_evidence` 和 `report_task_outcome` 七个工具；MCP 只做参数校验、HTTP 调用和精简投影，不复制 Diff、知识匹配、来源判断或权限业务。
2. 权限边界：本地 MCP 进程必须从环境变量读取后端地址、Analyzer Session 和 CSRF Token；所有工具继续调用原 HTTP Controller，因此仓库 `READ/MAINTAIN` 权限、Snapshot 校验、知识审核状态和错误码与 Web 调用完全一致。凭据只进入 Cookie/Header，不写入工具参数、响应正文或日志。
3. 默认输出：审查、知识和任务上下文默认只返回稳定 ID、版本、路径、行号、义务、未知代码与来源 ID；调用方显式设置 `includeEvidence/includeContent` 才扩展完整数据，或者使用 `get_evidence` 按 UUID 获取单条来源，避免把整份源码和知识正文塞入 Agent 上下文。
4. 真实性边界：`get_rules_for_symbol` 只投影不可变审查中的 `applicableKnowledge`，`get_required_tests` 和 `get_stale_knowledge` 也只读取持久化审查结果；检索候选不能被 MCP 提升为正式规则。工具错误保留后端稳定错误码，不把 HTTP 失败包装成模型结论。
5. 结果回报：`report_task_outcome` 已接通 REQ-022 的不可变结果 API，转发最终 Commit、实际测试/审批以及结构化误报、漏报和知识更新判断；复用同一 Session/CSRF、READ 权限和幂等边界，不保存 MCP 私有副本。
6. CI 与文档：Linux CI 新增 `mcp-server` 的 `npm ci` 和 `npm test`；模块 README 给出本地启动、三项环境变量、工具边界和凭据安全要求，仓库忽略 MCP 的独立 `node_modules`。
7. 验证结果：MCP `7` 项测试通过，覆盖 Session/CSRF 转发、安全方法不发送 CSRF、稳定错误码、缺失凭据拒绝启动、注册阶段无后端调用、stdio 协商与工具清单，以及通过 stdio 实际调用 `get_task_context` 后对 HTTP 路径、请求体和权限 Header 的逐项断言。同一工具输入的业务结果直接来自同一 HTTP API。
8. 数据库与限制：本需求不新增数据库迁移。stdio 适用于本地 Agent 子进程，不实现远程 OAuth；远程部署若需要 Streamable HTTP，应另行接入正式 OAuth/网关，不得复用本地 Session 环境变量方案。

### 18.13 REQ-013 实际实现记录

1. 实际实现：新增 `CodeGraphPropagation`，对同一已发布图谱产物依次执行 `impact --json` 与 `export --no-centrality`，用 export 的真实节点和边对 impact 的 affected 记录做精确映射；传播从焦点节点沿真实反向依赖逐层遍历，返回节点、原始边、每个受影响节点的一条完整最短路径、实际深度和覆盖信息。
2. 取消伪关系：删除两个 CodeGraph 服务中“把所有 affected 直接连到输入符号”的星形拼接，也删除按节点数量生成 `LOW/MEDIUM/HIGH` 风险的逻辑；返回结构不再包含 `risk`。如果 CLI 不能导出真实 edges，接口返回 `CODEGRAPH_EXPORT_NOT_AVAILABLE`，不会退回星形关系。
3. 节点与路径：每个节点必须来自 CLI export，带 CLI 节点 ID、符号、类型、仓库相对路径、起止行、深度和焦点标记；非法路径、无位置节点和 `contains` 结构边不会进入传播图。边保留 source、target、relation 和行号，派生边 ID仅由完整边事实稳定生成；路径按焦点到受影响节点排序，同时保留边原本的依赖方向。
4. 版本与产物：结果新增 `graphArtifactId`、`snapshotId` 和 `cliVersion`。查询只接受数据库中当前 Snapshot 的 `PUBLISHED` 产物，并校验产物目录存在且位于受管 CodeGraph 根目录；缺失、版本不一致、目录丢失和 Schema 不支持均返回独立稳定错误码。
5. 覆盖与限制：响应同时返回 CLI 报告节点/边数、实际可展示节点/边数、affected 记录总数、已映射数、未映射数和 `complete`。数量不一致、同名定义、动态资源引用或记录无法映射时加入稳定限制代码；不把“没有路径”解释为“没有影响”。
6. 前端实现：调用图谱按稳定节点 ID 渲染，支持同名符号；节点直接展示文件和行号。右侧改为“真实传播路径”，展示受影响节点、路径数、实际最大深度、Snapshot、产物、CLI 版本、覆盖状态和逐层传播链，不再展示风险等级；构建按钮只提示任务已提交，不再把异步入队误报为“产物已发布”。
7. CLI 契约：依据 CodeGraph 官方 CLI 的结构化 `impact` 与 NetworkX node-link `export` 契约实现；Linux-only 假 CLI 测试同时覆盖 init/版本发布和 impact/export 组合，断言 `controller → service → focus` 原始边没有被改造成 `focus → controller` 星形边。
8. 验证结果：后端全量 `182` 项测试通过、`2` 项 Linux-only CLI 测试在当前 Windows 环境跳过；新增 4 项跨平台解析测试覆盖真实多层路径、未映射降级、旧 CLI Schema 拒绝和非法路径拒绝。前端 `10` 项测试、Vue 类型检查和生产构建通过，`git diff --check` 通过。
9. 数据库与遗留限制：无新增迁移。当前机器未安装 CodeGraph CLI，Linux 假 CLI 契约需由 CI 执行；真实大型仓库的 export 性能、同名符号 `--file` 精确消歧和动态资源边覆盖仍需用实际 CLI 产物做后续基准，未验证前不会宣称全语言路径完备。

### 18.14 REQ-014 实际实现记录

1. 输入边界：模型总结只接受状态为 `COMPLETED` 的完整 `TaskReviewResult`。服务端先完成并冻结确定性审查，再从 Git 变更、变化符号、已验证知识、要求测试、审批项、过期知识和未知项生成有限 Evidence Catalog；候选检索结果不会被提升为正式引用。
2. 严格协议：模型输出必须是单个纯 JSON 对象，顶层只允许 `summary`、`findings` 和 `unknowns`，每条 finding 只允许 `text` 和 `evidenceIds`。运行时限制条目数、文本长度和引用数，并校验每个 Evidence ID 都存在于本次服务端目录；Markdown 围栏、尾随文本、额外字段或任一未知 ID 都会丢弃整个模型总结。
3. 事实隔离：模型内容保存于独立的 `modelSummary`，不能改写文件、符号、规则、测试、审批、知识状态或任何确定性字段。合法引用由服务端反向展开为带文件、行号、知识 ID 和 `MODEL_SUGGESTION` 来源的 Evidence，前端只使用这些服务端展开字段导航，不从模型自然语言中推断定位事实。
4. 故障隔离：未选择模型时状态为 `NOT_REQUESTED`；模型缺失、超时或调用失败时状态为 `UNAVAILABLE`；非法输出为 `REJECTED`。这些状态均作为确定性审查的附加结果返回，模型失败不会让 Task Review 失败或降级为未完成。
5. 前端实现：审查表单可选总结模型，并明确说明模型不改变确定性结果；证据主线新增独立的紫色 `MODEL_SUGGESTION` 阶段，展示提供方、总结、逐条引用、未知项及被拒绝/不可用原因，避免将模型建议伪装成平台事实。
6. 验证结果：新增 5 项模型总结单元测试，覆盖有效引用、任一未知引用整体拒绝、非严格 JSON 拒绝、模型不可用隔离和未选模型不调用；Task Review 相关 9 项定向测试通过。按 REQ-014 当时的测试集合计，后端全量 `187` 项测试通过、`2` 项 Linux-only CLI 测试跳过；前端 `12` 项测试、Vue 类型检查和生产构建通过。
7. 数据与限制：本需求不新增数据库迁移，模型总结随不可变审查结果 JSON 保存。运行时验证的是结构和引用存在性，模型文字是否真正被引用证据支持仍必须由 REQ-010 的人工金标准评测证明；在完成该评测前，不宣称模型总结具备可量化的语义正确率。

### 18.15 REQ-015 实际实现记录

1. 真实聚合接口：新增 `GET /api/repositories/{id}/health-overview`，在仓库 `READ` 权限内聚合当前 Repository/Snapshot、准备状态、知识卡持久化状态和最近 5 条不可变 Task Review；接口不读取 README、不调用模型，也不从前端展示文本反推健康结论。
2. 知识口径：知识总量只统计未归档卡片；`CURRENT/SUSPECT/STALE/UNVERIFIED` 直接使用 `source_version_status`。可信知识必须同时满足 `PUBLISHED + APPROVED + CURRENT`，未审核按 `UNREVIEWED` 统计，负责人缺失只统计 `REQUIRED` 且 `owner_account_id` 为空的知识。SQL 聚合项通过 MyBatis Schema 加载和字段契约测试固定。
3. 审查可用性：当前 Repository 必须存在已发布 Snapshot 且当前 Snapshot 有内容片段，才允许主操作发起变更审查。向量缺失、CodeGraph 缺失、没有可信知识、未审核、SUSPECT、STALE 和 REQUIRED 无负责人分别返回稳定 issue code；其中 Snapshot/内容缺失是阻塞，其余明确标记为降级缺口，不把部分能力缺失误报为全面不可用。
4. 页面信息架构：首屏按“项目身份与版本 → 工程状态 → 四项核心数据 → 知识真实性 → 代码类型 → 最近审查 → 当前缺口 → 准备流程”排列。分支、Commit、Snapshot 和未发布工作区状态集中展示；`开始变更审查` 是唯一主操作，准备和刷新降为次操作。
5. 删除无效展示：项目总览不再请求快照文件或选择 README，不再挂载文档阅读器，也不展示技术栈。代码类型只展示当前 Snapshot 的 `fileCategories` 数量、分类说明和真实样本路径；CodeGraph、向量和知识数沿用已发布产物及数据库计数。
6. 视觉实现：保留原准备/画像页的档案式层级和细线分区，增加蓝、青、绿、琥珀、红和紫的状态语义；页面在桌面端使用主次双栏，在窄屏按信息依赖顺序降为单栏，没有重新引入卡片瀑布或大面积渐变装饰。
7. 验证结果：新增 2 项工程健康服务测试、1 项权限控制器测试和 1 项 SQL 契约测试。后端全量 `191` 项测试通过、`2` 项 Linux-only CLI 测试在 Windows 跳过；前端 `12` 项测试全部通过，Vue 类型检查和生产构建通过，覆盖真实数据展示、README/技术栈移除和主操作事件。
8. 数据库与限制：不新增迁移，聚合完全读取现有知识卡和任务审查表。当前环境未启动后端与登录会话，因此未做真实账号下的浏览器截图验收；服务端聚合、组件渲染和生产构建已验证，实际视觉密度仍应在项目启动后用真实大数据量仓库复核。

### 18.16 REQ-016 实际实现记录

1. 工作台合并：`/search` 从普通片段列表升级为“代码与证据”，保留当前 Snapshot 的文件树和逐行源码预览，在同一右侧上下文面板提供检索结果、CodeGraph 真实关系、直接知识绑定和不可变审查引用；关系不再占用独立全屏主页面。
2. 真实混合检索：源码搜索改用现有 `/hybrid-search`，直接展示响应中的 Snapshot、`retrievalCapability`、实际启用通道、不可用通道和降级原因。结果只显示关键词、语义/字符向量和启发式关系等通道名称，不把内部 rank score 展示成概率；旧 Snapshot 命中仍会被前端二次排除并计数说明。
3. 文件证据接口：新增 `GET /api/repositories/{id}/code-evidence-context`。知识引用只返回当前账号有权查看且直接绑定到该文件的卡片，逐项保留修订、发布、审核、来源版本、内容哈希、行号、绑定 Snapshot 和 stale 状态；明确返回 `DIRECT_KNOWLEDGE_BINDINGS_ONLY`，不会用关键词相似度冒充适用规则。
4. 审查引用：Task Review 新增按仓库相对路径查询最近审查引用的只读投影，识别变更文件、变化符号、知识证据、要求测试、要求审批和未知项证据；引用保留审查 ID、任务、Snapshot、是否当前版本和符号。最多扫描最近 100 条并在截断时返回限制，不扫描模型自然语言。
5. 关系上下文：选中带符号的搜索结果后可在文件侧栏调用 REQ-013 的真实 CodeGraph 传播接口，展示已发布产物的 CLI 版本、Snapshot、覆盖、完整路径和限制。路径节点可回到精确源码；没有图谱或符号时只显示明确原因和构建入口，不生成替代连线。
6. 导航收敛：问答和知识的“查看源码/关系”均进入 `/search` 并携带 path、line、symbol 和 `relation=1`；变更审查的源码入口继续进入同一工作台。主菜单删除独立“调用图谱”和“源码检索”，合并为“代码与证据”；`/graph` 仅作为旧链接兼容重定向，不再渲染独立页面。
7. 权限与真实性：文件上下文端点强制仓库 `READ`；只有 `MAINTAIN` 账号可在上下文中看到草稿知识，普通读者只能看到平台原有可信知识范围。文件路径拒绝绝对路径和 `..`；历史 Snapshot 审查与旧知识绑定保留版本标签，不能冒充当前源码。
8. 验证结果：新增代码证据服务、权限控制器、Task Review 文件引用和前端上下文面板测试。后端全量 `194` 项测试通过、`2` 项 Linux-only CLI 测试跳过；前端 `13` 项测试、Vue 类型检查和生产构建通过，路由测试确认 `/graph` 仅为兼容重定向。
9. 数据库与限制：不新增迁移。知识上下文当前通过既有知识服务读取可见卡片后过滤直接绑定，适合首版但大型知识库需要后续下推为专用 SQL；审查引用最多扫描最近 100 条。当前无后端登录会话，未完成真实浏览器截图和大型仓库响应时间基准，因此不宣称已验证生产规模性能。

### 18.17 REQ-017 实际实现记录

1. 五阶段状态机：项目准备统一为 `SNAPSHOT → CHUNKS → EMBEDDINGS → CODEGRAPH → KNOWLEDGE_DRIFT → READY/DEGRADED`。项目总览直接显示五个阶段、当前后台任务和真实明细，进度按已完成阶段计算，不再把图谱完成误报成整个准备流程完成。
2. 向量自动修复：当前 Snapshot 已有 Chunk 但存在 `missingChunks` 时，准备接口自动创建 `INCREMENTAL` 修复任务；索引 Processor 即使 Git 无文件差异也会执行现有 embedding 补齐。提供方仍不可用时任务以 `vectors-degraded` 成功终态结束，页面停止自动循环并明确显示 DEGRADED，用户可按阶段再次重试。
3. 自动串联：内容和向量成功后，索引 Processor 只在同一 Snapshot 尚无发布产物时排队 CodeGraph；CodeGraph 原子发布成功后再创建独立 `KNOWLEDGE_DRIFT` 任务。知识失效检查已从内容索引中移除，确保它读取图谱发布后的当前版本状态。
4. 版本保护：知识失效 Worker 在开始和结束时核对 Snapshot，并把 Snapshot UUID 与 `ready/degraded` 写入不可变任务终态；执行期间版本切换会以 `KNOWLEDGE_DRIFT_FAILED` 失败，不把旧版本检查结果套到新版本。准备服务只采信当前 Snapshot 对应的检查终态。
5. 阶段级重试：新增 `POST /api/repositories/{id}/prepare/stages/{stage}/retry`，仅允许具备 `MAINTAIN` 权限的账号重试 `snapshot/content/vectors/graph/knowledge_drift` 指定阶段。项目总览只在 FAILED 或 DEGRADED 阶段展示“重试此阶段”，不再要求用户跳转仓库、索引和图谱菜单。
6. 后台任务真实性：新增 `KNOWLEDGE_DRIFT` 任务类型、独立 Task Service、Processor 和 Worker；任务中心展示“知识失效检查”并支持既有取消/失败重试语义。普通索引领取器只领取 FULL/INCREMENTAL，避免误消费 CodeGraph 或知识检查任务。
7. 验证结果：新增向量缺失自动修复、五阶段 READY、索引后自动排队图谱、图谱后排队漂移检查、漂移检查 Snapshot 绑定/切换拒绝、超时回收及前端精确阶段重试测试。REQ-017 完成时后端全量 `199` 项测试通过、`2` 项 Linux-only CLI 测试跳过；前端 `14` 项测试、Vue 类型检查和生产构建通过。
8. 数据库与限制：任务复用既有 `index_jobs` 表，`job_type` 无枚举约束，因此不新增迁移。当前环境没有 PostgreSQL 与 CodeGraph CLI，真实队列的唯一活动任务约束、CLI 完成后的跨 Worker 串联和大知识库漂移耗时仍由 Linux CI/部署环境验收；本地结论不冒充生产运行证明。

### 18.18 REQ-018 实际实现记录

1. 普通开发者导航：主导航固定为“项目总览、代码与证据、问项目、变更审查”四项，顺序与开发任务一致；不再把知识维护、仓库维护、索引和平台配置与日常研发任务平铺。
2. 项目维护分组：当前仓库具备 `canUpdate` 时显示“知识治理”；账号拥有任一可维护仓库、是管理员或尚无仓库需要首次接入时显示“项目管理”。切换到无维护权限的仓库会关闭不再可用的治理页签，并从知识治理回到项目总览。
3. 系统管理分组：超级管理员看到可折叠“系统管理”，内部固定为“索引任务、模型配置、账号权限、审计日志”。索引任务路由升级为管理员路由；账号操作和审计核对拆为 `/accounts` 与 `/audit` 两个单一任务页面，从账号行查看审计会携带用户名定位。
4. 路由权限：`/knowledge` 使用当前仓库维护能力守卫，`/repositories` 要求至少一个可维护项目或空账号首次接入条件；`/indexing`、`/settings`、`/accounts`、`/audit` 均要求超级管理员。直接输入 URL 也执行相同守卫，不只依赖菜单隐藏。
5. 服务端边界：全局索引任务总览 `/api/index-jobs/page` 和 `/api/index-jobs` 增加 `requireAdmin`；项目准备轮询所需的单任务读取和具备 MAINTAIN 权限的取消/重试接口保持原权限，不破坏自动准备流程。账号、审计和模型接口继续使用既有管理员校验。
6. 总览操作收敛：普通 READ 用户仍可查看可信知识健康，但不再看到“管理知识”“处理知识”或准备阶段修复按钮；只有服务端 capabilities 允许的维护者看到相应入口，避免可见按钮最终以 403 失败。
7. 导航可测试模型：菜单定义抽取为纯函数，测试分别固定普通开发者 4 项、维护者 2 项和管理员系统管理 4 项；关键路由测试固定维护/管理员元数据及独立审计页面，防止后续重新平铺。
8. 验证结果：后端全量 `200` 项测试通过、`2` 项 Linux-only CLI 测试跳过；前端 `18` 项测试、Vue 类型检查和生产构建通过。当前没有可用的后端登录会话，因此未执行三种角色的浏览器截图；权限投影、路由元数据、组件类型和服务端管理员拒绝均已自动验证。

### 18.19 REQ-019 实际实现记录

1. 提供方抽象：新增统一 `PullRequestProvider` 及 GitHub、GitLab 两个实现。GitHub 分别读取 Pull Request 元数据和 `application/vnd.github.diff` 原始 Diff，并通过 Issue comments API 写评论；GitLab读取 Merge Request 元数据和分页 Diffs，并通过 Notes API 写评论。两者均使用 10 秒连接/30 秒请求超时、禁止重定向、流式限量读取和脱敏错误，不把 Token 放入返回对象或日志文本。
2. 统一真实变化：新增 `UnifiedDiffRepositoryChangeParser`，把提供方 Patch 转成现有 `RepositoryChange`，保留新增、修改、删除、重命名、复制、二进制、增删行、Hunk、部分数据和限制原因。Patch 上限 5 MiB、文件上限 5,000，拒绝绝对路径、父目录穿越、控制字符和无效 Commit；超限时拒绝生成审查，不把截断数据当完整结果。
3. 版本一致性：PR/MR Head 必须与仓库当前已发布 Snapshot 的 Commit 完全一致，才调用 Task Review；`TaskReviewService.createExternal` 同时核对请求 Base/Head 与外部变化边界，并直接使用已解析的提供方 Patch，不重新从可能不同版本的本地工作区推断变化。版本不同返回 `PR_HEAD_NOT_CURRENT_SNAPSHOT`，不创建结论或评论。
4. 评论内容与幂等：评论固定展示适用知识、必须测试、必要审批、可能失效知识、未知项和数据限制，并明确标注“提示性审查、不是合并门禁”。隐藏 Marker 绑定平台仓库、提供方和 PR/MR 编号；最多分页扫描既有评论，找到 Marker 后 PATCH/PUT 原评论，否则创建一条新评论。模型总结不进入门禁，也不替代这些确定性栏目。
5. 手动入口与权限：新增 `POST /api/repositories/{id}/pull-request-reviews`，只允许 `MAINTAIN` 以上账号触发。目标项目从数据库已登记的 HTTPS Remote URL 派生，访问令牌来自该仓库已绑定且主机匹配的加密凭据；可选企业版 API Base 也必须使用 HTTPS 且与仓库同主机，只有 `github.com → api.github.com` 使用官方公共例外。
6. Webhook：新增 GitHub/GitLab 无会话入口，分别校验原始请求体 `HMAC-SHA256` 和 GitLab Secret Token；未配置密钥时入口明确关闭。验签后只处理 PR `opened/reopened/synchronize/ready_for_review` 和 MR `open/reopen/update`，按远程 URL 精确映射唯一已接入仓库；零条或多条映射都拒绝。审查 `clientRequestId` 由提供方、仓库、编号和 Head 生成，提供方重试不会重复创建 Task Review。
7. 前端：变更审查页对维护者增加“本地 Git / PR·MR”输入切换。PR/MR 表单提供平台、编号、任务、可选模型总结和企业 API Base；提交成功后复用现有证据主线展示完整 Task Review，并显示评论创建/更新状态及提供方链接。READ 用户仍只看到本地只读审查入口，不能调用带凭据的评论同步。
8. 测试结果：新增统一 Diff、安全路径、目标主机、GitHub/GitLab 本地 HTTP 契约、编排版本拒绝、隐藏评论更新、Webhook 验签、事件映射、远程仓库歧义拒绝、无浏览器会话安全边界和维护权限测试。完成时后端全量 `219` 项测试通过、`2` 项 Linux-only CLI 测试跳过；前端 `19` 项测试、Vue 类型检查和生产构建通过。
9. 数据库与限制：不新增表或迁移；Task Review 继续使用 V10 不可变存储，Webhook 仓库匹配新增只读 Mapper 查询。GitHub 每次最多扫描 1,000 条评论，GitLab 最多扫描 50 页 Diffs/Notes；Diff 超限显式标记部分结果，评论扫描超限则拒绝发布，避免无法确认 Marker 时制造重复评论。当前没有真实 GitHub/GitLab Token、外部 Webhook 回调和 PostgreSQL 环境，因此自动化验证使用本地 HTTP 契约与 Mapper 静态加载，生产部署仍需配置两个独立 Webhook Secret 并做真实项目验收。

### 18.20 REQ-020 实际实现记录

1. 单一判定入口：新增 `POST /api/repositories/{repositoryId}/task-reviews/{reviewId}/ci-check`，要求仓库 READ 权限。服务只读取已经完成的不可变 Task Review；工作区审查、缺少真实变化或请求 Head 与审查 Head 不一致都会拒绝，不在 CI 时刻重新推断代码。
2. 固定阻断策略：策略版本为 `deterministic-ci-v1`，只产生五类失败——结构化禁止路径命中、带 Git/代码直接证据的必需测试未报告或未通过、带直接证据的必要审批缺失或拒绝、带直接证据的 CRITICAL + REQUIRED 知识处于 SUSPECT/STALE、明确要求同步但没有真实有效的知识新修订。
3. 知识同步真实性：调用方不能自报“知识已更新”。服务重新读取当前知识卡，只有同一卡片修订号高于审查版本，并同时处于 `PUBLISHED + APPROVED + CURRENT`，才消除同步阻断。
4. 非阻断边界：图谱单独推断的测试、审批、失效和知识同步要求，关键词/向量候选、模型总结、未知项与 partial 数据全部进入 Advisory；自由文本开发要求不做规则解析。重复测试/审批回报冲突时取较差状态。
5. 知识模型与界面：`KnowledgeObligations` 新增 `prohibitedPathPatterns` 和 `knowledgeUpdateRequired`，保留三字段兼容构造。知识编辑器在开发要求区提供禁止路径和知识同步控件，详情页展示原始结构化规则；REFERENCE 知识仍不能携带任何义务。
6. 数据迁移：新增 `V12__ci_knowledge_obligations.sql`，同时回填知识卡和所有历史修订，更新 JSONB 默认值并约束新增数组/布尔字段。静态迁移契约已覆盖两张表；当前无 PostgreSQL/Docker，仍需在全新库及 V11 实库升级路径验证 Flyway。
7. 流水线客户端：新增 `scripts/ci-task-review.mjs`，从环境读取 Analyzer 地址、仓库/审查/Head、Session/CSRF 和可选测试/审批 JSON 文件；远端强制 HTTPS，本地允许回环 HTTP，禁止重定向，30 秒超时，不打印凭据。退出码 `0/1/2` 分别表示通过、确定性失败、平台或调用失败。
8. 自动化验证：服务测试固定五类阻断和全部非阻断边界，另覆盖 Head 拒绝、成功消除义务、READ 权限、V12 与知识规则校验；Node 客户端测试覆盖输入文件、HTTPS 边界、请求契约、凭据不泄漏和退出码。完成时后端全量 `225` 项测试通过、`2` 项 Linux-only CLI 测试跳过；前端 `19` 项测试、Vue 类型检查和生产构建通过；独立 CI 客户端 `4` 项测试通过，并已加入 Linux CI。
9. 当前限制：CI 客户端复用平台 Session + CSRF，需要受限专用账号和 Secret 轮换，尚未提供长期服务令牌；CI 请求本身仍是一次性判定，如需沉淀实际结果，调用方须再使用 REQ-022 结果回报接口。真实 CI Runner、真实 PostgreSQL V12 升级和长时间会话轮换尚未在当前机器验证，不能宣称生产验收完成。

### 18.21 REQ-021 实际实现记录

1. 工程事实模型：新增 `engineering_projects`、`engineering_project_repositories` 和 `engineering_project_contracts`。项目至少两个成员，仓库和规范化服务名在项目内唯一；契约提供方/消费方必须是两个不同成员。服务身份完全由管理者填写，不读取 README、不调用模型，也不按目录或向量相似度猜测。
2. 双端契约证据：保存契约时从双方当前 Snapshot 的内容索引精确读取仓库相对路径，按所有当前 Chunk 的 `startLine:contentHash` 生成 SHA-256 指纹。项目读取和 Task Review 都重新计算提供方与消费方指纹；任意一端缺失或变化后 `current=false`，契约范围不再产生确定性命中。
3. 权限与生命周期：新增 `/api/engineering-projects` 和 `/api/engineering-projects/{id}` 查询与 CRUD。完整项目只有在账号能读取全部成员时可见；创建、更新和删除要求对全部旧/新成员具有 MANAGE。被跨仓 Scope 引用时不得删除项目、移除/改名已有成员服务或删除已有契约；仓库仍属于活动项目时仓库删除被拒绝。
4. Scope 与迁移：`KnowledgeScope` 新增 `repositoryIds/serviceNames/contractIds` 并保留三字段兼容构造；V13 同时迁移当前卡片和全部修订 JSON，设置默认值、回填和数组类型约束。工程项目表保存登记 Snapshot 作为审计信息，但当前性以实际内容指纹为准，不添加指向历史文件产物的虚假外键。
5. 匹配语义：跨仓知识只有在目标/来源仓库属于同一可见工程项目，并显式命中目标仓库、目标服务或当前契约时才适用；如果还有路径、符号、模块范围则继续按 AND 收窄。契约只在目标变化路径精确等于登记证据路径时命中。单纯同项目、关键词/向量候选、模型输出和失效契约均不能建立适用性。
6. 证据与 CI：新增 `REPOSITORY/SERVICE/CONTRACT` 匹配原因和 `PLATFORM_FACT` 真相来源，Provenance 返回项目、服务、契约和目标证据路径，同时保留原知识的来源仓库和修订。REQ-020 的知识同步检查会按该来源仓库读取新修订，不再错误地只查询目标仓库。
7. 管理界面：项目管理页新增“跨仓工程项目”工作台，在一个对话框中查看项目、成员服务名、契约两端路径、稳定 UUID 和证据当前性。只能管理部分成员时保持只读；新建入口只提供可管理且有当前 Snapshot 的仓库，页面明确说明关系不会自动猜测。
8. 自动化验证：服务、Mapper Schema、Controller、Scope 匹配、Task Review 跨仓加载、Provenance 和前端项目工作台均新增测试；契约测试覆盖双方当前、任一方内容变化即失效，以及无当前证据拒绝保存。收尾时后端全量 `236` 项测试通过、`2` 项 Linux-only CLI 测试在 Windows 跳过；前端 `20` 项测试、Vue 类型检查和生产构建通过。
9. 当前限制：当前数据库没有仓库级质量门禁结果，因此只强校验当前 Snapshot、权限、显式服务身份和双端代码证据，不能宣称“单仓库已达到发布门槛”。当前机器没有 PostgreSQL、真实两仓数据和登录后端，V13 全新/升级迁移、多人权限组合、真实跨仓审查及大规模查询性能仍需部署环境验收。

### 18.22 REQ-022 实际实现记录

1. 追加式结果模型：新增 `task_review_outcomes` 与 `task_review_feedback`。结果绑定仓库、不可变审查、具名报告人和 clientRequestId，保存最终 Commit、摘要、测试/审批 JSON、规范化请求 SHA-256 与报告时间；反馈逐条保存误报、漏报或知识更新判断。两表禁止 UPDATE，修正只能追加新回报。
2. 版本真实性：只接受 40–64 位完整 Git 对象 ID。最终 Commit 与审查 Head 完全相同才标记 `EXACT_REVIEW_HEAD`；其他值标记 `REPORTER_ASSERTED_FINAL`，明确表示平台没有可用证据验证祖先关系，不把报告人声明冒充 Git 事实。
3. 幂等与权限：新增 `POST/GET /api/repositories/{repositoryId}/task-reviews/{reviewId}/outcomes` 和单条 GET，统一要求仓库 READ。唯一边界为审查、报告人和 clientRequestId；冲突后只有请求摘要哈希完全相同才返回原记录，不同内容返回 `TASK_OUTCOME_IDEMPOTENCY_CONFLICT`。成功写入记录审计事件。
4. 结构化回报：测试状态限制为 `PASSED/FAILED/SKIPPED`，审批限制为 `APPROVED/REJECTED`，键和账号不得重复，HTTP(S) 证据地址有数量和长度上限。读取时根据原审查计算必须测试/审批的已回报与缺失集合，未回报不会被自动解释为通过或失败。
5. 人工反馈边界：误报必须精确指向原审查中存在的适用知识、必须测试、必要审批、失效知识、未知项、变化文件或符号；漏报允许描述审查缺失对象但必须保留说明。知识更新判断只允许绑定原审查中的适用/失效知识，并限制为 `NEEDED/NOT_NEEDED/UNKNOWN`。
6. 不自动学习：结果服务不依赖知识写服务，不修改知识卡内容、发布、审核或来源版本状态，也不反向覆盖 Task Review 或 CI 结果。反馈是具名评测输入，不是自动生效规则。
7. 前端闭环：变更审查证据主线后新增“开发结果与人工反馈”账本。页面展示报告人、Commit 绑定级别、测试/审批/反馈数量和缺失义务；表单不预选成功状态，误报从现有对象中选择，漏报填写缺失对象，知识更新判断明确提示不会改知识。历史审查同样可以追加结果。
8. MCP 接通：`report_task_outcome` 不再返回不可用，现已转发同一 REST 结构，支持最终 Commit、测试、审批、误报、漏报和知识更新判断；继续使用 Session/CSRF、READ 权限和稳定错误码，不建立 MCP 私有存储。
9. 自动化验证：后端全量 `242` 项测试通过、`2` 项 Linux-only CLI 测试在 Windows 跳过；前端 `21` 项测试、Vue 类型检查和生产构建通过；MCP `8` 项测试通过，包含 stdio 实际调用后对 URL、CSRF 和完整请求体的断言。
10. 当前限制：当前机器没有 PostgreSQL 和登录后端，V14 全新库/V13 升级、触发器、真实多人回报与页面视觉仍需部署环境验收。当前回报按审查逐条读取，已足以形成任务闭环；跨仓库大规模反馈聚合、导出和趋势报表不在本需求中，不能宣传为已实现分析能力。
