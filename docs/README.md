# 文档索引

> 本目录文档于 2026-09-19 重建。旧文档已整体删除，仍可从 Git 历史找回：查看单个文件用 `git show HEAD:docs/<旧文件名>`；恢复整批旧文档用 `git checkout HEAD -- docs/`（注意这会把旧文件与当前新文档并列恢复到同一目录，恢复后需自行清理）。
>
> 2026-09-20：部署流程已收敛为完整发布包，运维相关章节随实现更新。
>
> 当前文档集**从现有实现反推生成**：依据源码、SQL 迁移、配置与测试，描述系统"已实现的需求"，不是新设计提案。每条非显然的规则都附有来源证据（`路径:行号`）。

## 阅读路径

| 你的目的 | 建议顺序 |
| --- | --- |
| 第一次了解这个产品 | [01 产品需求总览](01-product-overview.md) → [06 联合检索](06-unified-retrieval.md) → [07 知识管理](07-knowledge-management.md) |
| 接手某个领域开发 | [01 产品需求总览](01-product-overview.md) → 对应领域文档 → [11 数据模型](11-data-model.md) → [12 接口目录](12-api-catalog.md) |
| 对接 AI 客户端 | [09 MCP 接入](09-mcp-integration.md) → [mcp-server/README.md](../mcp-server/README.md) |
| 部署与运维 | [15 部署与启动操作手册](15-deployment-runbook.md) → [13 非功能与部署运维](13-nonfunctional-and-operations.md) |
| 验收与测试 | [14 验证基线](14-verification-baseline.md) → [12 接口目录](12-api-catalog.md) |

## 文档清单

| 文档 | 内容 | 主要读者 |
| --- | --- | --- |
| [01 产品需求总览](01-product-overview.md) | 产品定位、角色与权限摘要、端到端主流程、功能地图、术语表、版本模型、产品边界 | 全部 |
| [02 账号、认证、权限与审计](02-account-auth-permission.md) | 账号生命周期、会话与 CSRF、验证码与锁定、三级权限与所有权、账户访问令牌、仓库治理、审计日志 | 后端、平台管理员 |
| [03 仓库接入与来源凭据](03-repository-source-and-credentials.md) | 本地/远程 Git、GitLab、ZIP 接入，项目草稿、凭据管理与绑定、仓库资料与生命周期 | 后端、前端 |
| [04 分支工作区与准备](04-branch-version-and-preparation.md) | 分支所有权、工作区更新、准备任务、阅读上下文与 ZIP 的 WORKSPACE 映射 | 后端、前端 |
| [05 代码索引与向量索引](05-code-index-and-vector.md) | 片段切分与符号抽取、索引任务与增量、向量模型与覆盖、重建与复用 | 后端 |
| [06 联合检索](06-unified-retrieval.md) | 检索入口、多通道召回与排序、结果字段与来源、内容版本范围、降级诊断 | 后端、前端 |
| [07 知识管理](07-knowledge-management.md) | 知识卡生命周期与修订、分支归属、代码关联、Markdown 来源、附件、来源漂移、分支验证 | 后端、前端、知识维护者 |
| [08 证据问答与代码图谱](08-qa-and-codegraph.md) | 问答与引用校验、会话历史、图谱构建与查询、Atlas 可视化 | 后端、前端 |
| [09 MCP 接入](09-mcp-integration.md) | 12 个只读工具、令牌认证与权限、分支上下文、错误码、stdio 适配器 | 集成方、后端 |
| [10 任务中心与系统配置](10-tasks-settings-audit.md) | 索引/向量/分支任务、取消与重试、模型配置与连通性检测 | 后端、平台管理员 |
| [11 数据模型](11-data-model.md) | Flyway 迁移 V1–V9、核心表与约束、触发器、受管目录布局 | 后端、DBA |
| [12 接口目录](12-api-catalog.md) | 全部 HTTP 端点、方法、权限与用途，含无调用方端点清单 | 前后端、集成方 |
| [13 非功能与部署运维](13-nonfunctional-and-operations.md) | 安全约束、配置项清单、容量边界、部署形态、脚本、升级与备份 | 运维、后端 |
| [14 验证基线](14-verification-baseline.md) | 测试资产、验证命令、实测结果、已知缺口与未验收项 | 全部 |
| [15 部署与启动操作手册](15-deployment-runbook.md) | 完整发布包与两平台部署的操作步骤、诊断、停止、升级与故障对照 | 运维 |

`mcp-server/README.md`、`backend/README.md`、`deploy/README.md` 是各自模块的操作说明，与本目录互补。

## 文档集的生成方式与边界

1. **来源**：`backend/src/main/java`、`backend/src/main/resources`（含 `db/migration` 与 MyBatis XML）、`frontend/src`、`mcp-server/src`、`scripts`、`deploy`、`.github/workflows`、`evaluation`、`backend/src/test` 与 `frontend/src/**/*.spec.ts`。
2. **不含设计提案**：文中只描述已实现的行为。已在实现中移除的能力（变更审查、跨仓工程项目、PR/MR Webhook、审查型 CI、任务结果回报等）不写成需求，只在"边界与非目标"中列出。
3. **证据可核对**：文中的 `路径:行号` 指向反推时的实现位置；代码变动后行号可能偏移，请以符号名与文件为准。
4. **"已知缺口"**：每一份领域文档末尾的缺口清单只记录能从代码判断的真实问题（例如接口无调用方、上下文未传递、测试未接入自动化）。无法确认的一律标注"需人工确认"，不做推测。
5. **未覆盖**：真实外部模型、真实 CodeGraph CLI 全链路、远端 Git/GitLab 联调、浏览器整页端到端、生产部署与历史库升级未在本轮验证，统一记录在 [14 验证基线](14-verification-baseline.md)。

## 与代码同步的约定

修改实现时，请同步更新对应文档，最低要求：

| 变更类型 | 需同步的文档 |
| --- | --- |
| 新增或修改 HTTP 端点 | [12 接口目录](12-api-catalog.md)、对应领域文档的接口小节 |
| 新增 Flyway 迁移或修改表结构 | [11 数据模型](11-data-model.md) |
| 新增配置项或环境变量 | [13 非功能与部署运维](13-nonfunctional-and-operations.md) |
| 新增或改名 MCP 工具 | [09 MCP 接入](09-mcp-integration.md)、`backend/src/main/resources/mcp-tools.json`、`mcp-server/src/server.mjs`、`mcp-server/README.md` |
| 新增页面或路由 | [01 产品需求总览](01-product-overview.md) 的功能地图 |
| 测试资产或验证命令变化 | [14 验证基线](14-verification-baseline.md) |
