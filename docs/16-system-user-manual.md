# Analyzer Coder 系统使用手册与数据来源说明

核对日期：2026-09-09
适用范围：当前仓库中的 Vue 前端、Java 后端、PostgreSQL/pgvector、受管文件目录、MCP、CI 和 GitHub/GitLab 集成。
文档状态：以当前代码、接口和 Flyway 合并基线交叉核对。与旧说明冲突时，应以当前接口权限和实际数据链路为准。

## 1. 如何阅读本手册

本手册给每个功能标注数据来源，并使用以下口径：

| 标记 | 含义 |
| --- | --- |
| 原始事实 | 直接来自 Git、代码文件、账号输入或外部提供方的数据 |
| 平台记录 | 已写入 PostgreSQL，可在后续请求中恢复的数据 |
| 平台派生 | 根据原始事实计算出的索引、统计、匹配或建议 |
| 浏览器状态 | 只保存在当前浏览器，未写入服务端 |
| 外部结果 | 来自 OpenAI-compatible 模型、GitHub、GitLab 或 CodeGraph CLI |
| 待验收 | 代码中存在链路，但当前仓库没有足够证据证明目标部署环境已完整跑通 |

任何显示为“当前”的代码、索引、图谱、知识来源或审查结果，都必须同时核对 repositoryId 和 snapshotId。当前数据库只维护仓库的一个已发布代码版本，snapshotId 是一致性令牌，不是长期历史源码库。

## 2. 系统解决什么问题

系统主流程是：

1. 接入代码仓库并发布只读快照。
2. 对当前快照建立内容片段、向量和 CodeGraph 产物。
3. 在单个授权仓库内检索代码、浏览源码和核对关系。
4. 用本地证据或外部模型回答项目问题，并保存引用。
5. 在修改前做影响预估，在真实改动产生后创建不可变审查。
6. 维护经过人工评审的工程知识，让规则参与审查和 CI。
7. 通过 MCP 把同一套仓库权限和证据提供给编码代理。
8. 通过结果回报积累测试、审批、误报、漏报和知识更新记录。

系统不是在线 IDE，不负责编辑、保存或提交代码；也不会自动执行测试、完成审批、合并 PR/MR 或把模型建议直接发布为正式知识。

## 3. 角色、仓库隔离与权限

### 3.1 平台角色

| 角色 | 能力 |
| --- | --- |
| 普通账号 | 登录、创建自己的仓库、访问被授权仓库，以及使用相应仓库功能 |
| 超级管理员 | 查看全部仓库，并使用索引任务、模型配置、账号权限和审计日志 |

平台角色来自 accounts.account_role。普通账号能看到哪些仓库，来自 repositories.owner_account_id 和 repository_permissions，不按浏览器、令牌或 MCP 客户端单独复制一份权限。

### 3.2 仓库权限

| 仓库关系 | 能力 |
| --- | --- |
| READ | 查看仓库、源码、检索、问答、图谱、已发布知识和审查 |
| MAINTAIN | READ 加同步、内容索引、图谱构建和知识草稿维护 |
| MANAGE | MAINTAIN 加仓库资料编辑、知识评审和发布、跨仓项目管理 |
| OWNER | MANAGE 加成员授权、凭据绑定、所有权转移和仓库删除 |
| SUPER_ADMIN | 后端按全局管理员放行 |

权限判断由 AccessControlService 在每次后端请求中执行。前端按钮隐藏只用于减少误操作，不能替代后端校验。

### 3.3 访问令牌如何继承权限

访问令牌记录在 account_access_tokens，只包含账号标识、令牌摘要、前缀、有效期和使用时间。完整令牌只在创建时返回一次，数据库不保存明文。

令牌认证成功后，后端重新读取账号状态；工具访问仓库时，再实时查询 repository_permissions 和仓库所有者。因此：

- 给账号增加或收回仓库权限后，下次 MCP 调用立即按新权限执行，不需要重发令牌。
- 停用账号、改变角色、修改密码或重置密码会撤销旧令牌。
- 锁定或待改密账号不能使用令牌。
- 退出浏览器会话不会自动撤销独立访问令牌。
- 管理员令牌继承管理员权限，日常使用应给普通账号创建令牌并按仓库授权。
- 访问令牌只允许 MCP 和受限任务上下文、审查、结果回报接口，不能用于账号管理和创建更多令牌。
- 当前 CI 检查接口不在访问令牌白名单内，现有 CI 脚本仍使用受限账号的 Session Cookie 与 CSRF Token。

## 4. 第一次使用

1. 管理员创建账号，或使用初始管理员登录。
2. 首次登录使用临时密码并完成强制改密。
3. 进入“项目管理”，接入本地 Git、远程 Git、GitLab 或 ZIP。
4. 仓库所有者在“成员与所有权”中给其他账号分配 READ、MAINTAIN 或 MANAGE。
5. 顶部选择当前仓库；不熟悉使用顺序时，从右上角“帮助说明”打开“功能导航”。
6. 进入“项目总览”执行“准备项目”，确认内容索引存在片段；CodeGraph 或外部模型未配置时，仍可使用源码浏览、关键词检索和本地证据回答。
7. 进入“代码与证据”，先搜索仓库中明确存在的路径、类名或函数名。
8. 进入“问项目”，先选择“本地证据”，核对文件、行号、快照和原文。
9. 需要代理接入时，从右上角进入“MCP 接入”，创建自己的访问令牌并配置客户端。
10. 有真实 Git 改动后，再进入“变更审查”创建不可变审查并回报实际结果。

“本地 Git 路径”和 WORKTREE 都指 Java 后端所在机器可访问的目录，不是浏览器或 MCP 客户端电脑上的目录。

## 5. 功能使用与数据来源

### 5.1 登录与工作区

| 功能 | 操作 | 数据来源 | 写入结果与边界 |
| --- | --- | --- | --- |
| 登录 | 输入用户名、密码，必要时填写验证码 | accounts、login_failure_counters、login_captcha_challenges；接口 /api/auth/login | 成功后写 login_sessions、最后登录时间和 audit_events；会话通过 HttpOnly Cookie 使用 |
| 验证码 | 连续失败后按页面提示获取并填写 | 按用户名生成的挑战，答案只存摘要 | 挑战有有效期且只能使用一次；验证码不是第二身份因子 |
| 强制改密 | 首次登录或重置后填写当前密码和新密码 | accounts.must_change_password、临时密码有效期 | 更新密码摘要，删除旧会话并撤销账号全部访问令牌 |
| 退出 | 点击右上角退出 | 当前 login_sessions | 只删除当前浏览器会话，不撤销独立访问令牌 |
| 当前仓库 | 顶部选择仓库 | /api/repositories 返回当前账号可见仓库；accounts.last_repository_id 保存偏好 | 切换会影响总览、代码、问答、审查和知识页面；无权仓库不能写入偏好 |
| 帮助说明与功能导航 | 点击右上角“帮助说明”，按六步卡片进入功能 | 当前可见仓库、/api/repositories/{id}/profile、仓库 capabilities 和账号角色 | 展示选择项目、准备证据、代码、问答、审查、知识的数据来源与实时可用状态；管理员额外看到四个系统管理入口；页面本身不写业务数据 |
| 左侧菜单 | 按角色和当前仓库能力显示 | 登录账号角色、当前可读仓库和仓库 capabilities | 项目管理对所有已登录账号可见；知识治理要求已选择当前账号可读仓库；系统管理只对超级管理员可见 |
| 工作区页签 | 打开、关闭、关闭其他、关闭左右页签 | 当前路由 | 只写浏览器 localStorage 的 analyzer-coder.workspace-tabs.v1；换浏览器或清理站点数据后不会恢复 |

### 5.2 项目管理

入口：左侧“项目管理”。所有已登录账号都可以接入自己的仓库；已有仓库的维护操作按该仓库 capabilities 控制。

| 功能 | 操作 | 数据来源 | 写入结果与边界 |
| --- | --- | --- | --- |
| 仓库列表与搜索 | 按名称、描述、所有者或分支搜索 | /api/repositories/page；repositories 加 owner 和 repository_permissions | 只返回当前账号可见记录；超级管理员可见全部未删除仓库 |
| 本地 Git 接入 | 填名称和后端服务器允许根目录中的绝对路径 | 服务器文件系统、Git 元数据、APP_REPOSITORY_ALLOWED_ROOTS | 写 repositories，并将只读快照发布到 APP_MANAGED_DATA_ROOT；不修改原目录 |
| 远程 Git 接入 | 填 HTTPS 地址、分支和可选凭据 | 远端 Git 服务、git_credentials、repository_credential_bindings | 先写 repository_import_jobs，在受管 staging 中克隆，成功后写 repositories 和快照 |
| GitLab 接入 | 选择 GitLab，填写 HTTPS Clone URL、分支和 PAT 凭据 | GitLab Git HTTPS 接口和加密凭据 | 仍走远程导入任务；当前不是 GitLab OAuth 接入 |
| ZIP 接入 | 上传 ZIP 并填写名称 | 浏览器上传文件 | 校验后解压到受管目录并写 repositories；ZIP 没有真实 Git 历史，不能执行完整 Commit/Range 审查 |
| 导入进度 | 等待页面轮询或稍后刷新 | repository_import_jobs.status、current_step、error_message | 页面等待超时不等于后台任务失败；导入任务未统一显示在“索引任务”页 |
| 编辑仓库 | 修改名称、描述、默认分支 | repositories | 修改资料不自动同步代码；改分支后需执行同步 |
| 同步或重扫 | 点击“同步” | 本地源目录或远端跟踪分支、当前 Git 状态 | 发布新的 current_snapshot_id、current_commit 和快照目录；有变化时产生后续索引任务 |
| 内容索引 | 点击“内容索引”或从总览准备 | 当前受管快照 | 写 index_jobs，完成后替换当前仓库的 code_chunks，并更新向量和启发式关系 |
| 构建代码图谱 | 更多菜单中构建或重新构建 | 当前快照、CodeGraph CLI | 写 index_jobs 和 codegraph_artifacts，并在受管目录发布 codegraph.db；需要 MAINTAIN |
| 凭据管理 | 创建、编辑、检测、启停或删除凭据 | 人工输入的 Git Token/PAT、目标 Git 服务 | 密钥加密写 git_credentials，页面只返回掩码；检测结果不保证其他仓库或以后仍可访问 |
| 凭据绑定 | 在远程仓库编辑中绑定或替换凭据 | git_credentials、repository_credential_bindings | 后续同步和 PR/MR 集成使用该绑定；只有 OWNER 或管理员可管理 |
| 成员授权 | “成员与所有权”中选择账号和级别 | accounts、repositories.owner_account_id、repository_permissions | 实时影响页面、API 和 MCP 可见数据 |
| 转移所有权 | 选择新 OWNER，并设置原 OWNER 后续权限 | repositories、repository_governance_locks | 更新 owner_account_id 和 ownership_version；目标所有者名下重名时需同时处理名称 |
| 删除仓库 | 更多菜单中删除 | repositories、活动工程项目引用、知识 Scope 引用 | 先逻辑删除并写 repository_deletion_tombstones，再由 Worker 清理派生表和受管文件；本地原始目录不删除 |

数据不明确点：远程导入的成功依赖网络、凭据和目标服务；ZIP 安全校验不等价于恶意内容扫描；后台物理清理完成时间没有独立用户页面。

### 5.3 项目总览

入口：左侧“项目总览”。页面并行读取 /profile、/code-facts 和 /health-overview。

| 展示或操作 | 数据来源 | 口径与边界 |
| --- | --- | --- |
| 当前分支、Commit、快照、工作区脏状态 | repositories 当前版本字段和受管快照 | 必须以 /profile 返回版本为准，不用旧仓库列表数据替代 |
| 五阶段准备轨道 | repositories、index_jobs、code_chunks、当前向量统计、codegraph_artifacts、知识漂移任务 | 阶段为快照、内容、向量、图谱、知识失效检查；某阶段降级不代表其他阶段已完成 |
| 代码文件数、快照文件数 | 当前快照文件清单和 code_chunks | 只展示数量，不展示路径和命名规则推断的代码类型 |
| 向量覆盖 | 当前 code_chunks 与匹配当前激活模型的 chunk_embeddings | 100% 只表示当前模型空间内没有缺失片段，不代表召回准确或知识齐全 |

| 图谱节点、关系、热点 | 当前 codegraph_artifacts、codegraph.db、架构映射 | 节点和边数量是已发布静态产物统计；热点按关系权重派生，不是运行时流量 |
| 知识健康 | knowledge_cards 的发布、评审、来源版本和负责人状态 | trusted 要求已发布、已评审、来源 CURRENT；不证明知识内容绝对正确 |
| 最近审查和阻塞项 | task_reviews 最近 5 条、准备状态和知识统计 | 历史审查可以属于旧快照；页面应保留其原版本 |
| 准备、刷新、阶段重试 | 当前仓库与 index_jobs | 刷新只重读；准备和重试会创建或推进后台任务 |

数据不明确点：/code-facts 仍会返回项目类型、技术栈、职责分类和“置信度”等规则推断，当前总览不展示这些字段；图谱完整度、实际运行调用、测试覆盖率、安全性和代码质量也没有被总览证明。

### 5.4 代码与证据

入口：左侧“代码与证据”。所有读取限定当前仓库。

| 功能 | 数据来源 | 结果与边界 |
| --- | --- | --- |
| 文件树 | /api/repositories/{id}/files；repositories.current_snapshot_path | 展示当前受管快照文件清单 |
| 源码预览 | /api/repositories/{id}/files/content；当前快照文件 | 只读；路径越界、二进制、目录和超大文件会被拒绝；旧快照链接不会套到当前源码 |
| Markdown 渲染 | 当前 Markdown 文件正文 | 前端安全渲染；不会修改源文件 |
| 混合检索 | /hybrid-search；code_chunks、chunk_embeddings、启发式调用引用 | 返回路径、符号、行号、contentHash、snapshotId 和命中通道 |
| 检索诊断 | 同一次检索各通道运行结果 | 显示启用/失败通道、模型、召回数、耗时和降级原因；降级结果不能视为完整召回 |
| CodeGraph 关系 | /codegraph/latest 和 /codegraph/impact；codegraph_artifacts 与 codegraph.db | 静态图谱深度限制 1–5；反射、动态分派、运行时配置等可能缺失 |
| 关联知识 | /code-evidence-context；knowledge_cards、knowledge_code_refs 和 Scope 规则 | 确定性展示直接绑定、路径、精确符号和仓库范围；“可信”还要求发布、评审和来源状态 |
| 审查证据 | /code-evidence-context；task_reviews.result_payload | 最多扫描最近 20 条审查，截断时返回 REVIEW_HISTORY_TRUNCATED；不是完整历史全文检索 |
| 跳转知识或审查 | 引用中的 knowledgeId、reviewId、repositoryId、snapshotId | 版本不一致时应保留历史提示，不以当前文件冒充原证据 |

数据不明确点：混合检索分数是多通道排序值，不是事实可信度；静态图谱不能证明运行时调用；文件关联知识不等于该文件所有可能适用规则。

### 5.5 问项目

入口：左侧“问项目”。READ 即可使用。

| 功能 | 数据来源 | 写入与边界 |
| --- | --- | --- |
| 本地证据回答 | 问题、当前 code_chunks、已发布且可检索的 knowledge_cards，以及可用向量 | 不调用聊天模型；输出证据摘要和引用，不模拟模型推理 |
| 模型选择 | /ask/models；llm_provider_configs 和 runtime state | 只有检测可用且熔断器关闭的配置可选；每次请求显式携带 modelConfigId |
| 模型回答 | 检索证据经脱敏后发送到所选 OpenAI-compatible 模型 | 外部模型文本是建议；引用覆盖校验不等于语义正确 |
| 无证据或模型失败 | 当前检索结果和失败原因 | 无证据时明确不足；模型失败时可返回本地证据回答 |
| 多轮追问 | 当前问题、最近会话内容、当前仓库证据 | 最近上下文用于检索消歧和生成；当前无流式 Token、停止生成或断线续传 |
| 引用核对 | qa_citations 保存的路径、行号、快照、内容和来源状态 | 应按引用自己的 snapshotId 阅读；历史引用不自动升级到当前代码 |
| 历史、重命名、删除 | qa_conversations、qa_citations，按账号和仓库隔离 | 每轮保存问题、回答、诊断和引用；删除会话会删除对应引用 |

数据不明确点：外部模型的事实正确性需要人工核对；字符哈希向量只提供字符相似度；当前没有生产数据证明语义召回率或回答正确率。

### 5.6 变更审查

入口：左侧“变更审查”。页面包含修改前的影响预估和真实变更产生后的审查。

| 功能 | 数据来源 | 写入与边界 |
| --- | --- | --- |
| 需求影响预估 | 用户任务描述、可选模型解析或规则解析、当前混合检索、架构映射 | 返回分析候选、模块、依赖、风险、测试建议和未知项；分析本身没有持久化表 |
| 确认或排除候选 | 用户在页面的选择 | 只保存在当前 Vue 页面状态；离开页面、切仓或刷新后丢失 |
| WORKTREE 审查 | 后端仓库工作区的暂存、未暂存和未跟踪变化 | 不是 MCP 客户端电脑上的工作区；工作区审查不能用于严格 CI Head 绑定 |
| 单 Commit 审查 | 后端 Git 仓库中的指定完整或可解析 Commit | 读取真实 Git Diff、文件变化和符号变化 |
| Commit Range 审查 | 后端 Git 仓库中的 Base 与 Head | 结果绑定 baseCommit、headCommit、snapshotId 和来源位置 |
| PR/MR 审查 | GitHub/GitLab API、绑定凭据、远端 Patch 与 Head | 创建或更新外部提示评论；外部写入是否成功依赖真实提供方环境 |
| 变化符号与来源 | Git Hunk、源码声明、CodeGraph 节点和 code_chunks | resolution 会说明来自 CODEGRAPH、SOURCE_DECLARATION、CHUNK_SYMBOL 或 FILE_LEVEL |
| 适用知识 | 已发布、已评审、来源有效的 knowledge_cards，直接代码、路径、符号、模块和受治理跨仓范围 | 确定性命中与关键词/向量参考候选分开展示 |
| 测试与审批要求 | 命中知识的 obligations_payload | 是待完成义务；平台不自动运行测试或收集审批 |
| 失效知识与未知项 | 知识来源版本、Git 变化、证据缺口和静态分析限制 | 未知项不能自动扩大为通过或失败 |
| 可选模型总结 | 已形成的审查事实和所选问答模型 | 只增加总结建议，不改变确定性结论 |
| 审查历史 | task_reviews，完整结果保存在 result_payload | COMPLETED 或 FAILED 后不可变；历史记录保持创建时版本 |
| 回报开发结果 | 人工或 MCP 提交最终 Commit、测试、审批和反馈 | 写 task_review_outcomes、task_review_feedback；追加不可编辑 |
| Commit 绑定 | 回报 finalCommit 与审查 headCommit 比较 | 相同为 EXACT_REVIEW_HEAD；不同只记 REPORTER_ASSERTED_FINAL，不证明祖先关系 |

数据不明确点：影响预估不是最终审查；模型总结不是门禁；历史版本源码尚不能完整浏览；PR/MR Head 未索引时知识和架构仍来自平台当前快照，页面必须提示版本边界。

### 5.7 知识治理

入口：选择具有 READ 权限的当前仓库后显示“知识治理”。READ 可查看知识卡片和 Markdown 来源；MAINTAIN 才能生成、同步或编辑卡片。

| 功能 | 数据来源 | 写入与边界 |
| --- | --- | --- |
| 知识列表与筛选 | knowledge_cards | MAINTAIN 可见草稿；READ 侧只使用允许公开的已发布知识 |
| 新建或编辑 | 人工标题、Markdown、分类、标签、强度、范围、义务和负责人 | 写 knowledge_cards 并由触发器写 knowledge_card_revisions；保存统一形成草稿和未评审修订 |
| 发布状态 | DRAFT、PUBLISHED、ARCHIVED | 发布只控制检索和规则参与资格，不等于人工评审或来源有效 |
| 人工评审 | APPROVED 或 CHANGES_REQUESTED | MANAGE 具名写 reviewed_by 和 reviewed_at |
| 来源版本 | UNVERIFIED、CURRENT、SUSPECT、STALE | 来自代码引用指纹与知识漂移检查；CURRENT 只表示来源未检测到变化 |
| 适用范围 | 人工填写的路径 Glob、精确符号、模块、仓库、服务和契约 | scope_payload；来源绑定和适用范围是两个独立概念 |
| 测试、审批与 CI 约束 | 人工填写 requiredTests、审批账号、禁止路径、知识同步等 | obligations_payload；只有明确且有直接证据的 REQUIRED 规则可参与阻断 |
| 代码来源绑定 | 当前 code_chunks 中选中的 chunk | 写 knowledge_code_refs，保存 snapshotId、行号和 contentHash |
| 附件 | 人工上传的图片、PDF、文本和 Office 文件 | 文件写 APP_MANAGED_DATA_ROOT 的受管对象目录，元数据写 knowledge_attachments 和修订关联表 |
| 修订历史与恢复 | knowledge_card_revisions | 恢复会创建新的草稿修订，不覆盖旧事实 |
| Markdown 来源发现 | 仓库扫描得到的 Markdown 文档、规则和任务资产 | 写 repository_markdown_sources，按路径和正文哈希记录待生成、已生成或过期；READ 可查看 |
| Markdown 生成知识 | 当前快照对应 Markdown 正文和指纹 | MAINTAIN 可创建或更新草稿，写 knowledge_card_markdown_source_links；不会自动评审发布 |
| 来源漂移 | Git 差异、知识代码引用和当前快照 | 写 knowledge_drift_events；人工可确认 CURRENT 或标记 STALE |

数据不明确点：附件正文不参与检索，当前也没有外部恶意内容扫描；自动生成 Markdown 卡片不代表内容正确；知识 CURRENT、APPROVED、PUBLISHED 三个状态必须分别核对。

### 5.8 跨仓工程项目

入口：“项目管理”中的“跨仓工程项目”。

| 功能 | 数据来源 | 写入与边界 |
| --- | --- | --- |
| 新建工程项目 | 人工名称、说明和至少两个可 MANAGE 且已有快照的仓库 | engineering_projects、engineering_project_repositories |
| 服务名 | 人工为成员仓库设置的项目内唯一身份 | service_name，不从 README、目录名或模型推断 |
| 契约 | 人工选择提供方、消费方和双方真实索引路径 | engineering_project_contracts 保存两端 snapshotId、路径和内容指纹 |
| 契约当前状态 | 当前路径全部 Chunk 的 startLine 与 contentHash 重新生成指纹后比较 | 指纹一致只表示登记证据未变化，不证明接口兼容或真实运行调用 |
| 跨仓知识适用 | 来源知识 Scope 明确命中目标仓库、服务或当前契约，且创建者能读取来源仓库 | 审查时加载受治理知识；同一项目或关键词相似不会自动建立规则 |
| 删除成员、契约或项目 | 当前项目关系及知识 Scope 引用 | 被知识引用时拒绝删除，需先解除引用 |

数据不明确点：平台没有自动服务发现、接口兼容性分析、跨仓全局搜索或运行时调用验证。

### 5.9 索引任务与向量索引

入口：超级管理员左侧“索引任务”。

| 功能 | 数据来源 | 结果与边界 |
| --- | --- | --- |
| 任务列表 | /api/index-jobs/page；index_jobs 与可见仓库信息 | 当前页面每 2 秒轮询；任务由项目管理或总览发起 |
| 任务详情 | index_jobs 的类型、状态、步骤、执行模式、回退原因、时间、心跳和错误 | 任务只有当前步骤，没有完整事件流和统一百分比 |
| 取消 | index_jobs.cancel_requested 或状态更新 | 运行任务在安全检查点响应，不保证立即终止外部进程 |
| 重试 | 失败任务的仓库和类型 | 创建新的 index_jobs，不改写原终态任务，也没有持久化重试链 |
| 增量索引 | Git name-status 与当前索引基线 | 无基线、脏工作区、Diff 失败或变化比例超过 35% 时回退全量；文件扫描仍可能全量 |
| 当前向量汇总 | code_chunks、chunk_embeddings、knowledge_cards、knowledge_card_embeddings、当前 vector_model_activation | 只统计当前仓库、当前快照和当前模型空间 |
| 代码向量明细 | code_chunks 左连接当前有效 chunk_embeddings | EMBEDDED 或 MISSING 表示该模型下是否有有效向量 |
| 知识向量明细 | 已发布知识及当前修订对应 knowledge_card_embeddings | 代码覆盖与知识覆盖必须分别理解 |

数据不明确点：仓库导入、附件上传和删除清理不在索引任务页；进程崩溃、超时和重启恢复需要在目标部署环境继续故障注入验收。

### 5.10 模型配置

入口：超级管理员左侧“模型配置”。

| 功能 | 数据来源 | 写入与边界 |
| --- | --- | --- |
| 问答模型列表 | llm_provider_configs、llm_provider_runtime_states | 保存地址、模型、参数和密钥配置状态；API Key 加密保存 |
| 新建或编辑问答模型 | 管理员输入 OpenAI-compatible Base URL、模型、密钥和参数 | 写 llm_provider_configs 和 encrypted_secret_versions；保存不等于可用 |
| 问答模型检测 | 固定探针请求外部模型 | 写 llm_connectivity_checks 和 runtime state；不携带仓库代码 |
| 问答运行状态 | 最近成功、失败、错误码和熔断器 | 实际问答调用更新；达到失败阈值后问答降级本地证据 |
| 向量模型 | vector_model_configs | LOCAL_HASH 固定 64 维；OPENAI_COMPATIBLE 支持配置维度 |
| 向量检测 | 本地计算或外部 /embeddings | 返回实际维度和能力；外部响应长度必须匹配备案 |
| 激活向量模型 | vector_model_activation | 切换后旧模型向量不计入当前覆盖，需重新准备或重试向量阶段 |

数据不明确点：连接检测成功只证明固定探针当时可用；没有仓库级代码外发策略 UI；外部模型的费用、限流、保留和隐私策略来自提供方，平台页面不掌握。

### 5.11 账号权限与访问令牌

入口：超级管理员左侧“账号权限”；普通账号可在“MCP 接入”管理自己的令牌。

| 功能 | 数据来源 | 写入与边界 |
| --- | --- | --- |
| 账号列表与搜索 | accounts 和仓库权限数量聚合 | /api/accounts/page；状态由 enabled、locked_until、must_change_password 派生 |
| 新建账号 | 管理员输入用户名、显示名、角色和可选临时密码 | 写 accounts 和 audit_events；自动生成的临时密码只显示一次，24 小时有效 |
| 编辑账号 | displayName、role、enabled、account_version | 角色变化或停用会删除会话并撤销访问令牌 |
| 停用账号 | accounts.enabled | 仍拥有仓库的账号不能直接停用，必须先转移所有权；系统至少保留一个启用管理员 |
| 解锁 | accounts.locked_until 和失败计数 | 写账号状态和审计事件 |
| 重置密码 | accounts.password_hash、临时密码状态 | 删除该账号会话并撤销令牌；新密码只显示一次 |
| 查看账号审计 | audit_events 最近返回集合；账号列表跳转时携带目标用户名 | 页面在浏览器内定位筛选，不重新发服务端按人查询 |
| 创建访问令牌 | 账号 ID、名称、1–365 天有效期 | 写 account_access_tokens 的 SHA-256 摘要；rawToken 只返回一次 |
| 查看令牌 | account_access_tokens | 只显示名称、前缀、状态、到期和最近使用，不可恢复明文 |
| 撤销令牌 | tokenId 与账号 ID | 写 revoked_at 和 audit_events |
| 分配仓库 | 项目管理的“成员与所有权” | 写 repository_permissions；账号页旧权限写接口会明确要求改用仓库治理 |

### 5.12 审计日志

入口：超级管理员左侧“审计日志”。

数据来自 audit_events，并关联账号和仓库显示名称。当前接口一次最多读取最近 200 条，页面再在浏览器中按操作者、目标账号、事件、结果和日期筛选、分页。

审计日志覆盖登录、账号、权限、令牌和仓库治理等已显式写入的事件。它不是所有 HTTP 请求的访问日志，也不是代码变更 Diff 日志。超过当前 200 条窗口的历史不能通过现有页面筛选出来，这一点应视为明确的数据范围限制。

### 5.13 MCP 接入

入口：右上角“MCP 接入”。MCP HTTP 端点随 Java 后端一起启动，地址为平台地址加 /api/mcp，无需为每个账号启动独立服务。

使用步骤：

1. 账号先登录并完成改密。
2. 仓库 OWNER 在项目管理中给账号分配仓库权限。
3. 用户在 MCP 页面创建自己的访问令牌，或由管理员在账号页创建。
4. MCP 客户端配置 Streamable HTTP 地址，并增加 Authorization: Bearer 访问令牌。
5. 先调用 get_task_context，确认返回 repositoryId、snapshotId、证据或明确未知项。

| MCP 工具 | 数据来源 |
| --- | --- |
| get_task_context | 当前 repositories、code_chunks、已发布知识；传 taskReviewId 时增加 task_reviews 中的确定性结论 |
| review_change | 后端 Git 工作区或 Commit Diff、当前快照、知识、图谱，结果写 task_reviews |
| get_rules_for_symbol | 指定 task_reviews.result_payload 中已确定命中的符号规则 |
| get_required_tests | 指定不可变审查中的 requiredTests |
| get_stale_knowledge | 指定不可变审查中的 staleKnowledge |
| get_evidence | 指定审查或任务上下文的来源记录 |
| report_task_outcome | 人工或 Agent 实际回报，写 task_review_outcomes 和 task_review_feedback |

MCP 返回的数据仍按令牌账号的实时仓库权限隔离。repositoryId 必须由平台中可见仓库获得，不能使用仓库名称代替。MCP 不提供 OAuth 登录跳转，客户端必须支持自定义 Authorization 请求头。

### 5.14 Webhook 与 CI

| 功能 | 数据来源 | 边界 |
| --- | --- | --- |
| GitHub Webhook | GitHub 签名事件、APP_GITHUB_WEBHOOK_SECRET、唯一匹配的仓库 remote_url | 验签后按匹配仓库所有者执行审查和评论；未配置密钥时关闭 |
| GitLab Webhook | GitLab Secret Token 事件、APP_GITLAB_WEBHOOK_SECRET、唯一 remote_url | 同上；重复或无法唯一匹配的仓库不能安全处理 |
| PR/MR 评论 | 远端 Patch、Head、绑定凭据和审查结果 | 会写外部系统；测试和审批文字表示尚未回报，不表示失败 |
| CI 确定性检查 | 已完成且 Head 完全一致的 task_reviews、流水线提供的测试和审批 JSON | 只让直接证据支持的禁止路径、必需测试、必要审批、关键失效知识和知识同步要求阻断 |
| CI 退出码 | CiCheckService 结果 | 0 为通过，1 为明确规则失败，2 为配置、鉴权、网络或接口错误 |
| 质量评测脚本 | evaluation 数据集、人工标签和实际运行结果 | 发布指标必须来自完整评测；当前代码存在框架，不应把生成自评分当成人工质量结论 |

## 6. 物理数据来源总表

### 6.1 PostgreSQL 与 pgvector

| 领域 | 核心表 |
| --- | --- |
| 账号与会话 | accounts、login_sessions、login_captcha_challenges、login_failure_counters |
| 访问令牌 | account_access_tokens |
| 仓库与权限 | repositories、repository_permissions、repository_governance_locks |
| Git 凭据与导入 | git_credentials、repository_credential_bindings、repository_import_jobs |
| 删除与审计 | repository_deletion_tombstones、audit_events |
| 后台任务 | index_jobs |
| 内容与向量 | code_chunks、chunk_embeddings、knowledge_card_embeddings |
| 图谱 | codegraph_artifacts、heuristic_call_edges |
| 问答 | qa_conversations、qa_citations |
| 知识 | knowledge_cards、knowledge_card_revisions、knowledge_code_refs |
| 知识附件 | knowledge_attachments、knowledge_card_attachment_refs |
| Markdown 来源 | repository_markdown_sources、knowledge_card_markdown_source_links |
| 知识漂移 | knowledge_drift_events |
| 模型 | llm_provider_configs、llm_provider_runtime_states、llm_connectivity_checks、vector_model_configs、vector_model_activation、encrypted_secret_versions |
| 变更审查 | task_reviews、task_review_outcomes、task_review_feedback |
| 跨仓工程 | engineering_projects、engineering_project_repositories、engineering_project_contracts |
| 其他设置 | system_settings |

### 6.2 文件系统

| 数据 | 来源或落点 |
| --- | --- |
| 本地 Git 原目录 | APP_REPOSITORY_ALLOWED_ROOTS 下允许读取的服务器路径 |
| 远程导入暂存 | APP_MANAGED_DATA_ROOT/staging/imports |
| 当前仓库快照 | APP_MANAGED_DATA_ROOT/repositories 下的受管只读目录 |
| CodeGraph 产物 | 当前仓库受管目录中的 codegraph.db，并由 codegraph_artifacts 记录元数据 |
| 知识附件 | APP_MANAGED_DATA_ROOT 下的受管对象目录 |
| 浏览器页签 | 当前浏览器 localStorage，不在服务器文件系统 |

### 6.3 外部系统

| 外部系统 | 使用位置 |
| --- | --- |
| PostgreSQL/pgvector | 唯一运行期业务数据库和向量存储 |
| Git CLI | 快照版本、同步、Commit/Range/WORKTREE Diff |
| CodeGraph CLI | 静态符号图谱构建和影响路径 |
| OpenAI-compatible Chat | 问答模型、影响意图解析、可选审查总结 |
| OpenAI-compatible Embeddings | 语义向量生成和查询向量 |
| GitHub/GitLab | 远程克隆、PR/MR Patch、Webhook 和评论 |
| MCP 客户端 | 以账户访问令牌调用 Java /api/mcp |
| CI 流水线 | 提供 Head、测试和审批回报，读取确定性门禁结果 |

## 7. 数据一致性规则

1. 页面切换仓库后，旧请求结果不得覆盖新仓库。
2. 代码、Chunk、向量、图谱、知识来源和审查引用必须核对 snapshotId。
3. 历史审查与问答保留创建时版本，不能使用当前版本补齐缺失事实。
4. PUBLISHED、APPROVED、CURRENT 分别表示发布、人工评审、来源版本，不能互相替代。
5. 检索分数、模型回答、规则分类和图谱推断都不能替代原始文件或 Git 证据。
6. 任务终态和审查终态不应改写；重试或纠正应创建新任务或追加结果。
7. 访问令牌只代表账号身份，仓库访问权始终从当前账号与仓库关系实时读取。
8. 外部服务失败时必须显示降级或未知，不得将缺失数据默认解释为成功或零。

## 8. 当前不明确或仍需验收的功能点

| 编号 | 功能点 | 当前能确认什么 | 不明确或未闭环内容 | 使用建议 |
| --- | --- | --- | --- | --- |
| U01 | 历史源码 | 审查、问答和知识引用保存版本标识与部分内容 | 数据库只维护一个当前快照，不能完整浏览任意历史版本文件和前后 Diff | 历史结论优先看已保存证据；无法定位时不要跳当前源码替代 |
| U02 | 影响预估 | 能返回候选、模块、依赖、测试建议和未知项 | 分析与人工确认/排除未持久化，刷新后丢失 | 作为改前调查线索，最终以真实变更审查为准 |
| U03 | 项目画像 | 文件、语言、路径和图谱统计可复核 | 项目类型、技术栈、职责、建议和置信度是规则推断 | 查看 evidencePaths，并由项目维护者确认 |
| U04 | CodeGraph 完整性 | 能记录工具版本、节点、边和查询限制 | 当前环境是否安装可用、静态图是否覆盖动态调用未由代码证明 | 在目标仓库用已知调用链做端到端验收 |
| U05 | 向量与检索质量 | 可区分字符相似度和语义模型，并显示覆盖率 | 没有当前生产仓库的 Recall、误报、漏报和 P95 数据 | 先用已知查询建立人工金标准，再评价模型 |
| U06 | 外部模型 | 有配置、检测、熔断和本地降级 | 检测成功不证明真实长问题、限流、费用和隐私策略满足要求 | 生产前用受控仓库验证，并核对提供方政策 |
| U07 | PR/MR 与 Webhook | 有 API、验签、Patch 获取和评论逻辑 | 当前仓库没有真实提供方写入验收证据 | 先在测试项目验证权限、Head 变化、重复事件和评论更新 |
| U08 | 审计完整性 | 已显式记录的账号、权限、令牌和治理事件可查 | 页面只加载最近 200 条，不是全量访问日志 | 长期审计需服务端分页、导出和独立日志策略 |
| U09 | 附件内容 | 文件受控保存并绑定知识修订 | PDF/Office 正文不索引，没有外部恶意内容扫描 | 将附件视为人工参考，不视为已检索知识 |
| U10 | 跨仓契约 | 能核对双方登记路径的内容指纹 | 指纹一致不证明 API 兼容、调用存在或运行成功 | 契约状态只用于证据新鲜度，仍需集成测试 |
| U11 | 开发结果回报 | 保存具名测试、审批、反馈和 finalCommit | finalCommit 不同于审查 Head 时不验证祖先关系；证据 URL 不自动验真 | 由 CI 或评审人核对来源，必要时追加纠正回报 |
| U12 | 后台任务可靠性 | 有心跳、超时、取消、重试和启动恢复逻辑 | 目标部署环境的崩溃、断电、外部进程卡死尚需故障注入 | 上线前演练 Worker 重启和超时场景 |
| U13 | 远程导入安全 | 有协议和路径校验、加密凭据 | SSRF、DNS 重绑定、重定向和恶意仓库仍缺完整生产验收 | 通过出口网络策略限制目标地址 |
| U14 | 浏览器端到端 | 关键组件测试和构建可运行 | 完整视觉回归、跨页操作和不同分辨率没有自动化基线 | 发布前按本手册执行人工 UI 验收 |
| U15 | CI 身份 | CI 确定性接口与脚本已存在 | 访问令牌尚不能调用 ci-check，现阶段依赖短期 Session/CSRF | 使用专用最小权限账号并轮换 Secret |
| U16 | 多租户 | 账号级仓库可见性和仓库权限已实现 | 没有企业租户、组织边界、SSO/OIDC 和租户级密钥 | 不要把仓库权限隔离描述为完整多租户 |
| U17 | 质量门禁数据 | 有 evaluation 数据集和阈值框架 | 若缺少具名人工标签与完整实测结果，不能声称达到发布质量 | 发布报告必须附数据集版本、运行结果和评审人 |
| U18 | Flyway 升级 | 空库可由合并 V1 初始化 | 合并 V1 不兼容保留旧 Flyway 历史的业务库 | 已有数据环境先备份并设计迁移，不能直接清库 |

## 9. 部署与运行前置条件

Java 后端启动时会同时提供 REST 和 MCP。运行至少需要：

- Java 17 和可构建的 Spring Boot 后端。
- PostgreSQL，并安装 pgvector 扩展。
- APP_DATASOURCE_URL、APP_DATASOURCE_USERNAME、APP_DATASOURCE_PASSWORD。
- APP_MANAGED_DATA_ROOT 和 APP_REPOSITORY_ALLOWED_ROOTS。
- 首次空库所需的 APP_INITIAL_ADMIN_USERNAME 与 APP_INITIAL_ADMIN_PASSWORD。
- 保存 Git 或模型密钥时所需的独立主密钥。
- 需要图谱时安装并配置 CodeGraph CLI。
- 需要外部问答或语义向量时配置可访问的模型服务。
- 生产环境启用 HTTPS，并正确配置 Secure Cookie 和可信转发头。

Flyway 当前只有合并后的 V1 初始化基线，只适用于空库或明确重建后的数据库。已有业务数据的环境必须先制定迁移和回滚方案。

## 10. 建议验收顺序

1. 登录、强制改密、会话恢复和仓库权限隔离。
2. 小型本地 Git 或 ZIP 的接入、快照和内容索引。
3. 已知类名或函数名的检索、源码定位和快照一致性。
4. 本地证据问答、引用、历史恢复。
5. 成员授权后用另一个账号和其访问令牌验证同仓可见、未授权仓不可见。
6. 知识草稿、评审、发布、来源漂移和审查命中。
7. WORKTREE、Commit、Commit Range 审查及结果回报。
8. 当前向量模型覆盖、切换模型后的缺失和补建。
9. CodeGraph 已知调用链、失败降级和重建。
10. 测试 GitHub/GitLab 项目的 PR/MR、Webhook、评论与 Head 变化。
11. CI 的 PASS、规则 FAIL 和平台错误退出码 2。
12. Worker 重启、任务超时、数据库备份恢复和受管目录恢复。

## 11. 维护依据

本手册主要核对以下实现入口：

- 前端路由与菜单：frontend/src/router/index.ts、frontend/src/components/workspaceNavigation.ts
- 当前仓库与页签：frontend/src/stores/repositoryStore.ts、frontend/src/stores/workspaceTabs.ts
- 前端 API：frontend/src/api
- 后端接口：backend/src/main/java/com/analyzercoder/interfaces/rest
- 权限与令牌：backend/src/main/java/com/analyzercoder/security
- 核心服务：backend/src/main/java/com/analyzercoder/application
- 数据结构：backend/src/main/resources/db/migration/V1__init_schema.sql
- SQL 映射：backend/src/main/resources/mappers
- MCP：backend/src/main/resources/mcp-tools.json、mcp-server
- CI 与评测：scripts/ci-task-review.mjs、evaluation

