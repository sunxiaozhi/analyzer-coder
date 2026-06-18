# 架构、非功能与验收需求

## 总体架构

系统采用 Web 控制台、Flask API、分析引擎、状态存储、向量数据库、图数据库、对象存储和项目工作区分层架构。

```text
Vue Web Console
  -> Flask API
    -> Analyzer Service
      -> Java Analyzer / API Mapper / Call Graph / KB Loader
      -> State Store: MySQL
      -> Vector Store: Qdrant
      -> Graph Store: Neo4j
      -> Artifact Store: object storage or file service
      -> Project Workspace: .analzer_projects repositories and analysis workspace
```

## 存储分工

| 存储 | 用途 |
|---|---|
| MySQL | 用户、项目、授权、知识资产、模板、任务、审计、上下文包记录和分析记录 |
| Qdrant | 统一语义索引、metadata 过滤、相似度检索和检索运维 |
| Neo4j | 项目内 CodeFact、接口、SQL、Evidence、KnowledgeAsset、ContextPackage、ChangeTask 的基础关系 |
| 对象存储或文件服务 | 分析报告、导出文件、上下文包快照和大体积分析产物 |
| `.analzer_projects` | 项目仓库 clone/pull、分析工作目录和可再生成产物 |

MySQL 保存事实状态，Qdrant 负责语义召回，Neo4j 负责关系追踪和影响路径。三者通过 `projectId`、`snapshotId`、`sourceId`、`targetId` 对齐。

## 前端技术要求

- Vue 3、Vite、Element Plus。
- Composition API 和共享 composable 组织状态。
- Markdown 用于知识文档预览、上下文包预览和报告展示。
- 核心版只要求基础关系展示，不要求交互式图谱页面。
- 代码分析、向量索引、语义检索、上下文包和影响分析的结果状态应保持解耦。

## 后端技术要求

- Flask API 提供 Web API 和会话能力。
- API 路由负责请求解析、登录态获取和调用服务层。
- 业务逻辑放在服务层，避免路由直接操作文件、索引或数据库。
- 面向用户的 API 错误使用稳定错误模型。
- 所有项目级 API 必须做项目授权校验。
- 所有文件路径必须通过 workspace/project root 校验。

## 非功能需求

### 安全

- 所有项目数据必须按 `projectId` 隔离。
- 所有项目接口必须做后端权限校验。
- 文件路径必须限制在项目工作区或配置的知识库路径内。
- 上下文包导出前必须重新校验权限。
- 上下文包导出前必须执行基础敏感信息过滤。
- 审计不保存密码、token、私钥或完整敏感内容。

### 数据隔离

- Qdrant 查询必须带项目过滤条件。
- Neo4j 图节点和边必须包含 `projectId`，图查询必须带项目过滤条件。
- KnowledgeAsset、Evidence、ContextPackage、ChangeAnalysisTask、ReviewTask 必须包含 `projectId`。
- 跨项目检索默认禁止，增强版必须显式授权。

### 可靠性

- 耗时操作必须任务化。
- 任务失败不应清空上一份可用结果。
- 任务必须记录失败原因、开始时间、结束时间和触发人。
- 重试任务必须生成新任务记录，不能覆盖历史失败原因。
- 新索引构建成功前不覆盖旧索引。

### 性能

- 核心可用版面向中小型项目。
- 列表接口必须分页。
- 检索必须支持 topK 和来源过滤。
- Neo4j 图查询、图谱和调用关系接口只返回裁剪后的子集。

### 可观测性

- 分析、索引、上下文包和影响分析任务必须展示状态和失败原因。
- 系统配置应支持 MySQL、Qdrant、Neo4j、embedding 和对象存储连接测试。
- 治理看板展示失败任务、低命中查询和待复审项。

## 业务规则

- 普通用户不能查看、检索、导出或分析未授权项目。
- 管理员能力必须由后端校验，前端隐藏按钮不能作为权限控制。
- `.analzer_projects` 只保存项目仓库和可再生成产物，不保存业务状态事实来源。
- 知识资产从草稿进入已确认状态时，必须记录确认人和确认时间。
- 待复审知识进入检索结果或上下文包时必须展示风险提示。
- 草稿知识默认不进入团队级上下文包，作者显式选择除外。
- Qdrant 向量记录必须按项目隔离。
- Neo4j 图节点和边必须按项目与快照隔离。
- 变更影响分析必须标注置信度和不确定性。
- 上下文包导出必须记录审计事件，并保留引用来源。

## 验收用例

| 编号 | 场景 | 验收条件 |
|---|---|---|
| AC-01 | 未授权项目访问 | 普通用户访问未授权项目 API 时返回 403，前端不展示该项目数据 |
| AC-02 | 项目接入 | 管理员创建项目后，系统在 `.analzer_projects` 建立工作区，并在 MySQL 保存 Project |
| AC-03 | 代码分析部分失败 | 部分文件解析失败时，系统仍保存成功结果，并展示失败文件和原因 |
| AC-04 | 知识确认 | 项目负责人确认知识后，状态变为已确认，并记录确认人和确认时间 |
| AC-05 | 证据绑定 | 用户从检索结果绑定 Evidence 后，知识资产能展示来源、文件和符号信息 |
| AC-06 | Qdrant 索引 | 索引记录写入 Qdrant 时 payload 包含 projectId、sourceType、sourceId、status 和 updatedAt |
| AC-07 | Neo4j 图关系 | 代码分析后，系统把 CodeFact、接口、SQL、调用关系、Evidence 和 KnowledgeAsset 的基础关系写入 Neo4j，并能按 projectId 查询裁剪后的节点边 |
| AC-08 | 可信检索 | 检索结果按来源、分数和可信状态展示，已确认知识优先突出 |
| AC-09 | 待复审提示 | 待复审知识进入上下文包时，正文中出现风险标识和来源追溯 |
| AC-10 | 上下文导出 | 用户复制上下文包后，系统记录导出审计事件和引用清单 |
| AC-11 | diff 影响分析 | 输入 diff 后，系统输出影响代码、接口、知识资产、测试建议和置信度 |
| AC-12 | 影响不确定性 | 影响分析结果使用候选表达，不把结果描述为确定事实 |
| AC-13 | 治理看板 | 看板能展示待复审数量、索引状态、失败任务和低命中查询 |
| AC-14 | 配置失败 | Qdrant、Neo4j 或 embedding 服务不可用时，系统提示失败原因，并保留已有可用数据 |
| AC-15 | 知识变更同步 | 知识正文、状态或证据变化后，系统标记并更新 Qdrant 索引和 Neo4j 关系，检索结果展示最新状态 |
| AC-16 | ReviewTask 处理 | 项目负责人处理 ReviewTask 后，系统回写知识或证据状态，并记录审计 |
| AC-17 | 低命中治理 | 低命中或无结果查询生成脱敏 SearchLog，并能在治理看板中按项目查看 |
| AC-18 | 审计追踪 | 权限变更、知识确认、上下文导出和任务执行都能在审计记录中查询 |
