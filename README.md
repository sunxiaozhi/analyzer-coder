# Java Tree-sitter 分析器

这是一个基于 Python 的项目脚手架，用于通过 Tree-sitter 分析 Java 源码。

## 安装

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
```

## 命令行

命令每次运行都会默认把结果保存到 `.java_ts_results` 目录。
可以用 `--results-dir path\to\dir` 指定其他输出目录，也可以用 `--no-save`
只打印结果而不写入结果文件。

分析单个文件：

```powershell
java-ts-analyze examples\Sample.java
```

递归分析目录并输出 JSON：

```powershell
java-ts-analyze path\to\java-project --json
```

打印紧凑语法树：

```powershell
java-ts-analyze examples\Sample.java --tree
```

打印向量化切块：

```powershell
java-ts-analyze examples\Sample.java --chunks
```

打印项目级 Markdown 报告：

```powershell
java-ts-analyze . --source mixed --report
```

根据检测到的 endpoint 和组件打印 Mermaid 图：

```powershell
java-ts-analyze path\to\java-project --graph
```

生成适合 Obsidian Vault 使用的 Markdown 笔记：

```powershell
java-ts-analyze . --source mixed --obsidian path\to\ObsidianVault\JavaAnalysis
```

把切块写入本地 JSONL 向量库：

```powershell
java-ts-analyze examples\Sample.java --index .vector_store\sample.jsonl
```

索引或查询时使用 SentenceTransformer 模型：

```powershell
java-ts-analyze . --source mixed --index .vector_store\project.jsonl --embedder sentence-transformer --embedding-model BAAI/bge-small-zh-v1.5
```

把知识库文档索引到同一个向量库：

```powershell
java-ts-analyze docs --source kb --index .vector_store\project.jsonl
```

从一个项目根目录同时索引代码和知识库文档：

```powershell
java-ts-analyze . --source mixed --index .vector_store\project.jsonl
```

查询本地向量库：

```powershell
java-ts-analyze --store .vector_store\sample.jsonl --query "emptyList method" --top-k 3
```

把查询结果限制为代码切块或知识库切块：

```powershell
java-ts-analyze --store .vector_store\project.jsonl --query "phone number unique registration" --filter-source kb
java-ts-analyze --store .vector_store\project.jsonl --query "where is registration implemented" --filter-source code
```

## Python API

```python
from pathlib import Path

from java_ts_analyzer import JavaAnalyzer, build_chunks
from java_ts_analyzer.vector_store import JsonlVectorStore

analyzer = JavaAnalyzer()
result = analyzer.analyze_file(Path("examples/Sample.java"))
chunks = build_chunks(result)
records = JsonlVectorStore(".vector_store/sample.jsonl").write_chunks(chunks)
results = JsonlVectorStore(".vector_store/sample.jsonl").search("emptyList method")

print(result.package)
print(records[0].metadata)
print(results[0].text)
```

## Web 应用

项目包含一个 Flask 后端和 Vue 3 前端，位于 `web` 目录。

安装后端依赖：

```powershell
python -m pip install -e ".[dev,web]"
```

启动 Flask API：

```powershell
python -m web.backend.app
```

默认监听：

```text
http://127.0.0.1:5050
```

安装并启动 Vue 3 前端：

```powershell
cd web\frontend
npm install
npm run dev
```

默认访问：

```text
http://127.0.0.1:5173
```

前端通过 Vite 代理访问 `/api`，对应 Flask 后端的分析、索引和查询接口。

## 源码结构

Python 包位于 `src/java_ts_analyzer`：

- `__init__.py`：包的公开导出入口。它重新导出主分析器、数据模型和切块构建函数，方便调用方直接从 `java_ts_analyzer` 导入常用 API。
- `models.py`：所有分析结果的类型化 dataclass，包括源码位置、import、符号、类型、字段、方法、调用、Spring 组件、HTTP endpoint、SQL 引用、指标、文件分析结果和向量切块。
- `analyzer.py`：基于 Tree-sitter 的 Java 解析器和提取器。它读取 Java 源码文件，构建 AST，提取 Java 结构，检测 Spring 组件/endpoint 和 MyBatis SQL 注解，计算指标，并格式化紧凑语法树。
- `chunker.py`：把结构化的 `JavaFileAnalysis` 转换成适合向量搜索的小文本块。它会为类型、字段、方法、组件、endpoint 和 SQL 引用分别生成切块，并附带统一的 metadata。
- `kb_loader.py`：加载 Markdown、TXT、RST、AsciiDoc 等知识库文件。它按标题和长度切分文档，跳过生成目录/缓存目录，并生成和代码切块兼容的知识库切块。
- `embedding.py`：向量化后端和相似度工具。默认的 `HashingEmbedder` 是确定性的本地向量器；安装可选依赖后，`SentenceTransformerEmbedder` 可以使用真实的 sentence-transformers 模型。
- `vector_store.py`：本地 JSONL 向量库。它负责写入或 upsert 带 embedding 的切块记录，读取已有记录，应用 metadata 过滤，并返回按余弦相似度排序的搜索结果。
- `cli.py`：`java-ts-analyze` 的命令行入口。它串联分析、切块、索引、查询、报告生成、Mermaid 图输出、Obsidian 笔记生成和结果自动保存。

Web 子项目位于 `web`：

- `web/backend/app.py`：Flask API 服务，提供健康检查、分析、索引和查询接口，并复用现有 Java 分析器能力。
- `web/frontend`：Vue 3 + Vite 前端，用于输入路径、选择分析模式、建立索引、查询向量库并查看结果。

## 可提取的信息

- package 声明
- import 声明
- class、interface、enum、record 和 annotation 声明
- 类型修饰符、注解、父类和实现接口
- 方法和构造器
- 方法返回类型、参数、修饰符、注解和签名
- 字段类型、修饰符、注解和初始化表达式
- 方法调用点，以及所属类型/方法和参数数量
- Spring 风格组件注解，例如 controller、service、repository、component 和 mapper
- Spring MVC endpoint 注解，包括 `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@PatchMapping` 和 `@DeleteMapping`
- MyBatis SQL 注解，包括 `@Select`、`@Insert`、`@Update`、`@Delete` 以及 provider 变体
- 文件指标，包括行数、AST 节点数和符号数量
- 用于向量化的类型、字段、方法和构造器文档切块
- 用于向量化的组件、HTTP endpoint 和 SQL 引用切块
- 来自 Markdown、TXT、RST 和 AsciiDoc 文件的知识库切块
- 本地 JSONL 向量库，支持确定性哈希 embedding、余弦搜索、upsert 和来源过滤
- 可选的 SentenceTransformer embedding，用于索引和查询
- 项目级 Markdown 报告，包含清单、组件、endpoint、SQL 引用和知识库输入
- 解析器语法错误诊断
