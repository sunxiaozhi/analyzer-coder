from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass(frozen=True)
class SourceSpan:
    start_line: int
    start_column: int
    end_line: int
    end_column: int

    @classmethod
    def from_node(cls, node: Any) -> "SourceSpan":
        start_row, start_col = node.start_point
        end_row, end_col = node.end_point
        return cls(
            start_line=start_row + 1,
            start_column=start_col + 1,
            end_line=end_row + 1,
            end_column=end_col + 1,
        )


@dataclass(frozen=True)
class JavaImport:
    name: str
    is_static: bool
    is_wildcard: bool
    span: SourceSpan


@dataclass(frozen=True)
class JavaParameter:
    name: str
    type: str | None
    span: SourceSpan


@dataclass(frozen=True)
class JavaSymbol:
    kind: str
    name: str
    span: SourceSpan
    enclosing_type: str | None = None
    signature: str | None = None
    modifiers: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class JavaType:
    kind: str
    name: str
    span: SourceSpan
    enclosing_type: str | None = None
    modifiers: tuple[str, ...] = field(default_factory=tuple)
    annotations: tuple[str, ...] = field(default_factory=tuple)
    superclass: str | None = None
    interfaces: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class JavaField:
    name: str
    type: str | None
    span: SourceSpan
    enclosing_type: str | None = None
    modifiers: tuple[str, ...] = field(default_factory=tuple)
    annotations: tuple[str, ...] = field(default_factory=tuple)
    initializer: str | None = None


@dataclass(frozen=True)
class JavaMethod:
    name: str
    kind: str
    span: SourceSpan
    enclosing_type: str | None = None
    return_type: str | None = None
    parameters: list[JavaParameter] = field(default_factory=list)
    modifiers: tuple[str, ...] = field(default_factory=tuple)
    annotations: tuple[str, ...] = field(default_factory=tuple)
    signature: str | None = None


@dataclass(frozen=True)
class JavaCall:
    name: str
    span: SourceSpan
    enclosing_type: str | None = None
    enclosing_method: str | None = None
    qualifier: str | None = None
    argument_count: int = 0


@dataclass(frozen=True)
class JavaComponent:
    kind: str
    name: str
    annotation: str
    span: SourceSpan


@dataclass(frozen=True)
class JavaEndpoint:
    path: str
    http_methods: tuple[str, ...]
    annotation: str
    span: SourceSpan
    enclosing_type: str
    method_name: str


@dataclass(frozen=True)
class JavaSqlReference:
    operation: str
    statement: str
    annotation: str
    span: SourceSpan
    enclosing_type: str | None = None
    method_name: str | None = None


@dataclass(frozen=True)
class JavaMetrics:
    line_count: int
    import_count: int
    type_count: int
    field_count: int
    method_count: int
    call_count: int
    syntax_error_count: int
    max_type_nesting: int
    node_count: int
    node_type_counts: dict[str, int]
    component_count: int = 0
    endpoint_count: int = 0
    sql_reference_count: int = 0


@dataclass(frozen=True)
class JavaVectorChunk:
    id: str
    text: str
    metadata: dict[str, Any]


@dataclass(frozen=True)
class JavaFileAnalysis:
    file_path: str | None
    package: str | None
    imports: list[JavaImport]
    symbols: list[JavaSymbol]
    has_error: bool
    syntax_errors: list[SourceSpan]
    types: list[JavaType] = field(default_factory=list)
    fields: list[JavaField] = field(default_factory=list)
    methods: list[JavaMethod] = field(default_factory=list)
    calls: list[JavaCall] = field(default_factory=list)
    components: list[JavaComponent] = field(default_factory=list)
    endpoints: list[JavaEndpoint] = field(default_factory=list)
    sql_references: list[JavaSqlReference] = field(default_factory=list)
    metrics: JavaMetrics | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
