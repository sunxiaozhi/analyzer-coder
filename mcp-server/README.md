# 代码与知识检索 MCP

MCP 接口随 Java 后端启动，远程地址为 `https://<平台域名>/api/mcp`。它只暴露一个只读工具：

- `search_project`：在指定仓库的当前快照中联合检索代码与知识。

参数如下：

```json
{
  "repositoryId": "<仓库 UUID>",
  "query": "订单超时在哪里处理，有哪些相关约束？",
  "limit": 20
}
```

在平台创建账户访问令牌后，将它放入 MCP 客户端请求头：

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

每次调用都会按令牌绑定账户的实时仓库 READ 权限重新校验。工具不会修改代码、知识或仓库状态。

账户停用、角色变更、修改或重置密码会撤销旧令牌。锁定或待改密账户不能创建或使用令牌。退出浏览器登录不会撤销独立访问令牌，可在令牌管理中单独撤销。

令牌仅允许远程 MCP 和下方 stdio 适配器执行只读的代码与知识检索，不能用于账户管理、创建更多令牌或修改项目数据。管理接口仍要求浏览器登录和 CSRF 校验。正式部署使用 HTTPS；反向代理已有 `/api` 转发规则即可覆盖 `/api/mcp`，若客户端携带 Origin，需要与服务公开地址一致并正确配置受信任的转发头。

## 兼容：本地 stdio

仅支持本地进程的客户端可使用 Node.js 20+ stdio 适配器：

```powershell
$env:ANALYZER_API_BASE = 'http://127.0.0.1:8080'
$env:ANALYZER_ACCESS_TOKEN = '<账户访问令牌>'
npm --prefix mcp-server ci
npm --prefix mcp-server start
```

工具目录定义在 `backend/src/main/resources/mcp-tools.json`。调整参数时同步修改 stdio 适配器、Java 实现及测试。
