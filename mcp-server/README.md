# MCP 接入说明

Analyzer Coder 的 Java 后端在 `/api/mcp` 提供只读 Streamable HTTP MCP 服务。所有账户使用同一地址，调用者用自己的账户访问令牌认证；无需为每个项目或分支启动单独服务。仅支持 stdio 的客户端可使用本仓库的 Node.js 适配器，两种入口提供同名工具。

## 接入前准备

1. 启动 Java 后端，确认客户端可访问平台地址。在系统“MCP 接入”页面创建**账户访问令牌**，创建后立即保存；它与 Git/GitLab 仓库凭据不同。
2. 确认令牌所属账户拥有目标项目的 READ 权限。权限在每次调用时重新校验，撤销权限或令牌后即不可继续读取。
3. 联合检索需要已准备的代码内容版本。查询 Git 分支图谱还需要准备目标分支并发布 CodeGraph 产物；可在“项目管理”的分支列表操作，在 `list_codegraph_scopes` 中确认 `codegraphReady: true`。构建与准备不由 MCP 读取工具触发。

ZIP 项目使用固定逻辑分支 `WORKSPACE`，与 Git 分支一样出现在图谱发现列表中并要求显式分支上下文。

## 远程 HTTP 接入

在支持 Streamable HTTP 和自定义请求头的 MCP 客户端中填写服务地址与 Bearer 令牌。不同客户端的配置字段可能不同，下例是常见 JSON 形式：

```json
{
  "mcpServers": {
    "analyzer-coder": {
      "url": "https://<平台域名>/api/mcp",
      "headers": {
        "Authorization": "Bearer <账户访问令牌>"
      }
    }
  }
}
```

平台内“MCP 接入”页面可按当前平台地址生成并复制配置模板；只在客户端本地填入真实令牌，不要提交到代码仓库。正式部署使用 HTTPS。平台采用手动访问令牌，没有 OAuth 跳转；客户端必须能设置 Authorization 请求头。

## 本地 stdio 适配器

仅支持 stdio 的客户端使用 Node.js 20+。适配器仍连接同一个 Java 后端，图谱工具经后端 `/api/mcp` 执行，需提供账户访问令牌：

```powershell
$env:ANALYZER_API_BASE = 'http://127.0.0.1:8080'
$env:ANALYZER_ACCESS_TOKEN = '<账户访问令牌>'
npm --prefix mcp-server ci
npm --prefix mcp-server start
```

在客户端配置中，命令指向本仓库 `mcp-server/src/server.mjs` 的**绝对路径**，并通过客户端环境变量设置 `ANALYZER_API_BASE`、`ANALYZER_ACCESS_TOKEN`。如果 Java 后端不在本机，地址应填客户端能够访问的 HTTPS 地址。远程 HTTP 接入无需安装 Node.js 或运行此适配器。

## 选择项目和分支

MCP 工具不提供独立的选择弹窗。AI 客户端可展示发现结果让使用者按项目名、分支名选择；同名时应确认 `repositoryId` 和 `branchId`，不要自行猜测或默认切换分支。

1. 调用 `list_codegraph_scopes`，可传 `{ "page": 1, "pageSize": 20 }`。它只返回当前令牌可读取的 Git 项目和受管分支，每个分支包含名称、状态、`contentVersion`、`commitSha` 与 `codegraphReady`。只有 `codegraphReady: true` 的分支可做图谱查询。
2. 从所选条目取得 `repositoryId`、`branchId`。调用 `resolve_project_context` 固定版本，或在首次图谱查询中直接传这两个 ID：

```json
{
  "repositoryId": "<所选项目 UUID>",
  "branchId": "<所选分支 UUID>"
}
```

3. `resolve_project_context` 返回 `contextId`、`contentVersion`、`commitSha`、分支名和过期时间。图谱工具返回的 `context` 字段也包含这些坐标，另有 `artifactId`、`cliVersion` 和 `result`。后续调用传 `repositoryId + contextId`，例如：

```json
{
  "repositoryId": "<所选项目 UUID>",
  "contextId": "<上一步返回的 contextId>",
  "query": "订单超时由哪些函数处理？"
}
```

上例可用于 `codegraph_explore`。`contextId` 绑定账户和创建时的内容版本，有效期一小时；它不会随分支更新自动切换。过期后重新解析分支，并对照返回的 `commitSha`、`contentVersion`，确认是否切换到新版本。网页“分支工作区”的“复制 MCP 参数”也可取得项目和分支 ID。

## 工具目录

所有图谱工具都必须传 `repositoryId`，并且在 `branchId`、`contextId` 中至少传一个。不能传服务器文件路径、任意内容版本 ID 或图谱产物路径。下表列出额外参数；可选参数省略时使用服务端默认值。

| 工具 | 作用 | 额外参数 |
| --- | --- | --- |
| `list_codegraph_scopes` | 发现授权项目与分支 | 可选 `page`（默认 1）、`pageSize`（默认 20，最多 50） |
| `resolve_project_context` | 固定已准备分支内容版本 | `repositoryId`，以及 `branchId` 或 `contextId` |
| `search_project` | 联合检索代码与知识 | `repositoryId`、`query`；可选 `branchId` / `contextId`、`limit`（默认 20，最多 50） |
| `codegraph_explore` | 相关源码与调用路径 | `query`；可选 `maxFiles`（默认 8，最多 20） |
| `codegraph_node` | 读取符号或文件与行号 | `name` 或 `file` 至少一个；可选 `offset`、`limit` |
| `codegraph_search` | 搜索图谱符号 | `query`；可选 `limit`（默认 20，最多 50） |
| `codegraph_callers` / `codegraph_callees` | 查看调用方 / 被调用方 | `symbol`；可选 `limit`（默认 20，最多 50） |
| `codegraph_impact` | 符号影响分析 | `symbol`；可选 `depth`（默认 2，最多 5） |
| `codegraph_files` / `codegraph_status` | 已索引文件 / 图谱状态 | `files` 可选 `filter`、`pattern`；`status` 无额外参数 |
| `codegraph_affected` | 根据改动文件寻找受影响测试线索 | `files`：1–20 个分支内相对路径；可选 `depth`（默认 2，最多 5） |

只传 `repositoryId` 的 `search_project` 保持原有行为，读取项目默认内容版本；传入分支参数时返回 `{ "context": ..., "result": ... }`。图谱工具只读取所选分支的已发布受管产物，不执行测试、不构建图谱、不修改代码或知识。`codegraph_affected` 返回的是关联线索，并非实际运行的测试结果。

## 常见错误

| 现象 | 处理 |
| --- | --- |
| `401 / ACCESS_TOKEN_INVALID` | 令牌无效、过期、已撤销或账户状态改变；重新创建令牌。 |
| `403 / FORBIDDEN` | 令牌账户没有该项目 READ 权限；检查项目授权。 |
| `BRANCH_NOT_READY` | 所选分支没有已发布内容版本；在项目管理准备该分支。 |
| `CODEGRAPH_ARTIFACT_NOT_AVAILABLE` | 分支有内容版本但尚无已发布 CodeGraph 产物；构建图谱并确认 `codegraphReady`。 |
| `CONTEXT_EXPIRED` / `CONTEXT_MISMATCH` | 重新选择或解析分支，不要把其他账户、项目或分支的 `contextId` 混用。 |
| `CODEGRAPH_QUERY_FAILED` | 检查符号、文件和查询范围；若持续失败，查看服务端日志及 CodeGraph CLI 状态。 |
| 连接失败 | 确认 Java 后端运行、地址以 `/api/mcp` 结尾、客户端发送 Bearer 令牌；携带 Origin 时需与服务公开地址一致。 |

工具目录定义在 `backend/src/main/resources/mcp-tools.json`。调整接口时需同步更新 Java 实现、stdio 适配器和两处使用说明。
