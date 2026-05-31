from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from flask import Flask, jsonify


@dataclass
class APIError(Exception):
    message: str
    status_code: int = 400


def error_response(message: str, status_code: int) -> tuple[Any, int]:
    return jsonify({"error": message}), status_code


def register_error_handlers(app: Flask) -> None:
    @app.errorhandler(APIError)
    def api_error(error: APIError) -> tuple[Any, int]:
        return error_response(error.message, error.status_code)

    @app.errorhandler(ValueError)
    def value_error(error: ValueError) -> tuple[Any, int]:
        return error_response(str(error), 400)
