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

仅支持本地进程的客户端可使用 Node.js 20+ stdio 适配器：

```powershell
$env:ANALYZER_API_BASE = 'http://127.0.0.1:8080'
$env:ANALYZER_ACCESS_TOKEN = '<账户访问令牌>'
npm --prefix mcp-server ci
npm --prefix mcp-server start
```

工具目录定义在 `backend/src/main/resources/mcp-tools.json`。调整参数时同步修改 stdio 适配器、Java 实现及测试。
