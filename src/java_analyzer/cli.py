from __future__ import annotations

import argparse
from datetime import datetime
import json
import re
import sys
from pathlib import Path
from typing import Sequence

from java_analyzer.analyzer import JavaAnalyzer
from java_analyzer.call_graph import CallChain, build_call_chains, build_call_edges
from java_analyzer.chunker import build_chunks
from java_analyzer.embedding import HashingEmbedder, SentenceTransformerEmbedder
from java_analyzer.kb_loader import build_kb_chunks
from java_analyzer.models import JavaFileAnalysis, JavaVectorChunk
from java_analyzer.sql_flow import build_endpoint_sql_flows, build_sql_usages
from java_analyzer.vector_store import JsonlVectorStore

TOKEN_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+")
GRAPH_MAX_ENDPOINTS = 80
GRAPH_MAX_COMPONENTS = 120
GRAPH_MAX_METHOD_NODES = 160
GRAPH_MAX_METHOD_EDGES = 180
GRAPH_MAX_SQL_NODES = 80
GRAPH_MAX_TABLE_NODES = 80


def build_parser() -> argparse.ArgumentParser:
    # 将所有面向用户的模式集中到一个解析器中，让 CLI 同时承担分析、索引、
    # 检索、报告和图生成工具的职责。
    parser = argparse.ArgumentParser(
        prog="java-analyze",
        description="Analyze Java source code with Tree-sitter.",
    )
    parser.add_argument("path", type=Path, nargs="?", help="Java file or directory to analyze.")
    parser.add_argument(
        "--source",
        choices=["code", "kb", "mixed"],
        default="code",
        help="Input source to chunk/index. Defaults to code.",
    )
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON.")
    parser.add_argument("--tree", action="store_true", help="Print a compact syntax tree.")
    parser.add_argument("--chunks", action="store_true", help="Print vectorization chunks as JSON.")
    parser.add_argument("--report", action="store_true", help="Print a project-level Markdown report.")
    parser.add_argument("--graph", action="store_true", help="Print a Mermaid component graph.")
    parser.add_argument(
        "--obsidian",
        metavar="VAULT_DIR",
        type=Path,
        help="Generate Obsidian-friendly Markdown pages into VAULT_DIR.",
    )
    parser.add_argument(
        "--index",
        metavar="STORE",
        type=Path,
        help="Analyze path, vectorize chunks, and write a JSONL vector store.",
    )
    parser.add_argument(
        "--append",
        action="store_true",
        help="Upsert chunks into an existing vector store instead of overwriting it.",
    )
    parser.add_argument(
        "--query",
        metavar="TEXT",
        help="Search an existing JSONL vector store.",
    )
    parser.add_argument(
        "--filter-source",
        choices=["code", "kb"],
        help="Restrict search results to one source type.",
    )
    parser.add_argument(
        "--store",
        type=Path,
        default=Path(".java_vectors.jsonl"),
        help="Vector store path used by --query. Defaults to .java_vectors.jsonl.",
    )
    parser.add_argument("--top-k", type=int, default=5, help="Number of search results to return.")
    parser.add_argument(
        "--embedder",
        choices=["hashing", "sentence-transformer"],
        default="hashing",
        help="Embedding backend for --index and --query. Defaults to hashing.",
    )
    parser.add_argument(
        "--embedding-model",
        default="BAAI/bge-small-zh-v1.5",
        help="SentenceTransformer model name used when --embedder sentence-transformer.",
    )
    parser.add_argument(
        "--embedding-dimensions",
        type=int,
        default=384,
        help="Hashing embedding dimensions used when --embedder hashing.",
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        default=Path(".java_results"),
        help="Directory used to save each command result. Defaults to .java_results.",
    )
    parser.add_argument(
        "--no-save",
        action="store_true",
        help="Print results only and do not write a result file.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    analyzer = JavaAnalyzer()
    # 提前构建向量器，确保索引和查询使用同一套参数处理逻辑。
    embedder = _build_embedder(
        name=args.embedder,
        model_name=args.embedding_model,
        dimensions=args.embedding_dimensions,
    )

    if args.query:
        # 查询模式不需要源码路径，只读取已有向量库。
        output = _search_store(args.store, args.query, args.top_k, args.filter_source, embedder)
        _emit_output(args, output, kind="query", extension=".txt")
        return 0

    if args.path is None:
        raise SystemExit("path is required unless --query is used.")

    if args.obsidian:
        code_results = analyzer.analyze_path(args.path) if args.source in {"code", "mixed"} else []
        kb_chunks = build_kb_chunks(args.path) if args.source in {"kb", "mixed"} else []
        written_files = _write_obsidian_vault(
            output_dir=args.obsidian,
            project_path=args.path,
            source=args.source,
            code_results=code_results,
            kb_chunks=kb_chunks,
        )
        output = "\n".join(
            [
                f"generated {len(written_files)} Obsidian notes into {args.obsidian}",
                *(f"- {path}" for path in written_files),
            ]
        )
        _emit_output(args, output, kind="obsidian", extension=".txt")
        return 0

    if args.tree:
        if args.source != "code":
            raise SystemExit("--tree is only available for --source code.")
        if not args.path.is_file():
            raise SystemExit("--tree expects a single Java file.")
        _emit_output(args, analyzer.format_tree(args.path.read_bytes()), kind="tree", extension=".txt")
        return 0

    if args.report:
        # 报告模式会合并代码分析和可选知识库 chunk，但不写入索引。
        code_results = analyzer.analyze_path(args.path) if args.source in {"code", "mixed"} else []
        kb_chunks = build_kb_chunks(args.path) if args.source in {"kb", "mixed"} else []
        _emit_output(
            args,
            _build_report(args.path, args.source, code_results, kb_chunks),
            kind="report",
            extension=".md",
        )
        return 0

    if args.graph:
        # 图输出只来自代码表面：endpoint、组件、SQL 引用和推断出的组件依赖。
        if args.source == "kb":
            raise SystemExit("--graph requires --source code or --source mixed.")
        code_results = analyzer.analyze_path(args.path)
        _emit_output(args, _build_graph(code_results, args.path), kind="graph", extension=".mmd")
        return 0

    results = []
    chunks = (
        _build_source_chunks(analyzer, args.path, args.source)
        if args.index or args.chunks or args.source != "code"
        else []
    )

    if args.index:
        store = JsonlVectorStore(args.index)
        records = (
            store.upsert_chunks(chunks, embedder=embedder)
            if args.append
            else store.write_chunks(chunks, embedder=embedder)
        )
        mode = "upserted" if args.append else "indexed"
        _emit_output(
            args,
            f"{mode} {len(records)} chunks into {args.index}",
            kind="index",
            extension=".txt",
        )
        return 0

    if args.chunks:
        _emit_output(
            args,
            json.dumps([chunk.__dict__ for chunk in chunks], indent=2, ensure_ascii=False),
            kind="chunks",
            extension=".json",
        )
        return 0

    if args.json:
        if args.source != "code":
            output = json.dumps([chunk.__dict__ for chunk in chunks], indent=2, ensure_ascii=False)
            _emit_output(args, output, kind="json", extension=".json")
            return 0
        results = analyzer.analyze_path(args.path)
        output = json.dumps([result.to_dict() for result in results], indent=2, ensure_ascii=False)
        _emit_output(args, output, kind="json", extension=".json")
        return 0

    if args.source != "code":
        _emit_output(args, f"{args.path}: {len(chunks)} chunks", kind="summary", extension=".txt")
        return 0

    results = analyzer.analyze_path(args.path)
    _emit_output(
        args,
        "\n".join(_format_summary(result) for result in results),
        kind="summary",
        extension=".txt",
    )
    return 0


def _build_source_chunks(
    analyzer: JavaAnalyzer,
    path: Path,
    source: str,
) -> list[JavaVectorChunk]:
    # 来源模式决定哪些表面进入同一条下游切块/索引流水线。
    chunks: list[JavaVectorChunk] = []
    if source in {"code", "mixed"}:
        chunks.extend(
            chunk
            for result in analyzer.analyze_path(path)
            for chunk in build_chunks(result)
        )
    if source in {"kb", "mixed"}:
        chunks.extend(build_kb_chunks(path))
    return chunks


def _emit_output(args: argparse.Namespace, output: str, kind: str, extension: str) -> None:
    print(output)
    if args.no_save:
        return
    saved_path = _save_result(args.results_dir, kind, extension, output)
    print(f"saved result to {saved_path}", file=sys.stderr)


def _save_result(results_dir: Path, kind: str, extension: str, output: str) -> Path:
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    result_path = results_dir / f"{timestamp}-{kind}{extension}"
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(output.rstrip("\n") + "\n", encoding="utf-8")
    return result_path


def _write_obsidian_vault(
    output_dir: Path,
    project_path: Path,
    source: str,
    code_results: list[JavaFileAnalysis],
    kb_chunks: list[JavaVectorChunk],
) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    written_files: list[Path] = []

    index_path = output_dir / "Java Analysis.md"
    index_path.write_text(
        _obsidian_index(project_path, source, code_results, kb_chunks),
        encoding="utf-8",
    )
    written_files.append(index_path)

    component_dir = output_dir / "Components"
    endpoint_dir = output_dir / "Endpoints"
    component_dir.mkdir(parents=True, exist_ok=True)
    endpoint_dir.mkdir(parents=True, exist_ok=True)

    for result in code_results:
        for component in result.components:
            page_path = component_dir / f"{_safe_note_name(component.name)}.md"
            page_path.write_text(
                _obsidian_component_page(result, component.name, kb_chunks),
                encoding="utf-8",
            )
            written_files.append(page_path)

        for endpoint in result.endpoints:
            note_name = _endpoint_note_name(endpoint)
            page_path = endpoint_dir / f"{note_name}.md"
            page_path.write_text(
                _obsidian_endpoint_page(result, endpoint, kb_chunks),
                encoding="utf-8",
            )
            written_files.append(page_path)

    return written_files


def _obsidian_index(
    project_path: Path,
    source: str,
    code_results: list[JavaFileAnalysis],
    kb_chunks: list[JavaVectorChunk],
) -> str:
    metrics = [result.metrics for result in code_results if result.metrics is not None]
    components = [component for result in code_results for component in result.components]
    endpoints = [endpoint for result in code_results for endpoint in result.endpoints]
    packages = sorted({result.package for result in code_results if result.package})

    lines = [
        "---",
        "type: java-analysis",
        f"source: {source}",
        f"created: {datetime.now().isoformat(timespec='seconds')}",
        "---",
        "",
        "# Java Analysis",
        "",
        f"- Project: `{project_path}`",
        f"- Java files: {len(code_results)}",
        f"- 知识库切块数：{len(kb_chunks)}",
        f"- 包名：{', '.join(packages) if packages else '-'}",
        "",
        "## 统计概览",
        "",
        "| 指标 | 数量 |",
        "|---|---:|",
        f"| 类型 | {sum(metric.type_count for metric in metrics)} |",
        f"| 字段 | {sum(metric.field_count for metric in metrics)} |",
        f"| 方法和构造器 | {sum(metric.method_count for metric in metrics)} |",
        f"| 方法调用 | {sum(metric.call_count for metric in metrics)} |",
        f"| HTTP 接口 | {sum(metric.endpoint_count for metric in metrics)} |",
        "",
        "## Spring 组件",
        "",
    ]
    if components:
        for component in components:
            lines.append(f"- [[{component.name}]] `{component.annotation}`")
    else:
        lines.append("未检测到 Spring 风格组件。")

    lines.extend(["", "## HTTP 接口", ""])
    if endpoints:
        for endpoint in endpoints:
            methods = ",".join(endpoint.http_methods)
            lines.append(f"- [[{_endpoint_note_name(endpoint)}]] `{methods} {endpoint.path}`")
    else:
        lines.append("未检测到 Spring MVC 接口。")

    lines.extend(["", "## 知识库来源", ""])
    files = sorted({str(chunk.metadata.get("file_path", "")) for chunk in kb_chunks})
    if files:
        for file_path in files:
            lines.append(f"- `{file_path}`")
    else:
        lines.append("未包含知识库切块。")
    return "\n".join(lines) + "\n"


def _obsidian_component_page(
    result: JavaFileAnalysis,
    component_name: str,
    kb_chunks: list[JavaVectorChunk],
) -> str:
    component = next(item for item in result.components if item.name == component_name)
    fields = [field for field in result.fields if field.enclosing_type == component_name]
    methods = [method for method in result.methods if method.enclosing_type == component_name]
    endpoints = [endpoint for endpoint in result.endpoints if endpoint.enclosing_type == component_name]
    page_text = "\n".join(
        [
            component.name,
            component.annotation,
            *(method.signature or method.name for method in methods),
            *(endpoint.path for endpoint in endpoints),
        ]
    )
    related_knowledge = _related_kb_chunks(page_text, kb_chunks)

    lines = [
        "---",
        "type: java-component",
        f"component: {component.name}",
        f"kind: {component.kind}",
        f"file: {result.file_path or ''}",
        "---",
        "",
        f"# {component.name}",
        "",
        f"- 类型：`{_display_component_kind(component.kind)}`",
        f"- 注解：`{component.annotation}`",
        f"- 文件：`{result.file_path or ''}`",
        f"- 包名：`{result.package or ''}`",
        "",
        "## 字段",
        "",
    ]
    lines.extend(
        f"- `{field.type or '?'} {field.name}`"
        for field in fields
    )
    if not fields:
        lines.append("未检测到字段。")

    lines.extend(["", "## 方法", ""])
    lines.extend(f"- `{method.signature or method.name}`" for method in methods)
    if not methods:
        lines.append("未检测到方法。")

    lines.extend(["", "## HTTP 接口", ""])
    lines.extend(
        f"- [[{_endpoint_note_name(endpoint)}]] `{','.join(endpoint.http_methods)} {endpoint.path}`"
        for endpoint in endpoints
    )
    if not endpoints:
        lines.append("未检测到 HTTP 接口。")

    lines.extend(["", "## 相关知识", ""])
    lines.extend(_knowledge_lines(related_knowledge))
    return "\n".join(lines) + "\n"


def _obsidian_endpoint_page(
    result: JavaFileAnalysis,
    endpoint: JavaEndpoint,
    kb_chunks: list[JavaVectorChunk],
) -> str:
    method = next(
        (
            item
            for item in result.methods
            if item.enclosing_type == endpoint.enclosing_type and item.name == endpoint.method_name
        ),
        None,
    )
    page_text = "\n".join(
        [
            endpoint.path,
            endpoint.method_name,
            endpoint.enclosing_type,
            method.signature if method else "",
        ]
    )
    related_knowledge = _related_kb_chunks(page_text, kb_chunks)
    methods = ",".join(endpoint.http_methods)
    lines = [
        "---",
        "type: java-endpoint",
        f"path: {endpoint.path}",
        f"methods: {methods}",
        f"controller: {endpoint.enclosing_type}",
        f"handler: {endpoint.method_name}",
        f"file: {result.file_path or ''}",
        "---",
        "",
        f"# {methods} {endpoint.path}",
        "",
        f"- 控制器：[[{endpoint.enclosing_type}]]",
        f"- 处理函数：`{endpoint.enclosing_type}.{endpoint.method_name}`",
        f"- 注解：`{endpoint.annotation}`",
        f"- 文件：`{result.file_path or ''}`",
        "",
        "## 处理函数",
        "",
        f"- 签名：`{method.signature if method else endpoint.method_name}`",
        f"- 返回类型：`{method.return_type if method and method.return_type else ''}`",
        "",
        "## 相关知识",
        "",
    ]
    lines.extend(_knowledge_lines(related_knowledge))
    return "\n".join(lines) + "\n"


def _related_kb_chunks(
    text: str,
    kb_chunks: list[JavaVectorChunk],
    limit: int = 5,
) -> list[JavaVectorChunk]:
    query_terms = _meaningful_terms(text)
    scored: list[tuple[int, JavaVectorChunk]] = []
    for chunk in kb_chunks:
        overlap = len(query_terms & _meaningful_terms(chunk.text))
        if overlap:
            scored.append((overlap, chunk))
    return [chunk for _, chunk in sorted(scored, key=lambda item: item[0], reverse=True)[:limit]]


def _knowledge_lines(chunks: list[JavaVectorChunk]) -> list[str]:
    if not chunks:
        return ["未找到相关知识切块。"]
    lines = []
    for chunk in chunks:
        file_path = chunk.metadata.get("file_path", "")
        section = chunk.metadata.get("section", chunk.metadata.get("symbol_name", "document"))
        start_line = chunk.metadata.get("start_line", "")
        lines.append(f"- `{file_path}:{start_line}` {section}")
    return lines


def _meaningful_terms(text: str) -> set[str]:
    stop_words = {
        "api",
        "class",
        "com",
        "controller",
        "get",
        "java",
        "method",
        "post",
        "public",
        "return",
        "service",
        "string",
        "user",
        "void",
    }
    return {
        token.lower()
        for token in TOKEN_PATTERN.findall(text)
        if len(token) > 2 and token.lower() not in stop_words
    }


def _endpoint_note_name(endpoint: JavaEndpoint) -> str:
    methods = "-".join(endpoint.http_methods)
    return _safe_note_name(f"{methods} {endpoint.path}")


def _safe_note_name(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._ -]+", "-", value).strip(" .-")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned or "note"


def _format_summary(result: object) -> str:
    # 人类可读输出刻意保持紧凑；JSON 模式会暴露完整嵌套模型给自动化使用。
    lines = [
        result.file_path or "<memory>",
        f"  package: {result.package or '-'}",
        "  metrics: "
        f"{result.metrics.type_count} types, "
        f"{result.metrics.field_count} fields, "
        f"{result.metrics.method_count} methods, "
        f"{result.metrics.call_count} calls, "
        f"{result.metrics.local_variable_count} locals, "
        f"{result.metrics.return_count} returns, "
        f"{result.metrics.control_structure_count} controls, "
        f"{result.metrics.endpoint_count} endpoints, "
        f"{result.metrics.sql_reference_count} SQL refs, "
        f"{result.metrics.node_count} AST nodes",
        f"  imports: {len(result.imports)}",
    ]
    if result.has_error:
        lines.append(f"  syntax errors: {len(result.syntax_errors)}")

    if result.types:
        lines.append("  types:")
        for item in result.types:
            owner = f" in {item.enclosing_type}" if item.enclosing_type else ""
            extends = f" extends {item.superclass}" if item.superclass else ""
            implements = f" implements {', '.join(item.interfaces)}" if item.interfaces else ""
            annotations = f" {' '.join(item.annotations)}" if item.annotations else ""
            modifiers = f" [{' '.join(item.modifiers)}]" if item.modifiers else ""
            lines.append(f"    - {item.kind} {item.name}{owner}{extends}{implements}{modifiers}{annotations}")

    if result.fields:
        lines.append("  fields:")
        for item in result.fields:
            owner = f" ({item.enclosing_type})" if item.enclosing_type else ""
            initializer = f" = {item.initializer}" if item.initializer else ""
            modifiers = f" [{' '.join(item.modifiers)}]" if item.modifiers else ""
            annotations = f" {' '.join(item.annotations)}" if item.annotations else ""
            lines.append(f"    - {item.type or '?'} {item.name}{owner}{initializer}{modifiers}{annotations}")

    if result.methods:
        lines.append("  methods:")
        for item in result.methods:
            owner = f" ({item.enclosing_type})" if item.enclosing_type else ""
            parameters = ", ".join(
                f"{param.type or '?'} {param.name}" for param in item.parameters
            )
            return_type = f"{item.return_type} " if item.return_type else ""
            throws = f" throws {', '.join(item.throws)}" if item.throws else ""
            modifiers = f" [{' '.join(item.modifiers)}]" if item.modifiers else ""
            annotations = f" {' '.join(item.annotations)}" if item.annotations else ""
            lines.append(f"    - {item.kind} {return_type}{item.name}({parameters}){throws}{owner}{modifiers}{annotations}")

    if result.calls:
        lines.append("  calls:")
        for item in result.calls:
            owner = f" in {item.enclosing_type}.{item.enclosing_method}" if item.enclosing_type and item.enclosing_method else ""
            qualifier = f"{item.qualifier}." if item.qualifier else ""
            arguments = ", ".join(item.arguments) if item.arguments else f"args={item.argument_count}"
            lines.append(f"    - {item.kind} {qualifier}{item.name}({arguments}){owner}")

    if result.local_variables:
        lines.append("  local variables:")
        for item in result.local_variables:
            owner = f" in {item.enclosing_type}.{item.enclosing_method}" if item.enclosing_type and item.enclosing_method else ""
            initializer = f" = {item.initializer}" if item.initializer else ""
            lines.append(f"    - {item.type or '?'} {item.name}{initializer}{owner}")

    if result.returns:
        lines.append("  returns:")
        for item in result.returns:
            owner = f" in {item.enclosing_type}.{item.enclosing_method}" if item.enclosing_type and item.enclosing_method else ""
            expression = item.expression or ""
            lines.append(f"    - {expression}{owner}")

    if result.control_structures:
        lines.append("  control structures:")
        for item in result.control_structures:
            owner = f" in {item.enclosing_type}.{item.enclosing_method}" if item.enclosing_type and item.enclosing_method else ""
            condition = f" ({item.condition})" if item.condition else ""
            lines.append(f"    - {item.kind}{condition}{owner}")

    if result.components:
        lines.append("  components:")
        for item in result.components:
            lines.append(f"    - {item.kind} {item.name} {item.annotation}")

    if result.endpoints:
        lines.append("  endpoints:")
        for item in result.endpoints:
            methods = ",".join(item.http_methods)
            parameters = ", ".join(
                f"{parameter.source}:{parameter.alias or parameter.name}"
                for parameter in item.parameters
            )
            suffix = f" [{parameters}]" if parameters else ""
            lines.append(f"    - {methods} {item.path} -> {item.enclosing_type}.{item.method_name}{suffix}")

    if result.sql_references:
        lines.append("  sql:")
        for item in result.sql_references:
            owner = f"{item.enclosing_type}.{item.method_name}" if item.enclosing_type and item.method_name else item.method_name or "-"
            lines.append(f"    - {item.operation} {owner}: {item.statement}")

    return "\n".join(lines)


def _search_store(
    store_path: Path,
    query: str,
    top_k: int,
    filter_source: str | None,
    embedder: object,
) -> str:
    # 通过 source_type 过滤，调用方无需维护多个索引库也能只查代码或只查知识库。
    metadata_filter = {"source_type": filter_source} if filter_source else None
    try:
        results = JsonlVectorStore(store_path).search(
            query=query,
            top_k=top_k,
            metadata_filter=metadata_filter,
            embedder=embedder,
        )
    except ValueError as exc:
        raise SystemExit(
            f"{exc} Use the same --embedder and embedding options that created the store."
        ) from exc
    if not results:
        return f"no records found in {store_path}"

    lines = []
    for index, result in enumerate(results, start=1):
        metadata = result.metadata
        location = (
            f"{metadata.get('file_path', '')}:"
            f"{metadata.get('start_line', '')}"
        )
        symbol = metadata.get("symbol_name", "")
        kind = metadata.get("kind", "")
        source_type = metadata.get("source_type", "")
        lines.append(f"{index}. score={result.score:.4f} {source_type} {kind} {symbol} {location}")
        matched_terms = _matched_terms(query, result.text)
        if matched_terms:
            lines.append(f"   matched: {', '.join(matched_terms)}")
        lines.append(_indent(result.text, "   "))
    return "\n".join(lines)


def _indent(text: str, prefix: str) -> str:
    return "\n".join(f"{prefix}{line}" for line in text.splitlines())


def _build_embedder(name: str, model_name: str, dimensions: int) -> object:
    if name == "hashing":
        return HashingEmbedder(dimensions=dimensions)
    return SentenceTransformerEmbedder(model_name=model_name)


def _matched_terms(query: str, text: str) -> list[str]:
    query_terms = {token.lower() for token in TOKEN_PATTERN.findall(query)}
    text_terms = {token.lower() for token in TOKEN_PATTERN.findall(text)}
    return sorted(query_terms & text_terms)


def _build_report(
    path: Path,
    source: str,
    code_results: list[JavaFileAnalysis],
    kb_chunks: list[JavaVectorChunk],
) -> str:
    # 将逐文件指标聚合成 Markdown 报告，适合交接说明或 Obsidian 页面。
    metrics = [result.metrics for result in code_results if result.metrics is not None]
    total_types = sum(metric.type_count for metric in metrics)
    total_fields = sum(metric.field_count for metric in metrics)
    total_methods = sum(metric.method_count for metric in metrics)
    total_calls = sum(metric.call_count for metric in metrics)
    total_endpoints = sum(metric.endpoint_count for metric in metrics)
    sql_usages = build_sql_usages(code_results, path)
    total_sql = len(sql_usages)
    packages = sorted({result.package for result in code_results if result.package})

    lines = [
        "# Java 项目分析报告",
        "",
        f"- 项目路径：`{path}`",
        f"- 分析模式：`{_display_source_mode(source)}`",
        f"- 已分析 Java 文件数：{len(code_results)}",
        f"- 知识库切块数：{len(kb_chunks)}",
        f"- 包名：{', '.join(packages) if packages else '-'}",
        "",
        "## 统计概览",
        "",
        "| 指标 | 数量 |",
        "|---|---:|",
        f"| 类型 | {total_types} |",
        f"| 字段 | {total_fields} |",
        f"| 方法和构造器 | {total_methods} |",
        f"| 方法调用 | {total_calls} |",
        f"| HTTP 接口 | {total_endpoints} |",
        f"| SQL 引用 | {total_sql} |",
        "",
    ]
    lines.extend(_component_report_lines(code_results))
    lines.extend(_endpoint_report_lines(code_results))
    lines.extend(_endpoint_sql_flow_report_lines(path, code_results))
    lines.extend(_call_chain_report_lines(code_results))
    lines.extend(_sql_report_lines(code_results, path))
    lines.extend(_kb_report_lines(kb_chunks))
    return "\n".join(lines)


def _component_report_lines(results: list[JavaFileAnalysis]) -> list[str]:
    components = [component for result in results for component in result.components]
    lines = ["## Spring 组件", ""]
    if not components:
        return [*lines, "未检测到 Spring 风格组件。", ""]
    lines.extend(["| 类型 | 名称 | 注解 |", "|---|---|---|"])
    for item in components:
        lines.append(f"| {_display_component_kind(item.kind)} | {item.name} | `{item.annotation}` |")
    lines.append("")
    return lines


def _endpoint_report_lines(results: list[JavaFileAnalysis]) -> list[str]:
    endpoints = [endpoint for result in results for endpoint in result.endpoints]
    lines = ["## HTTP 接口", ""]
    if not endpoints:
        return [*lines, "未检测到 Spring MVC 接口。", ""]
    lines.extend(["| 请求方法 | 路径 | 处理函数 |", "|---|---|---|"])
    for item in endpoints:
        methods = ", ".join(item.http_methods)
        lines.append(f"| {methods} | `{item.path}` | `{item.enclosing_type}.{item.method_name}` |")
    lines.append("")
    return lines


def _endpoint_sql_flow_report_lines(path: Path, results: list[JavaFileAnalysis]) -> list[str]:
    flows = build_endpoint_sql_flows(results, path)
    lines = ["## 接口 SQL 流向", ""]
    if not flows:
        return [*lines, "未解析到接口到 SQL 的流向。", ""]

    lines.extend(["| 接口 | 代码路径 | SQL 所属对象 | 操作 | 表 | SQL 来源 |", "|---|---|---|---|---|---|"])
    for flow in flows[:100]:
        endpoint = f"{', '.join(flow.endpoint_methods)} {flow.endpoint_path}".replace("|", "\\|")
        code_path = " -> ".join(f"`{item}`" for item in flow.code_path).replace("|", "\\|")
        tables = ", ".join(f"`{table}`" for table in flow.sql.tables) or "-"
        source = f"{_display_sql_source(flow.sql.source)}：`{flow.sql.file_path}:{flow.sql.line}`".replace("|", "\\|")
        lines.append(
            f"| `{endpoint}` | {code_path} | `{flow.sql.owner}` | {_display_sql_operation(flow.sql.operation)} | {tables} | {source} |"
        )
    if len(flows) > 100:
        lines.append(f"| ... | ... | ... | ... | ... | 已省略 {len(flows) - 100} 条流向 |")
    lines.append("")
    return lines


def _call_chain_report_lines(results: list[JavaFileAnalysis]) -> list[str]:
    chains = build_call_chains(results, max_depth=4, max_chains=60)
    edges = build_call_edges(results)
    resolved_count = sum(1 for edge in edges if edge.callee is not None)
    lines = ["## 代码调用链", ""]
    lines.append(f"- 已解析内部调用：{resolved_count}/{len(edges)}")
    if not chains:
        return [*lines, "- 未解析到内部调用链。", ""]

    lines.extend(["", "| 入口方法 | 调用链 | 来源 |", "|---|---|---|"])
    for chain in chains:
        chain_text = _format_call_chain(chain).replace("|", "\\|")
        source = _format_chain_source(chain).replace("|", "\\|")
        lines.append(f"| `{chain.entrypoint.qualified_name}` | {chain_text} | {source} |")
    lines.append("")
    return lines


def _format_call_chain(chain: CallChain) -> str:
    names = [chain.entrypoint.qualified_name]
    for edge in chain.edges:
        if edge.callee is None:
            names.append(f"{edge.call_name} ?")
            continue
        names.append(edge.callee.qualified_name)
    return " -> ".join(f"`{name}`" for name in names)


def _format_chain_source(chain: CallChain) -> str:
    locations = []
    for edge in chain.edges:
        locations.append(f"{edge.caller.file_path}:{edge.line}")
    return "<br>".join(f"`{location}`" for location in locations)


def _sql_report_lines(results: list[JavaFileAnalysis], path: Path) -> list[str]:
    references = build_sql_usages(results, path)
    lines = ["## SQL 引用", ""]
    if not references:
        return [*lines, "未检测到 MyBatis SQL 注解或 XML 语句。", ""]
    lines.extend(["| 操作 | 所属对象 | 表 | 来源 | SQL 语句 |", "|---|---|---|---|---|"])
    for item in references:
        tables = ", ".join(f"`{table}`" for table in item.tables) or "-"
        source = f"{_display_sql_source(item.source)}：`{item.file_path}:{item.line}`".replace("|", "\\|")
        statement = item.statement.replace("|", "\\|")
        lines.append(f"| {_display_sql_operation(item.operation)} | `{item.owner}` | {tables} | {source} | `{statement}` |")
    lines.append("")
    return lines


def _kb_report_lines(chunks: list[JavaVectorChunk]) -> list[str]:
    lines = ["## 知识库", ""]
    if not chunks:
        return [*lines, "未包含知识库切块。", ""]
    files = sorted({str(chunk.metadata.get("file_path", "")) for chunk in chunks})
    lines.append(f"- 文件数：{len(files)}")
    lines.append(f"- 切块数：{len(chunks)}")
    for file_path in files:
        lines.append(f"- `{file_path}`")
    lines.append("")
    return lines


def _display_source_mode(source: str) -> str:
    return {
        "code": "代码",
        "kb": "知识库",
        "mixed": "代码 + 知识库",
    }.get(source, source)


def _display_component_kind(kind: str) -> str:
    return {
        "controller": "控制器",
        "rest_controller": "REST 控制器",
        "service": "服务",
        "repository": "仓储",
        "component": "组件",
        "mapper": "Mapper",
    }.get(kind, kind)


def _display_sql_operation(operation: str) -> str:
    return {
        "select": "查询",
        "insert": "新增",
        "update": "更新",
        "delete": "删除",
        "unknown": "未知",
    }.get(operation.lower(), operation)


def _display_sql_source(source: str) -> str:
    return {
        "annotation": "注解",
        "xml": "XML Mapper",
    }.get(source.lower(), source)


def _build_graph(results: list[JavaFileAnalysis], root: Path | None = None) -> str:
    # 按调用链展示，避免全局组件/方法池导致真实项目图过大；全量明细保留在 Markdown 报告中。
    endpoint_sql_flows = build_endpoint_sql_flows(results, root)
    selected_flows = endpoint_sql_flows[:GRAPH_MAX_ENDPOINTS]
    call_chains = [] if selected_flows else build_call_chains(results, max_depth=4, max_chains=GRAPH_MAX_ENDPOINTS)
    lines = [
        "flowchart LR",
        '    classDef endpoint fill:#e8f3ff,stroke:#2f73b7,color:#10253f',
        '    classDef method fill:#f2f0ff,stroke:#6d5bd0,color:#251d59',
        '    classDef sql fill:#fff4df,stroke:#b7791f,color:#4a2d05',
        '    classDef table fill:#f7ecff,stroke:#8b5bb7,color:#33164f',
        "",
    ]

    if selected_flows:
        endpoint_ids: dict[tuple[tuple[str, ...], str, str], str] = {}
        method_ids: dict[str, str] = {}
        sql_ids: dict[tuple[str, str, str, int], str] = {}
        table_ids: dict[str, str] = {}
        edge_lines: list[str] = []

        for flow in selected_flows:
            endpoint_key = (tuple(flow.endpoint_methods), flow.endpoint_path, flow.handler)
            if endpoint_key not in endpoint_ids:
                endpoint_ids[endpoint_key] = f"endpoint_{len(endpoint_ids) + 1}"
            previous_node = endpoint_ids[endpoint_key]

            for method_name in flow.code_path[:GRAPH_MAX_METHOD_NODES]:
                method_ids.setdefault(method_name, _method_node_id(method_name))
                method_node = method_ids[method_name]
                edge_lines.append(f"    {previous_node} -->|调用| {method_node}")
                previous_node = method_node

            sql_key = _sql_usage_key(flow.sql)
            if sql_key not in sql_ids:
                sql_ids[sql_key] = f"sql_{len(sql_ids) + 1}"
            sql_node = sql_ids[sql_key]
            edge_lines.append(f"    {previous_node} -->|执行 SQL| {sql_node}")

            for table in flow.sql.tables[:GRAPH_MAX_TABLE_NODES]:
                table_ids.setdefault(table, _table_node_id(table))
                edge_lines.append(f"    {sql_node} -->|访问表| {table_ids[table]}")

        lines.append('    subgraph chains["接口调用链"]')
        for (methods_tuple, endpoint_path, _handler), node_id in endpoint_ids.items():
            methods = ",".join(methods_tuple)
            lines.append(f'        {node_id}["{_escape_mermaid(methods + " " + endpoint_path)}"]:::endpoint')
        for method_name, node_id in sorted(method_ids.items()):
            lines.append(f'        {node_id}["{_escape_mermaid(method_name)}"]:::method')
        for sql_key, node_id in sql_ids.items():
            owner, source, _file_path, _line = sql_key
            label = f"{owner}\\n{_display_sql_source(source)}"
            lines.append(f'        {node_id}["{_escape_mermaid(label)}"]:::sql')
        for table, node_id in sorted(table_ids.items()):
            lines.append(f'        {node_id}["{_escape_mermaid(table)}"]:::table')
        lines.append("    end")
        lines.append("")
        lines.extend(_dedupe_lines(edge_lines))
        omitted = len(endpoint_sql_flows) - len(selected_flows)
        if omitted > 0:
            lines.append(f'    omitted["图谱已裁剪：省略接口 SQL 流向 {omitted} 条"]')
        return "\n".join(lines)

    if call_chains:
        method_ids: dict[str, str] = {}
        edge_lines: list[str] = []
        for chain in call_chains:
            names = [chain.entrypoint.qualified_name]
            names.extend(edge.callee.qualified_name for edge in chain.edges if edge.callee)
            for method_name in names:
                method_ids.setdefault(method_name, _method_node_id(method_name))
            for left, right in zip(names, names[1:]):
                edge_lines.append(f"    {method_ids[left]} -->|调用| {method_ids[right]}")

        lines.append('    subgraph chains["代码调用链"]')
        for method_name, node_id in sorted(method_ids.items()):
            lines.append(f'        {node_id}["{_escape_mermaid(method_name)}"]:::method')
        lines.append("    end")
        lines.append("")
        lines.extend(_dedupe_lines(edge_lines))
        return "\n".join(lines)

    if not selected_flows and not call_chains:
        lines.append('    empty["未检测到可绘制的接口、组件或 SQL 引用"]')

    return "\n".join(lines)


def _component_dependencies(
    results: list[JavaFileAnalysis],
    component_names: set[str],
) -> list[tuple[str, str]]:
    # 依赖推断保持保守，只连接类型名精确匹配的已知组件。
    dependencies: set[tuple[str, str]] = set()
    for result in results:
        for field in result.fields:
            if field.enclosing_type in component_names and field.type in component_names:
                dependencies.add((field.enclosing_type, field.type))
        for method in result.methods:
            if method.enclosing_type not in component_names:
                continue
            for parameter in method.parameters:
                if parameter.type in component_names:
                    dependencies.add((method.enclosing_type, parameter.type))
    return sorted(dependencies)


def _component_node_id(name: str) -> str:
    return "component_" + re.sub(r"[^A-Za-z0-9_]", "_", name)


def _method_node_id(name: str) -> str:
    return "method_" + re.sub(r"[^A-Za-z0-9_]", "_", name)


def _table_node_id(name: str) -> str:
    return "table_" + re.sub(r"[^A-Za-z0-9_]", "_", name)


def _sql_usage_key(usage: object) -> tuple[str, str, str, int]:
    return (
        str(getattr(usage, "owner", "")),
        str(getattr(usage, "source", "")),
        str(getattr(usage, "file_path", "")),
        int(getattr(usage, "line", 0) or 0),
    )


def _dedupe_lines(lines: list[str]) -> list[str]:
    seen = set()
    result = []
    for line in lines:
        if line in seen:
            continue
        seen.add(line)
        result.append(line)
    return result


def _escape_mermaid(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"')


if __name__ == "__main__":
    raise SystemExit(main())
