# 代码智库系统设计文档

版本：v0.1  
日期：2026-07-19  
状态：初稿，待评审

## 1. 设计目标

系统需要将 RAG 的语义检索能力和 CodeGraph 的结构化代码图谱能力结合起来，为代码问答、调用链查询、影响范围分析和知识沉淀提供统一服务。

设计重点：

1. 检索结果可追溯到文件、符号和行号。
2. CodeGraph schema 通过适配层隔离。
3. RAG 和 CodeGraph 独立演进，通过 Hybrid Retriever 融合。
4. 支持从单机 MVP 演进到多人共享服务。

## 2. 总体架构

```text
                 +----------------------+
                 |      Web Frontend    |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 |      API Server      |
                 | Spring Boot / REST   |
                 +----------+-----------+
                            |
        +-------------------+-------------------+
        |                   |                   |
        v                   v                   v
+---------------+   +---------------+   +---------------+
| Query Service |   | Repo Service  |   | Index Service |
+-------+-------+   +-------+-------+   +-------+-------+
        |                   |                   |
        v                   v                   v
+---------------+   +---------------+   +---------------+
| Hybrid        |   | PostgreSQL    |   | Spring Batch  |
| Retriever     |   | Business DB   |   | Index Jobs    |
+-------+-------+   +---------------+   +-------+-------+
        |                                       |
        +-------------------+-------------------+
                            |
        +-------------------+-------------------+
        |                   |                   |
        v                   v                   v
+---------------+   +---------------+   +---------------+
| Vector Store  |   | CodeGraph     |   | LLM /         |
| pgvector      |   | SQLite        |   | Embedding API |
+---------------+   +---------------+   +---------------+
```

## 3. 核心模块

### 3.0 技术基线

后端采用 Java 17+ + Spring Boot 3.x。Java 17 作为最低版本基线，运行时可按团队标准升级到 Java 21 或后续 LTS。主要工程组件：

1. Spring Web：REST API。
2. Spring Data JDBC：业务表数据访问。
3. jOOQ：复杂 SQL、pgvector 查询和统计查询。
4. Flyway：数据库迁移。
5. SQLite JDBC：只读访问 CodeGraph SQLite。
6. Spring AI：LLM、embedding 和 vector store provider 适配。
7. Spring Scheduler / Spring Batch：索引任务调度和长任务管理。

### 3.1 Repo Service

职责：

1. 管理仓库元数据。
2. 校验仓库路径。
3. 获取 Git commit、branch、文件列表。
4. 记录索引状态。

核心接口：

1. `create_repository(path, name)`
2. `get_repository(repo_id)`
3. `list_repositories()`
4. `delete_repository(repo_id)`
5. `get_index_status(repo_id)`

### 3.2 CodeGraph Port / Adapter

职责：

1. 读取 `.codegraph/` SQLite。
2. 隔离 CodeGraph 原始 schema。
3. 提供稳定的结构化代码查询接口。

当前领域端口：

```java
public interface CodeGraphPort {
    Optional<CodeSymbol> getSymbol(CodeRepositoryId repositoryId, String symbolId);
    List<CodeSymbol> searchSymbols(CodeRepositoryId repositoryId, String query, int limit);
    List<CodeGraphEdge> getCallers(CodeRepositoryId repositoryId, String symbolId, int depth);
    List<CodeGraphEdge> getCallees(CodeRepositoryId repositoryId, String symbolId, int depth);
}
```

当前基础设施实现类为 `SqliteCodeGraphAdapter`。后续需要在该端口上补齐 `getFileSymbols`、`getSymbolSource`、`getRelatedSymbols` 等能力。

返回对象应统一为领域模型：

```java
public record CodeSymbol(
    CodeRepositoryId repositoryId,
    String symbolId,
    String name,
    String kind,
    String filePath,
    int startLine,
    int endLine,
    String language
) {}
```

### 3.3 Index Service

职责：

1. 触发全量索引。
2. 触发增量索引。
3. 调用 CodeGraph Adapter 获取符号边界。
4. 生成 chunks。
5. 调用 embedding provider。
6. 写入向量库和业务数据库。

索引阶段：

```text
detect_repo
  -> detect_codegraph
  -> read_symbols
  -> chunk_code_by_symbol
  -> chunk_docs
  -> generate_embeddings
  -> upsert_vector_store
  -> mark_index_ready
```

### 3.4 Chunker

职责：

1. 对源代码按符号切分。
2. 对文档按标题和段落切分。
3. 处理超长函数，进行子 chunk 切分。
4. 保留上下文窗口，例如函数签名、类名、模块路径。

chunk 类型：

1. `symbol`
2. `file_summary`
3. `doc_section`
4. `test_case`
5. `config`
6. `knowledge_card`

chunk 元数据：

```json
{
  "chunk_id": "uuid",
  "repo_id": "repo_001",
  "commit_sha": "abc123",
  "file_path": "src/auth/token.ts",
  "symbol_id": "sym_001",
  "symbol_name": "validateToken",
  "symbol_kind": "function",
  "language": "typescript",
  "start_line": 10,
  "end_line": 48,
  "chunk_type": "symbol"
}
```

### 3.5 Hybrid Retriever

职责：

1. 根据问题选择检索策略。
2. 执行多路召回。
3. 使用 CodeGraph 扩展结构上下文。
4. 合并、去重、排序结果。

检索子模块：

1. `VectorRetriever`：向量语义检索。
2. `KeywordRetriever`：关键词、文件名、符号名匹配。
3. `SymbolRetriever`：CodeGraph 符号搜索。
4. `GraphRetriever`：caller、callee、related symbols 查询。
5. `Reranker`：对候选上下文重新排序。

问题路由：

```text
where_is_feature
  -> vector + keyword + symbol

explain_symbol
  -> symbol + source + vector

call_chain
  -> symbol + graph

impact_analysis
  -> symbol + callers + callees + related tests

architecture_summary
  -> vector + file summaries + graph aggregation
```

融合策略：

1. 每条候选结果归一化为 `RetrievalCandidate`。
2. 使用 `repo_id + file_path + start_line + end_line + symbol_id` 去重。
3. 分数由语义相似度、关键词匹配、图距离、符号精确匹配共同决定。
4. 结构化问题中提高 CodeGraph 结果权重。
5. 功能定位问题中提高向量和关键词结果权重。

候选对象：

```java
public record RetrievalCandidate(
    String sourceType,
    UUID repoId,
    String filePath,
    String symbolId,
    String symbolName,
    Integer startLine,
    Integer endLine,
    String text,
    double score,
    Map<String, Object> evidence
) {}
```

### 3.6 Context Builder

职责：

1. 控制上下文 token 数。
2. 保留必要代码片段。
3. 插入调用链摘要。
4. 保留引用 ID。
5. 避免重复上下文。

上下文格式：

```text
[source: S1]
file: src/auth/token.ts
symbol: validateToken
lines: 10-48
content:
...

[source: S2]
file: src/auth/middleware.ts
symbol: authMiddleware
lines: 20-64
content:
...

[graph]
validateToken <- authMiddleware <- routeGuard
```

### 3.7 Answer Service

职责：

1. 调用 LLM。
2. 要求答案基于 sources。
3. 输出引用。
4. 对证据不足的问题给出不确定性说明。

回答约束：

1. 不允许引用未检索到的文件。
2. 不允许编造函数名。
3. 必须区分“代码证据”和“推断”。
4. 影响分析应标注调用深度和范围限制。

## 4. 数据模型

### 4.1 repositories

```sql
CREATE TABLE repositories (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  path TEXT NOT NULL,
  default_branch TEXT,
  current_commit TEXT,
  codegraph_path TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
```

### 4.2 index_jobs

```sql
CREATE TABLE index_jobs (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id),
  job_type TEXT NOT NULL,
  status TEXT NOT NULL,
  current_step TEXT,
  error_message TEXT,
  started_at TIMESTAMP,
  finished_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL
);
```

### 4.3 code_chunks

```sql
CREATE TABLE code_chunks (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id),
  commit_sha TEXT NOT NULL,
  file_path TEXT NOT NULL,
  symbol_id TEXT,
  symbol_name TEXT,
  symbol_kind TEXT,
  language TEXT,
  chunk_type TEXT NOT NULL,
  start_line INT,
  end_line INT,
  content TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);
```

### 4.4 chunk_embeddings

pgvector 方案：

```sql
CREATE TABLE chunk_embeddings (
  chunk_id UUID PRIMARY KEY REFERENCES code_chunks(id),
  embedding_model TEXT NOT NULL,
  embedding vector,
  created_at TIMESTAMP NOT NULL
);
```

实际维度应由 embedding 模型决定，建表时固定。

### 4.5 qa_sessions

```sql
CREATE TABLE qa_sessions (
  id UUID PRIMARY KEY,
  repo_id UUID REFERENCES repositories(id),
  title TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
```

### 4.6 qa_messages

```sql
CREATE TABLE qa_messages (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES qa_sessions(id),
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  citations JSONB,
  retrieval_trace JSONB,
  created_at TIMESTAMP NOT NULL
);
```

### 4.7 knowledge_cards

```sql
CREATE TABLE knowledge_cards (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id),
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  source_message_id UUID,
  tags TEXT[],
  status TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
```

## 5. API 设计

### 5.1 仓库接口

```http
POST /api/repositories
GET  /api/repositories
GET  /api/repositories/{repo_id}
DELETE /api/repositories/{repo_id}
```

### 5.2 索引接口

```http
POST /api/repositories/{repo_id}/index
GET  /api/repositories/{repo_id}/index/status
GET  /api/index-jobs/{job_id}
```

### 5.3 问答接口

```http
POST /api/qa/sessions
GET  /api/qa/sessions/{session_id}
POST /api/qa/sessions/{session_id}/messages
```

请求：

```json
{
  "repo_id": "repo_001",
  "question": "validateToken 被哪些地方调用？",
  "options": {
    "max_depth": 2,
    "include_tests": true
  }
}
```

响应：

```json
{
  "answer": "...",
  "citations": [
    {
      "source_id": "S1",
      "file_path": "src/auth/token.ts",
      "symbol_name": "validateToken",
      "start_line": 10,
      "end_line": 48
    }
  ],
  "retrieval_trace": {
    "query_type": "call_chain",
    "vector_hits": 3,
    "symbol_hits": 1,
    "graph_hits": 8
  }
}
```

### 5.4 图查询接口

```http
GET /api/repositories/{repo_id}/symbols/search?q=validateToken
GET /api/repositories/{repo_id}/symbols/{symbol_id}/callers?depth=2
GET /api/repositories/{repo_id}/symbols/{symbol_id}/callees?depth=2
GET /api/repositories/{repo_id}/symbols/{symbol_id}/impact?depth=3
```

## 6. 关键流程

### 6.1 仓库导入

```text
用户输入 repo path
  -> 校验路径
  -> 读取 Git 信息
  -> 检测 .codegraph/
  -> 创建 repositories 记录
  -> 返回仓库详情
```

### 6.2 全量索引

```text
创建 index_job
  -> 扫描文件
  -> 读取 CodeGraph symbols
  -> 按 symbol 生成 chunks
  -> 对文档生成 chunks
  -> 写入 code_chunks
  -> 批量生成 embeddings
  -> 写入 chunk_embeddings
  -> 更新仓库索引状态
```

### 6.3 问答

```text
用户提问
  -> QueryClassifier 判断问题类型
  -> HybridRetriever 多路召回
  -> GraphRetriever 扩展调用关系
  -> Reranker 排序
  -> ContextBuilder 构造上下文
  -> AnswerService 调用 LLM
  -> 返回答案、引用、检索轨迹
```

### 6.4 影响分析

```text
用户输入 symbol 或自然语言
  -> SymbolRetriever 定位目标符号
  -> GraphRetriever 查询 callers / callees
  -> 查找相关测试和文档
  -> 按模块聚合
  -> 输出影响范围、风险点、建议验证项
```

## 7. 检索评分设计

初始分数：

```text
final_score =
  0.40 * vector_score +
  0.25 * keyword_score +
  0.25 * graph_score +
  0.10 * freshness_score
```

按问题类型调整：

1. 调用链问题：提高 `graph_score`。
2. 功能定位问题：提高 `vector_score` 和 `keyword_score`。
3. 符号解释问题：提高 `symbol exact match`。
4. 影响分析问题：提高 callers/callees 覆盖度。

图距离评分：

```text
direct edge: 1.0
depth 2: 0.7
depth 3: 0.45
depth > 3: 默认不进入上下文，只进入摘要
```

## 8. 增量索引设计

增量索引依据：

1. Git diff。
2. 文件 content hash。
3. CodeGraph 索引更新时间。
4. chunk content hash。

流程：

```text
获取 changed files
  -> 删除旧 chunks
  -> 重新读取相关 symbols
  -> 生成新 chunks
  -> 更新 embeddings
  -> 更新索引状态
```

注意：

1. 如果 CodeGraph 没有增量能力，系统应提示需要重新生成 CodeGraph 索引。
2. 如果调用关系变化，应重新计算相关 symbol 的图缓存。

## 9. 安全设计

### 9.1 路径安全

1. 仓库路径必须在允许范围内。
2. 禁止通过 API 读取任意系统路径。
3. 排除密钥、证书、环境变量文件。

### 9.2 LLM 数据安全

1. 外部 LLM 调用前应只发送必要上下文。
2. 支持配置最大上下文行数。
3. 支持关闭外部 LLM。
4. 后续支持敏感信息扫描和脱敏。

### 9.3 权限

MVP 可先不做多用户权限。企业版需要：

1. 用户登录。
2. 仓库访问权限。
3. 问答记录隔离。
4. 审计日志。

## 10. 可观测性设计

记录以下日志和指标：

1. 索引任务耗时。
2. 每阶段处理文件数、chunk 数。
3. embedding 调用耗时和失败率。
4. 检索命中来源。
5. LLM 调用耗时和 token 使用。
6. 用户问题类型分布。
7. 无答案率和低置信度率。

## 11. 前端设计

技术基线：Vue 3 + Vite + TypeScript + Element Plus，使用 Vue Router 管理路由、Pinia 管理跨页面状态。调用图后续采用 Vue Flow 或 Cytoscape.js，代码引用预览采用 Monaco Editor。

### 11.1 页面

1. 仓库管理页。
2. 索引任务页。
3. 代码问答页。
4. 符号搜索页。
5. 调用图 / 影响分析页。
6. 知识卡片页。

### 11.2 问答页布局

```text
+------------------------------------------------+
| repo selector | search / ask input              |
+-------------------------+----------------------+
| conversation            | citations            |
|                         | code preview         |
|                         | graph preview        |
+-------------------------+----------------------+
```

### 11.3 引用交互

1. 点击引用打开代码片段。
2. 代码片段高亮起止行。
3. 点击符号可以打开调用图。
4. 调用图节点可以继续展开 caller / callee。

## 12. 目录结构建议

```text
pom.xml

backend/
  pom.xml
  src/
    main/
      java/
        com/analyzercoder/
          application/
            rag/
            repository/
          config/
          domain/
            codegraph/
            rag/
            repository/
          infrastructure/
            codegraph/
            rag/
            repository/
          interfaces/
            rest/
          worker/
      resources/
        application.yml
        db/migration/
    test/
      java/

frontend/
  src/
    pages/
    components/
    api/
    features/
    stores/

docs/
  01-requirements.md
  02-tech-selection.md
  03-design.md
```

## 13. MVP 实施计划

### 阶段 1：基础骨架

1. Spring Boot 项目初始化。
2. PostgreSQL schema。
3. 仓库导入 API。
4. 索引任务表。

### 阶段 2：CodeGraph 接入

1. 检测 `.codegraph/`。
2. 实现 `SqliteCodeGraphAdapter`。
3. 支持 symbol search。
4. 支持 caller / callee 查询。

### 阶段 3：RAG 索引

1. chunk 数据模型。
2. symbol chunker。
3. doc chunker。
4. embedding provider。
5. pgvector 写入和查询。

### 阶段 4：混合检索和问答

1. QueryClassifier。
2. HybridRetriever。
3. ContextBuilder。
4. AnswerService。
5. 引用格式。

### 阶段 5：前端工作台

1. 仓库列表。
2. 索引状态。
3. 问答页面。
4. 代码引用预览。
5. 基础调用图。

## 14. 风险和应对

### 14.1 CodeGraph schema 不稳定

应对：

1. 所有访问走 adapter。
2. 为 adapter 写集成测试。
3. 记录 CodeGraph 版本。

### 14.2 RAG 答案幻觉

应对：

1. 强制引用。
2. 检索不足时不回答或低置信度回答。
3. 提示词要求区分证据和推断。

### 14.3 索引成本高

应对：

1. content hash 去重。
2. embedding 批处理。
3. 增量索引。
4. 跳过生成文件和依赖目录。

### 14.4 私有代码泄露

应对：

1. 默认私有部署。
2. 外部 LLM 可关闭。
3. 敏感文件排除。
4. 后续增加脱敏和审计。

## 15. 待设计深化项

1. CodeGraph SQLite 实际 schema 适配。
2. 支持的语言列表和符号粒度。
3. embedding 模型维度和成本评估。
4. reranker 是否使用独立模型。
5. 多仓库跨仓检索策略。
6. 权限模型。
7. 知识卡片审核流程。
