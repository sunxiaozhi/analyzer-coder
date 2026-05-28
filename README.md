# Java Tree-sitter Analyzer

Python project scaffold for analyzing Java source code with Tree-sitter.

## Install

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
```

## CLI

Analyze one file:

```powershell
java-ts-analyze examples\Sample.java
```

Analyze a directory recursively and print JSON:

```powershell
java-ts-analyze path\to\java-project --json
```

Print a compact syntax tree:

```powershell
java-ts-analyze examples\Sample.java --tree
```

Print vectorization chunks:

```powershell
java-ts-analyze examples\Sample.java --chunks
```

Print a project-level report:

```powershell
java-ts-analyze . --source mixed --report
```

Print a Mermaid graph from detected endpoints and components:

```powershell
java-ts-analyze path\to\java-project --graph
```

Index chunks into a local JSONL vector store:

```powershell
java-ts-analyze examples\Sample.java --index .vector_store\sample.jsonl
```

Use a SentenceTransformer model for indexing or querying:

```powershell
java-ts-analyze . --source mixed --index .vector_store\project.jsonl --embedder sentence-transformer --embedding-model BAAI/bge-small-zh-v1.5
```

Index knowledge-base documents into the same vector store:

```powershell
java-ts-analyze docs --source kb --index .vector_store\project.jsonl
```

Index code and knowledge-base documents from one project root:

```powershell
java-ts-analyze . --source mixed --index .vector_store\project.jsonl
```

Query the local vector store:

```powershell
java-ts-analyze --store .vector_store\sample.jsonl --query "emptyList method" --top-k 3
```

Restrict results to code or knowledge-base chunks:

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

## What It Extracts

- package declaration
- imports
- class, interface, enum, record, and annotation declarations
- type modifiers, annotations, superclass, and implemented interfaces
- methods and constructors
- method return types, parameters, modifiers, annotations, and signatures
- fields with type, modifiers, annotations, and initializer
- method invocation call sites with enclosing type/method and argument count
- Spring-style component annotations such as controllers, services, repositories, components, and mappers
- Spring MVC endpoint annotations including `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, and `@DeleteMapping`
- MyBatis SQL annotations including `@Select`, `@Insert`, `@Update`, `@Delete`, and provider variants
- file metrics including line count, AST node count, and symbol counts
- vectorization chunks for type, field, method, and constructor documents
- vectorization chunks for detected components, HTTP endpoints, and SQL references
- knowledge-base chunks from Markdown, TXT, RST, and AsciiDoc files
- local JSONL vector store with deterministic hashing embeddings, cosine search, upsert, and source filtering
- optional SentenceTransformer embeddings for indexing and querying
- project-level Markdown reports for inventory, components, endpoints, SQL references, and knowledge-base inputs
- parser diagnostics for syntax errors
