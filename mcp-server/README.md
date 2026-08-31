# Analyzer Coder MCP Server

这是本地 stdio MCP 适配器。它不复制仓库权限、知识审核、版本校验或任务审查逻辑，所有工具都调用 Analyzer Coder 后端 HTTP API。

## 启动

需要 Node.js 20+，并从已登录会话取得原始会话 Token 与对应 CSRF Token：

```powershell
$env:ANALYZER_API_BASE = 'http://127.0.0.1:8080'
$env:ANALYZER_SESSION_TOKEN = '<AC_SESSION cookie value>'
$env:ANALYZER_CSRF_TOKEN = '<csrfToken returned by /api/auth/login or /api/auth/me>'
npm --prefix mcp-server start
```

凭据只从环境读取，通过 `AC_SESSION` Cookie 和 `X-CSRF-Token` 原样交给后端；stdout 仅承载 MCP JSON-RPC，运行信息写入 stderr。

## 工具

- `review_change`：真实 Git 变更审查；
- `get_task_context`：版本化 Agent 上下文；
- `get_rules_for_symbol`：投影审查中已确定命中的符号规则；
- `get_required_tests`：读取审查产生的必须测试；
- `get_stale_knowledge`：读取审查隔离的陈旧知识；
- `get_evidence`：按来源 ID 获取完整真实性记录；
- `report_task_outcome`：把最终 Commit、实际测试/审批以及具名误报、漏报和知识更新判断追加为不可变结果；反馈不会自动修改正式知识。

除显式 `includeContent/includeEvidence` 外，工具默认返回精简结构和来源 ID，避免把大量源码或证据直接灌入 Agent 上下文。
