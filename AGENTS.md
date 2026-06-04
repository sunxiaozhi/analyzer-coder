# AGENTS.md

## 项目概览

这个仓库包含一个 Java/知识库分析器，以及一个本地 Web 控制台。

- Python 包：`src/java_ts_analyzer`
- Flask 后端：`web/backend`
- Vue 3 前端：`web/frontend`
- 文档：`docs`
- 运行时/生成数据：`.analzer_projects`、`.vector_store`、`.java_ts_results`、`logs`

分析器用于提取 Java 结构、Spring endpoint、MyBatis SQL 引用、知识库切块、本地 JSONL 向量索引、报告、图谱，以及 Obsidian 风格笔记。

## 环境准备

在 Windows PowerShell 中，从仓库根目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev,web]"
```

前端环境准备：

```powershell
cd web\frontend
npm install
```

## 常用命令

运行 Python 测试：

```powershell
.\.venv\Scripts\python.exe -m pytest
```

编译检查后端和 Python 源码：

```powershell
.\.venv\Scripts\python.exe -m compileall web\backend src
```

启动 Flask API：

```powershell
.\.venv\Scripts\python.exe -m web.backend.app
```

后端默认监听 `http://127.0.0.1:5050`。

启动 Vue 前端：

```powershell
cd web\frontend
npm run dev
```

前端默认监听 `http://127.0.0.1:5173`，并将 `/api` 代理到 Flask 后端。

构建前端：

```powershell
cd web\frontend
npm run build
```

运行命令行工具：

```powershell
java-ts-analyze path\to\java-project --json
java-ts-analyze . --source mixed --report
java-ts-analyze . --source mixed --index .vector_store\project.jsonl
java-ts-analyze --store .vector_store\project.jsonl --query "registration rule" --top-k 3
```

## 后端说明

- API 路由放在 `web/backend/routes.py`。
- 业务逻辑放在 `web/backend/services.py`。
- 面向用户的 API 错误使用 `APIError`。
- 路径校验必须严格。项目路径和知识库路径必须限制在配置的 workspace/project 根目录内。
- 知识库模板按项目隔离，存储在项目数据根目录下的 `knowledge_templates.json`。
- 知识库文件支持的后缀为 `.md`、`.markdown`、`.txt`、`.rst`、`.adoc`。
- 后端状态存储可能根据配置使用 MySQL 或 JSON；除非上下文代码明确，否则不要假设只有一种存储后端。

## 前端说明

- 前端使用 Vue 3、Vite、Element Plus 和 Composition API。
- 优先使用 `<script setup>`，并保持 SFC 顺序为 script、template、style。
- 页面共享状态放在 `web/frontend/src/composables/useAnalyzerConsole.ts`。
- 可复用的分析器 UI 放在 `web/frontend/src/components/analyzer`。
- 共享类型放在 `web/frontend/src/types.ts`。
- 保持 Element Plus 组件和 `@element-plus/icons-vue` 图标使用一致。
- 除非用户明确要求共享行为，否则不要耦合代码分析、向量索引和语义检索的结果状态。

## 测试与验证

修改后端或分析器后，至少运行：

```powershell
.\.venv\Scripts\python.exe -m compileall web\backend src
```

行为发生变化时，运行测试：

```powershell
.\.venv\Scripts\python.exe -m pytest
```

修改前端后，运行：

```powershell
cd web\frontend
npm run build
```

完成重要 UI 改动后，在浏览器中访问 `http://127.0.0.1:5173` 验证页面。

Vite/Rollup 构建时可能出现来自依赖的 PURE annotation 警告和 chunk 体积警告；只要构建退出码为 0，不要把这些警告视为失败。

## 编辑准则

- 改动范围应聚焦在用户请求的行为上。
- 除非任务明确要求，不要重写生成文件、缓存、运行时索引、日志或本地项目数据。
- 不要编辑 `.venv`、`web/frontend/node_modules`、`web/frontend/dist`、`.pytest_cache`、`.vector_store`、`.analzer_projects` 或 `logs`。
- 保留工作区中已有的用户改动。不要回滚无关文件。
- 新增项目文件默认使用 ASCII；如果已有内容或面向用户的中文文本需要，可以使用中文。
- 优先使用结构化解析和已有辅助 API，避免临时拼字符串式处理。

## 重要路径

- `src/java_ts_analyzer/analyzer.py`：Tree-sitter Java 提取逻辑。
- `src/java_ts_analyzer/chunker.py`：代码切块生成。
- `src/java_ts_analyzer/kb_loader.py`：知识库文档加载与切块。
- `src/java_ts_analyzer/vector_store.py`：JSONL 向量存储与检索。
- `src/java_ts_analyzer/cli.py`：命令行入口。
- `web/backend/app.py`：Flask 应用入口。
- `web/backend/routes.py`：API 路由。
- `web/backend/services.py`：Web 服务逻辑。
- `web/frontend/src/App.vue`：主控制台路由外壳。
- `web/frontend/src/composables/useAnalyzerConsole.ts`：主前端状态与动作。
- `web/frontend/src/components/analyzer`：分析器控制台面板。
