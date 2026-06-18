# 综合研发智能平台需求拆分索引

本目录按功能域拆分 `docs/superpowers/specs/rd-intelligence-platform-requirements.md` 的需求内容，便于后续按模块评审、排期和实现。原总需求文档保留作为完整背景与历史版本，本目录作为功能化需求入口。

## 文档清单

| 文档 | 覆盖功能 |
|---|---|
| `01-product-scope-and-roles.md` | 产品定位、目标用户、设计原则、核心范围、增强范围、边界 |
| `02-platform-projects-permissions.md` | 账号、角色、权限、项目空间、项目接入、数据隔离 |
| `03-code-intelligence-and-graph.md` | 代码分析、接口识别、SQL 线索、调用关系、Neo4j 图关系 |
| `04-knowledge-assets-and-evidence.md` | 知识文档、知识资产、状态流转、Evidence、知识变更同步 |
| `05-search-index-and-evidence.md` | Qdrant 索引、可信检索、SearchResult、SearchLog、结果复用 |
| `06-ai-context-package.md` | AI 上下文包、任务输入、上下文召回、导出审计 |
| `07-change-impact-analysis.md` | 文件列表/diff 影响初判、Neo4j 路径、Qdrant 语义候选、ReviewTask 候选 |
| `08-governance-review-audit.md` | 治理看板、ReviewTask、低命中治理、审计 |
| `09-architecture-nonfunctional-acceptance.md` | 架构选型、存储分工、非功能要求、验收用例 |

## 原章节覆盖映射

| 原需求章节 | 拆分后文档 |
|---|---|
| 1 背景与目标 | `01-product-scope-and-roles.md` |
| 2 产品定位 | `01-product-scope-and-roles.md` |
| 3 设计原则 | `01-product-scope-and-roles.md` |
| 4 目标用户与角色 | `01-product-scope-and-roles.md`、`02-platform-projects-permissions.md` |
| 5 一级功能域 | `01-product-scope-and-roles.md` |
| 6 全量功能地图 | `02` 至 `09` 各功能文档 |
| 7 核心业务闭环 | `02` 至 `08` 各功能文档 |
| 8 核心对象 | `02` 至 `08` 各功能文档 |
| 9 可信度与证据规则 | `04-knowledge-assets-and-evidence.md`、`05-search-index-and-evidence.md` |
| 10 AI 上下文包边界 | `06-ai-context-package.md` |
| 11 变更影响分析边界 | `07-change-impact-analysis.md` |
| 12 交付范围与优先级 | `01-product-scope-and-roles.md` |
| 13 验收标准 | `09-architecture-nonfunctional-acceptance.md` |
| 14 非功能需求 | `09-architecture-nonfunctional-acceptance.md` |
| 15 技术选型与架构设计 | `09-architecture-nonfunctional-acceptance.md` |
| 16 当前实现基础与规划映射 | `09-architecture-nonfunctional-acceptance.md` |
| 17 全局功能流图设计 | `03` 至 `08` 各功能文档 |
| 18 功能详细需求 | `02` 至 `08` 各功能文档 |
| 19 核心数据字段 | `02` 至 `08` 各功能文档 |
| 20 页面与操作清单 | `02` 至 `08` 各功能文档 |
| 21 业务规则与验收用例 | `09-architecture-nonfunctional-acceptance.md` |

## 核心首轮建议

首轮只验证“项目内可信上下文闭环”：

```text
项目接入
  -> 代码事实和 Neo4j 基础图关系
  -> 知识资产和 Evidence
  -> Qdrant 可信检索
  -> AI 上下文包
  -> 导出审计
```

变更影响、ReviewTask、治理看板作为后续核心切片接入，不应阻塞首轮上下文包价值验证。
