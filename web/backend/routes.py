from __future__ import annotations

from typing import Any, cast

from flask import Blueprint, current_app, jsonify, request, session

from web.backend.services import AnalyzerService, UserRecord
from web.backend.validation import json_payload

api_bp = Blueprint("api", __name__)


def analyzer_service() -> AnalyzerService:
    return cast(AnalyzerService, current_app.extensions["analyzer_service"])


def current_user() -> UserRecord:
    return analyzer_service().require_user(session.get("user_id"))


@api_bp.get("/health")
def health() -> Any:
    return jsonify(analyzer_service().health())


@api_bp.post("/auth/login")
def login() -> Any:
    payload = analyzer_service().authenticate(json_payload(request))
    session["user_id"] = payload["user"]["id"]
    return jsonify(payload)


@api_bp.post("/auth/logout")
def logout() -> Any:
    session.clear()
    return jsonify({"ok": True})


@api_bp.get("/auth/me")
def me() -> Any:
    return jsonify(analyzer_service().current_user(session.get("user_id")))


@api_bp.put("/auth/last-project")
def update_last_project() -> Any:
    return jsonify(analyzer_service().update_last_project(json_payload(request), current_user()))


@api_bp.get("/users")
def list_users() -> Any:
    return jsonify(analyzer_service().list_users(current_user()))


@api_bp.post("/users")
def create_user() -> Any:
    return jsonify(analyzer_service().create_user(json_payload(request), current_user())), 201


@api_bp.put("/users")
def update_user() -> Any:
    payload = json_payload(request)
    return jsonify(analyzer_service().update_user(str(payload.get("id", "")).strip(), payload, current_user()))


@api_bp.put("/users/password")
def update_user_password() -> Any:
    payload = json_payload(request)
    return jsonify(analyzer_service().update_user_password(str(payload.get("id", "")).strip(), payload, current_user()))


@api_bp.put("/users/access")
def update_user_access() -> Any:
    payload = json_payload(request)
    return jsonify(analyzer_service().update_user_access(str(payload.get("id", "")).strip(), payload, current_user()))


@api_bp.get("/projects")
def list_projects() -> Any:
    return jsonify(analyzer_service().list_projects(current_user()))


@api_bp.post("/projects")
def create_project() -> Any:
    return jsonify(analyzer_service().create_project(json_payload(request), current_user())), 201


@api_bp.post("/projects/<project_id>/pull")
def pull_project(project_id: str) -> Any:
    return jsonify(analyzer_service().pull_project(project_id, current_user()))


@api_bp.post("/analyze")
def analyze() -> Any:
    return jsonify(analyzer_service().analyze(json_payload(request), current_user()))


@api_bp.post("/index")
def index_project() -> Any:
    return jsonify(analyzer_service().index_project(json_payload(request), current_user()))


@api_bp.post("/query")
def query() -> Any:
    return jsonify(analyzer_service().query(json_payload(request), current_user()))


@api_bp.get("/kb/files")
def list_kb_files() -> Any:
    return jsonify(analyzer_service().list_kb_files(request.args.to_dict(), current_user()))


@api_bp.get("/kb/file")
def get_kb_file() -> Any:
    return jsonify(analyzer_service().get_kb_file(request.args.to_dict(), current_user()))


@api_bp.post("/kb/files")
def create_kb_file() -> Any:
    return jsonify(analyzer_service().save_kb_file(json_payload(request), current_user(), create=True)), 201


@api_bp.put("/kb/file")
def save_kb_file() -> Any:
    return jsonify(analyzer_service().save_kb_file(json_payload(request), current_user()))


@api_bp.delete("/kb/file")
def delete_kb_file() -> Any:
    return jsonify(analyzer_service().delete_kb_file(json_payload(request), current_user()))
