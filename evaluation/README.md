# 联合检索质量评测

评测范围与最小产品保持一致，只验证两件事：

- `retrieval.jsonl`：自然语言查询能否在前 10 条结果中找到正确代码路径；
- `qa.jsonl`：基于检索证据生成的回答是否有引用、是否得到人工支持。

执行：

```bash
node scripts/evaluate-quality.mjs --validate
node scripts/evaluate-quality.mjs --results evaluation/results/<run>.json
```

结果文件只需包含 `retrieval` 与 `qa` 两组记录。每条检索结果提供 `returnedPaths` 和 `latencyMs`；每条问答结果提供经具名人工判断的 `claims`、`assessor` 和 `latencyMs`。发布门槛见 `thresholds.json`。
