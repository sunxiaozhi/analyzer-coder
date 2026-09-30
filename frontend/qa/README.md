# UI 优化的本地验收

`ui-optimization.html` 加载实际组件，以内存 fetch 模拟数据完成界面检查。数据不会持久化，页面刷新即重置。它不验证真实后端、登录或外部服务。

## 运行

在 `frontend` 目录执行 `npm run dev`。浏览器脚本需要可用的 Playwright 和 Edge；不自动安装依赖。

```powershell
# 如果 Playwright 已能由 require('playwright') 解析，可省略此项。
$env:QA_PLAYWRIGHT_MODULE = '<现有 Playwright 包的绝对路径>'
$env:QA_BROWSER_CHANNEL = 'msedge'
node qa/verify-ui.cjs
node qa/verify-layout.cjs
```

默认访问 `http://127.0.0.1:5173/qa/ui-optimization.html`，结果写入忽略提交的 `qa/results/`。可用 `QA_URL`、`QA_OUTPUT` 指定其他本地端口和输出目录。脚本限制 URL 必须指向 localhost 验收页。

`QA_MATCH` 可按名称运行单个交互场景，结果写入 `interaction-partial.json`；完整运行写入 `interaction-results.json`。`verify-layout.cjs` 检查 9 个管理/辅助页面的四档窗口，输出 `management-layout.json`。

交互覆盖创建/编辑/发布/恢复、三种共享范围、附件上传下载、Markdown 来源、分支验证、知识与问答的源码往返、检索失败重试、模型保存失败、审计定位、任务详情、图谱源码及纯键盘操作。浏览器没有使用真实服务密钥。

## 优化前对照

执行 `python qa/prepare-baseline.py` 从 `fa98698` 提取主体视图和核心组件到被忽略的 `qa/baseline/`，随后打开：

- `/qa/ui-optimization.html?baseline#/knowledge`
- `/qa/ui-optimization.html?baseline#/search`
- `/qa/ui-optimization.html?baseline#/ask`

外壳、store 和未提取依赖仍来自当前代码。该方式用于主体布局对比，不是历史版本的完整部署。

最终验收报告位于 `docs/qa/2026-09-30-ui/verification.md`（仓库根目录下）。
