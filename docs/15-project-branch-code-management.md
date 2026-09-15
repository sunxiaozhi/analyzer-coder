# 项目与分支代码管理

## 职责划分

| 层级 | 管理内容 |
| --- | --- |
| 项目 | 名称、描述、代码来源、凭据、所有者、成员与权限、默认阅读分支 |
| 分支 | 跟踪/归档、代码同步、当前发布快照、同步状态 |
| 分支快照 | 提交、只读源码目录、内容索引、向量索引、CodeGraph、代码证据 |

多个分支属于同一项目并继承项目权限。Git 对象可在项目源仓库共享，但每次发布的源码快照和派生索引按分支快照隔离。同步不会 checkout 或修改用户源工作区；本地 Git 分支读取已提交代码，不包含未提交变更。

默认分支是首次阅读偏好：已有阅读选择优先。修改偏好不发布代码、不启动任务；未就绪的选定分支不会回退展示其他分支。

## 界面入口

- 项目管理：项目设置（资料、代码来源信息与凭据）、成员与权限。
- 分支列表：选择分支、查看代码/内容索引/图谱状态、跟踪与归档。
- 分支代码与索引面板：同步、一键准备和打开代码；单项重建、向量和 MCP 参数折叠在更多操作中。
- 知识详情：记录当前知识修订对阅读分支与快照的验证结论；知识列表支持验证状态筛选。
- 任务中心：按项目、分支分页查看准备任务历史与错误，不改变阅读分支。
- 顶部选择器：切换阅读分支；总览、图谱、代码检索、问答与知识读取使用锁定上下文。
- ZIP：保留单版本更新/索引/图谱流程，不伪造 Git 分支。

同步只获取目标分支并发布源码。内容索引固定到已同步快照，不拉取 Git；同一快照的内容索引重复提交为幂等操作。图谱可针对同一快照重新构建。向量任务要求内容索引就绪，就绪状态核对当前启用模型、维度、检索能力与内容哈希。

一键准备依次执行同步、内容索引、图谱；向量索引按需构建。任务按分支串行，不同分支可并发；进程恢复保留目标提交与快照，发布由权限和任务 token/generation 检查约束。失败保留已发布版本，任务面板显示失败，可重新提交对应操作。

历史快照及证据保留。旧快照的索引任务不更新新版本的发布指针或 Markdown 来源清单。

## 新 API

| 方法 | 路径（均以 /api/repositories/{repositoryId} 开头） | 作用 |
| --- | --- | --- |
| GET | /branch-index-statuses | 项目各分支当前发布快照的索引状态 |
| GET | /branches/{branchId}/index-status?contextId=... | 指定阅读快照的状态，含历史快照 |
| POST | /branches/{branchId}/code-jobs | 提交分支任务 |
| GET | /branch-preparation-jobs/history?pageNum=1&pageSize=15&branchId=... | 分支准备任务分页历史，branchId 可省略 |
| GET | /branch-overview | 固定快照的总览、代码事实和分支知识健康 |

code-jobs 请求字段：kind、contextId。kind 为 SYNC、CONTENT、GRAPH、PREPARE。
SYNC/PREPARE 不接受历史上下文；CONTENT/GRAPH 必须指定已锁定的 contextId，后端检查账号、项目、分支和快照对应关系。
向量继续使用 /branch-vector-jobs，固定 contextId 与 branchId。
branch-overview 必须提供 X-Branch-Context。新 Git 页面缺少/失效/切换中的上下文时显示阻断提示，不调用默认版本接口。

读取继承项目 READ 权限，任务继承 MAINTAIN；项目资料/分支归档等沿用既有项目治理权限。旧默认版本接口保留兼容，不再作为新 Git 管理界面的操作入口。

## 数据迁移与验证

V8 增加分支同步时间、快照内容索引发布时间和独立任务类型；回填已有内容索引。旧 SNAPSHOT/VECTORS 任务保留。旧索引路径同步维护新状态标记，旧分支准备发布时识别已经原子写入的内容。

数据库测试 BranchCodeOperationsDatabaseTest 使用唯一临时 schema，先迁移至 V7 并插入旧数据，再迁移 V8，覆盖独立任务、重复任务、同提交复用、分支隔离、历史保留和项目权限。启用环境变量：

- APP_BRANCH_OPERATIONS_TEST_URL
- APP_BRANCH_OPERATIONS_TEST_USER
- APP_BRANCH_OPERATIONS_TEST_PASSWORD

测试不会启动 Spring 后台 worker，退出清理仅针对明确验证过名称的临时 schema。生产迁移由正常 Spring Boot 启动执行，不能重跑历史迁移或重建已有数据库。
