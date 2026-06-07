from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
import re
from typing import Iterable
from urllib.parse import urlsplit

from java_analyzer.analyzer import JavaAnalyzer

FRONTEND_EXTENSIONS = {".js", ".jsx", ".ts", ".tsx", ".vue"}
SKIP_DIRS = {"node_modules", "dist", "build", ".git", ".vite", "coverage"}

CALL_PATTERN = re.compile(
    r"(?P<callee>[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*(?:<[^;\n(){}]+>)?\s*\("
)
FUNCTION_PATTERN = re.compile(r"(?:async\s+)?function\s+(?P<name>[A-Za-z_$][\w$]*)\s*(?:<[^>{}]+>)?\s*\(")
METHOD_PATTERN = re.compile(r"\bmethod\s*:\s*['\"](?P<method>[A-Za-z]+)['\"]")
API_PREFIX = "/api"


@dataclass(frozen=True)
class FrontendApiCall:
    method: str
    path: str
    normalized_path: str
    file_path: str
    line: int
    callee: str
    expression: str


@dataclass(frozen=True)
class BackendApiEndpoint:
    methods: tuple[str, ...]
    path: str
    normalized_path: str
    file_path: str
    line: int
    controller: str
    handler: str


@dataclass(frozen=True)
class ApiMapping:
    status: str
    confidence: str
    frontend: FrontendApiCall
    backend: BackendApiEndpoint | None
    reason: str
    match_strategy: str


@dataclass(frozen=True)
class ApiMappingResult:
    frontend_calls: list[FrontendApiCall]
    backend_endpoints: list[BackendApiEndpoint]
    mappings: list[ApiMapping]
    summary: dict[str, int]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def build_api_mapping(
    root: str | Path,
    frontend_path: str | Path = ".",
    backend_path: str | Path = ".",
    analyzer: JavaAnalyzer | None = None,
) -> ApiMappingResult:
    project_root = Path(root)
    frontend_root = project_root / frontend_path
    backend_root = project_root / backend_path
    frontend_calls = scan_frontend_api_calls(frontend_root, project_root)
    backend_endpoints = scan_backend_endpoints(backend_root, project_root, analyzer or JavaAnalyzer())
    mappings = map_api_calls(frontend_calls, backend_endpoints)
    summary = {
        "frontendCalls": len(frontend_calls),
        "backendEndpoints": len(backend_endpoints),
        "matched": sum(1 for item in mappings if item.status == "matched"),
        "methodMismatches": sum(1 for item in mappings if item.status == "method_mismatch"),
        "unmatched": sum(1 for item in mappings if item.status == "unmatched"),
    }
    return ApiMappingResult(
        frontend_calls=frontend_calls,
        backend_endpoints=backend_endpoints,
        mappings=mappings,
        summary=summary,
    )


def scan_frontend_api_calls(path: str | Path, project_root: str | Path | None = None) -> list[FrontendApiCall]:
    target = Path(path)
    root = Path(project_root) if project_root is not None else target
    calls: list[FrontendApiCall] = []
    for file_path in _iter_frontend_files(target):
        text = file_path.read_text(encoding="utf-8", errors="replace")
        helper_methods = _helper_methods(text)
        for match in CALL_PATTERN.finditer(text):
            parsed = _parse_call(text, match)
            if parsed is None:
                continue
            url, expression = parsed
            path_value = _api_path(url)
            if path_value is None:
                continue
            method = _infer_method(match.group("callee"), expression, helper_methods)
            calls.append(
                FrontendApiCall(
                    method=method,
                    path=path_value,
                    normalized_path=_normalize_path(path_value),
                    file_path=_relative_path(file_path, root),
                    line=_line_number(text, match.start()),
                    callee=match.group("callee"),
                    expression=_compact_expression(expression),
                )
            )
    return calls


def scan_backend_endpoints(
    path: str | Path,
    project_root: str | Path | None = None,
    analyzer: JavaAnalyzer | None = None,
) -> list[BackendApiEndpoint]:
    target = Path(path)
    root = Path(project_root) if project_root is not None else target
    java_analyzer = analyzer or JavaAnalyzer()
    endpoints: list[BackendApiEndpoint] = []
    for result in java_analyzer.analyze_path(target):
        for endpoint in result.endpoints:
            methods = tuple(method.upper() for method in endpoint.http_methods) or ("ANY",)
            endpoints.append(
                BackendApiEndpoint(
                    methods=methods,
                    path=endpoint.path,
                    normalized_path=_normalize_path(endpoint.path),
                    file_path=_relative_path(Path(result.file_path or ""), root),
                    line=endpoint.span.start_line,
                    controller=endpoint.enclosing_type,
                    handler=endpoint.method_name,
                )
            )
    return endpoints


def map_api_calls(
    frontend_calls: list[FrontendApiCall],
    backend_endpoints: list[BackendApiEndpoint],
) -> list[ApiMapping]:
    mappings: list[ApiMapping] = []
    for call in frontend_calls:
        path_matches = _path_matches(call, backend_endpoints)
        method_matches = [
            (endpoint, strategy)
            for endpoint, strategy in path_matches
            if _method_matches(call.method, endpoint.methods)
        ]
        if method_matches:
            endpoint, strategy = method_matches[0]
            confidence = "high" if call.method != "UNKNOWN" else "medium"
            reason = "path and HTTP method match" if call.method != "UNKNOWN" else "path matches; frontend method is inferred as unknown"
            mappings.append(ApiMapping("matched", confidence, call, endpoint, reason, strategy))
            continue
        if path_matches:
            endpoint, strategy = path_matches[0]
            mappings.append(
                ApiMapping(
                    "method_mismatch",
                    "medium",
                    call,
                    endpoint,
                    "path matches but HTTP method differs",
                    strategy,
                )
            )
            continue
        mappings.append(ApiMapping("unmatched", "low", call, None, "no backend route path matched", "none"))
    return mappings


def _iter_frontend_files(path: Path) -> Iterable[Path]:
    if path.is_file():
        if path.suffix.lower() in FRONTEND_EXTENSIONS:
            yield path
        return
    for file_path in sorted(path.rglob("*")):
        if not file_path.is_file() or file_path.suffix.lower() not in FRONTEND_EXTENSIONS:
            continue
        if any(part in SKIP_DIRS for part in file_path.parts):
            continue
        yield file_path


def _helper_methods(text: str) -> dict[str, str]:
    helpers: dict[str, str] = {}
    for match in FUNCTION_PATTERN.finditer(text):
        body_start = text.find("{", match.end())
        if body_start == -1:
            continue
        body_end = _matching_brace(text, body_start)
        body = text[body_start : body_end + 1] if body_end != -1 else text[body_start : body_start + 900]
        if "fetch(" not in body:
            continue
        method_match = METHOD_PATTERN.search(body)
        helpers[match.group("name")] = method_match.group("method").upper() if method_match else "GET"
    return helpers


def _parse_call(text: str, match: re.Match[str]) -> tuple[str, str] | None:
    open_index = text.rfind("(", 0, match.end())
    if open_index == -1:
        return None
    index = _skip_whitespace(text, open_index + 1)
    if index >= len(text) or text[index] not in {"'", '"', "`"}:
        return None
    parsed = _read_quoted(text, index)
    if parsed is None:
        return None
    url, url_end = parsed
    close_index = _matching_paren(text, open_index)
    if close_index == -1:
        close_index = min(len(text), url_end + 300)
    return url, text[match.start() : close_index + 1]


def _read_quoted(text: str, start: int) -> tuple[str, int] | None:
    quote = text[start]
    index = start + 1
    value: list[str] = []
    while index < len(text):
        char = text[index]
        if char == "\\":
            value.append(char)
            if index + 1 < len(text):
                value.append(text[index + 1])
            index += 2
            continue
        if char == quote:
            return "".join(value), index + 1
        value.append(char)
        index += 1
    return None


def _api_path(value: str) -> str | None:
    cleaned = value.strip()
    if not cleaned:
        return None
    if cleaned.startswith(("http://", "https://")):
        parsed = urlsplit(cleaned)
        cleaned = parsed.path
        if parsed.query:
            cleaned = f"{cleaned}?{parsed.query}"
    if cleaned.startswith(API_PREFIX):
        return cleaned
    if cleaned.startswith(f".{API_PREFIX}"):
        return cleaned[1:]
    return None


def _infer_method(callee: str, expression: str, helper_methods: dict[str, str]) -> str:
    method_match = METHOD_PATTERN.search(expression)
    if method_match:
        return method_match.group("method").upper()
    callee_tail = callee.split(".")[-1]
    if callee_tail in helper_methods:
        return helper_methods[callee_tail]
    lower_tail = callee_tail.lower()
    for method in ("get", "post", "put", "delete", "patch"):
        if lower_tail == method or lower_tail.endswith(method):
            return method.upper()
    if lower_tail == "fetch":
        return "GET"
    return "UNKNOWN"


def _path_matches(
    call: FrontendApiCall,
    endpoints: list[BackendApiEndpoint],
) -> list[tuple[BackendApiEndpoint, str]]:
    matches: list[tuple[BackendApiEndpoint, str, int]] = []
    call_variants = _path_variants(call.normalized_path)
    for endpoint in endpoints:
        endpoint_variants = _path_variants(endpoint.normalized_path)
        for call_path, call_strategy in call_variants:
            for endpoint_path, endpoint_strategy in endpoint_variants:
                score = _route_score(call_path, endpoint_path)
                if score < 0:
                    continue
                strategy = "direct" if call_strategy == endpoint_strategy == "direct" else "api_prefix_stripped"
                matches.append((endpoint, strategy, score))
                break
            else:
                continue
            break
    matches.sort(key=lambda item: item[2], reverse=True)
    return [(endpoint, strategy) for endpoint, strategy, _score in matches]


def _method_matches(frontend_method: str, backend_methods: tuple[str, ...]) -> bool:
    if frontend_method == "UNKNOWN":
        return True
    return "ANY" in backend_methods or frontend_method in backend_methods


def _path_variants(path: str) -> list[tuple[str, str]]:
    variants = [(path, "direct")]
    if path == API_PREFIX:
        variants.append(("/", "api_prefix_stripped"))
    elif path.startswith(f"{API_PREFIX}/"):
        variants.append((path[len(API_PREFIX) :] or "/", "api_prefix_stripped"))
    return variants


def _route_score(left: str, right: str) -> int:
    left_parts = _segments(left)
    right_parts = _segments(right)
    if len(left_parts) != len(right_parts):
        return -1
    score = 0
    for left_part, right_part in zip(left_parts, right_parts):
        if left_part == right_part:
            score += 2
            continue
        if _is_variable_segment(left_part) or _is_variable_segment(right_part):
            score += 1
            continue
        return -1
    return score


def _normalize_path(path: str) -> str:
    raw = path.split("?", 1)[0].strip()
    raw = re.sub(r"\$\{[^}]+\}", "{param}", raw)
    raw = re.sub(r"<([^>/]+)>", r"{\1}", raw)
    raw = re.sub(r":([A-Za-z_][\w-]*)", r"{\1}", raw)
    if not raw.startswith("/"):
        raw = "/" + raw
    raw = re.sub(r"/+", "/", raw)
    return raw.rstrip("/") or "/"


def _segments(path: str) -> list[str]:
    normalized = _normalize_path(path)
    if normalized == "/":
        return []
    return [part for part in normalized.strip("/").split("/") if part]


def _is_variable_segment(value: str) -> bool:
    return (value.startswith("{") and value.endswith("}")) or (value.startswith("<") and value.endswith(">"))


def _matching_paren(text: str, open_index: int) -> int:
    return _matching_pair(text, open_index, "(", ")")


def _matching_brace(text: str, open_index: int) -> int:
    return _matching_pair(text, open_index, "{", "}")


def _matching_pair(text: str, open_index: int, opener: str, closer: str) -> int:
    depth = 0
    quote = ""
    index = open_index
    while index < len(text):
        char = text[index]
        if quote:
            if char == "\\":
                index += 2
                continue
            if char == quote:
                quote = ""
            index += 1
            continue
        if char in {"'", '"', "`"}:
            quote = char
            index += 1
            continue
        if char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return -1


def _skip_whitespace(text: str, index: int) -> int:
    while index < len(text) and text[index].isspace():
        index += 1
    return index


def _line_number(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def _relative_path(path: Path, root: Path) -> str:
    try:
        return str(path.resolve().relative_to(root.resolve())).replace("\\", "/")
    except ValueError:
        return str(path).replace("\\", "/")


def _compact_expression(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()
