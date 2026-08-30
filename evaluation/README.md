# 质量评测与发布门禁

这里保存可执行的金标准，不保存演示分数。数据集绑定仓库内真实文件，覆盖三类项目形态：

- `JAVA_SPRING`：Spring Boot 后端；
- `VUE_TYPESCRIPT`：Vue/TypeScript 前端；
- `OPS_AUTOMATION`：部署配置和自动化脚本。

版本 2 在原有 50 个源码定位问题、30 个证据问答和 20 个影响预估任务之外，新增 10 个任务审查样本与 8 个知识漂移样本。执行：

```bash
node scripts/evaluate-quality.mjs --validate
node scripts/evaluate-quality.mjs --results evaluation/results/<run>.json
```

`--validate` 只证明结构、数量、路径和人工判断记录格式有效；输出中的 `scoreable` 只有在所有任务审查与漂移标签都由具名人工评审并记录时间后才为 `true`。当前新增样本刻意保留为 `PENDING_HUMAN_REVIEW`，因为开发程序不能冒充人工签署金标准；在复核完成前，`--results` 和发布门禁会拒绝计算通过。

实际结果文件结构：

```json
{
  "datasetVersion": "2.0.0",
  "runId": "release-candidate-2026-08-26",
  "retrieval": [{ "id": "RET-001", "latencyMs": 120, "returnedPaths": ["backend/..."] }],
  "qa": [{
    "id": "QA-001",
    "latencyMs": 900,
    "claims": [{ "text": "...", "cited": true, "supported": true }],
    "assessor": { "type": "HUMAN", "name": "reviewer", "assessedAt": "2026-08-26T10:00:00Z" }
  }],
  "change": [{ "id": "CHG-001", "latencyMs": 250, "predictedFiles": ["backend/..."] }],
  "taskReview": [{
    "id": "TR-001",
    "latencyMs": 320,
    "changedFiles": ["backend/..."],
    "changedSymbols": ["Citation"],
    "knowledgeIds": ["KN-VERSIONED-EVIDENCE"],
    "requiredTests": ["mvn -pl backend test"],
    "staleKnowledgeIds": [],
    "findings": [{
      "id": "finding-id",
      "sources": [{
        "id": "source-id", "sourceType": "CODE_FACT", "snapshotId": "snapshot-uuid",
        "filePath": "backend/...", "detail": "指定快照中的代码事实"
      }]
    }],
    "pathChecks": [{ "path": "backend/...", "existsAtVersion": true }]
  }],
  "knowledgeDrift": [{
    "id": "KD-001",
    "latencyMs": 180,
    "driftedKnowledgeIds": ["KN-SNAPSHOT-CONSISTENCY"],
    "evidence": [{
      "id": "drift-source-id", "sourceType": "GIT_FACT", "commitSha": "commit-sha",
      "filePath": "backend/...", "detail": "精确引用内容在该提交中变化"
    }]
  }]
}
```

任务审查中的逻辑知识标识必须在实际运行配置中映射到该环境的知识卡 UUID，不能按标题模糊匹配。`pathChecks` 必须覆盖所有变更文件和 Finding 来源路径，用于区分“真实但误报的文件”与“根本不存在的编造路径”。

陈述支持率只接受带姓名和时间的人工判定；程序不会把引用格式正确自动当成语义支持。所有用例与结果字段必须齐全，缺项、重复 ID、未知 ID、未检查路径或低于 `thresholds.json` 均以非零状态退出。报告同时输出文件误报/漏报、符号/知识/漂移的 TP、FP、FN，以及完整真实性来源比例和 P95 延迟，可由 CI 保存为发布证据。
