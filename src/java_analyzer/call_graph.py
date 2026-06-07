from __future__ import annotations

from dataclasses import asdict, dataclass

from java_analyzer.models import JavaCall, JavaFileAnalysis, JavaMethod


@dataclass(frozen=True)
class MethodRef:
    type_name: str
    method_name: str
    file_path: str
    line: int
    signature: str

    @property
    def qualified_name(self) -> str:
        return f"{self.type_name}.{self.method_name}"


@dataclass(frozen=True)
class CallEdge:
    caller: MethodRef
    callee: MethodRef | None
    call_name: str
    qualifier: str
    line: int
    resolution: str


@dataclass(frozen=True)
class CallChain:
    entrypoint: MethodRef
    edges: list[CallEdge]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def build_call_edges(results: list[JavaFileAnalysis]) -> list[CallEdge]:
    methods = _method_refs(results)
    methods_by_type_name = {
        (method.enclosing_type or "", method.name): ref
        for result in results
        for method in result.methods
        if method.enclosing_type
        for ref in [methods[(method.enclosing_type, method.name, method.span.start_line)]]
    }
    methods_by_name: dict[str, list[MethodRef]] = {}
    for ref in methods.values():
        methods_by_name.setdefault(ref.method_name, []).append(ref)

    fields = {
        (field.enclosing_type or "", field.name): field.type or ""
        for result in results
        for field in result.fields
        if field.enclosing_type
    }
    parameters = {
        (method.enclosing_type or "", method.name, parameter.name): parameter.type or ""
        for result in results
        for method in result.methods
        if method.enclosing_type
        for parameter in method.parameters
    }
    type_names = {ref.type_name for ref in methods.values()}

    edges: list[CallEdge] = []
    for result in results:
        for call in result.calls:
            caller = _caller_ref(call, methods)
            if caller is None:
                continue
            callee, resolution = _resolve_call(
                call=call,
                caller=caller,
                methods_by_type_name=methods_by_type_name,
                methods_by_name=methods_by_name,
                fields=fields,
                parameters=parameters,
                type_names=type_names,
            )
            edges.append(
                CallEdge(
                    caller=caller,
                    callee=callee,
                    call_name=call.name,
                    qualifier=call.qualifier or "",
                    line=call.span.start_line,
                    resolution=resolution,
                )
            )
    return edges


def build_call_chains(
    results: list[JavaFileAnalysis],
    max_depth: int = 4,
    max_chains: int = 80,
) -> list[CallChain]:
    edges = [edge for edge in build_call_edges(results) if edge.callee is not None]
    outgoing: dict[str, list[CallEdge]] = {}
    for edge in edges:
        outgoing.setdefault(edge.caller.qualified_name, []).append(edge)

    entrypoints = _entrypoints(results)
    if not entrypoints:
        entrypoints = sorted({edge.caller for edge in edges}, key=lambda item: item.qualified_name)

    chains: list[CallChain] = []
    for entrypoint in entrypoints:
        _walk_chains(
            entrypoint=entrypoint,
            current=entrypoint,
            outgoing=outgoing,
            path=[],
            seen={entrypoint.qualified_name},
            chains=chains,
            max_depth=max_depth,
            max_chains=max_chains,
        )
        if len(chains) >= max_chains:
            break
    return chains


def _method_refs(results: list[JavaFileAnalysis]) -> dict[tuple[str, str, int], MethodRef]:
    refs: dict[tuple[str, str, int], MethodRef] = {}
    for result in results:
        for method in result.methods:
            if not method.enclosing_type:
                continue
            refs[(method.enclosing_type, method.name, method.span.start_line)] = MethodRef(
                type_name=method.enclosing_type,
                method_name=method.name,
                file_path=result.file_path or "",
                line=method.span.start_line,
                signature=method.signature or method.name,
            )
    return refs


def _caller_ref(call: JavaCall, methods: dict[tuple[str, str, int], MethodRef]) -> MethodRef | None:
    if not call.enclosing_type or not call.enclosing_method:
        return None
    candidates = [
        ref
        for (type_name, method_name, _line), ref in methods.items()
        if type_name == call.enclosing_type and method_name == call.enclosing_method
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda ref: abs(ref.line - call.span.start_line))


def _resolve_call(
    call: JavaCall,
    caller: MethodRef,
    methods_by_type_name: dict[tuple[str, str], MethodRef],
    methods_by_name: dict[str, list[MethodRef]],
    fields: dict[tuple[str, str], str],
    parameters: dict[tuple[str, str, str], str],
    type_names: set[str],
) -> tuple[MethodRef | None, str]:
    qualifier = _simple_qualifier(call.qualifier or "")
    if not qualifier:
        local = methods_by_type_name.get((caller.type_name, call.name))
        if local:
            return local, "same_type"
        candidates = methods_by_name.get(call.name, [])
        if len(candidates) == 1:
            return candidates[0], "unique_method"
        return None, "unresolved"

    if qualifier in {"this", "super"}:
        local = methods_by_type_name.get((caller.type_name, call.name))
        return (local, "same_type") if local else (None, "unresolved")

    if qualifier in type_names:
        target = methods_by_type_name.get((qualifier, call.name))
        return (target, "static_type") if target else (None, "unresolved")

    field_type = fields.get((caller.type_name, qualifier))
    if field_type:
        target = methods_by_type_name.get((field_type, call.name))
        return (target, "field_type") if target else (None, "unresolved")

    parameter_type = parameters.get((caller.type_name, caller.method_name, qualifier))
    if parameter_type:
        target = methods_by_type_name.get((parameter_type, call.name))
        return (target, "parameter_type") if target else (None, "unresolved")

    return None, "unresolved"


def _entrypoints(results: list[JavaFileAnalysis]) -> list[MethodRef]:
    refs = _method_refs(results)
    entrypoints: list[MethodRef] = []
    for result in results:
        for endpoint in result.endpoints:
            candidates = [
                ref
                for (type_name, method_name, _line), ref in refs.items()
                if type_name == endpoint.enclosing_type and method_name == endpoint.method_name
            ]
            if candidates:
                entrypoints.append(min(candidates, key=lambda ref: ref.line))
    return sorted(entrypoints, key=lambda item: item.qualified_name)


def _walk_chains(
    entrypoint: MethodRef,
    current: MethodRef,
    outgoing: dict[str, list[CallEdge]],
    path: list[CallEdge],
    seen: set[str],
    chains: list[CallChain],
    max_depth: int,
    max_chains: int,
) -> None:
    if len(chains) >= max_chains:
        return
    next_edges = outgoing.get(current.qualified_name, [])
    if not next_edges or len(path) >= max_depth:
        if path:
            chains.append(CallChain(entrypoint=entrypoint, edges=list(path)))
        return
    for edge in next_edges:
        if edge.callee is None:
            continue
        callee_name = edge.callee.qualified_name
        if callee_name in seen:
            chains.append(CallChain(entrypoint=entrypoint, edges=[*path, edge]))
            continue
        _walk_chains(
            entrypoint=entrypoint,
            current=edge.callee,
            outgoing=outgoing,
            path=[*path, edge],
            seen={*seen, callee_name},
            chains=chains,
            max_depth=max_depth,
            max_chains=max_chains,
        )


def _simple_qualifier(value: str) -> str:
    value = value.strip().rstrip(".")
    if not value:
        return ""
    return value.split(".")[-1]
