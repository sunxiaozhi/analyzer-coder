from __future__ import annotations

import ast
import re
from pathlib import Path
from typing import Iterable

import tree_sitter_java as tsjava
from tree_sitter import Language, Node, Parser, Tree

from java_analyzer.models import (
    JavaAnnotation,
    JavaCall,
    JavaComponent,
    JavaEndpoint,
    JavaEndpointParameter,
    JavaField,
    JavaFileAnalysis,
    JavaImport,
    JavaControlStructure,
    JavaLocalVariable,
    JavaMethod,
    JavaMetrics,
    JavaParameter,
    JavaReturn,
    JavaSqlReference,
    JavaSymbol,
    JavaType,
    SourceSpan,
)

JAVA_LANGUAGE = Language(tsjava.language())

TYPE_NODES = {
    "annotation_type_declaration": "annotation",
    "class_declaration": "class",
    "enum_declaration": "enum",
    "interface_declaration": "interface",
    "record_declaration": "record",
}

MEMBER_NODES = {
    "constructor_declaration": "constructor",
    "method_declaration": "method",
}

COMPONENT_ANNOTATIONS = {
    "Component": "component",
    "Controller": "controller",
    "Mapper": "mapper",
    "Repository": "repository",
    "RestController": "rest_controller",
    "Service": "service",
}

ROUTE_ANNOTATIONS = {
    "DeleteMapping": ("DELETE",),
    "GetMapping": ("GET",),
    "PatchMapping": ("PATCH",),
    "PostMapping": ("POST",),
    "PutMapping": ("PUT",),
    "RequestMapping": (),
}

SQL_ANNOTATIONS = {
    "Delete": "delete",
    "DeleteProvider": "delete_provider",
    "Insert": "insert",
    "InsertProvider": "insert_provider",
    "Select": "select",
    "SelectProvider": "select_provider",
    "Update": "update",
    "UpdateProvider": "update_provider",
}

PARAMETER_SOURCE_ANNOTATIONS = {
    "CookieValue": "cookie",
    "MatrixVariable": "matrix",
    "PathVariable": "path",
    "RequestAttribute": "attribute",
    "RequestBody": "body",
    "RequestHeader": "header",
    "RequestParam": "query",
    "RequestPart": "part",
}

CONTROL_NODES = {
    "catch_clause": "catch",
    "do_statement": "do",
    "enhanced_for_statement": "for_each",
    "for_statement": "for",
    "if_statement": "if",
    "lambda_expression": "lambda",
    "switch_expression": "switch",
    "switch_statement": "switch",
    "synchronized_statement": "synchronized",
    "throw_statement": "throw",
    "try_statement": "try",
    "while_statement": "while",
}

ANNOTATION_NAME_PATTERN = re.compile(r"@(?:[A-Za-z_][\w]*\.)*([A-Za-z_][\w]*)")
REQUEST_METHOD_PATTERN = re.compile(r"RequestMethod\.([A-Z]+)")
STRING_LITERAL_PATTERN = re.compile(r'"((?:\\.|[^"\\])*)"')


class JavaAnalyzer:
    """基于 Tree-sitter 的分析器，把 Java 源码转换成类型化模型。"""

    def __init__(self) -> None:
        self.parser = Parser(JAVA_LANGUAGE)

    def parse(self, source: str | bytes) -> Tree:
        return self.parser.parse(_to_bytes(source))

    def analyze_source(
        self,
        source: str | bytes,
        file_path: str | Path | None = None,
    ) -> JavaFileAnalysis:
        source_bytes = _to_bytes(source)
        tree = self.parser.parse(source_bytes)
        root = tree.root_node

        # 对同一棵 AST 运行一组相互独立的提取器；切块、报告、图生成等下游功能
        # 都消费这一个分析结果。
        package = _extract_package(root, source_bytes)
        imports = _extract_imports(root, source_bytes)
        symbols = list(_extract_symbols(root, source_bytes))
        types = list(_extract_types(root, source_bytes))
        fields = list(_extract_field_details(root, source_bytes))
        methods = list(_extract_methods(root, source_bytes))
        calls = list(_extract_calls(root, source_bytes))
        local_variables = list(_extract_local_variables(root, source_bytes))
        returns = list(_extract_returns(root, source_bytes))
        control_structures = list(_extract_control_structures(root, source_bytes))

        # 框架相关表面从通用的类型/方法模型中派生，避免把 Tree-sitter 遍历逻辑分散到各处。
        components = _extract_components(types)
        endpoints = _extract_endpoints(types, methods)
        sql_references = _extract_sql_references(methods)
        syntax_errors = [
            SourceSpan.from_node(node)
            for node in _walk(root)
            if node.type == "ERROR" or node.is_missing
        ]
        metrics = _extract_metrics(
            root=root,
            source=source_bytes,
            imports=imports,
            types=types,
            fields=fields,
            methods=methods,
            calls=calls,
            local_variables=local_variables,
            returns=returns,
            control_structures=control_structures,
            syntax_errors=syntax_errors,
            components=components,
            endpoints=endpoints,
            sql_references=sql_references,
        )

        return JavaFileAnalysis(
            file_path=str(file_path) if file_path is not None else None,
            package=package,
            imports=imports,
            symbols=symbols,
            has_error=root.has_error,
            syntax_errors=syntax_errors,
            types=types,
            fields=fields,
            methods=methods,
            calls=calls,
            local_variables=local_variables,
            returns=returns,
            control_structures=control_structures,
            components=components,
            endpoints=endpoints,
            sql_references=sql_references,
            metrics=metrics,
        )

    def analyze_file(self, path: str | Path) -> JavaFileAnalysis:
        java_file = Path(path)
        return self.analyze_source(java_file.read_bytes(), java_file)

    def analyze_path(self, path: str | Path) -> list[JavaFileAnalysis]:
        target = Path(path)
        if target.is_file():
            return [self.analyze_file(target)]
        return [self.analyze_file(java_file) for java_file in sorted(target.rglob("*.java"))]

    def format_tree(self, source: str | bytes) -> str:
        source_bytes = _to_bytes(source)
        tree = self.parser.parse(source_bytes)
        lines: list[str] = []
        _append_tree_lines(tree.root_node, source_bytes, lines, depth=0)
        return "\n".join(lines)


def _to_bytes(source: str | bytes) -> bytes:
    if isinstance(source, bytes):
        return source
    return source.encode("utf-8")


def _node_text(node: Node, source: bytes) -> str:
    return source[node.start_byte : node.end_byte].decode("utf-8", errors="replace")


def _string_literal_value(node: Node, source: bytes) -> str:
    text = _node_text(node, source)
    try:
        value = ast.literal_eval(text)
    except (SyntaxError, ValueError):
        return text.strip('"')
    return value if isinstance(value, str) else str(value)


def _child_text(node: Node, source: bytes, field_name: str) -> str | None:
    child = node.child_by_field_name(field_name)
    if child is None:
        return None
    return _node_text(child, source)


def _walk(node: Node) -> Iterable[Node]:
    """按深度优先顺序产出所有节点。"""
    yield node
    for child in node.children:
        yield from _walk(child)


def _direct_children(root: Node, node_type: str) -> Iterable[Node]:
    for child in root.children:
        if child.type == node_type:
            yield child


def _extract_package(root: Node, source: bytes) -> str | None:
    for node in _direct_children(root, "package_declaration"):
        text = _node_text(node, source).strip()
        return text.removeprefix("package").rstrip(";").strip()
    return None


def _extract_imports(root: Node, source: bytes) -> list[JavaImport]:
    imports: list[JavaImport] = []
    for node in _direct_children(root, "import_declaration"):
        text = _node_text(node, source).strip().rstrip(";")
        body = text.removeprefix("import").strip()
        is_static = body.startswith("static ")
        if is_static:
            body = body.removeprefix("static").strip()
        imports.append(
            JavaImport(
                name=body,
                is_static=is_static,
                is_wildcard=body.endswith(".*"),
                span=SourceSpan.from_node(node),
            )
        )
    return imports


def _extract_symbols(root: Node, source: bytes) -> Iterable[JavaSymbol]:
    # 遍历嵌套声明时，用栈保存外层类型名，比反复回溯父节点更简单可靠。
    type_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaSymbol]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                yield JavaSymbol(
                    kind=TYPE_NODES[node.type],
                    name=name,
                    span=SourceSpan.from_node(node),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    modifiers=_extract_modifiers(node, source),
                )
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type in MEMBER_NODES:
            name = _child_text(node, source, "name")
            if name:
                yield JavaSymbol(
                    kind=MEMBER_NODES[node.type],
                    name=name,
                    span=SourceSpan.from_node(node),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    signature=_signature_text(node, source),
                    modifiers=_extract_modifiers(node, source),
                )
                return

        if node.type == "field_declaration":
            yield from _extract_fields(node, source, type_stack[-1] if type_stack else None)
            return

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_fields(
    node: Node,
    source: bytes,
    enclosing_type: str | None,
) -> Iterable[JavaSymbol]:
    modifiers = _extract_modifiers(node, source)
    field_type = _declared_type(node, source)
    for child in node.children:
        if child.type != "variable_declarator":
            continue
        name = _child_text(child, source, "name")
        if not name:
            continue
        yield JavaSymbol(
            kind="field",
            name=name,
            span=SourceSpan.from_node(child),
            enclosing_type=enclosing_type,
            signature=f"{field_type} {name}" if field_type else name,
            modifiers=modifiers,
        )


def _extract_types(root: Node, source: bytes) -> Iterable[JavaType]:
    # 将嵌套类/接口挂到它们直接所属的外层类型上。
    type_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaType]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                yield JavaType(
                    kind=TYPE_NODES[node.type],
                    name=name,
                    span=SourceSpan.from_node(node),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    modifiers=_extract_modifiers(node, source),
                    annotations=_extract_annotations(node, source),
                    annotation_details=_extract_annotation_details(node, source),
                    superclass=_extract_superclass(node, source),
                    interfaces=_extract_interfaces(node, source),
                )
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_field_details(root: Node, source: bytes) -> Iterable[JavaField]:
    type_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaField]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type == "field_declaration":
            field_type = _declared_type(node, source)
            modifiers = _extract_modifiers(node, source)
            annotations = _extract_annotations(node, source)
            for child in node.children:
                if child.type != "variable_declarator":
                    continue
                name = _child_text(child, source, "name")
                if not name:
                    continue
                yield JavaField(
                    name=name,
                    type=field_type,
                    span=SourceSpan.from_node(child),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    modifiers=modifiers,
                    annotations=annotations,
                    annotation_details=_extract_annotation_details(node, source),
                    initializer=_extract_initializer(child, source),
                )
            return

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_methods(root: Node, source: bytes) -> Iterable[JavaMethod]:
    # 构造器和普通方法共享大部分元数据，因此统一表示为 JavaMethod，并用 kind 区分。
    type_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaMethod]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type in MEMBER_NODES:
            name = _child_text(node, source, "name")
            if name:
                yield JavaMethod(
                    name=name,
                    kind=MEMBER_NODES[node.type],
                    span=SourceSpan.from_node(node),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    return_type=_declared_type(node, source),
                    parameters=_extract_parameters(node, source),
                    modifiers=_extract_modifiers(node, source),
                    annotations=_extract_annotations(node, source),
                    annotation_details=_extract_annotation_details(node, source),
                    signature=_signature_text(node, source),
                    throws=_extract_throws(node, source),
                )
                for child in node.children:
                    yield from visit(child)
                return

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_calls(root: Node, source: bytes) -> Iterable[JavaCall]:
    # 方法调用会附带所属类型和所属方法，让方法级 chunk 在检索时更有上下文。
    type_stack: list[str] = []
    method_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaCall]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type in MEMBER_NODES:
            name = _child_text(node, source, "name")
            if name:
                method_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                method_stack.pop()
                return

        if node.type == "method_invocation":
            name = _child_text(node, source, "name")
            if name:
                yield JavaCall(
                    name=name,
                    span=SourceSpan.from_node(node),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    enclosing_method=method_stack[-1] if method_stack else None,
                    qualifier=_extract_call_qualifier(node, source),
                    argument_count=_argument_count(node),
                    arguments=_argument_texts(node, source),
                    kind="method",
                )

        if node.type == "object_creation_expression":
            name = _object_creation_type(node, source)
            if name:
                yield JavaCall(
                    name=name,
                    span=SourceSpan.from_node(node),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    enclosing_method=method_stack[-1] if method_stack else None,
                    argument_count=_argument_count(node),
                    arguments=_argument_texts(node, source),
                    kind="constructor",
                )

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_local_variables(root: Node, source: bytes) -> Iterable[JavaLocalVariable]:
    type_stack: list[str] = []
    method_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaLocalVariable]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type in MEMBER_NODES:
            name = _child_text(node, source, "name")
            if name:
                method_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                method_stack.pop()
                return

        if node.type == "local_variable_declaration":
            variable_type = _declared_type(node, source)
            modifiers = _extract_modifiers(node, source)
            annotations = _extract_annotations(node, source)
            annotation_details = _extract_annotation_details(node, source)
            for child in node.children:
                if child.type != "variable_declarator":
                    continue
                name = _child_text(child, source, "name")
                if not name:
                    continue
                yield JavaLocalVariable(
                    name=name,
                    type=variable_type,
                    span=SourceSpan.from_node(child),
                    enclosing_type=type_stack[-1] if type_stack else None,
                    enclosing_method=method_stack[-1] if method_stack else None,
                    modifiers=modifiers,
                    annotations=annotations,
                    annotation_details=annotation_details,
                    initializer=_extract_initializer(child, source),
                )
            return

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_returns(root: Node, source: bytes) -> Iterable[JavaReturn]:
    type_stack: list[str] = []
    method_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaReturn]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type in MEMBER_NODES:
            name = _child_text(node, source, "name")
            if name:
                method_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                method_stack.pop()
                return

        if node.type == "return_statement":
            expression = _return_expression(node, source)
            yield JavaReturn(
                expression=expression,
                span=SourceSpan.from_node(node),
                enclosing_type=type_stack[-1] if type_stack else None,
                enclosing_method=method_stack[-1] if method_stack else None,
            )
            return

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_control_structures(root: Node, source: bytes) -> Iterable[JavaControlStructure]:
    type_stack: list[str] = []
    method_stack: list[str] = []

    def visit(node: Node) -> Iterable[JavaControlStructure]:
        if node.type in TYPE_NODES:
            name = _child_text(node, source, "name")
            if name:
                type_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                type_stack.pop()
                return

        if node.type in MEMBER_NODES:
            name = _child_text(node, source, "name")
            if name:
                method_stack.append(name)
                for child in node.children:
                    yield from visit(child)
                method_stack.pop()
                return

        if node.type in CONTROL_NODES:
            yield JavaControlStructure(
                kind=CONTROL_NODES[node.type],
                span=SourceSpan.from_node(node),
                enclosing_type=type_stack[-1] if type_stack else None,
                enclosing_method=method_stack[-1] if method_stack else None,
                condition=_control_condition(node, source),
            )

        for child in node.children:
            yield from visit(child)

    yield from visit(root)


def _extract_modifiers(node: Node, source: bytes) -> tuple[str, ...]:
    for child in node.children:
        if child.type != "modifiers":
            continue
        return tuple(
            _node_text(modifier, source)
            for modifier in child.children
            if not modifier.type.endswith("annotation")
        )
    return ()


def _extract_annotations(node: Node, source: bytes) -> tuple[str, ...]:
    for child in node.children:
        if child.type != "modifiers":
            continue
        return tuple(
            _node_text(modifier, source)
            for modifier in child.children
            if modifier.type.endswith("annotation")
        )
    return ()


def _extract_annotation_details(node: Node, source: bytes) -> list[JavaAnnotation]:
    for child in node.children:
        if child.type != "modifiers":
            continue
        return [
            _parse_annotation_node(modifier, source)
            for modifier in child.children
            if modifier.type.endswith("annotation")
        ]
    return []


def _parse_annotation_node(node: Node, source: bytes) -> JavaAnnotation:
    values: list[str] = []
    arguments: dict[str, tuple[str, ...]] = {}
    args = node.child_by_field_name("arguments")
    if args is None:
        for child in node.children:
            if child.type == "annotation_argument_list":
                args = child
                break
    if args is not None:
        for child in args.children:
            if child.type in {"(", ")", ","}:
                continue
            if child.type == "element_value_pair":
                key, parsed_values = _annotation_pair(child, source)
                if key:
                    arguments[key] = tuple(parsed_values)
                continue
            values.extend(_annotation_values(child, source))

    if values:
        arguments.setdefault("value", tuple(values))

    return JavaAnnotation(
        name=_child_text(node, source, "name") or _annotation_name(_node_text(node, source)) or "",
        raw=_node_text(node, source),
        span=SourceSpan.from_node(node),
        values=tuple(values),
        arguments=arguments,
    )


def _annotation_pair(node: Node, source: bytes) -> tuple[str | None, list[str]]:
    key: str | None = None
    value_nodes: list[Node] = []
    seen_equals = False
    for child in node.children:
        if child.type == "=":
            seen_equals = True
            continue
        if not seen_equals and key is None and child.type == "identifier":
            key = _node_text(child, source)
            continue
        if seen_equals:
            value_nodes.append(child)
    values = [value for child in value_nodes for value in _annotation_values(child, source)]
    return key, values


def _annotation_values(node: Node, source: bytes) -> list[str]:
    if node.type in {"{", "}", ","}:
        return []
    if node.type == "element_value_array_initializer":
        return [value for child in node.children for value in _annotation_values(child, source)]
    if node.type == "string_literal":
        return [_string_literal_value(node, source)]
    if node.type == "character_literal":
        return [_node_text(node, source).strip("'")]
    if node.type in {"true", "false", "decimal_integer_literal", "hex_integer_literal", "floating_point_literal"}:
        return [_node_text(node, source)]
    return [_node_text(node, source)]


def _declared_type(node: Node, source: bytes) -> str | None:
    typed = node.child_by_field_name("type")
    if typed is not None:
        return _node_text(typed, source)

    # 不同声明里的 Tree-sitter 字段名并不完全一致；如果没有 type 字段，
    # 就跳过语法符号和方法体，取第一个看起来像类型的子节点。
    skip = {
        ",",
        ";",
        "modifiers",
        "identifier",
        "formal_parameters",
        "constructor_body",
        "block",
        "dimensions",
        "=",
    }
    for child in node.children:
        if child.type in skip or child.type in {"class_body", "interface_body", "enum_body"}:
            continue
        if child.type.endswith("declaration") or child.type.endswith("declarator"):
            continue
        return _node_text(child, source)
    return None


def _extract_parameters(node: Node, source: bytes) -> list[JavaParameter]:
    params = node.child_by_field_name("parameters")
    if params is None:
        for child in node.children:
            if child.type == "formal_parameters":
                params = child
                break
    if params is None:
        return []

    result: list[JavaParameter] = []
    for child in params.children:
        if child.type not in {"formal_parameter", "spread_parameter"}:
            continue
        name = _child_text(child, source, "name")
        if not name:
            continue
        result.append(
            JavaParameter(
                name=name,
                type=_declared_type(child, source),
                span=SourceSpan.from_node(child),
                annotations=_extract_annotations(child, source),
                annotation_details=_extract_annotation_details(child, source),
            )
        )
    return result


def _extract_throws(node: Node, source: bytes) -> tuple[str, ...]:
    for child in node.children:
        if child.type != "throws":
            continue
        return tuple(
            _node_text(part, source)
            for part in child.children
            if part.type != "throws" and part.type != ","
        )
    return ()


def _extract_superclass(node: Node, source: bytes) -> str | None:
    for child in node.children:
        if child.type != "superclass":
            continue
        for part in child.children:
            if part.type != "extends":
                return _node_text(part, source)
    return None


def _extract_interfaces(node: Node, source: bytes) -> tuple[str, ...]:
    for child in node.children:
        if child.type not in {"super_interfaces", "extends_interfaces"}:
            continue
        for part in child.children:
            if part.type == "type_list":
                return tuple(
                    _node_text(item, source)
                    for item in part.children
                    if item.type != ","
                )
    return ()


def _extract_initializer(node: Node, source: bytes) -> str | None:
    seen_equals = False
    parts: list[str] = []
    for child in node.children:
        if child.type == "=":
            seen_equals = True
            continue
        if seen_equals:
            parts.append(_node_text(child, source))
    return " ".join(parts).strip() or None


def _return_expression(node: Node, source: bytes) -> str | None:
    parts = [
        _node_text(child, source)
        for child in node.children
        if child.type not in {"return", ";"}
    ]
    return " ".join(parts).strip() or None


def _control_condition(node: Node, source: bytes) -> str | None:
    for field_name in ("condition", "parameter", "resources"):
        child = node.child_by_field_name(field_name)
        if child is not None:
            return _strip_outer_parentheses(_node_text(child, source))

    skip = {
        "(",
        ")",
        ";",
        "do",
        "else",
        "finally",
        "for",
        "if",
        "try",
        "while",
        "switch",
        "synchronized",
        "throw",
    }
    stop = {
        "block",
        "catch_clause",
        "finally_clause",
        "switch_block",
    }
    parts: list[str] = []
    for child in node.children:
        if child.type in stop:
            break
        if child.type in skip:
            continue
        parts.append(_node_text(child, source))
    return _strip_outer_parentheses(" ".join(parts).strip()) or None


def _strip_outer_parentheses(value: str) -> str:
    value = value.strip()
    while value.startswith("(") and value.endswith(")") and _balanced_parentheses(value[1:-1]):
        value = value[1:-1].strip()
    return value


def _balanced_parentheses(value: str) -> bool:
    depth = 0
    for char in value:
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth < 0:
                return False
    return depth == 0


def _extract_call_qualifier(node: Node, source: bytes) -> str | None:
    name = node.child_by_field_name("name")
    if name is None:
        return None
    prefix = source[node.start_byte : name.start_byte].decode("utf-8", errors="replace")
    return prefix.rstrip(".").strip() or None


def _argument_node(node: Node) -> Node | None:
    args = node.child_by_field_name("arguments")
    if args is not None:
        return args
    for child in node.children:
        if child.type == "argument_list":
            return child
    return None


def _argument_count(node: Node) -> int:
    args = _argument_node(node)
    if args is None:
        return 0
    return sum(1 for child in args.children if child.type not in {"(", ")", ","})


def _argument_texts(node: Node, source: bytes) -> tuple[str, ...]:
    args = _argument_node(node)
    if args is None:
        return ()
    return tuple(
        _node_text(child, source)
        for child in args.children
        if child.type not in {"(", ")", ","}
    )


def _object_creation_type(node: Node, source: bytes) -> str | None:
    typed = node.child_by_field_name("type")
    if typed is not None:
        return _node_text(typed, source)
    for child in node.children:
        if child.type in {"new", "argument_list", "class_body"}:
            continue
        return _node_text(child, source)
    return None


def _extract_components(types: list[JavaType]) -> list[JavaComponent]:
    # 组件识别基于注解名，因此全限定注解和普通导入后的短注解可以用同一套逻辑处理。
    components: list[JavaComponent] = []
    for item in types:
        for annotation in item.annotation_details:
            if annotation.name not in COMPONENT_ANNOTATIONS:
                continue
            components.append(
                JavaComponent(
                    kind=COMPONENT_ANNOTATIONS[annotation.name],
                    name=item.name,
                    annotation=annotation.raw,
                    span=item.span,
                )
            )
    return components


def _extract_endpoints(types: list[JavaType], methods: list[JavaMethod]) -> list[JavaEndpoint]:
    base_paths_by_type: dict[str, tuple[str, ...]] = {}
    # 类级 mapping 提供基础路径；方法级 mapping 提供最终路由片段和 HTTP 动词。
    for item in types:
        route_annotations = [
            annotation
            for annotation in item.annotation_details
            if annotation.name in ROUTE_ANNOTATIONS
        ]
        paths = tuple(path for annotation in route_annotations for path in _annotation_paths(annotation))
        if paths:
            base_paths_by_type[item.name] = paths

    endpoints: list[JavaEndpoint] = []
    for item in methods:
        if not item.enclosing_type:
            continue
        base_paths = base_paths_by_type.get(item.enclosing_type, ("",))
        for annotation in item.annotation_details:
            if annotation.name not in ROUTE_ANNOTATIONS:
                continue
            endpoint_methods = _http_methods(annotation)
            endpoint_paths = _annotation_paths(annotation)
            for base_path in base_paths:
                for endpoint_path in endpoint_paths:
                    endpoints.append(
                        JavaEndpoint(
                            path=_join_paths(base_path, endpoint_path),
                            http_methods=endpoint_methods,
                            annotation=annotation.raw,
                            span=item.span,
                            enclosing_type=item.enclosing_type,
                            method_name=item.name,
                            parameters=_endpoint_parameters(item),
                        )
                    )
    return endpoints


def _extract_sql_references(methods: list[JavaMethod]) -> list[JavaSqlReference]:
    # 注解里的 SQL 单独作为检索表面，因为它常包含方法名里看不到的业务词。
    references: list[JavaSqlReference] = []
    for item in methods:
        for annotation in item.annotation_details:
            if annotation.name not in SQL_ANNOTATIONS:
                continue
            references.append(
                JavaSqlReference(
                    operation=SQL_ANNOTATIONS[annotation.name],
                    statement=" ".join(_annotation_strings(annotation)) or annotation.raw,
                    annotation=annotation.raw,
                    span=item.span,
                    enclosing_type=item.enclosing_type,
                    method_name=item.name,
                )
            )
    return references


def _endpoint_parameters(method: JavaMethod) -> list[JavaEndpointParameter]:
    parameters: list[JavaEndpointParameter] = []
    for parameter in method.parameters:
        source = "unknown"
        alias: str | None = None
        required: bool | None = None
        default_value: str | None = None
        raw_annotation: str | None = None
        for annotation in parameter.annotation_details:
            if annotation.name not in PARAMETER_SOURCE_ANNOTATIONS:
                continue
            source = PARAMETER_SOURCE_ANNOTATIONS[annotation.name]
            alias = _first_annotation_argument(annotation, "value", "name", "path")
            required = _annotation_bool_argument(annotation, "required")
            default_value = _first_annotation_argument(annotation, "defaultValue")
            raw_annotation = annotation.raw
            break
        parameters.append(
            JavaEndpointParameter(
                name=parameter.name,
                type=parameter.type,
                source=source,
                alias=alias,
                required=required,
                default_value=default_value,
                annotation=raw_annotation,
            )
        )
    return parameters


def _annotation_name(annotation: str) -> str | None:
    match = ANNOTATION_NAME_PATTERN.search(annotation)
    if not match:
        return None
    return match.group(1)


def _annotation_paths(annotation: JavaAnnotation) -> tuple[str, ...]:
    values = tuple(
        value
        for key in ("value", "path")
        for value in annotation.arguments.get(key, ())
    )
    return values or ("",)


def _annotation_strings(annotation: JavaAnnotation | str) -> tuple[str, ...]:
    if isinstance(annotation, str):
        return tuple(match.group(1) for match in STRING_LITERAL_PATTERN.finditer(annotation))
    return tuple(
        value
        for values in annotation.arguments.values()
        for value in values
        if not _looks_like_non_string_annotation_value(value)
    )


def _http_methods(annotation: JavaAnnotation) -> tuple[str, ...]:
    mapped = ROUTE_ANNOTATIONS.get(annotation.name)
    if mapped:
        return mapped
    # 裸 @RequestMapping 没有固定 HTTP 动词，除非显式出现 RequestMethod.*。
    method_values = annotation.arguments.get("method", ())
    methods = tuple(
        match.group(1)
        for value in method_values
        for match in [REQUEST_METHOD_PATTERN.search(value)]
        if match
    )
    return methods or ("ANY",)


def _first_annotation_argument(annotation: JavaAnnotation, *keys: str) -> str | None:
    for key in keys:
        values = annotation.arguments.get(key, ())
        if values:
            return values[0]
    return None


def _annotation_bool_argument(annotation: JavaAnnotation, key: str) -> bool | None:
    value = _first_annotation_argument(annotation, key)
    if value is None:
        return None
    if value == "true":
        return True
    if value == "false":
        return False
    return None


def _looks_like_non_string_annotation_value(value: str) -> bool:
    return (
        value in {"true", "false"}
        or value.endswith(".class")
        or value.startswith("RequestMethod.")
    )


def _join_paths(base_path: str, endpoint_path: str) -> str:
    parts = [part.strip("/") for part in (base_path, endpoint_path) if part.strip("/")]
    if not parts:
        return "/"
    return "/" + "/".join(parts)


def _extract_metrics(
    root: Node,
    source: bytes,
    imports: list[JavaImport],
    types: list[JavaType],
    fields: list[JavaField],
    methods: list[JavaMethod],
    calls: list[JavaCall],
    local_variables: list[JavaLocalVariable],
    returns: list[JavaReturn],
    control_structures: list[JavaControlStructure],
    syntax_errors: list[SourceSpan],
    components: list[JavaComponent],
    endpoints: list[JavaEndpoint],
    sql_references: list[JavaSqlReference],
) -> JavaMetrics:
    # 节点类型计数用于诊断，也方便后续检查解析器覆盖面。
    node_type_counts: dict[str, int] = {}
    node_count = 0
    for node in _walk(root):
        node_count += 1
        node_type_counts[node.type] = node_type_counts.get(node.type, 0) + 1

    return JavaMetrics(
        line_count=source.count(b"\n") + (0 if source.endswith(b"\n") else 1),
        import_count=len(imports),
        type_count=len(types),
        field_count=len(fields),
        method_count=len(methods),
        call_count=len(calls),
        syntax_error_count=len(syntax_errors),
        max_type_nesting=_max_type_nesting(root),
        node_count=node_count,
        node_type_counts=dict(sorted(node_type_counts.items())),
        component_count=len(components),
        endpoint_count=len(endpoints),
        sql_reference_count=len(sql_references),
        local_variable_count=len(local_variables),
        return_count=len(returns),
        control_structure_count=len(control_structures),
    )


def _max_type_nesting(root: Node) -> int:
    def visit(node: Node, depth: int) -> int:
        next_depth = depth + 1 if node.type in TYPE_NODES else depth
        return max([next_depth, *(visit(child, next_depth) for child in node.children)])

    return visit(root, 0)


def _signature_text(node: Node, source: bytes) -> str:
    name = _child_text(node, source, "name")
    parameters = _child_text(node, source, "parameters") or "()"
    return_type = _declared_type(node, source)
    if return_type:
        return f"{return_type} {name}{parameters}"
    return f"{name}{parameters}"


def _append_tree_lines(node: Node, source: bytes, lines: list[str], depth: int) -> None:
    name = ""
    name_node = node.child_by_field_name("name")
    if name_node is not None:
        name = f" name={_node_text(name_node, source)!r}"
    lines.append(
        f"{'  ' * depth}{node.type}{name} "
        f"[{node.start_point[0] + 1}:{node.start_point[1] + 1}-"
        f"{node.end_point[0] + 1}:{node.end_point[1] + 1}]"
    )
    for child in node.children:
        _append_tree_lines(child, source, lines, depth + 1)
