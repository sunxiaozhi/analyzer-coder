# 代码智库系统技术选型文档

版本：v0.1  
日期：2026-07-19  
状态：初稿，待评审

## 1. 选型原则

1. 先满足 MVP 闭环，再考虑平台化扩展。
2. CodeGraph SQLite 作为结构化代码事实源，不直接替代向量库。
3. RAG 检索和 CodeGraph 查询分层实现，通过混合检索层融合。
4. 核心检索逻辑尽量自研，避免被单一 RAG 框架深度绑定。
5. 模型、向量库、LLM Provider 必须可替换。
6. 私有代码默认按敏感数据处理。

## 2. 推荐技术栈

### 2.1 后端

推荐：Java 17+ + Spring Boot 3.x

理由：

1. Java / Spring Boot 适合构建长期维护的企业级服务，工程化、配置管理、可观测性、权限集成和部署生态成熟。
2. 代码智库系统需要管理仓库、索引任务、结构化查询、问答会话和权限，整体更接近企业知识平台而不是单纯 AI 实验脚本。
3. Spring Data JDBC / JPA、JDBC SQLite、PostgreSQL、Redis、任务调度和安全组件都比较成熟。
4. Spring AI 和 LangChain4j 已经提供 Java 侧 LLM、Embedding、Vector Store、RAG 和 Tool Calling 抽象，可减少从零对接模型服务的成本。

备选：

1. Kotlin + Spring Boot：适合希望保留 JVM 生态，同时获得更简洁语法的团队。
2. Python 实验服务：适合后续独立验证 reranker、本地模型或数据处理算法，但不作为主后端技术栈。
3. Node.js + NestJS：适合团队主要技术栈为 TypeScript 的情况。

结论：

MVP 采用 Java 17+ + Spring Boot 3.x。Java 17 作为最低版本基线，运行时可按团队标准升级到 Java 21 或后续 LTS。RAG 和 CodeGraph 融合逻辑作为 Java 领域服务实现；如后续需要实验复杂 reranker 或本地模型工具链，可增加独立 Python 实验服务，但不作为主链路依赖。

### 2.2 结构化代码数据

推荐：CodeGraph SQLite + 适配层

使用方式：

1. CodeGraph 负责解析代码并生成 `.codegraph/` 下的 SQLite 数据。
2. 系统通过 `CodeGraphAdapter` 读取 SQLite。
3. 业务层只使用统一接口，不直接访问原始表结构。

适配层接口建议：

1. `get_symbol`
2. `search_symbols`
3. `get_symbol_source`
4. `get_callers`
5. `get_callees`
6. `get_file_symbols`
7. `get_related_symbols`

结论：

CodeGraph SQLite 只作为只读事实库。系统自身的业务数据、索引元数据和用户数据不写入 CodeGraph SQLite。

### 2.3 业务数据库

推荐：PostgreSQL

用途：

1. 仓库信息。
2. 索引任务。
3. chunk 元数据。
4. 问答记录。
5. 用户知识卡片。
6. 权限和审计扩展。

理由：

1. 关系数据表达能力强。
2. 易于和 pgvector 组合，减少 MVP 基础设施数量。
3. 适合从单机 MVP 演进到多人共享服务。

备选：

1. SQLite：适合纯本地单用户 MVP。
2. MySQL：团队已有 MySQL 基础设施时可选，但向量检索集成不如 Postgres + pgvector 简洁。

结论：

MVP 如果以本地验证为主，可用 SQLite 快速启动；产品化服务建议直接使用 PostgreSQL。

### 2.3.1 Java 数据访问

推荐：

1. Spring Data JDBC：用于业务表的常规 CRUD，模型简单、行为可控。
2. jOOQ：用于复杂 SQL、pgvector 相似度查询、统计查询和可维护的类型安全 SQL。
3. Flyway：用于数据库 schema 版本管理。
4. SQLite JDBC：用于只读访问 CodeGraph SQLite。

不优先推荐：

1. 将所有查询都放进 JPA/Hibernate。代码智库包含较多检索 SQL、图关系查询和向量相似度查询，过度 ORM 化会降低可控性。
2. 业务层直接拼接 SQL 字符串。CodeGraph schema 适配可以使用明确封装的 SQL，但应集中在 adapter 内部。

结论：

业务元数据优先用 Spring Data JDBC；复杂检索 SQL 用 jOOQ；数据库迁移用 Flyway；CodeGraph SQLite 通过独立 adapter 使用 SQLite JDBC 只读访问。

### 2.4 向量数据库

推荐默认：PostgreSQL + pgvector

理由：

1. 与业务元数据在同一个数据库中，开发和部署复杂度低。
2. 支持向量相似度检索，并保留 Postgres 的 JOIN、事务、备份和权限能力。
3. 对 MVP 和中等规模代码库足够实用。

备选方案：

1. Qdrant：适合需要更强混合检索、多向量、过滤和横向扩展的场景。
2. LanceDB：适合本地嵌入式、单机实验、多模态或轻量部署。
3. OpenAI Vector Store：适合完全托管的文件检索场景，但对自定义 CodeGraph 融合和私有部署控制较弱。

结论：

1. MVP 推荐 PostgreSQL + pgvector。
2. 如果首期要求纯本地无服务部署，可选 LanceDB。
3. 如果首期就要多仓库、大规模、多租户和高级混合检索，可选 Qdrant。

### 2.5 RAG 编排

推荐：自研轻量 Retrieval Pipeline，局部使用 Spring AI / LangChain4j

理由：

1. 本项目核心价值在于 CodeGraph 结构化检索与 RAG 的融合，标准 RAG 框架无法完整覆盖。
2. 自研 pipeline 可以精确控制 query routing、SQL 查询、图扩展、rerank 和引用格式。
3. Spring AI / LangChain4j 可以提供模型、embedding、向量库和 RAG 组件抽象，但不作为不可替换核心。

建议模块：

1. `QueryClassifier`
2. `VectorRetriever`
3. `SymbolRetriever`
4. `GraphRetriever`
5. `HybridMerger`
6. `Reranker`
7. `ContextBuilder`
8. `AnswerGenerator`

结论：

MVP 自研 pipeline。Java 侧优先采用 Spring AI 做模型和向量库适配；如果需要更丰富的 RAG 构件、query routing 或 embedding store 适配，可引入 LangChain4j 的局部组件。

### 2.6 Embedding 模型

推荐：

1. 云端默认：OpenAI `text-embedding-3-large`
2. 成本优先：OpenAI `text-embedding-3-small`
3. 私有化 / 本地优先：BGE-M3 或 Jina Embeddings 系列

选择依据：

1. 代码智库需要处理中英文问题、代码、注释和文档。
2. embedding 模型需要支持多语言和较好的语义检索能力。
3. 模型应通过配置切换，避免写死供应商。

结论：

MVP 使用可配置 embedding provider。默认云端方案可选 OpenAI，本地化方案保留接口。

### 2.7 LLM

推荐：

1. 默认使用 OpenAI Responses API，模型可配置。
2. 本地化部署可接入 vLLM / Ollama / OpenAI-compatible API。

LLM 不应承担：

1. 自行猜测调用链。
2. 编造没有检索证据的实现。
3. 替代 CodeGraph 做结构化关系判断。

结论：

LLM 只负责理解问题、总结证据和生成回答。结构事实来自检索层。

### 2.8 后台任务

MVP 推荐：Spring Task / Spring Scheduler + 数据库任务表

产品化推荐：Spring Batch + Redis / MQ

用途：

1. 仓库扫描。
2. CodeGraph 索引检测。
3. chunk 生成。
4. embedding 批处理。
5. 增量索引。

结论：

本地 MVP 可用数据库任务表配合 Spring Scheduler 轮询执行。生产环境建议引入 Spring Batch 管理长任务状态，并按规模接入 Redis、RabbitMQ、Kafka 或云消息队列。

### 2.9 前端

推荐：Vue 3 + Vite + TypeScript + Element Plus

核心组件：

1. Monaco Editor：代码片段展示。
2. Vue Flow / Cytoscape.js：调用图和依赖图。
3. Pinia：跨页面状态管理；简单服务端请求由独立 API 模块管理。
4. Element Plus：后台工具型界面的基础组件库。

结论：

前端应以工作台为主，不做营销式首页。第一屏直接进入仓库、检索和问答能力。

### 2.10 部署

MVP：

1. Docker Compose。
2. Backend。
3. Frontend。
4. PostgreSQL + pgvector。
5. Redis 可选。

后续：

1. Kubernetes。
2. 独立 worker。
3. 独立向量库。
4. 对接企业 SSO。

## 3. 推荐组合

### 3.1 MVP 默认组合

1. Backend：Java 17+ + Spring Boot 3.x
2. Worker：Spring Scheduler / Spring Batch
3. Business DB：PostgreSQL
4. Vector DB：pgvector
5. CodeGraph：本地 `.codegraph/` SQLite 只读
6. AI SDK：Spring AI，必要时局部引入 LangChain4j
7. Embedding：OpenAI 或本地 embedding provider
8. LLM：OpenAI-compatible provider
9. Frontend：Vue 3 + Vite + TypeScript + Element Plus
10. Code Viewer：Monaco Editor
11. Graph Viewer：Vue Flow 或 Cytoscape.js

### 3.2 本地单机组合

1. Backend：Java 17+ + Spring Boot
2. Business DB：SQLite
3. Vector DB：LanceDB
4. CodeGraph：本地 SQLite
5. LLM：Ollama / OpenAI-compatible
6. Frontend：Vue 3 + Vite + TypeScript

### 3.3 企业服务组合

1. Backend：Java 17+ + Spring Boot
2. Worker：Spring Batch + Redis / MQ
3. Business DB：PostgreSQL
4. Vector DB：Qdrant 或 pgvector
5. Object Storage：S3-compatible
6. Auth：OIDC / SSO
7. Observability：OpenTelemetry + Prometheus + Loki

## 4. 关键技术决策

### 4.1 为什么不把 CodeGraph 数据全部写入向量库？

因为调用边、符号定义、文件关系属于结构事实。向量库擅长相似度检索，不擅长精确表达多跳调用链和影响分析。正确方式是：

1. 代码文本进入 RAG 索引。
2. 结构关系留在 CodeGraph SQLite。
3. 检索时通过混合检索层合并。

### 4.2 为什么需要 chunk 绑定 symbol_id？

因为代码问答需要从语义结果回到结构图谱。chunk 如果只保存文本和文件路径，后续很难做调用链扩展。绑定 symbol_id 后可以做到：

1. 向量命中函数片段。
2. 根据 symbol_id 查询 callers / callees。
3. 扩展上下游上下文。
4. 输出更可信的影响分析。

### 4.3 为什么不直接使用通用 RAG 框架全权检索？

代码智库需要稳定、可审计、可调优的检索链路。完全 agentic 的方式延迟和行为不稳定。MVP 应采用固定 pipeline：

1. 问题分类。
2. 多路召回。
3. 图扩展。
4. 重排。
5. 构造上下文。
6. 生成回答。

复杂问题后续可以引入 agentic workflow，但核心事实检索仍由确定性 pipeline 控制。

### 4.4 Java 方案下 Spring AI 和 LangChain4j 如何分工？

推荐默认：

1. Spring AI：作为主集成层，负责 Chat Model、Embedding Model、Vector Store、ChatClient、Advisor 和 Spring Boot 自动配置。
2. LangChain4j：作为可选补充，用于更丰富的 RAG 构件、embedding store 适配、query transformation 或实验性检索能力。
3. 自研 Hybrid Retriever：负责 CodeGraph SQLite 查询、向量检索、关键词检索、图扩展、重排和引用组装。

结论：

不要把业务核心绑定到某个框架的 Agent 抽象。Java 框架用于工程化和 Provider 适配，代码智库的核心检索逻辑保持自有领域模型。

## 5. 参考资料

1. Spring Boot 官方文档：https://docs.spring.io/spring-boot/
2. Spring AI 官方文档：https://docs.spring.io/spring-ai/reference/
3. Spring AI RAG 文档：https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html
4. LangChain4j 官方文档：https://docs.langchain4j.dev/
5. pgvector 官方仓库：https://github.com/pgvector/pgvector
6. pgvector Java 支持：https://github.com/pgvector/pgvector-java
7. Qdrant Hybrid Search 文档：https://qdrant.tech/documentation/search/text-search/hybrid-search/
8. LanceDB 官方文档：https://docs.lancedb.com/
9. OpenAI embedding 模型文档：https://developers.openai.com/api/docs/models/text-embedding-3-large
