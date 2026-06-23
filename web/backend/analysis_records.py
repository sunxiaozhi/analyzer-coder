from __future__ import annotations

from dataclasses import asdict, is_dataclass
from pathlib import Path
from typing import Any, Callable

from java_analyzer.models import JavaFileAnalysis, SourceSpan

from web.backend.service_models import AnalysisContext


def build_analysis_records(
    context: "AnalysisContext",
    results: list[JavaFileAnalysis],
    relative_to_root: Callable[[Path, Path], str],
    relative: Callable[[Path], str],
) -> list[dict[str, Any]]:
    # 将 tree-sitter 的嵌套分析结果展开为可分页、可筛选的 MySQL 记录。
    # 这里不写向量数据，向量切块由 indexing 模块单独生成。
    records: list[dict[str, Any]] = []
    for result in results:
        file_path = _analysis_file_path(context, result.file_path, relative_to_root, relative)
        records.append(
            _analysis_record(
                record_type="file",
                file_path=file_path,
                package=result.package or "",
                key_parts=[file_path or "<memory>"],
                payload={
                    "filePath": file_path,
                    "package": result.package,
                    "hasError": result.has_error,
                },
            )
        )
        if result.metrics is not None:
            records.append(
                _analysis_record(
                    record_type="metrics",
                    file_path=file_path,
                    package=result.package or "",
                    key_parts=[file_path or "<memory>", "metrics"],
                    payload=asdict(result.metrics),
                )
            )
        for index, item in enumerate(result.imports):
            records.append(_record_from_item("import", file_path, result.package, item, [item.name, index]))
        for index, item in enumerate(result.symbols):
            records.append(_record_from_item("symbol", file_path, result.package, item, [item.name, index]))
        for index, item in enumerate(result.types):
            records.append(_record_from_item("type", file_path, result.package, item, [item.name, index]))
        for index, item in enumerate(result.fields):
            records.append(
                _record_from_item("field", file_path, result.package, item, [item.enclosing_type or "", item.name, index])
            )
        for index, item in enumerate(result.methods):
            records.append(
                _record_from_item("method", file_path, result.package, item, [item.enclosing_type or "", item.name, index])
            )
        for index, item in enumerate(result.calls):
            records.append(
                _record_from_item(
                    "call",
                    file_path,
                    result.package,
                    item,
                    [item.enclosing_type or "", item.enclosing_method or "", item.name, index],
                )
            )
        for index, item in enumerate(result.local_variables):
            records.append(
                _record_from_item(
                    "local_variable",
                    file_path,
                    result.package,
                    item,
                    [item.enclosing_type or "", item.enclosing_method or "", item.name, index],
                )
            )
        for index, item in enumerate(result.returns):
            records.append(
                _record_from_item(
                    "return",
                    file_path,
                    result.package,
                    item,
                    [item.enclosing_type or "", item.enclosing_method or "", index],
                )
            )
        for index, item in enumerate(result.control_structures):
            records.append(
                _record_from_item(
                    "control_structure",
                    file_path,
                    result.package,
                    item,
                    [item.enclosing_type or "", item.enclosing_method or "", item.kind, index],
                )
            )
        for index, item in enumerate(result.components):
            records.append(_record_from_item("component", file_path, result.package, item, [item.name, index]))
        for index, item in enumerate(result.endpoints):
            records.append(
                _record_from_item(
                    "endpoint",
                    file_path,
                    result.package,
                    item,
                    [item.enclosing_type, item.method_name, item.path, index],
                )
            )
        for index, item in enumerate(result.sql_references):
            records.append(
                _record_from_item(
                    "sql_reference",
                    file_path,
                    result.package,
                    item,
                    [item.enclosing_type or "", item.method_name or "", index],
                )
            )
        for index, item in enumerate(result.syntax_errors):
            records.append(
                _analysis_record(
                    record_type="syntax_error",
                    file_path=file_path,
                    package=result.package or "",
                    span=item,
                    key_parts=[file_path or "<memory>", "syntax_error", index],
                    payload=asdict(item),
                )
            )
    return records


def _record_from_item(
    record_type: str,
    file_path: str,
    package_name: str | None,
    item: Any,
    key_parts: list[Any],
) -> dict[str, Any]:
    payload = asdict(item) if is_dataclass(item) else dict(item)
    return _analysis_record(
        record_type=record_type,
        file_path=file_path,
        package=package_name or "",
        span=getattr(item, "span", None),
        symbol_name=str(getattr(item, "name", "") or getattr(item, "method_name", "") or ""),
        enclosing_type=str(getattr(item, "enclosing_type", "") or ""),
        enclosing_method=str(getattr(item, "enclosing_method", "") or ""),
        key_parts=[file_path or "<memory>", record_type, *key_parts],
        payload=payload,
    )


def _analysis_record(
    record_type: str,
    file_path: str,
    package: str,
    key_parts: list[Any],
    payload: dict[str, Any],
    span: SourceSpan | None = None,
    symbol_name: str = "",
    enclosing_type: str = "",
    enclosing_method: str = "",
) -> dict[str, Any]:
    # key 需要在同一次分析内稳定，便于覆盖写入和排查重复记录。
    key = "::".join(str(part) for part in key_parts)
    return {
        "key": key,
        "type": record_type,
        "filePath": file_path,
        "package": package,
        "symbolName": symbol_name,
        "enclosingType": enclosing_type,
        "enclosingMethod": enclosing_method,
        "startLine": span.start_line if span else 0,
        "startColumn": span.start_column if span else 0,
        "endLine": span.end_line if span else 0,
        "endColumn": span.end_column if span else 0,
        "payload": payload,
    }


def _analysis_file_path(
    context: "AnalysisContext",
    file_path: str | None,
    relative_to_root: Callable[[Path, Path], str],
    relative: Callable[[Path], str],
) -> str:
    if not file_path:
        return ""
    path = Path(file_path)
    if path.is_absolute():
        try:
            # 优先展示项目内相对路径；逃出项目根时再退回 workspace 相对路径。
            return relative_to_root(context.root, path)
        except ValueError:
            return relative(path)
    return str(path).replace("\\", "/")
