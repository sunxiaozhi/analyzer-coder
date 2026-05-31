from __future__ import annotations

import argparse
from datetime import datetime
import json
import re
import sys
from pathlib import Path
from typing import Sequence

from java_ts_analyzer.analyzer import JavaAnalyzer
from java_ts_analyzer.chunker import build_chunks
from java_ts_analyzer.embedding import HashingEmbedder, SentenceTransformerEmbedder
from java_ts_analyzer.kb_loader import build_kb_chunks
from java_ts_analyzer.models import JavaFileAnalysis, JavaVectorChunk
from java_ts_analyzer.vector_store import JsonlVectorStore

TOKEN_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+")


def build_parser() -> argparse.ArgumentParser:
    # 将所有面向用户的模式集中到一个解析器中，让 CLI 同时承担分析、索引、
    # 检索、报告和图生成工具的职责。
    parser = argparse.ArgumentParser(
        prog="java-ts-analyze",
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
        default=Path(".java_ts_vectors.jsonl"),
        help="Vector store path used by --query. Defaults to .java_ts_vectors.jsonl.",
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
        default=Path(".java_ts_results"),
        help="Directory used to save each command result. Defaults to .java_ts_results.",
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
        _emit_output(args, _build_graph(code_results), kind="graph", extension=".mmd")
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
        f"- Knowledge chunks: {len(kb_chunks)}",
        f"- Packages: {', '.join(packages) if packages else '-'}",
        "",
        "## Inventory",
        "",
        "| Metric | Count |",
        "|---|---:|",
        f"| Types | {sum(metric.type_count for metric in metrics)} |",
        f"| Fields | {sum(metric.field_count for metric in metrics)} |",
        f"| Methods and constructors | {sum(metric.method_count for metric in metrics)} |",
        f"| Method calls | {sum(metric.call_count for metric in metrics)} |",
        f"| HTTP endpoints | {sum(metric.endpoint_count for metric in metrics)} |",
        "",
        "## Components",
        "",
    ]
    if components:
        for component in components:
            lines.append(f"- [[{component.name}]] `{component.annotation}`")
    else:
        lines.append("No Spring-style components detected.")

    lines.extend(["", "## Endpoints", ""])
    if endpoints:
        for endpoint in endpoints:
            methods = ",".join(endpoint.http_methods)
            lines.append(f"- [[{_endpoint_note_name(endpoint)}]] `{methods} {endpoint.path}`")
    else:
        lines.append("No Spring MVC endpoints detected.")

    lines.extend(["", "## Knowledge Sources", ""])
    files = sorted({str(chunk.metadata.get("file_path", "")) for chunk in kb_chunks})
    if files:
        for file_path in files:
            lines.append(f"- `{file_path}`")
    else:
        lines.append("No knowledge-base chunks included.")
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
        f"- Kind: `{component.kind}`",
        f"- Annotation: `{component.annotation}`",
        f"- File: `{result.file_path or ''}`",
        f"- Package: `{result.package or ''}`",
        "",
        "## Fields",
        "",
    ]
    lines.extend(
        f"- `{field.type or '?'} {field.name}`"
        for field in fields
    )
    if not fields:
        lines.append("No fields detected.")

    lines.extend(["", "## Methods", ""])
    lines.extend(f"- `{method.signature or method.name}`" for method in methods)
    if not methods:
        lines.append("No methods detected.")

    lines.extend(["", "## Endpoints", ""])
    lines.extend(
        f"- [[{_endpoint_note_name(endpoint)}]] `{','.join(endpoint.http_methods)} {endpoint.path}`"
        for endpoint in endpoints
    )
    if not endpoints:
        lines.append("No endpoints detected.")

    lines.extend(["", "## Related Knowledge", ""])
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
        f"- Controller: [[{endpoint.enclosing_type}]]",
        f"- Handler: `{endpoint.enclosing_type}.{endpoint.method_name}`",
        f"- Annotation: `{endpoint.annotation}`",
        f"- File: `{result.file_path or ''}`",
        "",
        "## Handler",
        "",
        f"- Signature: `{method.signature if method else endpoint.method_name}`",
        f"- Return type: `{method.return_type if method and method.return_type else ''}`",
        "",
        "## Related Knowledge",
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
        return ["No related knowledge chunks found."]
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
            modifiers = f" [{' '.join(item.modifiers)}]" if item.modifiers else ""
            annotations = f" {' '.join(item.annotations)}" if item.annotations else ""
            lines.append(f"    - {item.kind} {return_type}{item.name}({parameters}){owner}{modifiers}{annotations}")

    if result.calls:
        lines.append("  calls:")
        for item in result.calls:
            owner = f" in {item.enclosing_type}.{item.enclosing_method}" if item.enclosing_type and item.enclosing_method else ""
            qualifier = f"{item.qualifier}." if item.qualifier else ""
            lines.append(f"    - {qualifier}{item.name}(args={item.argument_count}){owner}")

    if result.components:
        lines.append("  components:")
        for item in result.components:
            lines.append(f"    - {item.kind} {item.name} {item.annotation}")

    if result.endpoints:
        lines.append("  endpoints:")
        for item in result.endpoints:
            methods = ",".join(item.http_methods)
            lines.append(f"    - {methods} {item.path} -> {item.enclosing_type}.{item.method_name}")

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
    total_sql = sum(metric.sql_reference_count for metric in metrics)
    packages = sorted({result.package for result in code_results if result.package})

    lines = [
        "# Java Project Analysis Report",
        "",
        f"- Path: `{path}`",
        f"- Source mode: `{source}`",
        f"- Java files analyzed: {len(code_results)}",
        f"- Knowledge chunks: {len(kb_chunks)}",
        f"- Packages: {', '.join(packages) if packages else '-'}",
        "",
        "## Inventory",
        "",
        "| Metric | Count |",
        "|---|---:|",
        f"| Types | {total_types} |",
        f"| Fields | {total_fields} |",
        f"| Methods and constructors | {total_methods} |",
        f"| Method calls | {total_calls} |",
        f"| HTTP endpoints | {total_endpoints} |",
        f"| SQL references | {total_sql} |",
        "",
    ]
    lines.extend(_component_report_lines(code_results))
    lines.extend(_endpoint_report_lines(code_results))
    lines.extend(_sql_report_lines(code_results))
    lines.extend(_kb_report_lines(kb_chunks))
    return "\n".join(lines)


def _component_report_lines(results: list[JavaFileAnalysis]) -> list[str]:
    components = [component for result in results for component in result.components]
    lines = ["## Components", ""]
    if not components:
        return [*lines, "No Spring-style components detected.", ""]
    lines.extend(["| Kind | Name | Annotation |", "|---|---|---|"])
    for item in components:
        lines.append(f"| {item.kind} | {item.name} | `{item.annotation}` |")
    lines.append("")
    return lines


def _endpoint_report_lines(results: list[JavaFileAnalysis]) -> list[str]:
    endpoints = [endpoint for result in results for endpoint in result.endpoints]
    lines = ["## HTTP Endpoints", ""]
    if not endpoints:
        return [*lines, "No Spring MVC endpoints detected.", ""]
    lines.extend(["| Methods | Path | Handler |", "|---|---|---|"])
    for item in endpoints:
        methods = ", ".join(item.http_methods)
        lines.append(f"| {methods} | `{item.path}` | `{item.enclosing_type}.{item.method_name}` |")
    lines.append("")
    return lines


def _sql_report_lines(results: list[JavaFileAnalysis]) -> list[str]:
    references = [reference for result in results for reference in result.sql_references]
    lines = ["## SQL References", ""]
    if not references:
        return [*lines, "No MyBatis SQL annotations detected.", ""]
    lines.extend(["| Operation | Owner | Statement |", "|---|---|---|"])
    for item in references:
        owner = f"{item.enclosing_type or ''}.{item.method_name or ''}".strip(".")
        statement = item.statement.replace("|", "\\|")
        lines.append(f"| {item.operation} | `{owner}` | `{statement}` |")
    lines.append("")
    return lines


def _kb_report_lines(chunks: list[JavaVectorChunk]) -> list[str]:
    lines = ["## Knowledge Base", ""]
    if not chunks:
        return [*lines, "No knowledge-base chunks included.", ""]
    files = sorted({str(chunk.metadata.get("file_path", "")) for chunk in chunks})
    lines.append(f"- Files: {len(files)}")
    lines.append(f"- Chunks: {len(chunks)}")
    for file_path in files:
        lines.append(f"- `{file_path}`")
    lines.append("")
    return lines


def _build_graph(results: list[JavaFileAnalysis]) -> str:
    # 图刻意保持高层视角：endpoint 指向处理器，组件依赖由字段类型和构造器参数推断。
    components = [component for result in results for component in result.components]
    endpoints = [endpoint for result in results for endpoint in result.endpoints]
    sql_references = [reference for result in results for reference in result.sql_references]
    component_names = {component.name for component in components}

    lines = [
        "flowchart LR",
        '    classDef endpoint fill:#e8f3ff,stroke:#2f73b7,color:#10253f',
        '    classDef component fill:#eef8ef,stroke:#3c8c4a,color:#17351d',
        '    classDef sql fill:#fff4df,stroke:#b7791f,color:#4a2d05',
        "",
    ]

    if endpoints:
        lines.append('    subgraph endpoints["HTTP Endpoints"]')
        for index, endpoint in enumerate(endpoints, start=1):
            methods = ",".join(endpoint.http_methods)
            lines.append(f'        endpoint_{index}["{_escape_mermaid(methods + " " + endpoint.path)}"]:::endpoint')
        lines.append("    end")
        lines.append("")

    if components:
        lines.append('    subgraph components["Spring Components"]')
        for component in components:
            node_id = _component_node_id(component.name)
            label = f"{component.name}\\n@{component.annotation.lstrip('@').split('(')[0]}"
            lines.append(f'        {node_id}["{_escape_mermaid(label)}"]:::component')
        lines.append("    end")
        lines.append("")

    if sql_references:
        lines.append('    subgraph sql["SQL References"]')
        for index, reference in enumerate(sql_references, start=1):
            label = f"{reference.operation}\\n{reference.method_name or ''}"
            lines.append(f'        sql_{index}["{_escape_mermaid(label)}"]:::sql')
        lines.append("    end")
        lines.append("")

    for index, endpoint in enumerate(endpoints, start=1):
        target = _component_node_id(endpoint.enclosing_type)
        lines.append(f"    endpoint_{index} -->|handles| {target}")

    for owner, dependency in _component_dependencies(results, component_names):
        lines.append(f"    {_component_node_id(owner)} -->|uses| {_component_node_id(dependency)}")

    for index, reference in enumerate(sql_references, start=1):
        if reference.enclosing_type:
            lines.append(f"    {_component_node_id(reference.enclosing_type)} -->|executes| sql_{index}")

    if not endpoints and not components and not sql_references:
        lines.append('    empty["No graphable endpoints, components, or SQL references detected"]')

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


def _escape_mermaid(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"')


if __name__ == "__main__":
    raise SystemExit(main())
