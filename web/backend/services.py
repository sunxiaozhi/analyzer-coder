from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from flask.config import Config
from werkzeug.security import check_password_hash, generate_password_hash

from java_ts_analyzer.analyzer import JavaAnalyzer
from java_ts_analyzer.chunker import build_chunks
from java_ts_analyzer.embedding import HashingEmbedder
from java_ts_analyzer.kb_loader import build_kb_chunks
from java_ts_analyzer.vector_store import JsonlVectorStore
from java_ts_analyzer.cli import (
    _build_graph,
    _build_report,
    _format_summary,
    _save_result,
)
from web.backend.errors import APIError
from web.backend.fusion import build_evidence, serialize_result
from web.backend.mysql_state import MySqlStateStore
from web.backend.paths import relative_to_workspace, workspace_path
from web.backend.validation import source_value

KB_EXTENSIONS = {".adoc", ".markdown", ".md", ".rst", ".txt"}

DEFAULT_KB_TEMPLATES = [
    {
        "id": "domain",
        "name": "业务规则",
        "path": "domain/user-registration.md",
        "content": """# 用户注册

## 业务规则
- 手机号必须唯一

## 相关接口
- POST /api/users/register

## 相关代码
- UserController.register
- UserService.createUser

## 边界条件
- 手机号为空时拒绝
- 手机号已存在时返回业务错误
""",
    },
    {
        "id": "api",
        "name": "接口说明",
        "path": "api/user-api.md",
        "content": """# 用户接口

## 接口
- POST /api/users/register

## 请求
- phone: 用户手机号

## 响应
- 注册成功返回用户信息
- 手机号重复返回业务错误
""",
    },
    {
        "id": "decision",
        "name": "决策记录",
        "path": "decisions/phone-unique-rule.md",
        "content": """# 手机号唯一规则

## 决策
注册以手机号作为唯一身份标识。

## 原因
- 降低重复账户风险
- 便于后续登录和通知流程

## 影响范围
- 用户注册
- 用户资料更新
""",
    },
    {
        "id": "troubleshooting",
        "name": "故障排查",
        "path": "troubleshooting/registration-errors.md",
        "content": """# 注册错误排查

## 现象
用户注册失败。

## 排查
- 检查手机号是否已存在
- 检查注册接口是否返回业务错误
- 检查数据库唯一约束
""",
    },
]


class AnalyzerService:
    def __init__(
        self,
        workspace_root: Path,
        default_store: Path,
        results_dir: Path,
        projects_dir: Path,
        admin_username: str = "admin",
        admin_password: str = "admin123",
        state_backend: str = "mysql",
        mysql_config: dict[str, Any] | None = None,
        analyzer: JavaAnalyzer | None = None,
        embedder: HashingEmbedder | None = None,
    ) -> None:
        self.workspace_root = workspace_root
        self.default_store = default_store
        self.results_dir = results_dir
        self.projects_dir = workspace_path(workspace_root, projects_dir)
        self.projects_file = self.projects_dir / "projects.json"
        self.users_file = self.projects_dir / "users.json"
        self.admin_username = admin_username
        self.admin_password = admin_password
        self.state_backend = state_backend
        self.mysql_store: MySqlStateStore | None = None
        self.analyzer = analyzer or JavaAnalyzer()
        self.embedder = embedder or HashingEmbedder()
        if self.state_backend == "mysql":
            config = mysql_config or {}
            self.mysql_store = MySqlStateStore(
                host=str(config.get("host", "127.0.0.1")),
                port=int(config.get("port", 3306)),
                user=str(config.get("user", "root")),
                password=str(config.get("password", "root")),
                database=str(config.get("database", "code_knowledge")),
            )
            self.mysql_store.initialize()
            self.mysql_store.migrate_from_json(self.users_file, self.projects_file)
        self._ensure_default_admin()

    @classmethod
    def from_config(cls, config: Config) -> "AnalyzerService":
        return cls(
            workspace_root=Path(config["WORKSPACE_ROOT"]),
            default_store=Path(config["DEFAULT_STORE"]),
            results_dir=Path(config["RESULTS_DIR"]),
            projects_dir=Path(config["PROJECTS_DIR"]),
            admin_username=str(config["ADMIN_USERNAME"]),
            admin_password=str(config["ADMIN_PASSWORD"]),
            state_backend=str(config.get("STATE_BACKEND", "mysql")),
            mysql_config={
                "host": config.get("MYSQL_HOST", "127.0.0.1"),
                "port": config.get("MYSQL_PORT", 3306),
                "user": config.get("MYSQL_USER", "root"),
                "password": config.get("MYSQL_PASSWORD", "root"),
                "database": config.get("MYSQL_DATABASE", "code_knowledge"),
            },
        )

    def health(self) -> dict[str, Any]:
        return {"ok": True, "workspace": str(self.workspace_root)}

    def authenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        username = str(payload.get("username", "")).strip()
        password = str(payload.get("password", ""))
        if not username or not password:
            raise APIError("username and password are required.", 400)
        for user in self._load_users():
            if user.username == username and check_password_hash(user.passwordHash, password):
                return {"user": self._public_user(user)}
        raise APIError("invalid username or password.", 401)

    def require_user(self, user_id: object) -> "UserRecord":
        value = str(user_id or "").strip()
        if not value:
            raise APIError("login required.", 401)
        return self._require_user(value)

    def current_user(self, user_id: object) -> dict[str, Any]:
        return {"user": self._public_user(self.require_user(user_id))}

    def list_users(self, actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        return {"users": [self._public_user(user) for user in self._load_users()]}

    def create_user(self, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        username = str(payload.get("username", "")).strip()
        password = str(payload.get("password", "")).strip()
        display_name = str(payload.get("displayName", "")).strip() or username
        project_ids = [str(item).strip() for item in payload.get("projectIds", []) if str(item).strip()]
        if not username:
            raise APIError("username is required.", 400)
        if len(password) < 6:
            raise APIError("password must be at least 6 characters.", 400)
        users = self._load_users()
        if any(user.username == username for user in users):
            raise APIError("username already exists.", 409)
        self._validate_project_ids(project_ids)
        now = utc_now()
        user = UserRecord(
            id=self._unique_user_id(username, users) if self.state_backend != "mysql" else "",
            username=username,
            displayName=display_name,
            passwordHash=generate_password_hash(password),
            isAdmin=bool(payload.get("isAdmin", False)),
            lastProjectId="",
            projectIds=project_ids,
            createdAt=now,
            updatedAt=now,
        )
        if self.mysql_store:
            user = UserRecord(**self.mysql_store.insert_user(asdict(user)))
        else:
            users.append(user)
            self._save_users(users)
        return {"user": self._public_user(user)}

    def update_user(self, user_id: str, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        if not user_id:
            raise APIError("user id is required.", 400)
        username = str(payload.get("username", "")).strip()
        display_name = str(payload.get("displayName", "")).strip() or username
        if not username:
            raise APIError("username is required.", 400)
        users = self._load_users()
        if any(user.id != user_id and user.username == username for user in users):
            raise APIError("username already exists.", 409)
        for user in users:
            if user.id == user_id:
                user.username = username
                user.displayName = display_name
                user.isAdmin = bool(payload.get("isAdmin", False))
                user.updatedAt = utc_now()
                self._ensure_admin_remains(users)
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def update_user_password(self, user_id: str, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        if not user_id:
            raise APIError("user id is required.", 400)
        password = str(payload.get("password", "")).strip()
        if len(password) < 6:
            raise APIError("password must be at least 6 characters.", 400)
        users = self._load_users()
        for user in users:
            if user.id == user_id:
                user.passwordHash = generate_password_hash(password)
                user.updatedAt = utc_now()
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def update_user_access(self, user_id: str, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        if not user_id:
            raise APIError("user id is required.", 400)
        project_ids = [str(item).strip() for item in payload.get("projectIds", []) if str(item).strip()]
        self._validate_project_ids(project_ids)
        users = self._load_users()
        for user in users:
            if user.id == user_id:
                user.projectIds = project_ids
                user.updatedAt = utc_now()
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def update_last_project(self, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        project_id = str(payload.get("projectId") or "").strip()
        if project_id:
            self._require_project(project_id, actor)
        users = self._load_users()
        for user in users:
            if user.id == actor.id:
                user.lastProjectId = project_id
                user.updatedAt = utc_now()
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def list_projects(self, user: "UserRecord") -> dict[str, Any]:
        return {"projects": [asdict(project) for project in self._projects_for_user(user)]}

    def create_project(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        name = str(payload.get("name", "")).strip()
        git_url = str(payload.get("gitUrl", "")).strip()
        branch = str(payload.get("branch", "")).strip() or None
        if not name:
            raise APIError("name is required.", 400)
        if not git_url:
            raise APIError("gitUrl is required.", 400)

        projects = self._load_projects()
        project_key = self._unique_project_id(name, projects)
        project_root = self.projects_dir / project_key
        repo_path = project_root / "repo"
        project_root.mkdir(parents=True, exist_ok=False)

        command = ["git", "clone", "--depth", "1"]
        if branch:
            command.extend(["--branch", branch])
        command.extend([git_url, str(repo_path)])
        try:
            self._run_git(command, cwd=self.workspace_root)
        except APIError:
            shutil.rmtree(project_root, ignore_errors=True)
            raise

        project = ProjectRecord(
            id=project_key if self.state_backend != "mysql" else "",
            name=name,
            gitUrl=git_url,
            branch=branch or "",
            path=self._relative(repo_path),
            createdAt=utc_now(),
            updatedAt=utc_now(),
            projectKey=project_key,
        )
        if self.mysql_store:
            project = ProjectRecord(**self.mysql_store.insert_project(asdict(project)))
        else:
            projects.append(project)
            self._save_projects(projects)
        self._grant_project_access(user.id, project.id)
        return {"project": asdict(project)}

    def pull_project(self, project_id: str, user: "UserRecord") -> dict[str, Any]:
        project = self._require_project(project_id, user)
        repo_path = self._project_repo_path(project)
        self._run_git(["git", "pull", "--ff-only"], cwd=repo_path)
        project.updatedAt = utc_now()
        projects = [project if item.id == project.id else item for item in self._load_projects()]
        self._save_projects(projects)
        return {"project": asdict(project)}

    def analyze(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        mode = str(payload.get("mode", "report"))
        context = self._analysis_context(payload, user)

        output, extension, normalized_mode = self._analysis_output(context, payload, mode)
        saved_path = self._save_latest_analysis_result(context.results_dir, normalized_mode, extension, output)
        return {
            "output": output,
            "savedPath": self._relative(saved_path),
            "mode": normalized_mode,
            "source": "code",
            "projectId": context.project_id,
        }

    def index_project(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        source = source_value(payload)
        context = self._analysis_context(payload, user)
        store_path = context.store_path(payload.get("store"))
        append = bool(payload.get("append", False))

        chunks = self._build_source_chunks(context, payload, source)
        store = JsonlVectorStore(store_path)
        records = store.upsert_chunks(chunks, embedder=self.embedder) if append else store.write_chunks(chunks, embedder=self.embedder)
        message = f"{'upserted' if append else 'indexed'} {len(records)} chunks into {self._relative(store_path)}"
        saved_path = _save_result(context.results_dir, "web-index", ".txt", message)
        return {
            "message": message,
            "count": len(records),
            "store": self._relative(store_path),
            "savedPath": self._relative(saved_path),
            "projectId": context.project_id,
        }

    def index_status(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        store_path = context.store_path(payload.get("store"))
        if not store_path.exists():
            return {
                "exists": False,
                "store": self._relative(store_path),
                "size": 0,
                "updatedAt": "",
                "total": 0,
                "sources": {},
                "kinds": {},
                "projectId": context.project_id,
            }

        records = JsonlVectorStore(store_path).read_records()
        sources: dict[str, int] = {}
        kinds: dict[str, int] = {}
        for record in records:
            source_type = str(record.metadata.get("source_type") or "unknown")
            kind = str(record.metadata.get("kind") or "unknown")
            sources[source_type] = sources.get(source_type, 0) + 1
            kinds[kind] = kinds.get(kind, 0) + 1
        return {
            "exists": True,
            "store": self._relative(store_path),
            "size": store_path.stat().st_size,
            "updatedAt": datetime.fromtimestamp(store_path.stat().st_mtime, timezone.utc).isoformat(),
            "total": len(records),
            "sources": sources,
            "kinds": kinds,
            "projectId": context.project_id,
        }

    def index_records(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        store_path = context.store_path(payload.get("store"))
        limit = max(1, min(int(payload.get("limit") or 50), 200))
        offset = max(0, int(payload.get("offset") or 0))
        source_filter = str(payload.get("source") or "").strip()
        kind_filter = str(payload.get("kind") or "").strip()
        query = str(payload.get("query") or "").strip().lower()

        if not store_path.exists():
            return {
                "records": [],
                "total": 0,
                "limit": limit,
                "offset": offset,
                "store": self._relative(store_path),
                "projectId": context.project_id,
            }

        filtered = []
        for record in JsonlVectorStore(store_path).read_records():
            metadata = record.metadata
            source_type = str(metadata.get("source_type") or "")
            kind = str(metadata.get("kind") or "")
            if source_filter and source_type != source_filter:
                continue
            if kind_filter and kind != kind_filter:
                continue
            haystack = "\n".join(
                [
                    record.id,
                    record.text,
                    str(metadata.get("file_path") or ""),
                    str(metadata.get("symbol_name") or ""),
                    str(metadata.get("type_name") or ""),
                ]
            ).lower()
            if query and query not in haystack:
                continue
            filtered.append(record)

        page = filtered[offset : offset + limit]
        return {
            "records": [
                {
                    "id": record.id,
                    "text": record.text,
                    "metadata": record.metadata,
                }
                for record in page
            ],
            "total": len(filtered),
            "limit": limit,
            "offset": offset,
            "store": self._relative(store_path),
            "projectId": context.project_id,
        }

    def query(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        query_text = str(payload.get("query", "")).strip()
        if not query_text:
            raise APIError("query is required.", 400)

        context = self._analysis_context(payload, user)
        store_path = context.store_path(payload.get("store"))
        top_k = int(payload.get("topK", 5))
        filter_source = payload.get("filterSource")
        metadata_filter = {"source_type": filter_source} if filter_source in {"code", "kb"} else None
        store = JsonlVectorStore(store_path)
        results = store.search(
            query=query_text,
            top_k=top_k,
            metadata_filter=metadata_filter,
            embedder=self.embedder,
        )
        response = [serialize_result(result) for result in results]
        evidence = build_evidence(query_text, results, store.read_records())
        saved_path = _save_result(
            context.results_dir,
            "web-query",
            ".json",
            json.dumps({"results": response, "evidence": evidence}, ensure_ascii=False, indent=2),
        )
        return {
            "results": response,
            "evidence": evidence,
            "savedPath": self._relative(saved_path),
            "store": self._relative(store_path),
            "projectId": context.project_id,
        }

    def list_kb_templates(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        return {
            "templates": self._load_kb_templates(context),
            "projectId": context.project_id,
        }

    def create_kb_template(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        templates = self._load_kb_templates(context)
        template = self._template_from_payload(payload, templates)
        templates.append(template)
        self._save_kb_templates(context, templates)
        return {"template": template, "templates": templates, "projectId": context.project_id}

    def update_kb_template(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        template_id = str(payload.get("id") or "").strip()
        if not template_id:
            raise APIError("template id is required.", 400)
        templates = self._load_kb_templates(context)
        for index, template in enumerate(templates):
            if template["id"] == template_id:
                updated = self._template_from_payload(payload, templates, current_id=template_id)
                templates[index] = updated
                self._save_kb_templates(context, templates)
                return {"template": updated, "templates": templates, "projectId": context.project_id}
        raise APIError("knowledge template not found.", 404)

    def delete_kb_template(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        template_id = str(payload.get("id") or "").strip()
        if not template_id:
            raise APIError("template id is required.", 400)
        templates = self._load_kb_templates(context)
        remaining = [template for template in templates if template["id"] != template_id]
        if len(remaining) == len(templates):
            raise APIError("knowledge template not found.", 404)
        self._save_kb_templates(context, remaining)
        return {"deleted": template_id, "templates": remaining, "projectId": context.project_id}

    def list_kb_files(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        root = self._kb_root(context, payload)
        if not root.exists():
            return {"files": [], "root": self._relative(root), "projectId": context.project_id}

        files = [
            {
                "path": self._relative_to_root(root, path),
                "name": path.name,
                "size": path.stat().st_size,
                "updatedAt": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
            }
            for path in sorted(root.rglob("*"))
            if path.is_file() and path.suffix.lower() in KB_EXTENSIONS
        ]
        return {"files": files, "root": self._relative(root), "projectId": context.project_id}

    def get_kb_file(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        root = self._kb_root(context, payload)
        path = self._kb_file_path(root, payload.get("path"))
        if not path.exists():
            raise APIError("knowledge file not found.", 404)
        return {
            "file": {
                "path": self._relative_to_root(root, path),
                "name": path.name,
                "size": path.stat().st_size,
                "updatedAt": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
            },
            "content": path.read_text(encoding="utf-8", errors="replace"),
            "root": self._relative(root),
            "projectId": context.project_id,
        }

    def save_kb_file(self, payload: dict[str, Any], user: "UserRecord", create: bool = False) -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        root = self._kb_root(context, payload)
        path = self._kb_file_path(root, payload.get("path"))
        content = str(payload.get("content", ""))
        if create and path.exists():
            raise APIError("knowledge file already exists.", 409)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return {
            "file": {
                "path": self._relative_to_root(root, path),
                "name": path.name,
                "size": path.stat().st_size,
                "updatedAt": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
            },
            "content": content,
            "root": self._relative(root),
            "projectId": context.project_id,
        }

    def delete_kb_file(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        root = self._kb_root(context, payload)
        path = self._kb_file_path(root, payload.get("path"))
        if not path.exists():
            raise APIError("knowledge file not found.", 404)
        path.unlink()
        return {"deleted": self._relative_to_root(root, path), "root": self._relative(root), "projectId": context.project_id}

    def _analysis_output(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        mode: str,
    ) -> tuple[str, str, str]:
        code_target = context.root

        if mode == "json":
            results = self.analyzer.analyze_path(code_target)
            output = json.dumps([result.to_dict() for result in results], ensure_ascii=False, indent=2)
            return output, ".json", "json"

        if mode == "graph":
            return _build_graph(self.analyzer.analyze_path(code_target)), ".mmd", "graph"

        if mode == "chunks":
            chunks = self._build_source_chunks(context, payload, "code")
            return json.dumps([chunk.__dict__ for chunk in chunks], ensure_ascii=False, indent=2), ".json", "chunks"

        if mode == "summary":
            output = "\n".join(_format_summary(result) for result in self.analyzer.analyze_path(code_target))
            return output, ".txt", "summary"

        code_results = self.analyzer.analyze_path(code_target)
        return _build_report(code_target, "code", code_results, []), ".md", "report"

    def _save_latest_analysis_result(self, results_dir: Path, mode: str, extension: str, output: str) -> Path:
        result_path = results_dir / f"web-{mode}{extension}"
        result_path.parent.mkdir(parents=True, exist_ok=True)
        result_path.write_text(output.rstrip("\n") + "\n", encoding="utf-8")
        return result_path

    def _build_source_chunks(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        source: str,
    ) -> list[Any]:
        chunks: list[Any] = []
        if source in {"code", "mixed"}:
            code_target = self._source_target(context, payload, "code")
            chunks.extend(
                chunk
                for result in self.analyzer.analyze_path(code_target)
                for chunk in build_chunks(result)
            )
        if source in {"kb", "mixed"}:
            chunks.extend(build_kb_chunks(self._source_target(context, payload, "kb")))
        return chunks

    def _source_target(self, context: "AnalysisContext", payload: dict[str, Any], source: str) -> Path:
        fallback = payload.get("path", ".")
        key = "codePath" if source == "code" else "kbPath"
        return context.path(payload.get(key) or fallback)

    def _kb_root(self, context: "AnalysisContext", payload: dict[str, Any]) -> Path:
        return context.path(payload.get("kbPath") or "docs")

    def _kb_templates_file(self, context: "AnalysisContext") -> Path:
        return context.data_root / "knowledge_templates.json"

    def _load_kb_templates(self, context: "AnalysisContext") -> list[dict[str, str]]:
        path = self._kb_templates_file(context)
        if not path.exists():
            now = utc_now()
            return [
                {
                    **template,
                    "createdAt": now,
                    "updatedAt": now,
                }
                for template in DEFAULT_KB_TEMPLATES
            ]
        data = json.loads(path.read_text(encoding="utf-8"))
        templates: list[dict[str, str]] = []
        for item in data.get("templates", []):
            templates.append(
                {
                    "id": str(item.get("id") or ""),
                    "name": str(item.get("name") or ""),
                    "path": str(item.get("path") or ""),
                    "content": str(item.get("content") or ""),
                    "createdAt": str(item.get("createdAt") or utc_now()),
                    "updatedAt": str(item.get("updatedAt") or utc_now()),
                }
            )
        return templates

    def _save_kb_templates(self, context: "AnalysisContext", templates: list[dict[str, str]]) -> None:
        path = self._kb_templates_file(context)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"templates": templates}, ensure_ascii=False, indent=2), encoding="utf-8")

    def _template_from_payload(
        self,
        payload: dict[str, Any],
        templates: list[dict[str, str]],
        current_id: str = "",
    ) -> dict[str, str]:
        name = str(payload.get("name") or "").strip()
        template_path = str(payload.get("path") or "").strip().replace("\\", "/")
        content = str(payload.get("content") or "")
        if not name:
            raise APIError("template name is required.", 400)
        if not template_path:
            raise APIError("template path is required.", 400)
        if Path(template_path).is_absolute():
            raise APIError("template path must be relative.", 400)
        if Path(template_path).suffix.lower() not in KB_EXTENSIONS:
            raise APIError("template path extension must be markdown, text, rst, or asciidoc.", 400)
        now = utc_now()
        existing_ids = {template["id"] for template in templates if template["id"] != current_id}
        template_id = current_id or self._unique_template_id(name, existing_ids)
        created_at = now
        if current_id:
            for template in templates:
                if template["id"] == current_id:
                    created_at = template.get("createdAt") or now
                    break
        return {
            "id": template_id,
            "name": name,
            "path": template_path,
            "content": content,
            "createdAt": created_at,
            "updatedAt": now,
        }

    def _unique_template_id(self, name: str, existing: set[str]) -> str:
        base = slugify(name)
        if base not in existing:
            return base
        index = 2
        while f"{base}-{index}" in existing:
            index += 1
        return f"{base}-{index}"

    def _kb_file_path(self, root: Path, value: object) -> Path:
        raw = str(value or "").strip().replace("\\", "/")
        if not raw:
            raise APIError("path is required.", 400)
        if Path(raw).is_absolute():
            raise APIError("knowledge file path must be relative.", 400)
        path = workspace_path(root, raw)
        if path.suffix.lower() not in KB_EXTENSIONS:
            raise APIError("knowledge file extension must be markdown, text, rst, or asciidoc.", 400)
        return path

    def _relative_to_root(self, root: Path, path: Path) -> str:
        return str(path.resolve().relative_to(root.resolve())).replace("\\", "/")

    def _workspace_path(self, value: object) -> Path:
        return workspace_path(self.workspace_root, value)

    def _relative(self, path: Path) -> str:
        return relative_to_workspace(self.workspace_root, path)

    def _analysis_context(self, payload: dict[str, Any], user: "UserRecord") -> "AnalysisContext":
        project_id = str(payload.get("projectId") or "").strip()
        if not project_id:
            if not user.isAdmin:
                raise APIError("projectId is required.", 403)
            return AnalysisContext(
                workspace_root=self.workspace_root,
                root=self.workspace_root,
                data_root=self.workspace_root,
                default_store=self._workspace_path(self.default_store),
                results_dir=self.workspace_root / self.results_dir,
                project_id="",
            )

        project = self._require_project(project_id, user)
        project_root = self._project_root(project)
        return AnalysisContext(
            workspace_root=self.workspace_root,
            root=self._project_repo_path(project),
            data_root=project_root,
            default_store=project_root / "vector_store" / "project.jsonl",
            results_dir=project_root / "results",
            project_id=project.id,
        )

    def _ensure_default_admin(self) -> None:
        if self.mysql_store:
            self.mysql_store.ensure_admin(self.admin_username, self.admin_password, utc_now())
            return
        users = self._load_users()
        if users:
            return
        now = utc_now()
        admin = UserRecord(
            id=slugify(self.admin_username),
            username=self.admin_username,
            displayName="系统管理员",
            passwordHash=generate_password_hash(self.admin_password),
            isAdmin=True,
            projectIds=[],
            createdAt=now,
            updatedAt=now,
        )
        self._save_users([admin])

    def _load_users(self) -> list["UserRecord"]:
        if self.mysql_store:
            return [UserRecord(**item) for item in self.mysql_store.list_users()]
        if not self.users_file.exists():
            return []
        data = json.loads(self.users_file.read_text(encoding="utf-8"))
        users: list[UserRecord] = []
        for item in data.get("users", []):
            users.append(
                UserRecord(
                    id=item["id"],
                    username=item["username"],
                    displayName=item.get("displayName") or item["username"],
                    passwordHash=item["passwordHash"],
                    isAdmin=bool(item.get("isAdmin", False)),
                    lastProjectId=str(item.get("lastProjectId") or ""),
                    projectIds=list(item.get("projectIds", [])),
                    createdAt=item.get("createdAt", utc_now()),
                    updatedAt=item.get("updatedAt", utc_now()),
                )
            )
        return users

    def _save_users(self, users: list["UserRecord"]) -> None:
        if self.mysql_store:
            self.mysql_store.save_users(users)
            return
        self.projects_dir.mkdir(parents=True, exist_ok=True)
        payload = {"users": [asdict(user) for user in users]}
        self.users_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def _require_user(self, user_id: str) -> "UserRecord":
        for user in self._load_users():
            if user.id == user_id:
                return user
        raise APIError("login required.", 401)

    def _public_user(self, user: "UserRecord") -> dict[str, Any]:
        data = asdict(user)
        data.pop("passwordHash", None)
        return data

    def _require_admin(self, user: "UserRecord") -> None:
        if not user.isAdmin:
            raise APIError("admin required.", 403)

    def _ensure_admin_remains(self, users: list["UserRecord"]) -> None:
        if not any(user.isAdmin for user in users):
            raise APIError("at least one admin is required.", 400)

    def _validate_project_ids(self, project_ids: list[str]) -> None:
        existing = {project.id for project in self._load_projects()}
        missing = [project_id for project_id in project_ids if project_id not in existing]
        if missing:
            raise APIError(f"project not found: {', '.join(missing)}", 404)

    def _projects_for_user(self, user: "UserRecord") -> list["ProjectRecord"]:
        projects = self._load_projects()
        if user.isAdmin:
            return projects
        allowed = set(user.projectIds)
        return [project for project in projects if project.id in allowed]

    def _can_access_project(self, user: "UserRecord", project_id: str) -> bool:
        return user.isAdmin or project_id in set(user.projectIds)

    def _grant_project_access(self, user_id: str, project_id: str) -> None:
        if self.mysql_store:
            self.mysql_store.grant_access(user_id, project_id, utc_now())
            return
        users = self._load_users()
        for user in users:
            if user.id == user_id:
                if project_id not in user.projectIds:
                    user.projectIds.append(project_id)
                    user.updatedAt = utc_now()
                    self._save_users(users)
                return

    def _unique_user_id(self, username: str, users: list["UserRecord"]) -> str:
        base = slugify(username)
        existing = {user.id for user in users}
        if base not in existing:
            return base
        index = 2
        while f"{base}-{index}" in existing:
            index += 1
        return f"{base}-{index}"

    def _load_projects(self) -> list["ProjectRecord"]:
        if self.mysql_store:
            return [ProjectRecord(**item) for item in self.mysql_store.list_projects()]
        if not self.projects_file.exists():
            return []
        data = json.loads(self.projects_file.read_text(encoding="utf-8"))
        projects: list[ProjectRecord] = []
        for item in data.get("projects", []):
            item.setdefault("projectKey", item["id"])
            projects.append(ProjectRecord(**item))
        return projects

    def _save_projects(self, projects: list["ProjectRecord"]) -> None:
        if self.mysql_store:
            self.mysql_store.save_projects(projects)
            return
        self.projects_dir.mkdir(parents=True, exist_ok=True)
        payload = {"projects": [asdict(project) for project in projects]}
        self.projects_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def _require_project(self, project_id: str, user: "UserRecord" | None = None) -> "ProjectRecord":
        for project in self._load_projects():
            if project.id == project_id:
                if user is not None and not self._can_access_project(user, project.id):
                    raise APIError("project access denied.", 403)
                return project
        raise APIError("project not found.", 404)

    def _project_root(self, project: "ProjectRecord") -> Path:
        return workspace_path(self.workspace_root, self.projects_dir / (project.projectKey or project.id))

    def _project_repo_path(self, project: "ProjectRecord") -> Path:
        return workspace_path(self.workspace_root, project.path)

    def _unique_project_id(self, name: str, projects: list["ProjectRecord"]) -> str:
        base = slugify(name)
        existing = {project.projectKey or project.id for project in projects}
        if base not in existing:
            return base
        index = 2
        while f"{base}-{index}" in existing:
            index += 1
        return f"{base}-{index}"

    def _run_git(self, command: list[str], cwd: Path) -> None:
        try:
            completed = subprocess.run(
                command,
                cwd=cwd,
                check=True,
                capture_output=True,
                text=True,
                timeout=300,
            )
        except subprocess.CalledProcessError as error:
            message = (error.stderr or error.stdout or "git command failed.").strip()
            raise APIError(message, 400) from error
        except subprocess.TimeoutExpired as error:
            raise APIError("git command timed out.", 504) from error
        if completed.stderr:
            return


@dataclass
class ProjectRecord:
    id: str
    name: str
    gitUrl: str
    branch: str
    path: str
    createdAt: str
    updatedAt: str
    projectKey: str = ""


@dataclass
class UserRecord:
    id: str
    username: str
    displayName: str
    passwordHash: str
    isAdmin: bool
    projectIds: list[str]
    createdAt: str
    updatedAt: str
    lastProjectId: str = ""


@dataclass
class AnalysisContext:
    workspace_root: Path
    root: Path
    data_root: Path
    default_store: Path
    results_dir: Path
    project_id: str

    def path(self, value: object) -> Path:
        return workspace_path(self.root, value)

    def store_path(self, value: object) -> Path:
        if not value:
            return self.default_store
        return workspace_path(self.data_root, value)


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", value.strip().lower()).strip("-")
    return slug or "project"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()
