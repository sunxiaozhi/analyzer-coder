# 综合研发智能平台核心可用版设计

日期：2026-06-18

## 1. 设计目标

本设计定义综合研发智能平台的完整平台蓝图和核心可用版范围。平台面向多项目研发组织，但每个项目默认隔离。代码、知识、索引、上下文包、影响分析、权限和审计都以项目空间为边界。跨项目能力作为增强能力，必须在显式授权后开放。

核心可用版不是临时演示，而是一组能够端到端运行的最小产品能力：

```text
创建项目
  -> 同步代码
  -> 生成代码事实
  -> 沉淀并确认知识资产
  -> 构建统一索引
  -> 执行可信检索
  -> 生成 AI 上下文包
  -> 输入文件列表或 diff 做变更影响初判
  -> 回流知识复审和治理
```

## 2. 总体方案

平台采用项目空间驱动方案。

组织层只负责用户、项目、全局配置和系统级审计入口。组织层不混合项目业务数据。

项目空间层是主要工作区。每个项目独立拥有代码仓库、仓库快照、代码事实、知识资产、知识文档、索引记录、检索结果、上下文包、变更影响任务、项目成员和审计事件。

该方案的优点是边界清晰、权限简单、实现风险可控。后续可以把 AI 研发助手升级为高频入口，但底层项目隔离、证据、索引和审计模型不需要重做。

## 3. 平台蓝图

平台分为 8 个核心模块。

| 模块 | 说明 |
|---|---|
| 项目与成员管理 | 项目创建、Git 地址、默认路径、成员授权、项目负责人设置 |
| 代码事实中心 | 仓库同步、代码分析、接口识别、SQL 线索、调用关系、代码事实查询和基础图数据导出 |
| 知识资产中心 | 知识文档、结构化知识资产、证据绑定、确认、待复审、归档 |
| 统一索引中心 | 代码、知识文档、知识资产、接口、SQL、调用链的索引构建和状态查看 |
| 可信检索中心 | 项目内检索，展示来源、状态、分数、证据和推荐原因 |
| AI 研发助手工作台 | 核心可用版生成上下文包；增强版支持内置 AI 问答 |
| 变更影响分析 | 文件列表或 diff 输入，输出候选影响、关联知识、风险和测试建议 |
| 治理与审计 | 知识状态、复审风险、索引状态、任务失败、导出记录和操作审计 |

核心可用版不做跨项目检索、不做复杂审批流、不做 PR/MR 自动集成、不做自动写代码。所有增强能力必须复用项目空间、权限、证据和索引模型。

## 4. 角色权限

核心可用版采用项目级角色模型。一个用户可以在不同项目中拥有不同角色。

### 4.1 系统管理员

系统管理员是组织层角色，权限包括：

- 创建、归档、恢复项目。
- 创建用户、停用用户、重置密码。
- 设置用户可访问哪些项目。
- 指定项目负责人。
- 配置全局存储、模型、路径策略。
- 查看系统级健康状态和审计入口。
- 进入任意项目排查问题，但操作必须记录审计。

系统管理员不默认负责知识确认，除非同时是该项目负责人。

### 4.2 项目负责人

项目负责人是项目内最高业务角色，权限包括：

- 管理本项目成员和只读成员。
- 修改项目默认代码路径、知识库路径和索引配置。
- 触发代码分析、索引构建、变更影响分析。
- 创建、编辑、确认、标记待复审、归档知识资产。
- 处理待复审候选。
- 查看项目内审计、治理指标和任务失败详情。
- 导出上下文包。

项目负责人负责把草稿知识变成团队可信知识。

### 4.3 项目成员

项目成员是日常研发使用者，权限包括：

- 查看项目代码事实、分析结果、接口、SQL、调用关系。
- 触发代码分析、索引构建、可信检索。
- 创建和编辑自己创建的草稿知识。
- 给知识资产绑定证据。
- 提交知识给项目负责人确认。
- 生成和导出 AI 上下文包。
- 执行变更影响初判。

项目成员不能确认知识，不能归档他人知识，不能修改项目成员权限。

### 4.4 只读成员

只读成员用于测试、运维、外部协作或审阅场景，权限包括：

- 查看项目基础信息、代码事实、知识资产、索引状态。
- 执行可信检索。
- 查看已生成的分析报告和接口映射。
- 查看上下文包结果。

只读成员默认不能导出上下文包，不能触发分析、索引、影响分析，不能创建、编辑、确认或归档知识。

### 4.5 通用规则

- 所有项目级对象必须包含 `projectId`。
- 后端必须校验用户是否有项目访问权限。
- 前端隐藏按钮只作为体验优化，不能作为权限依据。
- 跨项目访问默认禁止。
- 关键操作必须写审计，包括登录、项目创建、成员授权、知识确认、知识归档、索引构建、上下文包导出、变更影响分析和项目配置修改。

## 5. 核心业务闭环

### 5.1 项目接入闭环

系统管理员创建项目，填写项目名称、Git 地址、分支、默认代码路径、默认知识库路径，设置项目负责人和成员。系统同步仓库后生成项目工作区和初始同步记录。项目成员只能看到自己被授权的项目。

### 5.2 代码事实生成闭环

项目成员进入代码事实中心，选择分析范围并触发分析。系统提取代码结构、Spring 接口、SQL 线索、调用关系和基础模块信息，形成可查询的代码事实。分析失败时保留成功部分，并记录失败文件、失败原因和分析时间。

### 5.3 知识沉淀闭环

成员创建知识文档或结构化知识资产。知识资产包含标题、类型、摘要、正文、标签、负责人、状态和证据。证据可以来自代码文件、类、方法、接口、SQL、检索结果或手工引用。项目负责人确认后，知识资产进入已确认状态。

### 5.4 可信检索闭环

用户输入自然语言问题，选择检索范围。系统只在当前项目内检索代码、知识文档、知识资产和接口信息。结果必须展示来源类型、标题、摘要、分数、知识状态、更新时间、证据数量和推荐原因。已确认知识优先展示，待复审知识显示风险提示。

### 5.5 AI 上下文包闭环

用户输入任务类型和任务描述，例如需求开发、缺陷修复、接口变更、排障或代码评审。系统召回相关知识、代码事实、接口、SQL 和规范，用户可以勾选、排除和排序。系统生成可复制的 Markdown 或纯文本上下文包，并保存引用清单和导出审计。核心可用版只生成上下文包，不直接调用大模型写代码。

### 5.6 变更影响初判闭环

用户输入文件列表或 diff。系统识别变更文件、变更符号和对应代码事实，查找关联接口、SQL、知识资产和可能测试范围。输出必须使用候选影响、可能影响、置信度、不确定性说明等表达，不能把静态分析结果描述成绝对结论。命中的已确认知识可以生成待复审候选，但需要项目负责人确认后才进入待复审状态。

### 5.7 治理回流闭环

系统聚合项目内的知识状态、待复审数量、索引状态、分析失败、低命中查询、上下文导出和影响分析命中。项目负责人可以从治理入口跳转回知识资产、索引、分析任务或复审动作。核心可用版只做项目内基础治理，不做复杂组织级报表。

## 6. 核心数据对象

### 6.1 Project

项目空间。关键字段：

- `id`
- `projectKey`
- `name`
- `gitUrl`
- `branch`
- `workspacePath`
- `defaultCodePath`
- `defaultKnowledgePath`
- `ownerUserId`
- `status`
- `createdAt`
- `updatedAt`

### 6.2 ProjectMember

用户在某个项目内的角色。关键字段：

- `projectId`
- `userId`
- `role`: `owner | member | readonly`
- `status`
- `createdAt`
- `updatedAt`

### 6.3 RepositorySnapshot

一次仓库同步或分析基线。关键字段：

- `id`
- `projectId`
- `branch`
- `commitHash`
- `commitMessage`
- `syncedAt`
- `analyzedAt`
- `sourcePaths`
- `status`

### 6.4 CodeFact

平台从代码中提取出来的结构化事实。关键字段：

- `id`
- `projectId`
- `snapshotId`
- `factType`: `file | class | method | endpoint | sql | call | module | frontend_call`
- `filePath`
- `symbolName`
- `signature`
- `startLine`
- `endLine`
- `metadata`
- `confidence`

CodeFact 是检索对象、知识证据、上下文包引用和变更影响匹配目标。

### 6.5 KnowledgeAsset

团队确认或沉淀的结构化知识。关键字段：

- `id`
- `projectId`
- `type`: `business_rule | adr | incident | api_doc | standard | glossary | module_note`
- `title`
- `summary`
- `content`
- `status`: `draft | confirmed | stale | archived`
- `tags`
- `ownerUserId`
- `createdBy`
- `confirmedBy`
- `confirmedAt`
- `reviewDueAt`
- `createdAt`
- `updatedAt`

已确认知识优先进入检索和上下文包。待复审知识可进入，但必须提示风险。归档知识默认不参与召回。

### 6.6 Evidence

知识资产和代码、文档、检索结果之间的连接。关键字段：

- `id`
- `projectId`
- `assetId`
- `evidenceType`: `code_fact | file | endpoint | sql | search_result | external_ref`
- `targetId`
- `filePath`
- `symbolName`
- `startLine`
- `endLine`
- `note`
- `confidence`

证据不能跨项目绑定。证据失效后不自动改变知识状态，只生成待复审候选。

### 6.7 IndexRecord

检索单元。关键字段：

- `id`
- `projectId`
- `snapshotId`
- `sourceType`: `code | knowledge_doc | knowledge_asset | endpoint | sql | call_chain`
- `sourceId`
- `chunkId`
- `title`
- `text`
- `vectorId`
- `metadata`
- `status`
- `indexedAt`

核心可用版默认使用 Qdrant 保存向量数据，`vectorId` 对应 Qdrant point id。所有检索必须带 `projectId` 过滤。索引记录必须能追溯回源对象。

### 6.8 SearchResult

查询时生成的展示对象，不一定长期保存。关键字段：

- `projectId`
- `sourceType`
- `sourceId`
- `title`
- `snippet`
- `score`
- `status`
- `updatedAt`
- `evidenceCount`
- `explanation`

SearchResult 可以加入上下文包，也可以绑定为知识证据。

### 6.9 ContextPackage

给 AI 编程工具使用的可信上下文。关键字段：

- `id`
- `projectId`
- `taskType`
- `taskText`
- `selectedItems`
- `excludedItems`
- `content`
- `format`
- `createdBy`
- `createdAt`
- `exportedAt`

上下文包必须保存引用清单，不能只保存最终文本。导出必须写审计。

### 6.10 ChangeAnalysisTask

一次影响初判记录。关键字段：

- `id`
- `projectId`
- `snapshotId`
- `inputType`: `file_list | diff`
- `inputContent`
- `changedFiles`
- `matchedCodeFacts`
- `impactedEndpoints`
- `impactedKnowledgeAssets`
- `risks`
- `testSuggestions`
- `confidence`
- `status`
- `createdBy`
- `createdAt`

影响结果必须区分事实和候选推断。标记知识待复审必须由项目负责人确认。

### 6.11 ReviewTask

知识复审任务。关键字段：

- `id`
- `projectId`
- `assetId`
- `sourceTaskId`
- `reason`
- `status`: `open | resolved | ignored`
- `ownerUserId`
- `dueAt`
- `createdAt`
- `updatedAt`

核心可用版中，ReviewTask 主要由变更影响命中已确认知识、证据失效、复审日期逾期或治理项手动创建。系统可以生成复审候选，但最终状态处理由项目负责人确认。

### 6.12 AuditEvent

关键操作记录。关键字段：

- `id`
- `projectId`
- `actorUserId`
- `action`
- `targetType`
- `targetId`
- `detail`
- `createdAt`
- `ipAddress`

审计事件不保存敏感明文，只保存对象 ID、脱敏摘要和操作结果。

## 7. 页面信息架构

### 7.1 组织层页面

| 页面 | 功能 |
|---|---|
| 登录页 | 用户名密码登录，显示失败原因，登录后进入最近访问项目或项目列表 |
| 项目列表 | 展示可访问项目；管理员可创建项目；按项目名、状态、负责人过滤 |
| 账号与权限 | 管理员创建用户、停用用户、重置密码、授权项目和设置项目角色 |
| 系统配置 | 配置工作区、状态存储、默认 Qdrant 向量库、embedding 服务、对象存储和路径策略，查看健康状态 |

### 7.2 项目空间页面

| 页面 | 核心功能 |
|---|---|
| 项目总览 | 展示项目状态、仓库快照、分析状态、索引状态、知识统计和快捷入口 |
| 项目配置 | 修改默认路径、查看仓库与分支、管理项目成员、归档项目 |
| 代码事实 | 触发分析，查看代码事实列表和详情，查看失败文件，导出基础图数据，绑定证据或加入上下文 |
| 接口关系 | 查看 endpoint、前端调用、匹配关系和接口风险；接口说明通过 `api_doc` 知识资产承载 |
| 知识文档 | 浏览、创建、编辑、删除知识文件，应用模板，预览 Markdown |
| 知识资产 | 查看、过滤、创建、编辑、绑定证据、确认、标记待复审、归档 |
| 统一索引 | 构建索引，查看索引状态、来源分布、记录和失败原因 |
| 可信检索 | 自然语言检索，过滤来源，查看证据，复制结果，加入上下文包，绑定为证据，生成知识资产草稿 |
| AI 研发助手 | 输入任务，召回候选，上下文勾选排除排序，生成和导出上下文包，展示风险和测试建议 |
| 变更影响 | 输入文件列表或 diff，查看候选影响、风险、测试建议和待复审候选 |
| 治理看板 | 查看知识状态、缺证据、待复审、索引失败、分析失败和导出记录 |
| 项目审计 | 按操作人、类型、时间过滤项目内关键操作 |

所有项目空间页面必须显示当前项目。除组织层页面外，未选择项目时应展示明确空状态。

核心可用版的图谱能力只要求后端生成和导出裁剪后的 JSON 节点边数据，并在代码事实、接口关系或分析报告中查看基础关系。Neo4j 路径查询、Cytoscape.js 交互式图谱、子图导出和图节点直接加入上下文包属于增强版。

核心可用版不建设独立 InterfaceAsset。接口说明、入参出参、错误码、调用方、负责人和状态先通过 `api_doc` 类型的 KnowledgeAsset 承载，API 映射结果作为 Evidence 绑定。独立接口资产、参数级治理和接口负责人流转属于增强版。

检索结果收藏属于增强版。核心可用版支持复制结果、加入上下文包、绑定为证据和生成知识资产草稿。

## 8. 核心接口设计

接口按项目空间组织。除登录、用户、系统配置外，项目内接口都必须带 `projectId`，并由后端校验用户角色。

### 8.1 认证与用户

- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`
- `GET /api/users`
- `POST /api/users`
- `PUT /api/users/{userId}`

### 8.2 项目与成员

- `GET /api/projects`
- `POST /api/projects`
- `GET /api/projects/{projectId}`
- `PUT /api/projects/{projectId}`
- `POST /api/projects/{projectId}/sync`
- `GET /api/projects/{projectId}/members`
- `PUT /api/projects/{projectId}/members`

### 8.3 系统配置

- `GET /api/system/config`
- `PUT /api/system/config`
- `POST /api/system/config/test`

系统配置接口仅系统管理员可用。核心可用版至少支持工作区路径、状态存储、默认 Qdrant、embedding 服务、对象存储和路径策略的查看、保存和连接测试。

### 8.4 代码事实

- `POST /api/projects/{projectId}/analysis`
- `GET /api/projects/{projectId}/analysis/tasks`
- `GET /api/projects/{projectId}/analysis/tasks/{taskId}`
- `GET /api/projects/{projectId}/code-facts`
- `GET /api/projects/{projectId}/code-facts/{factId}`
- `GET /api/projects/{projectId}/code-graph`
- `GET /api/projects/{projectId}/interface-map`

### 8.5 知识文档

- `GET /api/projects/{projectId}/knowledge/files`
- `GET /api/projects/{projectId}/knowledge/files/content`
- `POST /api/projects/{projectId}/knowledge/files`
- `PUT /api/projects/{projectId}/knowledge/files/content`
- `DELETE /api/projects/{projectId}/knowledge/files`
- `GET /api/projects/{projectId}/knowledge/templates`
- `POST /api/projects/{projectId}/knowledge/templates`

### 8.6 知识资产与证据

- `GET /api/projects/{projectId}/knowledge/assets`
- `POST /api/projects/{projectId}/knowledge/assets`
- `POST /api/projects/{projectId}/knowledge/assets/from-search-result`
- `GET /api/projects/{projectId}/knowledge/assets/{assetId}`
- `PUT /api/projects/{projectId}/knowledge/assets/{assetId}`
- `POST /api/projects/{projectId}/knowledge/assets/{assetId}/confirm`
- `POST /api/projects/{projectId}/knowledge/assets/{assetId}/mark-stale`
- `POST /api/projects/{projectId}/knowledge/assets/{assetId}/archive`
- `POST /api/projects/{projectId}/knowledge/assets/{assetId}/evidence`
- `DELETE /api/projects/{projectId}/knowledge/assets/{assetId}/evidence/{evidenceId}`

### 8.7 统一索引

- `POST /api/projects/{projectId}/index/build`
- `GET /api/projects/{projectId}/index/status`
- `GET /api/projects/{projectId}/index/records`

### 8.8 可信检索

- `POST /api/projects/{projectId}/search`
- `POST /api/projects/{projectId}/search/results/{resultId}/copy`

检索结果必须包含 `sourceType`、`sourceId`、`title`、`snippet`、`score`、`status`、`updatedAt`、`evidenceCount` 和 `explanation`。

### 8.9 AI 研发助手

- `POST /api/projects/{projectId}/context-packages/preview`
- `POST /api/projects/{projectId}/context-packages`
- `GET /api/projects/{projectId}/context-packages`
- `GET /api/projects/{projectId}/context-packages/{packageId}`
- `POST /api/projects/{projectId}/context-packages/{packageId}/export`

增强版预留：

- `POST /api/projects/{projectId}/ai/chat`

### 8.10 变更影响分析

- `POST /api/projects/{projectId}/change-analysis`
- `GET /api/projects/{projectId}/change-analysis`
- `GET /api/projects/{projectId}/change-analysis/{taskId}`
- `POST /api/projects/{projectId}/change-analysis/{taskId}/review-candidates/{assetId}/confirm`

### 8.11 治理与审计

- `GET /api/projects/{projectId}/governance/summary`
- `GET /api/projects/{projectId}/governance/issues`
- `GET /api/projects/{projectId}/review-tasks`
- `PUT /api/projects/{projectId}/review-tasks/{reviewTaskId}`
- `GET /api/projects/{projectId}/audit-events`

## 9. 后端服务边界

后端服务建议拆分如下：

| 服务 | 职责 |
|---|---|
| AuthService | 登录、当前用户、管理员校验 |
| SystemConfigService | 工作区、状态存储、默认 Qdrant、embedding、对象存储和路径策略配置 |
| ProjectService | 项目、仓库同步、项目成员 |
| CodeAnalysisService | 代码扫描、分析任务、失败文件 |
| CodeFactService | 代码事实查询、接口关系 |
| KnowledgeDocumentService | 知识文件和模板 |
| KnowledgeAssetService | 知识资产、状态流转、证据绑定 |
| IndexService | 索引构建、索引状态、索引记录 |
| SearchService | 可信检索、结果解释、证据关联 |
| ContextPackageService | 候选召回、上下文包生成、导出 |
| ChangeAnalysisService | 文件列表和 diff 影响初判 |
| GovernanceService | 治理摘要和治理项 |
| ReviewTaskService | 复审任务创建、处理和忽略 |
| AuditService | 审计记录和查询 |

分析、索引、上下文生成、影响分析都是任务型操作，要有状态、失败原因和可重试入口。普通查询接口不应触发重型任务。

## 10. 关键数据流

### 10.1 项目接入

```text
提交项目信息
  -> 校验管理员权限
  -> 校验项目名、Git 地址和分支
  -> 创建 Project
  -> 创建项目工作区
  -> 拉取仓库
  -> 创建 RepositorySnapshot
  -> 创建 ProjectMember
  -> 写入 AuditEvent
```

Git 拉取失败时，项目进入 `sync_failed` 状态，失败原因保留，用户可重试同步。

### 10.2 代码事实生成

```text
触发分析
  -> 创建分析任务
  -> 扫描文件
  -> 解析 Java/Spring/MyBatis/前端 API
  -> 生成 CodeFact
  -> 生成接口关系和调用关系
  -> 保存成功事实和失败文件
  -> 更新 RepositorySnapshot
  -> 写入 AuditEvent
```

单文件失败不导致整个任务失败。任务整体失败时保留上一版可用分析结果。

### 10.3 知识沉淀

```text
创建知识资产
  -> 保存草稿
  -> 绑定 Evidence
  -> 校验证据 projectId
  -> 项目负责人确认
  -> 记录确认人和确认时间
  -> 进入索引候选
```

草稿可以无证据。确认无证据知识时必须形成缺证据治理项。

### 10.4 统一索引

```text
触发索引
  -> 创建索引任务
  -> 读取 CodeFact、知识文档、知识资产、接口、SQL 和调用链
  -> 生成统一 chunk
  -> 写入 IndexRecord
  -> 写入默认 Qdrant 向量库
  -> 更新索引状态
  -> 写入 AuditEvent
```

新索引构建成功前不覆盖旧索引。embedding 或 Qdrant 失败时，最近成功索引继续可用。

### 10.5 可信检索

```text
输入问题
  -> 校验项目权限
  -> 强制带 projectId 过滤
  -> 查询索引
  -> 读取 metadata 和源对象
  -> 关联知识状态、证据和更新时间
  -> 返回可追溯 SearchResult
```

不允许只返回文本片段。每条结果必须能跳转到源对象。

### 10.6 AI 上下文包

```text
输入任务
  -> 根据任务类型生成召回策略
  -> 调用可信检索生成候选上下文
  -> 用户勾选、排除、排序
  -> 生成上下文包正文
  -> 保存 ContextPackage 和引用清单
  -> 导出前执行权限复检和基础敏感信息过滤
  -> 导出时写入 AuditEvent
```

上下文包必须包含任务描述、用户约束、已确认知识、待复审风险提示、相关代码事实、接口、SQL、风险点、测试建议、引用来源清单和生成时间。核心可用版可以根据文件名、常见密钥格式和配置规则做基础敏感信息过滤；企业级敏感信息治理属于增强版。

### 10.7 变更影响分析

```text
输入文件列表或 diff
  -> 创建 ChangeAnalysisTask
  -> 解析变更文件和行区间
  -> 匹配 CodeFact
  -> 查找 Evidence
  -> 找到 KnowledgeAsset
  -> 输出影响接口、影响知识、风险和测试建议
  -> 生成待复审候选或 ReviewTask
  -> 写入 AuditEvent
```

结果分层：

- 确定事实：变更文件、变更行、直接命中的代码事实。
- 高置信候选：直接命中的 endpoint、SQL、绑定知识。
- 中置信候选：调用关系一跳内影响。
- 低置信候选：模块级、目录级、关键词推断影响。

### 10.8 治理回流

治理数据来源包括知识状态、证据情况、任务状态、上下文包导出、检索无结果和影响分析命中知识。

核心可用版治理项包括：

- 缺证据知识。
- 待复审知识。
- 索引失败。
- 分析失败。
- 最近影响分析命中已确认知识。
- 最近上下文包导出。
- 低命中查询或频繁无结果查询。

每个治理项必须能跳转到对象。需要人员处理的治理项可以生成 ReviewTask。项目负责人可以处理或忽略治理项，忽略也要记录审计。

## 11. 状态设计

### 11.1 项目状态

- `active`
- `syncing`
- `sync_failed`
- `archived`

### 11.2 仓库快照状态

- `created`
- `analyzing`
- `analyzed`
- `partial_failed`
- `failed`

### 11.3 知识资产状态

- `draft`
- `confirmed`
- `stale`
- `archived`

### 11.4 任务状态

- `queued`
- `running`
- `succeeded`
- `partial_failed`
- `failed`
- `cancelled`

适用任务包括仓库同步、代码分析、索引构建、上下文生成和变更影响分析。

### 11.5 索引状态

- `not_built`
- `building`
- `ready`
- `stale`
- `failed`

## 12. 错误处理

所有错误必须面向用户可理解，不能只返回技术栈异常。

| 场景 | 常见错误 | 处理方式 |
|---|---|---|
| 项目接入 | Git 不可访问、分支不存在、鉴权失败、路径非法、项目名重复 | 保留失败原因，允许重试，项目列表显示失败状态 |
| 代码分析 | 路径不存在、编码异常、单文件解析失败、文件过大、不支持语言 | 部分失败不清空成功结果，失败文件可查看 |
| 索引 | embedding 不可用、向量库不可用、metadata 缺失、写入失败 | 最近成功索引继续可用，显示数据新鲜度风险 |
| 检索 | 索引未构建、无可检索数据、向量库异常、未授权来源 | 返回明确空状态或权限错误，不能泄露未授权项目摘要 |
| 上下文包 | 召回为空、内容超长、引用失效、导出权限不足 | 允许手动添加上下文项，导出前再次校验权限 |
| 变更影响 | diff 非法、文件列表为空、分析基线缺失、无法定位符号 | 区分无法分析和未发现影响，说明不确定性来源 |

## 13. 非功能要求

### 13.1 安全

- 所有项目数据必须按 `projectId` 隔离。
- 所有项目接口必须做后端权限校验。
- 文件路径必须限制在项目工作区或配置的知识库路径内。
- 上下文包导出前必须重新校验权限。
- 上下文包导出前必须执行基础敏感信息过滤。
- 审计不保存密码、token、私钥或完整敏感内容。

### 13.2 可靠性

- 耗时操作必须任务化。
- 任务失败不应清空上一份可用结果。
- 任务必须记录失败原因、开始时间、结束时间和触发人。
- 重试任务必须生成新任务记录，不能覆盖历史失败原因。

### 13.3 性能

- 核心可用版面向中小型项目。
- 列表接口必须分页。
- 检索必须支持 topK 和来源过滤。
- 图谱和调用关系只返回裁剪后的子集。

### 13.4 可追溯

- 检索结果必须能追溯到源对象。
- 上下文包必须保存引用清单。
- 知识确认必须保存确认人和确认时间。
- 影响分析必须保存输入原文、解析结果和置信度。

### 13.5 可扩展

- 默认向量库为 Qdrant，向量库、embedding、重排模型应通过配置抽象保留替换能力。
- 内置 AI 问答必须复用可信检索和上下文包。
- commit、PR、MR 影响分析必须复用文件列表和 diff 分析能力。
- 跨项目检索必须在项目隔离模型之上扩展。

## 14. 核心可用版范围

核心可用版必须包含以下能力，但不要求在同一个迭代一次性全部完成。实现时应按第 17 章的依赖顺序交付，保证每个切片都有可演示、可验收的闭环。

1. 多项目管理、项目默认隔离、项目级角色、项目级授权和后端权限校验。
2. Git 地址创建项目、分支配置、默认路径配置、手动同步、同步失败状态和重试。
3. Java 结构分析、Spring endpoint、MyBatis SQL、前端 API 调用、基础调用关系、代码事实列表和详情。
4. 知识文档维护、结构化知识资产、轻量状态流转、标签、负责人、复审日期、证据绑定。
5. 代码、知识文档、知识资产、接口、SQL、调用链的统一索引、默认 Qdrant 写入、全量构建、索引状态和来源分布。
6. 自然语言可信检索、来源过滤、可信状态展示、证据展示、复制结果、加入上下文包、绑定为证据、生成知识资产草稿。
7. 任务类型和任务描述输入、候选上下文召回、勾选排除排序、Markdown 或纯文本上下文包、引用清单和导出审计。
8. 文件列表和 diff 输入、变更文件识别、直接命中代码事实、候选接口影响、候选知识影响、风险点、测试建议、置信度、不确定性说明和待复审候选。
9. 项目治理摘要、知识状态统计、缺证据知识、待复审知识、ReviewTask、低命中查询、分析失败、索引失败、上下文导出记录、影响分析记录和项目内审计查询。

### 14.1 首轮落地切片

首轮落地建议先做“项目内可信上下文闭环”，暂不把完整治理看板、复杂图谱、PR/MR、内置 AI 问答纳入同一实现目标。

首轮切片必须跑通：

1. 系统管理员创建项目，配置项目负责人和成员，项目数据按 `projectId` 隔离。
2. 项目成员同步仓库并触发代码分析，生成 Java/Spring/MyBatis/前端 API 基础 CodeFact。
3. 项目成员创建知识资产并绑定 CodeFact 或检索结果 Evidence，项目负责人确认知识。
4. 系统构建当前项目 Qdrant 索引，支持按来源和可信状态检索。
5. 用户基于可信检索结果生成 Markdown 或纯文本上下文包，并记录引用清单和导出审计。

首轮切片可以延后但不能废弃：

- 文件列表和 diff 影响分析。
- ReviewTask 和治理摘要。
- 低命中查询统计。
- 基础 JSON 图数据导出。

这样做的原因是上下文包闭环最能验证平台价值，也最依赖项目隔离、代码事实、知识证据和检索能力。变更影响和治理应在这些基础稳定后接入，否则会放大前置数据质量问题。

## 15. 增强版范围

增强版在核心闭环稳定后扩展：

- 跨项目检索、组织级知识地图、多项目治理看板。
- 细粒度权限矩阵、知识提交/驳回审批流、复审任务和复审提醒。
- 知识版本历史、质量评分、重复知识检测、冲突知识提示。
- 更完整调用图、跨语言调用关系、前后端参数级映射、MyBatis XML 深度解析。
- 独立 InterfaceAsset、参数级接口治理、接口负责人流转、错误码治理。
- 增量索引、失效记录删除、重排模型、混合关键词和向量排序。
- 检索结果收藏、检索组合查询增强、交互式图谱页面和子图导出。
- 内置 AI 问答、流式回答、多轮会话、模型配置、AI 调用审计。
- commit 分析、分支对比、PR/MR 集成、webhook、CI 自动触发、风险报告。
- 接口文档覆盖率、规范覆盖率、复审 SLA、组织级趋势报表。

## 16. 明确不做

近期不做：

- 自动修改代码。
- 自动提交代码。
- 自动判断知识真假。
- 把影响分析结果包装成确定结论。
- 企业计费和商业多租户。
- 复杂低代码流程引擎。
- 完整替代 IDE 或代码托管平台。

## 17. 核心可用版交付顺序

建议按以下顺序交付：

1. 多项目、角色、项目接入。
2. 代码事实和知识资产。
3. 统一索引和可信检索。
4. AI 上下文包。
5. 变更影响初判。
6. 治理摘要和审计。

原因是后续能力都依赖前面的数据基础。没有稳定项目隔离和代码事实，就难以做可信检索；没有可信检索和引用清单，就难以做上下文包；没有 CodeFact、Evidence 和 KnowledgeAsset 关系，就难以做影响分析。

## 18. 验收标准

### 18.1 项目与权限

- 系统管理员可以创建项目并指定负责人。
- 用户只能看到被授权项目。
- 项目负责人可以管理本项目成员。
- 未授权用户访问项目 API 返回 403。
- 系统管理员可以查看、保存并测试工作区、状态存储、默认 Qdrant、embedding、对象存储和路径策略配置。

### 18.2 代码事实

- 用户可以同步仓库并触发代码分析。
- 系统能产出文件、类、方法、接口、SQL、调用关系等代码事实。
- 单文件失败时任务为部分失败，成功事实仍可用。
- 分析结果绑定到明确仓库快照。
- 系统可以导出裁剪后的基础 JSON 图数据。

### 18.3 知识资产

- 成员可以创建草稿知识。
- 知识资产可以绑定代码事实或检索结果作为证据。
- 项目负责人可以确认、标记待复审、归档知识。
- 已确认知识记录确认人和确认时间。

### 18.4 统一索引与检索

- 系统可以构建当前项目索引。
- 检索必须带项目过滤。
- 检索结果展示来源、标题、分数、状态、证据数量和推荐原因。
- 已确认知识优先展示，待复审知识提示风险。
- 检索结果可以复制、加入上下文包、绑定为证据或生成知识资产草稿。
- 索引记录写入默认 Qdrant 时，payload 包含 `projectId`、`sourceType`、`sourceId`、`chunkId`、`status`、`filePath`、`title` 和 `updatedAt`。
- Qdrant 或 embedding 服务不可用时，系统提示失败原因，并保留最近成功索引。

### 18.5 AI 上下文包

- 用户可以输入任务描述并召回候选上下文。
- 用户可以勾选、排除、排序上下文项。
- 系统生成 Markdown 或纯文本上下文包。
- 上下文包保存引用清单。
- 导出上下文包记录审计。
- 导出前执行权限复检和基础敏感信息过滤。

### 18.6 变更影响

- 用户可以输入文件列表或 diff。
- 系统输出变更文件、命中代码事实、候选接口影响、候选知识影响、风险和测试建议。
- 结果标注置信度和不确定性。
- 命中已确认知识时生成待复审候选或 ReviewTask。
- 最终标记待复审必须由项目负责人确认。

### 18.7 治理与审计

- 项目负责人可以看到知识状态统计、缺证据知识、待复审知识、ReviewTask、低命中查询、索引失败和分析失败。
- 项目负责人可以处理或忽略 ReviewTask，处理和忽略都记录审计。
- 审计能查询项目创建、成员授权、知识确认、索引构建、上下文导出和影响分析记录。
- 审计记录包含操作人、时间、对象、动作和结果。
