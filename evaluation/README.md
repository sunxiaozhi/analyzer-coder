# 质量评测与发布门禁

这里保存可执行的金标准，不保存演示分数。数据集绑定仓库内真实文件，覆盖三类项目形态：

- `JAVA_SPRING`：Spring Boot 后端；
- `VUE_TYPESCRIPT`：Vue/TypeScript 前端；
- `OPS_AUTOMATION`：部署配置和自动化脚本。

数据规模固定为 50 个源码定位问题、30 个带人工证据的问答、20 个已知修改范围任务。执行：

```bash
node scripts/evaluate-quality.mjs --validate
node scripts/evaluate-quality.mjs --results evaluation/results/<run>.json
```

实际结果文件结构：

```json
{
  "datasetVersion": "1.0.0",
  "runId": "release-candidate-2026-08-26",
  "retrieval": [{ "id": "RET-001", "latencyMs": 120, "returnedPaths": ["backend/..."] }],
  "qa": [{
    "id": "QA-001",
    "latencyMs": 900,
    "claims": [{ "text": "...", "cited": true, "supported": true }],
    "assessor": { "type": "HUMAN", "name": "reviewer", "assessedAt": "2026-08-26T10:00:00Z" }
  }],
  "change": [{ "id": "CHG-001", "latencyMs": 250, "predictedFiles": ["backend/..."] }]
}
```

陈述支持率只接受带姓名和时间的人工判定；程序不会把引用格式正确自动当成语义支持。所有用例必须齐全，缺项、重复 ID、未知 ID、路径越界或低于 `thresholds.json` 均以非零状态退出。结果报告写入标准输出，可由 CI 保存为发布证据。
