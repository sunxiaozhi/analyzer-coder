# 变更影响分析需求

## 功能目标

变更影响分析定位为影响初判，不承诺完整静态分析准确性。核心可用版支持文件列表或 diff 输入，基于 MySQL 代码事实、Neo4j 图关系和 Qdrant 语义候选，输出候选影响、风险点、测试建议和待复审候选。

## 功能点

| 功能域 | 功能点 |
|---|---|
| 变更输入 | 文件列表、diff |
| 影响代码 | 受影响类、方法、接口、调用链、模块 |
| 影响知识 | 关联业务规则、ADR、接口说明、故障案例、规范 |
| 影响接口 | 新增接口、删除接口、路径变化、方法变化、前端调用影响 |
| 测试建议 | 推荐测试模块、接口用例、回归范围、风险场景 |
| 风险提示 | 缺少知识、知识待复审、接口未匹配、调用链不完整 |
| 复审触发 | 生成待复审候选或 ReviewTask |

## 核心对象

### ChangeAnalysisTask

关键字段：

- `id`
- `projectId`
- `snapshotId`
- `inputType`: `file_list | diff`
- `inputContent`
- `changedFiles`
- `matchedCodeFacts`
- `impactedEndpoints`
- `impactedKnowledgeAssets`
- `risks`
- `testSuggestions`
- `confidence`
- `status`
- `createdBy`
- `createdAt`

## 变更影响闭环

```text
输入文件列表或 diff
  -> 创建 ChangeAnalysisTask
  -> 解析变更文件和行区间
  -> 匹配 MySQL CodeFact
  -> 使用 Neo4j 展开有限深度影响路径
  -> 使用 Qdrant 补充语义相关候选
  -> 查找 Evidence
  -> 找到 KnowledgeAsset
  -> 输出影响接口、影响知识、风险和测试建议
  -> 生成待复审候选或 ReviewTask
  -> 写入 Neo4j IMPACTS/REQUIRES_REVIEW 关系
  -> 写入 AuditEvent
```

## 结果分层

- 确定事实：变更文件、变更行、直接命中的 CodeFact。
- 高置信候选：直接命中的 endpoint、SQL、绑定知识、Neo4j 一跳路径。
- 中置信候选：Neo4j 有限深度路径内的调用关系、接口和模块影响。
- 低置信候选：模块级、目录级、关键词或 Qdrant 语义召回影响。

候选影响必须带置信度、推荐原因和不确定性说明。系统不能把静态分析结果描述为确定结论。

## 边界

核心可用版不要求：

- commit 分析。
- 分支对比。
- PR/MR 集成。
- CI 自动触发。
- 完整跨语言调用图。
- 复杂运行时依赖推断。
- 自动生成最终测试计划。
- 自动判断业务规则冲突。

commit、分支对比、PR/MR 范围属于增强版输入。

## 页面与操作

| 页面 | 主要操作 | 关键展示 |
|---|---|---|
| 变更影响 | 输入文件列表或 diff、运行分析、标记待复审候选 | 影响代码、接口、知识、测试建议、置信度 |

## 验收标准

- 用户可以输入文件列表或 diff。
- 系统输出变更文件、命中代码事实、候选接口影响、候选知识影响、风险和测试建议。
- 结果标注置信度和不确定性。
- 影响分析使用 MySQL 命中直接 CodeFact，使用 Neo4j 展开有限深度关系路径，并可使用 Qdrant 补充语义候选。
- 命中已确认知识时生成待复审候选或 ReviewTask。
- 最终标记待复审必须由项目负责人确认。
