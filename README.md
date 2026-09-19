# Analyzer Coder · 代码与知识联合检索

这是一个面向开发者的最小检索工具：导入代码仓库，建立版本化索引，用一次查询同时找到相关代码与项目知识，并能回到文件、行号和知识卡来源。

## 产品边界

核心路径只有四步：

1. 导入 Git 或 ZIP 仓库；
2. 为当前快照建立代码索引；
3. 维护与代码范围关联的知识卡；
4. 在“联合检索”页面统一检索两类结果。

系统保留基于检索证据的问答、代码关系和知识失效提示作为辅助能力。它不再提供跨仓工程项目、跨仓服务契约、变更审查、PR/MR Webhook、审查 CI、任务结果回报或审查型 MCP 工具。

## 第一次使用

1. 登录后在“项目管理”导入一个小仓库。
2. Git 项目在“项目管理”的分支面板同步并构建索引（或一键准备），再切换阅读分支；ZIP 项目保留单版本准备流程。
3. 在“知识管理”录入一条业务约束，并绑定相关路径或符号。
4. 打开“联合检索”，搜索类名、函数名、错误信息或业务术语。
5. 检查混排结果是否同时包含源码位置和相关知识卡。

没有配置外部模型时，系统仍可使用关键词与字符相似度检索；这不等同于完整语义理解。向量模型和 CodeGraph 都是可选增强，不影响最小路径成立。

## 启动

- Linux 源码启动：`bash scripts/start.sh`
- Linux 预构建宿主机启动（已有前端 dist、后端 JAR、本机 PostgreSQL 和 Nginx）：`bash scripts/start-prebuilt-host.sh`
- 后端：准备 PostgreSQL/pgvector 后运行 Spring Boot，详见 [后端说明](backend/README.md)
- 前端：`npm --prefix frontend ci`，然后 `npm --prefix frontend run dev`
- 环境诊断：`node scripts/check-runtime.mjs`

后端不能只靠前端开发服务器启动；数据库、仓库目录配置和 Java 服务都必须可用。

## 验证

```sh
mvn -pl backend -am test
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix mcp-server test
node --test scripts/evaluate-quality.test.mjs
node scripts/evaluate-quality.mjs --validate
```

数据库集成测试默认跳过，应只在独立测试数据库与独立受管文件目录中执行。实测基线、环境前提与尚未验收的项见 [验证基线](docs/14-verification-baseline.md)。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `backend` | 仓库接入、版本化索引、代码与知识联合检索 API |
| `frontend` | 项目管理、知识维护和统一检索工作台 |
| `mcp-server` | stdio 适配器；与后端 `/api/mcp` 一起提供只读代码、知识和分支图谱工具 |
| `evaluation` | 检索召回和证据问答质量样本 |
| `scripts` / `deploy` | 启动、诊断与部署脚本 |
| `docs` | 从当前实现反推的需求文档集，入口见 [文档索引](docs/README.md) |

产品定位、端到端主流程与功能地图见 [产品需求总览](docs/01-product-overview.md)。
MCP 配置、分支选择和工具参数见 [MCP 接入说明](mcp-server/README.md)；MCP 工具目录见 [MCP 接入需求](docs/09-mcp-integration.md)。
仓库接入与分支同步/索引的边界见 [仓库接入与来源凭据](docs/03-repository-source-and-credentials.md) 和 [分支、快照与准备](docs/04-branch-snapshot-and-preparation.md)。
部署与启动操作见 [部署与启动操作手册](docs/15-deployment-runbook.md)。
