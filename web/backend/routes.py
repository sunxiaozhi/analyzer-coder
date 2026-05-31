from __future__ import annotations

from typing import Any, cast

from flask import Blueprint, current_app, jsonify, request

from web.backend.services import AnalyzerService
from web.backend.validation import json_payload

api_bp = Blueprint("api", __name__)


def analyzer_service() -> AnalyzerService:
    return cast(AnalyzerService, current_app.extensions["analyzer_service"])


@api_bp.get("/health")
def health() -> Any:
    return jsonify(analyzer_service().health())


@api_bp.get("/projects")
def list_projects() -> Any:
    return jsonify(analyzer_service().list_projects())


@api_bp.post("/projects")
def create_project() -> Any:
    return jsonify(analyzer_service().create_project(json_payload(request))), 201


@api_bp.post("/projects/<project_id>/pull")
def pull_project(project_id: str) -> Any:
    return jsonify(analyzer_service().pull_project(project_id))


@api_bp.post("/analyze")
def analyze() -> Any:
    return jsonify(analyzer_service().analyze(json_payload(request)))


@api_bp.post("/index")
def index_project() -> Any:
    return jsonify(analyzer_service().index_project(json_payload(request)))


@api_bp.post("/query")
def query() -> Any:
    return jsonify(analyzer_service().query(json_payload(request)))
