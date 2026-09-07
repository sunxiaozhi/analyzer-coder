# Analyzer Coder · 代码证据工作台

导入代码仓库，定位源码、查看版本化引用、阅读本地证据回答，并结合可选的 CodeGraph 与问答模型分析调用关系和变更影响。

当前最小可用路径：**导入仓库 → 内容索引 → 关键词/字符相似度检索 → 本地证据回答 → 查看源码引用和会话历史**。本地证据模式不需要问答模型，不会生成模型推理；内置字符相似度不等于语义理解。完整调用图谱仍需要 CodeGraph CLI。

## 启动与诊断

- Linux 源码启动：`bash scripts/start.sh`，配置要求见 [部署说明](docs/08-linux-git-deployment.md)。该脚本要求安装 CodeGraph。
- 开发环境：按 [后端说明](backend/README.md) 启动 PostgreSQL/pgvector 和 Spring Boot；前端执行 `cd frontend`、`npm ci`、`npm run dev`。
- 在已加载后端环境变量的终端执行 `node scripts/check-runtime.mjs`，检查命令、数据库端口、后端健康和仓库目录配置。诊断不会读取或打印密钥，也不启动服务。可加 `--json` 保存结果。

后端不能仅靠 `npm run dev` 启动。PostgreSQL、后端、仓库白名单及受管存储配置缺一不可。TCP 可达只说明端口开放，真实数据库与迁移可用性仍需要后端健康检查和集成测试确认。

## 第一次使用

1. 登录并导入一个小仓库，推荐先使用 ZIP 或本地 Git 仓库。
2. 在项目总览执行准备，确认当前快照的内容索引片段数大于零。
3. 进入代码检索，先搜索仓库中确实存在的类名、函数名或路径。
4. 进入问答，回答方式选择“本地证据”，询问同一个符号的位置。检查引用的路径、行号、快照和原始内容。
5. 刷新页面，重新打开会话，核对引用仍然可追溯。
6. 配置外部模型或 CodeGraph 后，再验收语义召回、模型生成和调用图谱。向量失败不会阻断代码图谱入队；独立阶段失败会保留在准备状态中。

切换向量模型后，旧模型向量不计入当前覆盖率。到项目总览重新准备或重试向量阶段；搜索请求不会替你同步重建全仓库。已发生向量降级的快照不会自动无限重试模型，修复配置后请显式重试向量阶段。

## 验证

安装前端/MCP 依赖并启动 Docker 后，可执行 `node scripts/verify-core.mjs`。脚本创建独立临时 pgvector 数据库，执行前端测试/构建、全部后端单元与集成测试、真实 HTTP 导入到问答验收、MCP 和 CI 脚本测试，结束后移除测试容器。默认端口 15439，可用 `--port=15440` 更换；不会使用业务库凭据。

逐项使用说明见 [功能与数据流](docs/13-system-usage-map.md)。

```sh
mvn -pl backend -am test
npm --prefix frontend test
npm --prefix frontend run build
node --test scripts/evaluate-quality.test.mjs scripts/ci-task-review.test.mjs
node scripts/evaluate-quality.mjs --validate
```

数据库集成验收必须在**独立测试数据库**、独立受管文件目录和完整后端环境变量下执行：

```sh
# Bash；PowerShell 使用 $env:APP_RUN_POSTGRES_IT='true'
export APP_RUN_POSTGRES_IT=true
mvn -pl backend '-Dtest=*IT' test
```

普通 `mvn test` 会跳过数据库集成验收。`RepositoryImportToAskIT` 使用真实导入、文件扫描、MyBatis、pgvector 和内置向量服务，覆盖本地证据回答、历史恢复及模型切换后的索引恢复；它不验证真实 CodeGraph CLI 或外部模型。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `backend` | Java 17 / Spring Boot、后台任务、MyBatis、Git 与模型调用 |
| `frontend` | Vue 3 / TypeScript 工作台 |
| `mcp-server` | 给开发代理提供上下文和审查工具的薄适配层 |
| `evaluation` | 检索、问答、影响分析、任务审查和知识漂移的评测样本 |
| `scripts` / `deploy` | 启动、运行诊断、部署与发布校验 |

实际缺陷、已修复项、验证边界及后续优先级见 [数据链路审计](docs/12-data-flow-audit.md)。
