# 纯知识库项目最小改造设计

日期：2026-10-11。需求：[纯知识库项目最小改造需求](knowledge-only-requirements.md)。

## 设计决策

复用 repositories 项目、repository_branches 工作区身份、知识卡、修订、权限、附件、向量与问答历史。增加来源枚举 KNOWLEDGE，表示不含代码的项目。保留内部固定 WORKSPACE 身份以复用知识归属外键，用户不操作分支。不初始化 Git、不伪造 commit 或代码 content_version、不执行扫描和代码索引。

repositories.path 指向受管项目数据位置，供现有存储模型和清理机制复用；并非源码路径。codegraph_path 仅保留兼容字段，纯知识库不创建相应产物。代码专属功能必须显式拒绝 KNOWLEDGE。

## 数据库变更

只调整 branch_read_contexts.content_version：从 NOT NULL 改为允许 NULL。新增增量脚本 V2，不修改既有 V1 的校验和。source_type 是 text，可直接保存 KNOWLEDGE，无需新增类型列或项目表。

repository_branches.content_version、commit_sha、content_path 已允许 NULL。纯知识工作区保持这些字段为空，状态 READY；知识卡 branch_id 及相关外键继续保留。知识分支验证记录要求代码版本，纯知识库不产生该记录。

上下文恢复使用 IS NOT DISTINCT FROM 比较版本；只有 KNOWLEDGE 可创建无代码版本的上下文，代码项目仍要求版本与内容路径。所有上下文继续校验账号、项目、活动工作区和有效期，并固定当时已发布知识的修订。

## 创建与业务接口

- 新增 POST /api/repositories/knowledge-projects，输入 name、description。认证用户成为所有者，校验重名，事务内保存正式项目及 READY 的固定工作区，返回既有 RepositoryResponse。
- 现有项目响应 sourceType=KNOWLEDGE，关闭代码索引、图谱与代码凭据能力。项目资料编辑不允许修改知识工作区名称。
- 沿用知识 CRUD、附件、历史、发布、检索和问答 API。工作区上下文自动选择，前端不暴露分支概念。
- 纯知识发布校验权限和 expectedRevision、记录审核发布，跳过代码引用和版本验证。禁止提交代码引用，知识范围固定项目共享。
- 发布/编辑/撤回后沿用上下文刷新机制，防止新的修订被旧上下文误读。知识向量继续按卡片修订生成。

## 检索与问答

知识检索继续使用工作区范围及上下文固定修订；KNOWLEDGE 跳过代码分支验证条件，仅接受已发布审核知识。纯知识检索跳过代码关键词、代码向量和调用关系通道，诊断不把无代码索引视为降级。问答沿用证据、引用校验、模型选择与历史；无证据文案按类型显示。

## 界面适配

现有创建表单增加知识库选项，不要求或提交代码字段。创建后选择正式项目并跳转知识管理。项目管理、设置、公共项目栏和总览展示知识库用途；隐藏分支与代码准备操作。知识页隐藏 Markdown 来源、分支范围和验证、代码引用及失效提示。检索、问答不要求内容索引。导航隐藏图谱，直接进入代码页提供知识库导航。沿用现有布局、字体与配色，只改变与用途有关的控件与文案。

## 测试计划与实施记录

后端验证无代码项目创建、无版本上下文、知识发布与检索过滤、权限、非法代码操作和已有代码上下文；前端验证创建校验、自动进入知识页、问答无需代码索引以及控件隐藏。运行前后端相关测试、前端类型检查及构建，并在隔离 PostgreSQL 测试库可用时验证真实创建—发布—检索—问答链路。没有执行的检查明确记录，不触碰业务数据库或生产配置。

2026-10-11 已实施：新增 KnowledgeProjectService / Controller、代码操作拦截器、V2 迁移，调整上下文解析、发布与检索；前端新增知识总览和检索面板，适配项目创建、知识管理及问答。既有代码项目流程继续使用原有分支版本约束。未修改 VERSION，未打包发布，未修改日常业务库或生产配置。

| 验证 | 结果 |
| --- | --- |
| 后端全量 `*Test` | 382 项，失败 0、错误 0，11 项依环境条件跳过 |
| 新增 HTTP 代码操作边界与现有上下文拦截器 | 7 项通过，其中新增 2 项在全量之后补充运行 |
| 前端全量 | 47 个文件、202 项通过 |
| 前端类型检查和生产构建 | 通过；保留既有资源体积提示 |
| 修改 Java 文件 Spotless 检查 | 通过 |

全库 `mvn -o -pl backend spotless:check` 未通过，存在本次未修改文件的既有格式问题；本次修改的 18 个 Java 文件单独检查通过，未对无关文件执行格式化。验收结束后已删除本轮临时数据库容器。

数据库验证使用独立临时 PostgreSQL 17 / pgvector 容器、独立数据库与 `.runtime/knowledge-only-tests/` 数据目录。APP_RUN_POSTGRES_IT=true 启用 KnowledgeProjectDatabaseTest，两项实际验证无文件创建、空版本上下文、草稿隔离、发布、知识检索、本地证据连续问答及历史、撤回、账号/项目/有效期隔离、拒绝代码准备和重名。APP_BRANCH_OPERATIONS_TEST_URL/USER/PASSWORD 指向同一隔离实例的独立测试 schema，原有 BranchCodeOperationsDatabaseTest 验证代码同步、索引、图谱任务准备流程并通过。

重现时使用专用测试库，设置 APP_DATASOURCE_URL/USERNAME/PASSWORD、APP_MANAGED_DATA_ROOT、APP_REPOSITORY_ALLOWED_ROOTS 及测试初始化账号/模型加密键，再执行 `mvn -o -pl backend -am "-Dtest=*Test" "-Dsurefire.failIfNoSpecifiedTests=false" clean test`。前端执行 `npm --prefix frontend run test` 与 `npm --prefix frontend run build`。

本轮未进行浏览器整页端到端、外部问答模型/远端向量服务联调、正式部署验收。附件正文解析及后续接入代码仍不在需求范围内。
