# 分支工作区与准备流程

## 核心模型

系统的业务边界是“项目 + 分支”。项目负责来源、权限和治理；分支拥有代码、知识、阅读上下文及全部派生产物。

系统不保存可浏览或可恢复的历史源码副本，也不存在独立的版本实体。`repository_branches.content_version` 是每次发布时生成的内部 UUID，只用于拒绝过期任务、固定一次阅读请求以及隔离索引、向量、图谱和问答证据。它不是用户可管理的版本，不提供列表、保留或删除 API。

每个分支只有一个受管工作区：

`<managed-root>/<repositoryId>/branches/<branchId>/content`

同步新提交时原地更新该目录。`previous_content_version` 仅在增量内容索引期间暂存上一代令牌；新索引发布后立即清空，并清理上一代代码片段和图谱产物。

## 来源映射

- 本地 Git：分支名解析到本地 `refs/heads/<name>`。
- 远程 Git / GitLab：先抓取明确分支，再发布到对应受管分支。
- ZIP：导入时建立内部 Git 提交，对外固定为逻辑分支 `WORKSPACE`；它使用与 Git 完全相同的分支准备、阅读和知识路径，但不允许添加其他跟踪分支。

`default_branch` 只是首次进入项目时的阅读偏好。修改它不会同步代码、切换服务端工作树或改变其他分支数据。

## 同步与准备

分支任务只有五种：

| kind | 行为 |
| --- | --- |
| `SYNC` | 解析提交并更新分支工作区，生成新的内部内容令牌 |
| `CONTENT` | 扫描当前工作区并发布代码片段与 Markdown 来源 |
| `GRAPH` | 为当前令牌构建 CodeGraph |
| `VECTORS` | 为当前代码片段补齐向量 |
| `PREPARE` | 依次执行同步、内容索引和图谱构建 |

同一分支同类活动任务会去重。任务执行前后都校验权限、分支状态、任务尝试令牌和当前内容令牌；旧任务不能覆盖新同步结果。分支状态为 `PENDING`、`BUILDING`、`READY` 或 `FAILED`。

同步成功但内容索引尚未完成时，旧片段可短暂作为增量输入，但不会成为新阅读上下文。内容索引发布时，未变文件可复用片段内容并改绑新令牌，完成后再删除旧派生数据。

## 阅读上下文

客户端选择分支后创建 `branch_read_contexts`，其中固定：账号、项目、分支、当前内容令牌和过期时间。后续检索、代码读取、知识、总览、问答和图谱请求必须携带 `branchId`/`contextId`，并在服务端重新验证：

- 上下文属于当前账号和项目；
- 分支仍处于活动状态；
- 上下文令牌仍等于分支当前令牌；
- 分支已完成准备。

不满足条件时请求失败，不回退到默认分支，也不借用其他分支数据。

## 知识绑定

`knowledge_cards.branch_id` 是必填外键。知识卡及每次修订只属于一个分支；Markdown 来源和来源证明同样携带分支标识。系统不提供“项目共享”“选择多个分支”或知识范围复制接口。

分支验证记录按 `(card, revision, branch, content_version)` 保存，用来说明某次知识修订是否已经针对当前代码发布进行核对。验证记录不会改变知识所有权。

## 数据所有权

| 数据 | 所有者 |
| --- | --- |
| `repository_branches` | 项目下的稳定分支身份及当前工作区状态 |
| `code_chunks` | 项目 + 分支 + 当前内容令牌 |
| `knowledge_cards` / `knowledge_card_revisions` | 项目 + 分支 |
| `repository_markdown_sources` | 项目 + 分支 + 当前内容令牌 |
| `codegraph_artifacts` / `chunk_embeddings` | 当前内容令牌派生产物 |
| `branch_read_contexts` | 账号 + 项目 + 分支 + 当前内容令牌 |
| `branch_preparation_jobs` | 项目 + 分支 |

## 关键实现

- 表结构：`backend/src/main/resources/db/migration/V3__branch_contexts.sql`、`V7__branch_lifecycle_and_provenance.sql`、`V8__branch_code_operations.sql`
- 分支发布与阅读：`RepositoryBranchService`
- 分阶段操作：`BranchCodeOperationsService`、`BranchPreparationJobs`
- 工作区更新：`GitBranchContentVersionFactory`
- 增量内容索引：`BranchContentIndexService`
- 知识归属：`BranchKnowledgeService`、`IntelligenceMapper.xml`
