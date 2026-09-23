# 数据模型

## 设计原则

项目是权限与来源容器，分支是代码和知识的唯一业务边界。数据库中没有历史源码副本表；分支行直接保存当前提交、工作区和发布状态。

`content_version` 是内部发布代次令牌。它用于并发控制、固定阅读上下文和关联派生产物，不代表可管理的历史对象。任何业务接口都不能列出、恢复或单独删除旧令牌。

## 核心关系

```text
repositories
  └─ repository_branches
       ├─ code_chunks ── chunk_embeddings
       ├─ knowledge_cards ── knowledge_card_revisions
       ├─ repository_markdown_sources
       ├─ branch_read_contexts ── branch_context_knowledge
       ├─ branch_preparation_jobs
       ├─ knowledge_branch_validations
       └─ codegraph_artifacts（由 content_version 隔离）
```

## 主要表

### `repositories`

保存项目名称、来源类型、受管路径、所有者、凭据绑定和默认阅读分支。`default_branch` 不承担代码发布职责。

仓库级当前版本字段仍供接入阶段写入；触发器将入口数据汇入默认分支。正常产品读写均以 `repository_branches` 为准。

### `repository_branches`

关键字段：

- `id`, `repo_id`, `name`：稳定分支身份，`(repo_id,name)` 唯一。
- `content_version`：当前发布代次令牌。
- `previous_content_version`：仅供一次增量索引复用，完成后清空。
- `commit_sha`, `content_path`, `published_at`：当前受管工作区坐标。
- `generation`：阻止并发准备任务后写覆盖。
- `preparation_status`, `preparation_error`：准备状态。
- `tracking_status`, `archived_at`：跟踪生命周期。
- `last_synced_at`, `content_indexed_at`：当前发布就绪标记。

### `code_chunks`

`branch_id` 必填并与 `repo_id` 组成外键，`content_version` 标记产生片段的发布代次。索引发布后只保留分支当前代次；同步与索引之间可以短暂保留 `previous_content_version` 对应片段，用于增量复制。

### `knowledge_cards` 与 `knowledge_card_revisions`

两表都有必填 `branch_id`。修订触发器把当前卡片的分支身份写入历史修订，知识不能跨分支共享或后改“适用范围”。代码引用仍记录产生证据时的内容令牌，以便检测漂移。

### `branch_read_contexts`

保存账号、项目、分支、内容令牌和过期时间。解析上下文时必须连接当前分支并验证令牌仍一致；表中不复制工作区路径。

### `repository_markdown_sources`

以 `(repo_id, branch_id, file_path)` 唯一。同步分支时更新当前内容令牌和哈希；由 Markdown 生成的知识卡沿用来源分支。

### `branch_preparation_jobs`

任务种类为 `SYNC`、`CONTENT`、`GRAPH`、`VECTORS`、`PREPARE`。`CONTENT`、`GRAPH`、`VECTORS` 必须固定 `target_content_version`；同步类任务在运行中解析并回写目标令牌。

## 迁移摘要

Flyway 仅保留 `V1__init_schema.sql`，一次建立当前 42 张业务表、约束、索引、触发器及必要的初始配置。已退役的变更评审与跨仓工程项目结构不再创建。

此基线只适用于空数据库。已有 V1–V9 执行历史与新版 V1 校验和不同；按重新建库的方式部署，不在旧库上直接执行迁移。

## 受管文件布局

```text
<repository-root>/<repositoryId>/worktree/                 # 接入后的源仓库
<content-root>/<repositoryId>/branches/<branchId>/
  content/                                                 # 分支唯一工作区
  current-commit                                           # 当前提交
  current-changes                                          # 最近一次增量清单
```

工作区由 `GitBranchContentVersionFactory` 原子更新。`.codegraph` 随分支工作区保留，以支持增量图谱构建。失败的 staging 目录会清理，不会成为可读内容。

## 删除与一致性

- 删除项目时级联删除分支及其代码、知识、上下文和任务数据，并清理受管目录；不会修改用户的原始本地仓库。
- 取消跟踪只改变分支生命周期，不产生历史版本管理面。
- 当前令牌变化后，旧阅读上下文立即失效。
- 旧代码片段和图谱产物在新内容索引发布后清理；问答和知识引用保留其证据坐标文本，不依赖历史源码副本继续可读。

## 架构禁项

以下结构不得重新引入：

- 独立的分支历史源码副本表；
- 分支上的“发布版本指针 + 多个版本实体”模型；
- 知识的项目共享或多分支范围表；
- 用户可见的版本保留、引用检查或手工删除接口；
- ZIP 专属的仓库级准备流水线。
