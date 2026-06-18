# 代码智能与 Neo4j 图关系需求

## 功能目标

代码智能底座负责从项目代码中提取可复用的结构化事实，并将基础关系写入 Neo4j，供证据绑定、可信检索、上下文包和变更影响分析复用。

## 功能点

| 功能域 | 功能点 |
|---|---|
| 语言解析 | Java 解析，多语言扩展预留 |
| 结构提取 | package、import、类、接口、枚举、字段、方法、构造器、注解 |
| 框架识别 | Spring Controller/Service/Repository/Component、Mapper、前端 API 调用 |
| 接口识别 | HTTP method、URL path、Controller、handler、参数线索 |
| SQL 识别 | MyBatis 注解 SQL、Mapper 引用、表名、字段、数据访问线索 |
| 调用关系 | 类依赖、组件依赖、方法调用、接口到实现链路 |
| 代码图谱 | Neo4j 项目图写入、基础邻居查询、有限深度路径查询 |
| 分析报告 | Markdown 报告、JSON 结果、结构摘要、图数据导出 |
| 代码证据 | 文件、类、方法、接口、SQL、调用链节点可被引用为 Evidence |

## 核心对象

### CodeFact

代码事实。关键字段：

- `id`
- `projectId`
- `snapshotId`
- `factType`: `file | class | method | endpoint | sql | call | module | frontend_call`
- `filePath`
- `packageName`
- `symbolName`
- `signature`
- `startLine`
- `endLine`
- `metadata`

### Neo4j 图节点

核心节点类型：

- Project
- RepositorySnapshot
- File
- Class
- Method
- Endpoint
- SQL
- FrontendCall
- KnowledgeAsset
- Evidence
- IndexRecord
- ContextPackage
- ChangeTask
- ReviewTask

### Neo4j 图边

核心边类型：

- `HAS_SNAPSHOT`
- `CONTAINS`
- `DECLARES`
- `HAS_METHOD`
- `EXPOSES`
- `CALLS`
- `USES_SQL`
- `FRONTEND_CALLS`
- `EVIDENCES`
- `SUPPORTS`
- `INDEXES`
- `RETRIEVES`
- `INCLUDES`
- `IMPACTS`
- `REQUIRES_REVIEW`

## 代码事实生成闭环

```text
触发分析
  -> 创建分析任务
  -> 扫描文件
  -> 解析 Java/Spring/MyBatis/前端 API
  -> 生成 CodeFact
  -> 生成接口关系和调用关系
  -> 写入 MySQL
  -> 写入 Neo4j 图节点边
  -> 保存成功事实和失败文件
  -> 更新 RepositorySnapshot
  -> 写入 AuditEvent
```

单文件失败不导致整个任务失败。任务整体失败时保留上一版可用分析结果。Neo4j 写入失败时，任务应标记为 `partial_failed` 或 `failed`，并保留 MySQL 中已保存的 CodeFact 和失败原因，便于重试图数据写入。

## 证据有效性检查

代码分析完成后必须检查已绑定 Evidence 的有效性：

```text
新 RepositorySnapshot
  -> 对比 Evidence 的 filePath、symbolName、line range、signature
  -> 无法定位 Evidence
  -> 生成治理项或 ReviewTask
  -> 不自动改变 KnowledgeAsset 状态
```

## 图关系规则

- Neo4j 节点和边必须包含 `projectId`。
- 跨快照关系必须包含 `snapshotId` 或 commit 范围。
- 推断关系必须包含 `confidence` 和 `reason`。
- Neo4j 不替代 MySQL 事实状态存储，也不替代 Qdrant 语义检索。
- Qdrant 与 Neo4j 通过 `sourceId`、`targetId`、`projectId` 和 `snapshotId` 对齐。
- 核心版只要求基础邻居查询、有限深度路径查询和裁剪后 JSON 节点边导出。
- Cytoscape.js 交互式图谱、复杂路径探索、跨项目图谱和子图工作台属于增强版。

## 页面与操作

| 页面 | 主要操作 | 关键展示 |
|---|---|---|
| 代码事实 | 触发分析、查看事实列表、查看失败文件、导出图数据、绑定证据、加入上下文 | 结构摘要、endpoint、SQL、调用关系、图数据 |
| 接口关系 | 查看 endpoint、前端调用、匹配关系、接口风险 | endpoint、frontend call、匹配状态 |

## 验收标准

- 用户可以同步仓库并触发代码分析。
- 系统能产出文件、类、方法、接口、SQL、调用关系等代码事实。
- 系统能把基础代码关系、接口关系、证据关系和知识关系写入 Neo4j。
- 单文件失败时任务为部分失败，成功事实仍可用。
- 分析结果绑定到明确仓库快照。
- 系统可以从 Neo4j 查询并导出裁剪后的基础 JSON 图数据。
