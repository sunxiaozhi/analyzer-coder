# 统一索引、可信检索与结果复用需求

## 功能目标

统一索引与可信检索负责把代码事实、知识文档、知识资产、接口、SQL 和调用链构建为可追溯的检索单元，并通过 Qdrant 提供项目内语义召回。SearchLog 负责记录低命中查询，为治理回流提供输入。

## 功能点

| 功能域 | 功能点 |
|---|---|
| 混合索引 | 代码 chunk、知识文档 chunk、知识资产 chunk、接口 chunk、调用链 chunk |
| 索引构建 | 全量构建、重建、stale 记录更新 |
| 索引运维 | 索引状态、来源分布、类型分布、记录查看、失败诊断 |
| 检索方式 | 自然语言检索、关键词检索、来源过滤、可信状态过滤 |
| 结果组织 | 直接命中、关联代码、关联知识、关联接口、关联调用链 |
| 可信标识 | 已确认、待复审、草稿、来源、更新时间、证据数量、分数 |
| 证据链 | 问题到知识到代码；接口到实现到 SQL；变更到影响资产 |
| 结果复用 | 复制结果、加入上下文包、生成知识资产草稿、绑定 Evidence |

## 核心对象

### IndexRecord

关键字段：

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

### SearchResult

关键字段：

- `projectId`
- `resultSnapshotId`
- `sourceType`
- `sourceId`
- `chunkId`
- `title`
- `snippet`
- `score`
- `status`
- `updatedAt`
- `evidenceCount`
- `explanation`

### SearchLog

关键字段：

- `id`
- `projectId`
- `queryHash`
- `querySummary`
- `filters`
- `topScore`
- `resultCount`
- `isLowHit`
- `usedForContext`
- `createdBy`
- `createdAt`

## 统一索引闭环

```text
触发索引
  -> 创建索引任务
  -> 读取 CodeFact、知识文档、知识资产、接口、SQL 和调用链
  -> 生成统一 chunk
  -> 写入 IndexRecord
  -> 写入 Qdrant
  -> 更新索引状态
  -> 写入 AuditEvent
```

新索引构建成功前不覆盖旧索引。embedding 或 Qdrant 失败时，最近成功索引继续可用，并展示数据新鲜度风险。

## 可信检索闭环

```text
输入问题
  -> 校验项目权限
  -> 强制带 projectId 过滤
  -> 查询 Qdrant
  -> 读取 metadata 和源对象
  -> 关联知识状态、证据和更新时间
  -> 记录 SearchLog 和可选 SearchResultSnapshot
  -> 返回可追溯 SearchResult
```

不允许只返回文本片段。每条结果必须能跳转到源对象。

## 检索结果复用闭环

```text
用户发起检索
  -> 生成 SearchLog 和 SearchResultSnapshot
  -> 用户复制、加入上下文包或生成知识资产草稿
  -> 后端使用 sourceType、sourceId、chunkId 或 resultSnapshotId 重新校验项目权限
  -> 创建 ContextPackage 引用或 KnowledgeAsset/Evidence
  -> 写入审计和必要的 Neo4j 关系
```

规则：

- SearchResult 可以是短期展示对象，但复用时必须能回溯到稳定源对象。
- 生成知识资产草稿时，应保存原始命中片段、源对象 ID、chunkId、检索问题和生成时间。
- 复制检索结果只记录脱敏审计，不保存用户复制后的外部用途。
- 低命中、无结果或用户反馈“不相关”的查询应进入 SearchLog。

## 低命中规则

- `resultCount = 0` 直接记为低命中。
- `topScore` 低于项目配置阈值时记为低命中。
- 用户手动反馈“不相关”时记为低命中。
- SearchLog 只保存脱敏 query 摘要和过滤条件，不保存敏感原文。

## 数据规则

- Qdrant payload 必须包含 `projectId`、`sourceType`、`sourceId`、`chunkId`、`status`、`filePath`、`title` 和 `updatedAt`。
- SearchLog 必须脱敏保存，不应记录密码、token、私钥或完整敏感请求体。
- 查询必须带项目过滤条件。
- 草稿知识默认不进入团队级上下文包，但作者可以显式选择。

## 页面与操作

| 页面 | 主要操作 | 关键展示 |
|---|---|---|
| 统一索引 | 构建索引、查看索引状态、来源分布、失败原因 | 状态、来源分布、记录列表 |
| 可信检索 | 输入问题、筛选来源、查看证据、复制结果、加入上下文包、绑定为证据、生成资产草稿 | 结果列表、分数、状态、证据链、解释 |

## 验收标准

- 系统可以构建当前项目索引。
- 检索必须带项目过滤。
- 检索结果展示来源、标题、分数、状态、证据数量和推荐原因。
- 已确认知识优先展示，待复审知识提示风险。
- 检索结果复用时，后端重新校验权限和源对象有效性。
- 低命中、无结果或用户反馈不相关的查询会生成脱敏 SearchLog。
- Qdrant 或 embedding 服务不可用时，系统提示失败原因，并保留最近成功索引。
