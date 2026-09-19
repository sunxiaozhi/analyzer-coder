# 产品需求总览

> 本文档由当前实现反推生成（2026-09-19）。描述已实现的需求，不是新设计。

## 1 产品定位

Analyzer Coder（界面上称"代码知识平台"）是一个面向开发者的**代码与知识联合检索工具**：把代码仓库与团队知识放进同一个可追溯的检索入口，帮助使用者回答"代码在哪、为什么这样写、有哪些相关约束"，并且每条结果都能回到文件、行号或知识卡原文核对。

产品要解决三个具体问题：

| 问题 | 应对能力 |
| --- | --- |
| 只知道业务术语，不知道对应代码位置 | 在同一个结果列表里混排源码片段与项目知识 |
| 代码与文档分散，查到实现后仍不知道相关约束 | 知识卡绑定路径/符号/模块范围并参与同一检索 |
| 生成的答案缺少可核对来源 | 结果与问答都保留仓库、分支、快照、提交、知识修订标识 |

设计原则（可从实现中直接观察到）：

1. **一切结论绑定版本**：代码结果与知识结果都携带快照身份，不把不同分支或不同提交的材料混在一起。
2. **降级不伪装**：向量模型、CodeGraph 属于增强能力，未配置或不可用时保留关键词检索路径，并明确标注当前范围与降级原因。
3. **只读优先**：平台浏览与检索受管快照，不在原仓库中编辑或提交代码。
4. **权限在服务端判定**：前端隐藏按钮只用于减少误操作，最终以服务端校验为准。

## 2 角色与权限摘要

角色只有两种（`backend/src/main/java/com/analyzercoder/security/AccountRole.java:4-7`）：

- `SUPER_ADMIN`：平台管理员，对所有仓库直接放行，可执行仅所有者动作，可见仓库集合为全部（`AccessControlService.java:23-25`、`:47-49`、`:56-60`）。
- `NORMAL`：普通用户，按仓库成员权限判定。

仓库权限是**三级递增**（`RepositoryPermission.java:4-11`）：

| 级别 | 含义 | 典型能力 |
| --- | --- | --- |
| `READ` | 读取 | 浏览代码、检索、查看知识、问答、图谱查询、通过令牌只读访问 |
| `MAINTAIN` | 维护 | 在 READ 基础上：同步/准备分支、构建内容索引与图谱、创建与编辑知识、上传附件、记录来源复核与分支验证 |
| `MANAGE` | 管理 | 在 MAINTAIN 基础上：编辑仓库资料、配置模型与凭据、治理成员 |

此外存在**所有者（owner）关系**，它不是权限级别：所有权记录在 `repositories.owner_account_id`（`backend/src/main/resources/db/migration/V1__init_schema.sql:72`），成员权限表 `repository_permissions` 明确不含 OWNER（`V1__init_schema.sql:179`）。账号等于所有者时按 MANAGE 处理，并可执行仅所有者动作（转移所有权、删除仓库、授予权限、管理凭据），错误码 `OWNER_REQUIRED`（`AccessControlService.java:46-54`）。

前端展示用的能力位由 `AccessControlService.describe`（`:62-95`）计算，字段定义在 `RepositoryAccess.Capabilities`（`RepositoryAccess.java:13-23`）：`canRead`、`canEditRepository`、`canUpdate`、`canIndex`、`canBuildCodeGraph`、`canConfigure`、`canGrant`、`canManageCredential`、`canTransferOwnership`、`canDelete`。

## 3 端到端主流程

```
接入项目 → 准备分支快照 → 建立内容索引 → 维护知识 → 联合检索 → 回到来源
                                    ↘ 可选：向量索引 / 代码图谱 / 证据问答 / MCP
```

1. **接入项目**：在"项目管理"接入本地 Git、远程 Git/GitLab 或 ZIP 来源，得到项目（`repositoryId`）与受管快照。
2. **准备分支快照**：Git 项目按分支同步并"一键准备"（同步 → 内容索引 → 代码图谱），ZIP 项目走单版本流程；准备结果是带 `commitSha` 与内容路径的不可变快照。
3. **建立内容索引**：源码被切分为携带路径、符号、行列号的代码片段（`chunk`）。
4. **维护知识**：在"知识管理"录入或从仓库 Markdown 生成知识卡，选择范围（项目共享 / 分支专属），绑定路径、符号或模块。
5. **联合检索**：在"联合检索"输入自然语言、类名、函数名或错误信息，一个列表同时返回代码与已发布知识。
6. **回到来源**：代码结果打开文件与行号，知识结果打开知识卡并显示修订、状态与代码关联。

阅读范围由**阅读上下文**固定：项目 + 分支 + 快照 + 提交，通过 `X-Branch-Context` 请求头传递，`contextId` 有效期 1 小时并绑定账号（`BranchReadContext`、`BranchRequestContext`、`RepositoryBranchService`）。

## 4 功能地图

| 领域 | 需求文档 | 核心能力 |
| --- | --- | --- |
| 账号、认证、权限与审计 | [02-account-auth-permission.md](02-account-auth-permission.md) | 账号生命周期、会话与 CSRF、验证码、锁定、三级权限与所有权、账户访问令牌、治理、审计日志 |
| 仓库接入与来源凭据 | [03-repository-source-and-credentials.md](03-repository-source-and-credentials.md) | 本地/远程 Git、GitLab、ZIP 接入，项目草稿、凭据管理与绑定、仓库资料与生命周期 |
| 分支、快照与准备 | [04-branch-snapshot-and-preparation.md](04-branch-snapshot-and-preparation.md) | 分支跟踪与归档、快照发布与复用、准备任务与阶段、阅读上下文、快照保留与清理 |
| 代码索引与向量索引 | [05-code-index-and-vector.md](05-code-index-and-vector.md) | 片段切分与符号抽取、索引任务与增量、向量模型与覆盖、重建与复用 |
| 联合检索 | [06-unified-retrieval.md](06-unified-retrieval.md) | 多通道召回与排序、结果字段与来源、快照范围、降级诊断 |
| 知识管理 | [07-knowledge-management.md](07-knowledge-management.md) | 知识卡生命周期与修订、适用范围、代码关联、Markdown 来源、附件、来源漂移、分支验证 |
| 证据问答与代码图谱 | [08-qa-and-codegraph.md](08-qa-and-codegraph.md) | 问答与引用校验、会话历史、图谱构建与查询、Atlas 可视化 |
| MCP 接入 | [09-mcp-integration.md](09-mcp-integration.md) | 12 个只读工具、令牌认证与权限、分支上下文、错误码、stdio 适配器 |
| 任务中心与系统配置 | [10-tasks-settings-audit.md](10-tasks-settings-audit.md) | 索引/向量/分支任务、取消与重试、模型配置与连通性检测 |
| 数据模型 | [11-data-model.md](11-data-model.md) | Flyway 迁移 V1–V9、核心表与约束、触发器、受管目录布局 |
| 接口目录 | [12-api-catalog.md](12-api-catalog.md) | 全部 HTTP 端点、权限与死接口清单 |
| 非功能与部署运维 | [13-nonfunctional-and-operations.md](13-nonfunctional-and-operations.md) | 安全约束、配置项、容量边界、部署形态、脚本 |
| 验证基线 | [14-verification-baseline.md](14-verification-baseline.md) | 测试资产、验证命令、实测结果、未验收项 |

前端页面与路由的对应关系（`frontend/src/router/index.ts:14-43`、`frontend/src/components/workspaceNavigation.ts:32-73`）：

| 路由 | 名称 | 导航可见性 |
| --- | --- | --- |
| `/overview` | 项目总览 | 全部用户 |
| `/search` | 联合检索 | 全部用户 |
| `/atlas` | 代码图谱 | 全部用户 |
| `/ask` | 项目问答 | 全部用户 |
| `/knowledge` | 知识管理 | 具备所选项目 READ 权限 |
| `/repositories` | 项目管理 | 可管理项目的用户 |
| `/indexing` | 任务中心 | 仅管理员 |
| `/settings` | 模型配置 | 仅管理员 |
| `/accounts` | 账号权限 | 仅管理员 |
| `/audit` | 审计日志 | 仅管理员 |
| `/help` | 功能导航 | 顶栏入口 |
| `/mcp` | MCP 接入 | 顶栏入口 |
| `/` | 重定向到 `/overview` | 不显示 |
| `/graph` | 兼容旧链接，重定向到 `/search` 并补 `relation=1` 查询参数 | 不显示 |

上表的"导航可见性"指左侧导航是否展示入口，不等于路由拦截：路由守卫（`frontend/src/router/index.ts:45-57`）只校验公开页、登录态、强制改密与 `meta.admin` 四项。`meta.projectManage`（`:23`）与 `meta.repositoryRead`（`:37`）虽然已声明，但未参与守卫判定，直接输入 URL 不会被前端拦截——真正的边界始终由服务端权限校验决定。

## 5 术语表

| 术语 | 含义 |
| --- | --- |
| 项目（project） | 一个 Git 仓库或 ZIP 来源的逻辑身份，对外标识 `repositoryId`；不因分支增加而复制 |
| 分支（branch） | 项目下的受管分支，具有跟踪状态与准备状态 |
| 快照（snapshot） | 某一提交的不可变受管内容副本，标识 `snapshotId`，含 `commitSha` 与内容路径 |
| 提交（commitSha） | 快照对应的 Git 提交标识 |
| 默认版本 | `repositories.current_snapshot_id` 所指向的版本，用于单版本/ZIP 与旧接口；多分支下由默认分支镜像触发器维护 |
| 阅读上下文 | 由项目 + 分支 + 快照 + 提交固定的一次阅读范围，`contextId` 有效 1 小时 |
| 代码片段（chunk） | 索引后的最小检索单位，含文件路径、符号、起止行与内容摘要 |
| 知识卡（knowledge card） | 人工维护或从 Markdown 生成的知识单元，有修订、审核与发布状态 |
| 修订（revision） | 知识卡的一次不可变版本 |
| 项目共享知识 / 分支专属知识 | 知识卡的适用面；两者是彼此独立的资产，不按标题或正文自动合并 |
| 来源漂移（source drift） | 知识卡绑定的代码来源发生变化，需要人工确认当前或标记失效 |
| 联合检索 | 单一的代码 + 知识检索入口 |
| 证据问答 | 基于检索材料生成带引用回答的能力，可降级为本地证据模式 |
| 代码图谱（CodeGraph） | 基于静态分析的符号与关系产物，用于结构浏览与影响分析 |
| 准备（preparation） | 把某分支的提交发布为快照并建立索引的流程 |

## 6 版本与证据模型

这是全系统最重要的约束，跨所有领域成立：

1. **代码结果与知识结果都保留快照身份**，历史记录保留原引用快照，不随当前版本漂移。
2. **检索与代码读取限定当前阅读上下文**（或单版本项目的默认快照）；分支模式下未准备的分支不借用其他分支产物。
3. **知识进入正式检索的条件**：`publication_status='PUBLISHED'` 且 `review_status='APPROVED'`，且来源版本状态不是 `SUSPECT`/`STALE`（`backend/src/main/resources/mappers/IntelligenceMapper.xml:319`，关键词通道见 `:42-48`）。
4. **知识修订不可变**：历史修订与发布记录由数据库触发器保护，编辑产生新修订而非覆盖历史。

## 7 可选能力的降级原则

向量检索、语义模型、代码图谱、知识失效提示都属于**增强能力**，不是最小路径的前置条件：

- 未配置外部聊天模型时，问答走本地证据模式并返回带源码引用的回答。
- 未配置向量模型时，检索保留关键词与字符相似度通道；字符相似度不得被描述为语义理解。
- 未发布 CodeGraph 产物时，图谱查询返回明确错误，不回退到其他分支。
- 增强能力失败不阻断关键词检索与来源查看。

## 8 产品边界与非目标

以下能力**不在当前范围内**，实现中也不存在对应入口：

- 变更审查、提交范围审查、工作区差异审查。
- 需求影响预估与影响候选的调查草稿。
- PR/MR Webhook 与审查型 CI。
- 测试要求执行、审批判定与任务结果回报。
- 任何基于检索结果自动阻断发布的能力。
- 跨仓工程项目、跨仓服务契约与跨仓知识聚合（`V9__remove_cross_repository_projects.sql` 已移除相关结构与知识范围字段）。
- 平台自动修改代码、提交、执行测试或修改仓库。

平台可以提供**静态关系与影响线索**（代码图谱），但静态关系不代表运行时真实调用链，也不构成审查结论。

## 9 已知限制

本总览只描述能力与边界，具体缺口（未接入自动化的测试、无量化结论的检索质量、接口与契约漂移、未验收的外部依赖等）统一记录在 [14-verification-baseline.md](14-verification-baseline.md) 的"已知缺口与未验收项"一节。
