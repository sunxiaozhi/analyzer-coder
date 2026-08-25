# 代码智库系统技术选型基线

版本：v1.1
日期：2026-07-31
状态：按当前实现校准
实现基线：1.0.0 发布候选工作区

> 本文只描述当前已经进入主链路的技术。候选方案和未来演进单列说明，不得据此判断某项能力已经交付。

## 1. 当前技术栈总览

| 层次 | 当前实现 | 说明 |
| --- | --- | --- |
| 后端运行时 | Java 17、Spring Boot 3.5 | 单体服务，REST API 与后台任务同进程 |
| Web | Spring MVC、Bean Validation、Actuator | Cookie 会话，自定义权限校验 |
| 数据访问 | MyBatis、Mapper XML、PageHelper | PostgreSQL 方言，复杂查询使用显式 SQL |
| 数据库 | PostgreSQL 17、pgvector | 业务数据、任务、图谱投影和可变维度向量统一存储 |
| 迁移 | Flyway | 当前迁移基线为 `V1__baseline.sql` |
| 后台任务 | Spring Scheduler + 数据库任务表 | 无 Redis、MQ、Spring Batch 运行依赖 |
| Git/图谱 | Git CLI、CodeGraph CLI、`ProcessBuilder` | CodeGraph 为可选外部命令，产物登记后再发布 |
| LLM/Embedding | JDK `HttpClient` + OpenAI-compatible HTTP | 未引入 Spring AI 或 LangChain4j |
| 前端 | Vue 3、Vite、TypeScript、Element Plus、Pinia | Composition API 与 `<script setup>` |
| 内容处理 | 后端 CommonMark + OWASP Sanitizer；前端 `marked` + DOMPurify + highlight.js | 知识正文服务端安全渲染，文件预览前端消毒；代码预览非 Monaco |
| 图展示 | 当前项目自有图谱视图 | 未引入 Vue Flow/Cytoscape.js |
| 部署 | 开发 Compose；生产 Compose + Dockerfile；systemd/Nginx 模板 | 支持容器化或传统 Linux 主机部署 |

## 2. 后端选型

### 2.1 Java 17 与 Spring Boot 3.5

当前服务承载账号、仓库、索引任务、检索、问答、知识卡片和系统配置。Spring Boot 提供配置、事务、定时任务、HTTP、校验与健康检查；没有拆分独立 AI 服务或 worker 服务。

约束：

1. Controller 负责协议适配和输入校验，业务编排位于 application/service 层。
2. 当前代码采用轻量分层，不宣称已经严格实现完整六边形架构。
3. 外部 Git、CodeGraph 和模型调用均在服务端执行，浏览器不直接持有仓库凭据或模型密钥。

### 2.2 MyBatis、PageHelper 与 PostgreSQL

业务库固定为 PostgreSQL，使用 MyBatis Mapper 接口与 XML。PageHelper 只用于普通页码列表；Top-K 向量检索、任务领取、锁与图谱查询使用显式 SQL。

分页契约：

- `pageNum` 从 1 开始；`pageSize` 由接口限制最大值。
- 返回业务自己的分页 DTO，不向前端暴露 PageHelper 类型。
- 排序字段由服务端白名单映射，不接受用户 SQL 表达式。
- 权限条件必须进入数据库查询或在服务层强制校验，不依赖前端隐藏按钮。

当前不使用 JPA、MyBatis-Plus、jOOQ、Redis 或第二套业务数据库。

### 2.3 Flyway

Flyway 默认启用。当前迁移已压缩为单一 `V1__init_schema.sql`，直接创建包含多轮问答、可变维度向量、项目资产类型、约束、索引和初始配置的完整结构。该基线面向空库安装；已经执行过旧版 V3/V4/V5 的数据库不能直接复用其 Flyway 历史，必须先备份并按部署策略重建或迁移。

### 2.4 知识正文与附件

知识正文以 Markdown 为可编辑事实格式。后端使用 CommonMark（表格、删除线扩展）生成 HTML，并由 OWASP Java HTML Sanitizer 按白名单消毒；前端预览使用 `marked` 后再次经 DOMPurify 消毒。

附件采用受管文件系统的 SHA-256 对象路径，PostgreSQL 保存元数据与修订引用。当前校验扩展名、大小和部分文件魔数：图片单文件不超过 10 MiB，其他附件不超过 50 MiB，每个修订最多 20 个且合计不超过 200 MiB。当前没有 Apache Tika、ClamAV、S3 或附件正文抽取，附件正文不进入检索与模型上下文。

### 2.5 后台任务

任务采用数据库表持久化状态，由 Spring Scheduler 轮询并领取。该方案覆盖仓库准备、同步、内容索引、向量处理和 CodeGraph 构建等当前任务，不依赖 Redis、RabbitMQ、Kafka 或 Spring Batch。

任务状态是恢复和审计依据；进程重启后通过数据库状态继续处理或收敛遗留任务。

## 3. 代码、图谱与检索

### 3.1 仓库快照

平台把可分析版本发布到受管目录。远程 Git、GitLab 和 ZIP 先进入 staging，校验成功后原子发布；本地 Git 原目录只作为输入，不由删除流程清理。

### 3.2 CodeGraph

CodeGraph CLI 是可选依赖。后端为当前仓库版本创建独立分析副本，执行命令并校验 `.codegraph` 产物，再把可查询结果投影/登记到系统数据中。当前实现不是由业务代码长期直连一个外部 CodeGraph SQLite 作为唯一事实库。

未安装 CodeGraph 时，仓库、内容索引、源码检索、问答和知识卡片仍可使用；图分析不可用。

### 3.3 内容切片与 pgvector

代码切片、元数据和向量均存入 PostgreSQL。向量列支持按模型保存不同维度，检索时同时匹配模型标识和维度：

- 路径、符号和正文关键词召回；
- 余弦/向量相似度召回；
- 合并、去重和 Top-K；
- 按仓库与当前索引版本隔离。

不同索引任务的中间产物不是用户查询维度；面向用户只认仓库当前发布版本。历史任务用于任务审计，不在“当前向量索引”页混合展示。

### 3.4 向量模型

当前支持：

- `LOCAL_HASH`：本地确定性 64 维向量；
- OpenAI-compatible `/embeddings`：支持 1–4096 维，检测必须确认返回长度等于备案维度。

向量记录保存模型标识和版本关联。切换模型后不能把旧模型向量冒充为新模型结果；系统按当前实现触发重建/按需刷新。

## 4. 模型调用

问答模型由系统设置备案，包含地址、模型标识、密钥和生成参数。实现直接通过 JDK `HttpClient` 调用 OpenAI-compatible 接口，没有 Spring AI、LangChain4j 或 Responses API SDK 依赖。

模型调用由知识问答页面显式选择：

1. 管理员备案模型并完成连接检测；
2. READ 用户在知识问答页面选择检测可用的模型；
3. 问答请求携带模型配置 ID，后端按该配置调用。

未选择模型或模型不可用时，接口明确拒绝请求。检索证据中的凭据、访问密钥和私钥先脱敏，超长 Prompt 在安全上限内截断；模型运行失败时返回本地证据型回答并标注降级信息，不伪装成模型回答。

模型密钥单独加密/脱敏存储，列表接口不回显明文。当前支持单仓、一问一答历史恢复；未实现多轮上下文、模型流式回答、停止生成或断线续传。

## 5. 前端选型

Vue 3 + TypeScript + Vite 是唯一当前前端栈，Element Plus 提供基础控件，Pinia 管理登录态、当前仓库和工作区页签。

实现约束：

- 使用 Composition API 与 `<script setup>`；
- 路由视图通过 `WorkspaceShell` 统一承载；
- 当前仓库偏好保存到服务端，工作区页签保存在浏览器 `localStorage`；
- 动态工作区使用 `KeepAlive`，最多缓存 12 个页面；
- Markdown 使用 `marked` 解析并经 DOMPurify 消毒；
- 代码高亮使用 highlight.js；
- `.bat`/`.cmd` 预览由后端提供 GB18030 回退解码；
- 当前不使用 Monaco、Vue Flow、Cytoscape.js。

## 6. 安全基线

- 会话 Cookie 为 HttpOnly、SameSite=Lax；生产环境通过 `APP_SESSION_COOKIE_SECURE=true` 设置 Secure，并通过受信反向代理解析 Forwarded 头。
- 修改请求校验 CSRF。
- 密码散列、登录失败计数、验证码、会话撤销均由后端负责。
- 本地仓库路径必须位于允许根目录；远程地址受协议和网络边界校验。
- ZIP 导入限制路径穿越、数量、大小和压缩比。
- 源码预览拒绝目录、越界路径、NUL/二进制内容和超过 2 MiB 的文件。
- 问答模型由每次请求显式选择；发送前对代码证据中的凭据和私钥执行脱敏。

## 7. 部署基线

根目录 `compose.yaml` 用于本地 PostgreSQL，数据库端口只绑定 `127.0.0.1` 且密码必须由 `.env` 提供。`compose.prod.yaml`、前后端 Dockerfile、Nginx、systemd 和环境模板构成 Linux 发布基线；HTTPS 证书、备份、集中密钥管理、日志保留和监控告警仍由部署环境负责。

## 8. 未采用或尚未交付

下列方案可以作为后续评估项，但不属于当前实现：

- Spring AI、LangChain4j；
- Redis、消息队列、Spring Batch、独立 worker；
- Qdrant、LanceDB、OpenAI Vector Store；
- SQLite 业务库；
- S3、ClamAV、Apache Tika；
- Monaco、Vue Flow、Cytoscape.js；
- OIDC/SSO、OpenTelemetry、Kubernetes；
- 流式多轮问答、reranker 与 agentic workflow。

引入任何一项都应先更新需求、数据边界、故障降级、安全评审和验收标准，不得只修改技术选型文档。

## 9. 当前选择结论

当前系统是一套以 PostgreSQL 为统一持久化、以数据库任务驱动异步处理、以自研检索和 OpenAI-compatible HTTP 适配完成模型能力的 Java/Vue 单体应用。这个组合已经覆盖现阶段闭环；下一阶段优先修复安全与权限缺口，再评估基础设施拆分，而不是提前引入未被规模证明需要的组件。
