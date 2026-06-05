from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from java_ts_analyzer.call_graph import MethodRef, build_call_edges
from java_ts_analyzer.models import JavaEndpoint, JavaFileAnalysis

SQL_TABLE_PATTERN = re.compile(
    r"\b(?:from|join|into|update)\s+([`\"\[]?[A-Za-z_][\w$]*(?:\.[A-Za-z_][\w$]*)?[`\"\]]?)",
    re.IGNORECASE,
)
XML_STATEMENT_TAGS = {"select", "insert", "update", "delete"}


@dataclass(frozen=True)
class SqlUsage:
    operation: str
    owner_type: str
    method_name: str
    statement: str
    tables: list[str]
    source: str
    file_path: str
    line: int

    @property
    def owner(self) -> str:
        return f"{self.owner_type}.{self.method_name}".strip(".")


@dataclass(frozen=True)
class EndpointSqlFlow:
    endpoint_methods: tuple[str, ...]
    endpoint_path: str
    handler: str
    code_path: list[str]
    sql: SqlUsage

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def build_sql_usages(results: list[JavaFileAnalysis], root: str | Path | None = None) -> list[SqlUsage]:
    usages = _annotation_sql_usages(results)
    if root is not None:
        usages.extend(_xml_sql_usages(Path(root)))
    return sorted(usages, key=lambda item: (item.owner, item.operation, item.file_path, item.line))


def build_endpoint_sql_flows(
    results: list[JavaFileAnalysis],
    root: str | Path | None = None,
    max_depth: int = 6,
) -> list[EndpointSqlFlow]:
    usages = build_sql_usages(results, root)
    usages_by_method: dict[tuple[str, str], list[SqlUsage]] = {}
    for usage in usages:
        usages_by_method.setdefault((usage.owner_type, usage.method_name), []).append(usage)

    methods = _method_refs(results)
    outgoing: dict[str, list[tuple[MethodRef, int]]] = {}
    for edge in build_call_edges(results):
        if edge.callee is None:
            continue
        outgoing.setdefault(edge.caller.qualified_name, []).append((edge.callee, edge.line))

    flows: list[EndpointSqlFlow] = []
    for endpoint in _endpoints(results):
        entrypoint = methods.get((endpoint.enclosing_type, endpoint.method_name))
        if entrypoint is None:
            continue
        reachable = _reachable_paths(entrypoint, outgoing, max_depth)
        for method in reachable:
            for usage in usages_by_method.get((method.type_name, method.method_name), []):
                flows.append(
                    EndpointSqlFlow(
                        endpoint_methods=endpoint.http_methods,
                        endpoint_path=endpoint.path,
                        handler=entrypoint.qualified_name,
                        code_path=[ref.qualified_name for ref in reachable[method]],
                        sql=usage,
                    )
                )
    return flows


def extract_tables(statement: str) -> list[str]:
    tables = []
    for match in SQL_TABLE_PATTERN.finditer(statement):
        table = match.group(1).strip("`\"[]")
        if table.lower() not in {"select", "set", "where"} and table not in tables:
            tables.append(table)
    return tables


def _annotation_sql_usages(results: list[JavaFileAnalysis]) -> list[SqlUsage]:
    usages: list[SqlUsage] = []
    file_by_type = {
        item.name: result.file_path or ""
        for result in results
        for item in result.types
    }
    for result in results:
        for reference in result.sql_references:
            if not reference.enclosing_type or not reference.method_name:
                continue
            usages.append(
                SqlUsage(
                    operation=reference.operation,
                    owner_type=reference.enclosing_type,
                    method_name=reference.method_name,
                    statement=reference.statement,
                    tables=extract_tables(reference.statement),
                    source="annotation",
                    file_path=file_by_type.get(reference.enclosing_type, result.file_path or ""),
                    line=reference.span.start_line,
                )
            )
    return usages


def _xml_sql_usages(root: Path) -> list[SqlUsage]:
    usages: list[SqlUsage] = []
    if not root.exists():
        return usages
    for file_path in sorted(root.rglob("*.xml")):
        text = file_path.read_text(encoding="utf-8", errors="replace")
        if "<mapper" not in text:
            continue
        try:
            document = ET.fromstring(text)
        except ET.ParseError:
            continue
        if _tag_name(document.tag) != "mapper":
            continue
        owner_type = str(document.attrib.get("namespace") or "").split(".")[-1]
        if not owner_type:
            continue
        for node in document.iter():
            operation = _tag_name(node.tag)
            if operation not in XML_STATEMENT_TAGS:
                continue
            method_name = str(node.attrib.get("id") or "").strip()
            if not method_name:
                continue
            statement = _normalize_sql(" ".join(node.itertext()))
            usages.append(
                SqlUsage(
                    operation=operation,
                    owner_type=owner_type,
                    method_name=method_name,
                    statement=statement,
                    tables=extract_tables(statement),
                    source="xml",
                    file_path=str(file_path),
                    line=_xml_statement_line(text, operation, method_name),
                )
            )
    return usages


def _method_refs(results: list[JavaFileAnalysis]) -> dict[tuple[str, str], MethodRef]:
    refs: dict[tuple[str, str], MethodRef] = {}
    for result in results:
        for method in result.methods:
            if not method.enclosing_type:
                continue
            refs.setdefault(
                (method.enclosing_type, method.name),
                MethodRef(
                    type_name=method.enclosing_type,
                    method_name=method.name,
                    file_path=result.file_path or "",
                    line=method.span.start_line,
                    signature=method.signature or method.name,
                ),
            )
    return refs


def _endpoints(results: list[JavaFileAnalysis]) -> list[JavaEndpoint]:
    return sorted(
        [endpoint for result in results for endpoint in result.endpoints],
        key=lambda item: (item.path, item.enclosing_type, item.method_name),
    )


def _reachable_paths(
    entrypoint: MethodRef,
    outgoing: dict[str, list[tuple[MethodRef, int]]],
    max_depth: int,
) -> dict[MethodRef, list[MethodRef]]:
    paths: dict[MethodRef, list[MethodRef]] = {entrypoint: [entrypoint]}
    queue: list[MethodRef] = [entrypoint]
    while queue:
        current = queue.pop(0)
        path = paths[current]
        if len(path) > max_depth:
            continue
        for next_ref, _line in outgoing.get(current.qualified_name, []):
            if next_ref in paths:
                continue
            paths[next_ref] = [*path, next_ref]
            queue.append(next_ref)
    return paths


def _tag_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def _normalize_sql(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def _xml_statement_line(text: str, operation: str, method_name: str) -> int:
    pattern = re.compile(rf"<\s*{re.escape(operation)}\b[^>]*\bid\s*=\s*['\"]{re.escape(method_name)}['\"]")
    match = pattern.search(text)
    if not match:
        return 1
    return text.count("\n", 0, match.start()) + 1
