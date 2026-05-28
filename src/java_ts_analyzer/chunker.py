from __future__ import annotations

from java_ts_analyzer.models import JavaFileAnalysis, JavaVectorChunk, SourceSpan


def build_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    chunks.extend(_type_chunks(analysis))
    chunks.extend(_field_chunks(analysis))
    chunks.extend(_method_chunks(analysis))
    chunks.extend(_component_chunks(analysis))
    chunks.extend(_endpoint_chunks(analysis))
    chunks.extend(_sql_reference_chunks(analysis))
    return chunks


def _type_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    for item in analysis.types:
        lines = [
            f"Java {item.kind} {item.name}",
            f"Package: {analysis.package or ''}",
            f"File: {analysis.file_path or ''}",
            f"Modifiers: {' '.join(item.modifiers)}",
            f"Annotations: {' '.join(item.annotations)}",
        ]
        if item.enclosing_type:
            lines.append(f"Enclosing type: {item.enclosing_type}")
        if item.superclass:
            lines.append(f"Extends: {item.superclass}")
        if item.interfaces:
            lines.append(f"Implements: {', '.join(item.interfaces)}")

        chunks.append(
            JavaVectorChunk(
                id=_chunk_id(analysis, "type", item.name, item.span),
                text=_clean_text(lines),
                metadata=_metadata(
                    analysis=analysis,
                    kind="type",
                    type_name=item.name,
                    symbol_name=item.name,
                    span=item.span,
                    extra={
                        "type_kind": item.kind,
                        "enclosing_type": item.enclosing_type or "",
                        "superclass": item.superclass or "",
                        "interfaces": ", ".join(item.interfaces),
                    },
                ),
            )
        )
    return chunks


def _field_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    for item in analysis.fields:
        lines = [
            f"Java field {item.name}",
            f"Package: {analysis.package or ''}",
            f"File: {analysis.file_path or ''}",
            f"Enclosing type: {item.enclosing_type or ''}",
            f"Type: {item.type or ''}",
            f"Modifiers: {' '.join(item.modifiers)}",
            f"Annotations: {' '.join(item.annotations)}",
        ]
        if item.initializer:
            lines.append(f"Initializer: {item.initializer}")

        chunks.append(
            JavaVectorChunk(
                id=_chunk_id(analysis, "field", item.name, item.span),
                text=_clean_text(lines),
                metadata=_metadata(
                    analysis=analysis,
                    kind="field",
                    type_name=item.enclosing_type or "",
                    symbol_name=item.name,
                    span=item.span,
                    extra={
                        "field_type": item.type or "",
                        "initializer": item.initializer or "",
                    },
                ),
            )
        )
    return chunks


def _method_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    for item in analysis.methods:
        calls = [
            call.name
            for call in analysis.calls
            if call.enclosing_type == item.enclosing_type and call.enclosing_method == item.name
        ]
        parameters = ", ".join(
            f"{parameter.type or '?'} {parameter.name}" for parameter in item.parameters
        )
        lines = [
            f"Java {item.kind} {item.name}",
            f"Package: {analysis.package or ''}",
            f"File: {analysis.file_path or ''}",
            f"Enclosing type: {item.enclosing_type or ''}",
            f"Signature: {item.signature or ''}",
            f"Return type: {item.return_type or ''}",
            f"Parameters: {parameters}",
            f"Modifiers: {' '.join(item.modifiers)}",
            f"Annotations: {' '.join(item.annotations)}",
            f"Calls: {', '.join(calls)}",
        ]

        chunks.append(
            JavaVectorChunk(
                id=_chunk_id(analysis, item.kind, item.name, item.span),
                text=_clean_text(lines),
                metadata=_metadata(
                    analysis=analysis,
                    kind=item.kind,
                    type_name=item.enclosing_type or "",
                    symbol_name=item.name,
                    span=item.span,
                    extra={
                        "signature": item.signature or "",
                        "return_type": item.return_type or "",
                        "parameters": parameters,
                        "calls": ", ".join(calls),
                    },
                ),
            )
        )
    return chunks


def _component_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    for item in analysis.components:
        lines = [
            f"Java Spring component {item.name}",
            f"Component kind: {item.kind}",
            f"Annotation: {item.annotation}",
            f"Package: {analysis.package or ''}",
            f"File: {analysis.file_path or ''}",
        ]
        chunks.append(
            JavaVectorChunk(
                id=_chunk_id(analysis, "component", item.name, item.span),
                text=_clean_text(lines),
                metadata=_metadata(
                    analysis=analysis,
                    kind="component",
                    type_name=item.name,
                    symbol_name=item.name,
                    span=item.span,
                    extra={
                        "component_kind": item.kind,
                        "annotation": item.annotation,
                    },
                ),
            )
        )
    return chunks


def _endpoint_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    for item in analysis.endpoints:
        methods = ", ".join(item.http_methods)
        lines = [
            f"Java HTTP endpoint {methods} {item.path}",
            f"Controller: {item.enclosing_type}",
            f"Method: {item.method_name}",
            f"Annotation: {item.annotation}",
            f"Package: {analysis.package or ''}",
            f"File: {analysis.file_path or ''}",
        ]
        chunks.append(
            JavaVectorChunk(
                id=_chunk_id(analysis, "endpoint", f"{item.enclosing_type}.{item.method_name}", item.span),
                text=_clean_text(lines),
                metadata=_metadata(
                    analysis=analysis,
                    kind="endpoint",
                    type_name=item.enclosing_type,
                    symbol_name=item.method_name,
                    span=item.span,
                    extra={
                        "path": item.path,
                        "http_methods": methods,
                        "annotation": item.annotation,
                    },
                ),
            )
        )
    return chunks


def _sql_reference_chunks(analysis: JavaFileAnalysis) -> list[JavaVectorChunk]:
    chunks: list[JavaVectorChunk] = []
    for item in analysis.sql_references:
        lines = [
            f"Java SQL {item.operation} {item.method_name or ''}",
            f"Enclosing type: {item.enclosing_type or ''}",
            f"Statement: {item.statement}",
            f"Annotation: {item.annotation}",
            f"Package: {analysis.package or ''}",
            f"File: {analysis.file_path or ''}",
        ]
        chunks.append(
            JavaVectorChunk(
                id=_chunk_id(
                    analysis,
                    "sql",
                    f"{item.enclosing_type or ''}.{item.method_name or item.operation}",
                    item.span,
                ),
                text=_clean_text(lines),
                metadata=_metadata(
                    analysis=analysis,
                    kind="sql",
                    type_name=item.enclosing_type or "",
                    symbol_name=item.method_name or item.operation,
                    span=item.span,
                    extra={
                        "operation": item.operation,
                        "statement": item.statement,
                        "annotation": item.annotation,
                    },
                ),
            )
        )
    return chunks


def _chunk_id(
    analysis: JavaFileAnalysis,
    kind: str,
    symbol_name: str,
    span: SourceSpan,
) -> str:
    file_path = analysis.file_path or "<memory>"
    return f"{file_path}::{kind}::{symbol_name}::{span.start_line}"


def _metadata(
    analysis: JavaFileAnalysis,
    kind: str,
    type_name: str,
    symbol_name: str,
    span: SourceSpan,
    extra: dict[str, str],
) -> dict[str, str | int]:
    metadata: dict[str, str | int] = {
        "source_type": "code",
        "file_path": analysis.file_path or "",
        "package": analysis.package or "",
        "kind": kind,
        "type_name": type_name,
        "symbol_name": symbol_name,
        "start_line": span.start_line,
        "start_column": span.start_column,
        "end_line": span.end_line,
        "end_column": span.end_column,
    }
    metadata.update(extra)
    return metadata


def _clean_text(lines: list[str]) -> str:
    return "\n".join(line for line in lines if not line.endswith(": "))
