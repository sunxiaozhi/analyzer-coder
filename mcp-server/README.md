# 代码知识平台 MCP 接入

## 推荐：Java 远程 MCP

MCP 接口随 Java 后端启动，地址为 `https://<平台域名>/api/mcp`。使用 Streamable HTTP 的无状态 JSON 响应模式，支持协议版本 2025-03-26、2025-06-18、2025-11-25，不提供独立 SSE GET 流或 OAuth 登录跳转。

1. 管理员在“账号权限 → 访问令牌”选择账户创建令牌；用户也可在“MCP 接入 → 管理我的访问令牌”管理自己的令牌。
2. 创建时填写名称和 1–365 天有效期，明文只显示一次。后端仅存 SHA-256 摘要。
3. 在支持自定义请求头的 MCP 客户端填写地址，并设置 `Authorization: Bearer <访问令牌>`。实际配置字段以客户端为准。

```json
{
  "mcpServers": {
    "analyzer-coder": {
      "url": "https://<平台域名>/api/mcp",
      "headers": { "Authorization": "Bearer <访问令牌>" }
    }
  }
}
```

所有账户使用同一地址。令牌只绑定账户，不保存一份仓库权限；每个工具调用都实时校验账户的仓库 READ 权限，并沿用现有业务规则。成员权限在项目管理的仓库治理中维护。管理员账户的令牌继承管理员权限，因此应为普通开发者使用其自己的账户。

账户停用、角色变更、修改或重置密码会撤销旧令牌。锁定或待改密账户不能创建或使用令牌。退出浏览器登录不会撤销独立访问令牌，可在令牌管理中单独撤销。

令牌仅允许远程 MCP 和下方 stdio 适配器使用的任务上下文、审查、结果回报接口，不能用于账户管理或创建更多令牌。管理接口仍要求浏览器登录和 CSRF 校验。正式部署使用 HTTPS；反向代理已有 `/api` 转发规则即可覆盖 `/api/mcp`，若客户端携带 Origin，需要与服务公开地址一致并正确配置受信任的转发头。

数据库新增 Flyway `V2__account_access_tokens.sql`，正常启动时自动迁移；关闭 Flyway 的环境需先按部署流程执行迁移。未执行迁移的旧后端不支持访问令牌。

## 兼容：本地 stdio

只支持本地进程的客户端仍可使用 Node.js 20+ 适配器：

```powershell
$env:ANALYZER_API_BASE = 'http://127.0.0.1:8080'
$env:ANALYZER_ACCESS_TOKEN = '<账户访问令牌>'
npm --prefix mcp-server ci
npm --prefix mcp-server start
```

优先使用 `ANALYZER_ACCESS_TOKEN`；旧的 `ANALYZER_SESSION_TOKEN`（AC_SESSION）与 `ANALYZER_CSRF_TOKEN` 组合仍兼容。凭据只从环境读取，stdout 仅承载 MCP JSON-RPC，运行信息写入 stderr。

## 工具

- `review_change`：真实 Git 变更审查；
- `get_task_context`：版本化 Agent 上下文；
- `get_rules_for_symbol`：审查中确定命中的符号规则；
- `get_required_tests`：审查要求的测试；
- `get_stale_knowledge`：审查中隔离的过期知识；
- `get_evidence`：当前仓库审查中的完整证据；
- `report_task_outcome`：追加具名交付结果和反馈，不自动修改正式知识。

默认提供精简数据，使用工具支持的 `includeContent/includeEvidence` 请求完整内容。`repositoryId` 必须属于令牌账户可访问的仓库；`reviewId` 和证据查找同样受仓库约束。

远程工具参数目录保存在 `backend/src/main/resources/mcp-tools.json`，由 stdio 工具的 Zod schema 导出。更新工具输入时运行 `npm --prefix mcp-server run export:catalog` 并同步 Java 工具执行实现及测试。
