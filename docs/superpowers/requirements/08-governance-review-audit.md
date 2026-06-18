# 治理、复审与审计需求

## 功能目标

治理运营负责把知识缺失、证据失效、索引失败、分析失败、低命中查询、上下文导出和影响分析命中回流为可处理的治理项或 ReviewTask，帮助项目负责人维护知识可信度和数据新鲜度。

## 功能点

| 功能域 | 功能点 |
|---|---|
| 知识看板 | 知识总数、已确认数、待复审数、逾期数、类型分布 |
| 项目看板 | 项目代码规模、接口数、知识覆盖、索引状态、最近任务 |
| 质量看板 | 缺证据知识、过期知识、低质量文档 |
| 使用看板 | 检索次数、上下文包生成次数、热门问题、低命中查询 |
| 治理项 | 缺证据、证据失效、复审逾期、低命中、任务失败 |
| ReviewTask | 创建、处理、忽略、回写知识或证据状态 |
| 审计追踪 | 权限变更、知识确认、上下文导出、任务执行 |

## 核心对象

### ReviewTask

关键字段：

- `id`
- `projectId`
- `assetId`
- `sourceTaskId`
- `reason`
- `status`: `open | resolved | ignored`
- `ownerUserId`
- `dueAt`
- `createdAt`
- `updatedAt`

### AuditEvent

关键字段：

- `id`
- `projectId`
- `actorUserId`
- `action`
- `targetType`
- `targetId`
- `detail`
- `createdAt`
- `ipAddress`

## 治理闭环

```text
低质量知识、过期知识、低命中查询、索引失败、变更命中
  -> 生成治理项
  -> 分配负责人或复审人
  -> 复审知识和证据
  -> 更新 KnowledgeAsset 或 Evidence
  -> 更新 Neo4j 图关系
  -> 重建 Qdrant 索引
  -> 影响之后的检索、上下文包和影响分析
```

治理项来源：

- 知识缺少证据、证据失效、复审日期逾期。
- 可信检索低命中或用户频繁无结果查询。
- 变更影响分析命中已确认知识。
- API 映射发现未匹配接口、方法不一致或接口缺说明。
- 分析任务、索引任务、embedding 任务、Neo4j 写入任务失败。

治理闭环输出：

- ReviewTask 或治理待办。
- 知识状态变更记录。
- Evidence 更新记录。
- SearchLog 处理记录。
- Neo4j 图关系更新记录。
- 重新索引任务。
- 审计事件。

## ReviewTask 处理闭环

```text
打开 ReviewTask
  -> 查看触发原因、关联 KnowledgeAsset、Evidence、ChangeTask 和图路径
  -> 项目负责人更新知识或证据，或确认无需处理
  -> resolved/ignored
  -> 回写 KnowledgeAsset/Evidence 状态
  -> 更新 Neo4j 图关系
  -> 标记 Qdrant 索引 stale 或触发索引任务
  -> 写入 AuditEvent
```

处理规则：

- `resolved` 表示负责人已复审并更新或确认相关 KnowledgeAsset/Evidence；如果知识仍然有效，可以从待复审回到已确认。
- `ignored` 表示负责人明确暂不处理候选影响；系统保留原因，不自动改变 KnowledgeAsset 状态。
- ReviewTask 处理后必须写审计，并根据处理结果触发索引和图关系更新。

## 审计规则

- 关键操作必须写审计，包括登录、项目创建、成员授权、知识确认、知识归档、索引构建、上下文包导出、变更影响分析和项目配置修改。
- AuditEvent 不应保存敏感明文；必要时保存脱敏摘要或对象 ID。
- 上下文包导出必须记录引用清单和导出人。
- 忽略 ReviewTask 也必须记录原因和审计事件。

## 页面与操作

| 页面 | 主要操作 | 关键展示 |
|---|---|---|
| 治理看板 | 查看指标、筛选项目、跳转资产、触发复审或重建索引 | 知识状态、索引状态、失败任务、低命中查询 |
| 项目审计 | 按操作人、类型、时间过滤项目内关键操作 | 操作人、时间、对象、动作和结果 |

## 验收标准

- 项目负责人可以看到知识状态统计、缺证据知识、待复审知识、ReviewTask、低命中查询、索引失败和分析失败。
- 项目负责人可以处理或忽略 ReviewTask，处理和忽略都记录审计。
- ReviewTask 处理后会回写 KnowledgeAsset 或 Evidence 状态，并触发索引和图关系更新。
- 低命中或无结果查询生成脱敏 SearchLog，并能在治理看板中按项目查看。
- 审计能查询项目创建、成员授权、知识确认、索引构建、上下文导出和影响分析记录。
