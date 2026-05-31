from __future__ import annotations

from typing import Any

from flask import Request

from web.backend.errors import APIError


def json_payload(request: Request) -> dict[str, Any]:
    payload = request.get_json(silent=True) or {}
    if not isinstance(payload, dict):
        raise APIError("request body must be a JSON object.", 400)
    return payload


def source_value(payload: dict[str, Any]) -> str:
    value = str(payload.get("source", "code"))
    if value not in {"code", "kb", "mixed"}:
        raise APIError("source must be one of: code, kb, mixed.", 400)
    return value
