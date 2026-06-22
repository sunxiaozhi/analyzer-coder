from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass, is_dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from flask.config import Config
from werkzeug.security import check_password_hash, generate_password_hash

from java_analyzer.analyzer import JavaAnalyzer
from java_analyzer.api_mapper import build_api_mapping
from java_analyzer.chunker import build_chunks
from java_analyzer.embedding import HashingEmbedder
from java_analyzer.models import JavaFileAnalysis, JavaVectorChunk, SourceSpan
from java_analyzer.vector_store import SearchResult, VectorRecord
from java_analyzer.cli import (
    _build_graph,
    _build_report,
    _format_summary,
)
from web.backend.errors import APIError
from web.backend.external_stores import (
    ExternalAnalysisStores,
    ExternalStoreConfig,
    ExternalStoreError,
    Neo4jConfig,
    QdrantConfig,
)
from web.backend.fusion import build_evidence, serialize_result
from web.backend.mysql_state import MySqlStateStore
from web.backend.paths import relative_to_workspace, workspace_path
from web.backend.rag import build_rag_flow
from web.backend.validation import source_value

KB_EXTENSIONS = {".adoc", ".markdown", ".md", ".rst", ".txt"}
KNOWLEDGE_ASSET_TYPES = {
    "business_rule",
    "adr",
    "incident",
    "api_doc",
    "standard",
    "glossary",
    "module_note",
}
KNOWLEDGE_ASSET_STATUSES = {"draft", "pending_review", "confirmed", "stale", "archived"}

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
        projects_dir: Path,
        admin_username: str = "admin",
        admin_password: str = "admin123",
        mysql_config: dict[str, Any] | None = None,
        mysql_store: MySqlStateStore | None = None,
        external_stores_config: ExternalStoreConfig | None = None,
        external_stores: ExternalAnalysisStores | None = None,
        analyzer: JavaAnalyzer | None = None,
        embedder: HashingEmbedder | None = None,
    ) -> None:
        self.workspace_root = workspace_root
        self.projects_dir = workspace_path(workspace_root, projects_dir)
        self.admin_username = admin_username
        self.admin_password = admin_password
        self.analyzer = analyzer or JavaAnalyzer()
        self.embedder = embedder or HashingEmbedder()
        if mysql_store is not None:
            self.mysql_store = mysql_store
        else:
            config = mysql_config or {}
            self.mysql_store = MySqlStateStore(
                host=str(config.get("host", "127.0.0.1")),
                port=int(config.get("port", 3306)),
                user=str(config.get("user", "root")),
                password=str(config.get("password", "root")),
                database=str(config.get("database", "code_knowledge")),
            )
            self.mysql_store.initialize()
        self.external_stores = external_stores or (
            ExternalAnalysisStores(external_stores_config) if external_stores_config else None
        )
        if self.external_stores is None:
            raise APIError("Qdrant and Neo4j stores are required.", 500)
        self._ensure_default_admin()

    @classmethod
    def from_config(cls, config: Config) -> "AnalyzerService":
        return cls(
            workspace_root=Path(config["WORKSPACE_ROOT"]),
            projects_dir=Path(config["PROJECTS_DIR"]),
            admin_username=str(config["ADMIN_USERNAME"]),
            admin_password=str(config["ADMIN_PASSWORD"]),
            mysql_config={
                "host": config.get("MYSQL_HOST", "127.0.0.1"),
                "port": config.get("MYSQL_PORT", 3306),
                "user": config.get("MYSQL_USER", "root"),
                "password": config.get("MYSQL_PASSWORD", "root"),
                "database": config.get("MYSQL_DATABASE", "code_knowledge"),
            },
            mysql_store=config.get("MYSQL_STORE"),
            external_stores_config=ExternalStoreConfig(
                qdrant=QdrantConfig(
                    url=str(config.get("QDRANT_URL", "http://127.0.0.1:6333")),
                    collection=str(config.get("QDRANT_COLLECTION", "java_analyzer_chunks")),
                    api_key=str(config.get("QDRANT_API_KEY", "")),
                    timeout=float(config.get("EXTERNAL_STORE_TIMEOUT", 5)),
                ),
                neo4j=Neo4jConfig(
                    url=str(config.get("NEO4J_URL", "http://127.0.0.1:7474")),
                    database=str(config.get("NEO4J_DATABASE", "neo4j")),
                    username=str(config.get("NEO4J_USERNAME", "neo4j")),
                    password=str(config.get("NEO4J_PASSWORD", "neo4j")),
                    timeout=float(config.get("EXTERNAL_STORE_TIMEOUT", 5)),
                ),
            ),
            external_stores=config.get("EXTERNAL_STORES"),
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
            id="",
            username=username,
            displayName=display_name,
            passwordHash=generate_password_hash(password),
            isAdmin=bool(payload.get("isAdmin", False)),
            lastProjectId="",
            projectIds=project_ids,
            createdAt=now,
            updatedAt=now,
        )
        user = UserRecord(**self.mysql_store.insert_user(asdict(user)))
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
            id="",
            name=name,
            gitUrl=git_url,
            branch=branch or "",
            path=self._relative(repo_path),
            createdAt=utc_now(),
            updatedAt=utc_now(),
            projectKey=project_key,
        )
        project = ProjectRecord(**self.mysql_store.insert_project(asdict(project)))
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

        code_target = self._source_target(context, payload, "code")
        code_results = self.analyzer.analyze_path(code_target)
        output, extension, normalized_mode = self._analysis_output(context, payload, mode, code_results)
        analysis_records = self._analysis_records_for_results(context, code_results)
        analysis_record_count = self._save_analysis_records(context, analysis_records)
        external_sync = self._sync_external_analysis_stores(context, payload, analysis_records, code_results)
        snapshot = self._save_analysis_output(context, normalized_mode, extension, output)
        return {
            "output": output,
            "savedPath": snapshot["uri"],
            "mode": normalized_mode,
            "source": "code",
            "projectId": context.project_id,
            "analysisRecordCount": analysis_record_count,
            "externalSync": external_sync,
        }

    def analysis_result(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        mode = str(payload.get("mode", "report"))
        context = self._analysis_context(payload, user)
        extension, normalized_mode = self._analysis_result_extension(mode)
        snapshot = self._load_analysis_output(context, normalized_mode)
        if not snapshot:
            return {
                "exists": False,
                "output": "",
                "savedPath": "",
                "mode": normalized_mode,
                "source": "code",
                "projectId": context.project_id,
                "updatedAt": "",
            }

        output = str(snapshot.get("output") or "")
        if normalized_mode == "report" and _is_legacy_english_report(output):
            output, extension, normalized_mode = self._analysis_output(context, payload, "report")
            snapshot = self._save_analysis_output(context, normalized_mode, extension, output)

        return {
            "exists": True,
            "output": output,
            "savedPath": str(snapshot.get("uri") or ""),
            "mode": normalized_mode,
            "source": "code",
            "projectId": context.project_id,
            "updatedAt": str(snapshot.get("updatedAt") or ""),
        }

    def index_project(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        source = source_value(payload)
        context = self._analysis_context(payload, user)
        append = bool(payload.get("append", False))

        chunks = self._build_source_chunks(context, payload, source)
        records = self._vector_records_for_chunks(chunks)
        if not append:
            self.external_stores.delete_qdrant_project_records(project_id=context.project_id)
        sync_result = self.external_stores.sync(project_id=context.project_id, analysis_records=[], vector_records=records)
        store_label = self._vector_store_label()
        message = f"已将 {len(records)} 个切块写入 {store_label}"
        return {
            "message": message,
            "count": len(records),
            "store": store_label,
            "savedPath": "",
            "projectId": context.project_id,
            "externalSync": sync_result,
        }

    def index_status(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        records = self._load_vector_records(context)
        if not records:
            return {
                "exists": False,
                "store": self._vector_store_label(),
                "size": 0,
                "updatedAt": "",
                "total": 0,
                "sources": {},
                "kinds": {},
                "projectId": context.project_id,
            }

        sources: dict[str, int] = {}
        kinds: dict[str, int] = {}
        for record in records:
            source_type = str(record.metadata.get("source_type") or "unknown")
            kind = str(record.metadata.get("kind") or "unknown")
            sources[source_type] = sources.get(source_type, 0) + 1
            kinds[kind] = kinds.get(kind, 0) + 1
        return {
            "exists": True,
            "store": self._vector_store_label(),
            "size": 0,
            "updatedAt": utc_now(),
            "total": len(records),
            "sources": sources,
            "kinds": kinds,
            "projectId": context.project_id,
        }

    def index_records(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        limit = max(1, min(int(payload.get("limit") or 50), 200))
        offset = max(0, int(payload.get("offset") or 0))
        source_filter = str(payload.get("source") or "").strip()
        kind_filter = str(payload.get("kind") or "").strip()
        query = str(payload.get("query") or "").strip().lower()

        records = self._load_vector_records(context)
        if not records:
            return {
                "records": [],
                "total": 0,
                "limit": limit,
                "offset": offset,
                "store": self._vector_store_label(),
                "projectId": context.project_id,
            }

        filtered = []
        for record in records:
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
            "store": self._vector_store_label(),
            "projectId": context.project_id,
        }

    def query(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        return self._query(payload, user, include_rag=True)

    def rag_search(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        return self._query(payload, user, include_rag=True)

    def _query(self, payload: dict[str, Any], user: "UserRecord", *, include_rag: bool) -> dict[str, Any]:
        query_text = str(payload.get("query", "")).strip()
        if not query_text:
            raise APIError("query is required.", 400)

        context = self._analysis_context(payload, user)
        top_k = int(payload.get("topK", 5))
        filter_source = payload.get("filterSource")
        source_type = str(filter_source) if filter_source in {"code", "kb", "knowledge_asset"} else ""
        query_embedding = self.embedder.embed(query_text)
        qdrant_results = self.external_stores.search_qdrant(
            project_id=context.project_id,
            query_vector=query_embedding,
            top_k=top_k,
            source_type=source_type,
        )
        results = [
            SearchResult(
                id=str(item.get("id") or ""),
                score=float(item.get("score") or 0),
                text=str(item.get("text") or ""),
                metadata=dict(item.get("metadata") or {}),
            )
            for item in qdrant_results
        ]
        records = self._load_vector_records(context)
        response = [serialize_result(result) for result in results]
        evidence = build_evidence(query_text, results, records)
        rag = build_rag_flow(query_text, results, evidence) if include_rag else None
        output = {
            "results": response,
            "evidence": evidence,
            "savedPath": "",
            "store": self._vector_store_label(),
            "projectId": context.project_id,
        }
        if rag is not None:
            output["rag"] = rag
        return output

    def api_mapping(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        frontend_path = self._relative_to_root(
            context.root,
            context.path(payload.get("frontendPath") or payload.get("path") or "."),
        )
        backend_path = self._relative_to_root(
            context.root,
            context.path(payload.get("backendPath") or payload.get("path") or "."),
        )
        result = build_api_mapping(
            root=context.root,
            frontend_path=frontend_path,
            backend_path=backend_path,
            analyzer=self.analyzer,
        )
        snapshot = self._save_analysis_output(
            context,
            "api-map",
            ".json",
            json.dumps(result.to_dict(), ensure_ascii=False, indent=2),
        )
        return {
            **result.to_dict(),
            "savedPath": snapshot["uri"],
            "projectId": context.project_id,
        }

    def list_knowledge_assets(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        asset_type = str(payload.get("type") or "").strip()
        status = str(payload.get("status") or "").strip()
        query = str(payload.get("query") or "").strip().lower()
        page = self._query_int(payload.get("page"), default=1, minimum=1, maximum=100000, field="page")
        page_size = self._query_int(payload.get("pageSize"), default=20, minimum=1, maximum=100, field="pageSize")
        assets = self._load_knowledge_assets(context)
        filtered = []
        for asset in assets:
            if asset_type and asset.type != asset_type:
                continue
            if status and asset.status != status:
                continue
            haystack = "\n".join(
                [
                    asset.title,
                    asset.summary,
                    asset.content,
                    " ".join(asset.tags),
                    " ".join(str(value) for evidence in asset.evidence for value in evidence.values()),
                ]
            ).lower()
            if query and query not in haystack:
                continue
            filtered.append(asset)
        total = len(filtered)
        offset = (page - 1) * page_size
        paged = filtered[offset : offset + page_size]
        return {
            "assets": [asdict(asset) for asset in paged],
            "total": total,
            "page": page,
            "pageSize": page_size,
            "types": sorted(KNOWLEDGE_ASSET_TYPES),
            "statuses": sorted(KNOWLEDGE_ASSET_STATUSES),
            "projectId": context.project_id,
        }

    def analysis_records(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        record_type = str(payload.get("type") or "").strip()
        limit = self._query_int(payload.get("limit"), default=200, minimum=1, maximum=1000, field="limit")
        offset = self._query_int(payload.get("offset"), default=0, minimum=0, maximum=1000000, field="offset")
        records = self._load_analysis_records(context, record_type, limit, offset)
        type_counts = self._analysis_record_counts(context)
        return {
            "records": records,
            "total": sum(type_counts.values()) if not record_type else type_counts.get(record_type, len(records)),
            "limit": limit,
            "offset": offset,
            "types": type_counts,
            "projectId": context.project_id,
        }

    def create_knowledge_asset(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        assets = self._load_knowledge_assets(context)
        asset = self._knowledge_asset_from_payload(payload, assets, user)
        assets.append(asset)
        self._save_knowledge_assets(context, assets)
        return {
            "asset": asdict(asset),
            "assets": [asdict(item) for item in assets],
            "projectId": context.project_id,
        }

    def update_knowledge_asset(self, asset_id: str, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        asset_id = asset_id.strip()
        if not asset_id:
            raise APIError("knowledge asset id is required.", 400)
        assets = self._load_knowledge_assets(context)
        for index, asset in enumerate(assets):
            if asset.id == asset_id:
                updated = self._knowledge_asset_from_payload(payload, assets, user, current=asset)
                assets[index] = updated
                self._save_knowledge_assets(context, assets)
                return {
                    "asset": asdict(updated),
                    "assets": [asdict(item) for item in assets],
                    "projectId": context.project_id,
                }
        raise APIError("knowledge asset not found.", 404)

    def delete_knowledge_asset(self, asset_id: str, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        asset_id = asset_id.strip()
        assets = self._load_knowledge_assets(context)
        remaining = [asset for asset in assets if asset.id != asset_id]
        if len(remaining) == len(assets):
            raise APIError("knowledge asset not found.", 404)
        self._save_knowledge_assets(context, remaining)
        return {
            "deleted": asset_id,
            "assets": [asdict(item) for item in remaining],
            "projectId": context.project_id,
        }

    def transition_knowledge_asset(
        self,
        asset_id: str,
        payload: dict[str, Any],
        user: "UserRecord",
        status: str,
    ) -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        assets = self._load_knowledge_assets(context)
        for asset in assets:
            if asset.id == asset_id:
                asset.status = status
                asset.updatedAt = utc_now()
                if status == "confirmed":
                    asset.reviewerUserId = user.id
                    asset.confirmedAt = asset.updatedAt
                self._save_knowledge_assets(context, assets)
                return {
                    "asset": asdict(asset),
                    "assets": [asdict(item) for item in assets],
                    "projectId": context.project_id,
                }
        raise APIError("knowledge asset not found.", 404)

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
        prefix = self._kb_prefix(payload)
        files = self.mysql_store.list_knowledge_documents(context.project_id, prefix=prefix)
        return {"files": files, "root": "mysql://knowledge_documents", "projectId": context.project_id}

    def get_kb_file(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        document_path = self._kb_document_path(payload.get("path"))
        document = self.mysql_store.get_knowledge_document(context.project_id, document_path)
        if not document:
            raise APIError("knowledge file not found.", 404)
        return {
            "file": {
                "path": document["path"],
                "name": document["name"],
                "size": document["size"],
                "updatedAt": document["updatedAt"],
            },
            "content": document["content"],
            "root": "mysql://knowledge_documents",
            "projectId": context.project_id,
        }

    def save_kb_file(self, payload: dict[str, Any], user: "UserRecord", create: bool = False) -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        document_path = self._kb_document_path(payload.get("path"))
        content = str(payload.get("content", ""))
        if create and self.mysql_store.get_knowledge_document(context.project_id, document_path):
            raise APIError("knowledge file already exists.", 409)
        document = self.mysql_store.save_knowledge_document(
            context.project_id,
            path=document_path,
            content=content,
            updated_by=user.id,
        )
        return {
            "file": {
                "path": document["path"],
                "name": document["name"],
                "size": document["size"],
                "updatedAt": document["updatedAt"],
            },
            "content": content,
            "root": "mysql://knowledge_documents",
            "projectId": context.project_id,
        }

    def delete_kb_file(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        document_path = self._kb_document_path(payload.get("path"))
        if not self.mysql_store.delete_knowledge_document(context.project_id, document_path):
            raise APIError("knowledge file not found.", 404)
        return {"deleted": document_path, "root": "mysql://knowledge_documents", "projectId": context.project_id}

    def _vector_records_for_chunks(self, chunks: list[Any]) -> list[VectorRecord]:
        chunk_list = list(chunks)
        embeddings = self.embedder.embed_many([chunk.text for chunk in chunk_list])
        return [
            VectorRecord(
                id=chunk.id,
                text=chunk.text,
                metadata=chunk.metadata,
                embedding=embedding,
            )
            for chunk, embedding in zip(chunk_list, embeddings, strict=True)
        ]

    def _load_vector_records(self, context: "AnalysisContext", limit: int = 5000) -> list[VectorRecord]:
        return [
            VectorRecord(
                id=str(item.get("id") or ""),
                text=str(item.get("text") or ""),
                metadata=dict(item.get("metadata") or {}),
                embedding=[],
            )
            for item in self.external_stores.list_qdrant_records(project_id=context.project_id, limit=limit)
        ]

    def _vector_store_label(self) -> str:
        collection = self.external_stores.config.qdrant.collection
        return f"qdrant://{collection}"

    def _save_analysis_records(self, context: "AnalysisContext", records: list[dict[str, Any]]) -> int:
        self.mysql_store.save_analysis_records(context.project_id, records)
        return len(records)

    def _sync_external_analysis_stores(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        analysis_records: list[dict[str, Any]],
        results: list[JavaFileAnalysis],
    ) -> dict[str, Any]:
        chunks = [chunk for result in results for chunk in build_chunks(result)]
        vector_records = self._vector_records_for_chunks(chunks)
        try:
            return self.external_stores.sync(
                project_id=context.project_id,
                analysis_records=analysis_records,
                vector_records=vector_records,
            )
        except ExternalStoreError as exc:
            return {
                "enabled": True,
                "ok": False,
                "error": str(exc),
            }

    def _load_analysis_records(
        self,
        context: "AnalysisContext",
        record_type: str,
        limit: int,
        offset: int,
    ) -> list[dict[str, Any]]:
        return self.mysql_store.list_analysis_records(context.project_id, record_type, limit, offset)

    def _analysis_record_counts(self, context: "AnalysisContext") -> dict[str, int]:
        return self.mysql_store.count_analysis_records_by_type(context.project_id)

    def _analysis_records_for_results(
        self,
        context: "AnalysisContext",
        results: list[JavaFileAnalysis],
    ) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        for result in results:
            file_path = self._analysis_file_path(context, result.file_path)
            records.append(
                self._analysis_record(
                    record_type="file",
                    file_path=file_path,
                    package=result.package or "",
                    key_parts=[file_path or "<memory>"],
                    payload={
                        "filePath": file_path,
                        "package": result.package,
                        "hasError": result.has_error,
                    },
                )
            )
            if result.metrics is not None:
                records.append(
                    self._analysis_record(
                        record_type="metrics",
                        file_path=file_path,
                        package=result.package or "",
                        key_parts=[file_path or "<memory>", "metrics"],
                        payload=asdict(result.metrics),
                    )
                )
            for index, item in enumerate(result.imports):
                records.append(self._record_from_item("import", file_path, result.package, item, [item.name, index]))
            for index, item in enumerate(result.symbols):
                records.append(self._record_from_item("symbol", file_path, result.package, item, [item.name, index]))
            for index, item in enumerate(result.types):
                records.append(self._record_from_item("type", file_path, result.package, item, [item.name, index]))
            for index, item in enumerate(result.fields):
                records.append(self._record_from_item("field", file_path, result.package, item, [item.enclosing_type or "", item.name, index]))
            for index, item in enumerate(result.methods):
                records.append(self._record_from_item("method", file_path, result.package, item, [item.enclosing_type or "", item.name, index]))
            for index, item in enumerate(result.calls):
                records.append(self._record_from_item("call", file_path, result.package, item, [item.enclosing_type or "", item.enclosing_method or "", item.name, index]))
            for index, item in enumerate(result.local_variables):
                records.append(self._record_from_item("local_variable", file_path, result.package, item, [item.enclosing_type or "", item.enclosing_method or "", item.name, index]))
            for index, item in enumerate(result.returns):
                records.append(self._record_from_item("return", file_path, result.package, item, [item.enclosing_type or "", item.enclosing_method or "", index]))
            for index, item in enumerate(result.control_structures):
                records.append(self._record_from_item("control_structure", file_path, result.package, item, [item.enclosing_type or "", item.enclosing_method or "", item.kind, index]))
            for index, item in enumerate(result.components):
                records.append(self._record_from_item("component", file_path, result.package, item, [item.name, index]))
            for index, item in enumerate(result.endpoints):
                records.append(self._record_from_item("endpoint", file_path, result.package, item, [item.enclosing_type, item.method_name, item.path, index]))
            for index, item in enumerate(result.sql_references):
                records.append(self._record_from_item("sql_reference", file_path, result.package, item, [item.enclosing_type or "", item.method_name or "", index]))
            for index, item in enumerate(result.syntax_errors):
                records.append(
                    self._analysis_record(
                        record_type="syntax_error",
                        file_path=file_path,
                        package=result.package or "",
                        span=item,
                        key_parts=[file_path or "<memory>", "syntax_error", index],
                        payload=asdict(item),
                    )
                )
        return records

    def _record_from_item(
        self,
        record_type: str,
        file_path: str,
        package_name: str | None,
        item: Any,
        key_parts: list[Any],
    ) -> dict[str, Any]:
        payload = asdict(item) if is_dataclass(item) else dict(item)
        return self._analysis_record(
            record_type=record_type,
            file_path=file_path,
            package=package_name or "",
            span=getattr(item, "span", None),
            symbol_name=str(getattr(item, "name", "") or getattr(item, "method_name", "") or ""),
            enclosing_type=str(getattr(item, "enclosing_type", "") or ""),
            enclosing_method=str(getattr(item, "enclosing_method", "") or ""),
            key_parts=[file_path or "<memory>", record_type, *key_parts],
            payload=payload,
        )

    def _analysis_record(
        self,
        record_type: str,
        file_path: str,
        package: str,
        key_parts: list[Any],
        payload: dict[str, Any],
        span: SourceSpan | None = None,
        symbol_name: str = "",
        enclosing_type: str = "",
        enclosing_method: str = "",
    ) -> dict[str, Any]:
        key = "::".join(str(part) for part in key_parts)
        return {
            "key": key,
            "type": record_type,
            "filePath": file_path,
            "package": package,
            "symbolName": symbol_name,
            "enclosingType": enclosing_type,
            "enclosingMethod": enclosing_method,
            "startLine": span.start_line if span else 0,
            "startColumn": span.start_column if span else 0,
            "endLine": span.end_line if span else 0,
            "endColumn": span.end_column if span else 0,
            "payload": payload,
        }

    def _analysis_file_path(self, context: "AnalysisContext", file_path: str | None) -> str:
        if not file_path:
            return ""
        path = Path(file_path)
        if path.is_absolute():
            try:
                return self._relative_to_root(context.root, path)
            except ValueError:
                return self._relative(path)
        return str(path).replace("\\", "/")

    def _analysis_output(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        mode: str,
        code_results: list[JavaFileAnalysis] | None = None,
    ) -> tuple[str, str, str]:
        code_target = self._source_target(context, payload, "code")
        results = code_results if code_results is not None else self.analyzer.analyze_path(code_target)

        if mode == "json":
            output = json.dumps([result.to_dict() for result in results], ensure_ascii=False, indent=2)
            return output, ".json", "json"

        if mode == "graph":
            return _build_graph(results, code_target), ".mmd", "graph"

        if mode == "chunks":
            chunks = [chunk for result in results for chunk in build_chunks(result)]
            return json.dumps([chunk.__dict__ for chunk in chunks], ensure_ascii=False, indent=2), ".json", "chunks"

        if mode == "summary":
            output = "\n".join(_format_summary(result) for result in results)
            return output, ".txt", "summary"

        return _build_report(code_target, "code", results, []), ".md", "report"

    def _analysis_result_extension(self, mode: str) -> tuple[str, str]:
        if mode == "json":
            return ".json", "json"
        if mode == "graph":
            return ".mmd", "graph"
        if mode == "chunks":
            return ".json", "chunks"
        if mode == "summary":
            return ".txt", "summary"
        return ".md", "report"

    def _save_analysis_output(
        self,
        context: "AnalysisContext",
        mode: str,
        extension: str,
        output: str,
    ) -> dict[str, Any]:
        return self.mysql_store.save_analysis_output(
            context.project_id,
            mode=mode,
            extension=extension,
            output=output.rstrip("\n") + "\n",
        )

    def _load_analysis_output(self, context: "AnalysisContext", mode: str) -> dict[str, Any] | None:
        return self.mysql_store.get_analysis_output(context.project_id, mode)

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
            chunks.extend(self._build_knowledge_document_chunks(context, payload))
            chunks.extend(self._build_knowledge_asset_chunks(context))
        return chunks

    def _build_knowledge_document_chunks(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
    ) -> list[JavaVectorChunk]:
        prefix = self._kb_prefix(payload)
        documents = self.mysql_store.list_knowledge_documents(context.project_id, prefix=prefix)
        chunks: list[JavaVectorChunk] = []
        for document in documents:
            loaded = self.mysql_store.get_knowledge_document(context.project_id, str(document.get("path") or ""))
            if not loaded:
                continue
            document_path = str(loaded["path"])
            chunks.append(
                JavaVectorChunk(
                    id=f"kb:{document_path}",
                    text=str(loaded["content"]),
                    metadata={
                        "source_type": "kb",
                        "kind": "knowledge_document",
                        "file_path": document_path,
                        "title": str(loaded["name"]),
                        "start_line": 1,
                    },
                )
            )
        return chunks

    def _build_knowledge_asset_chunks(self, context: "AnalysisContext") -> list[JavaVectorChunk]:
        chunks: list[JavaVectorChunk] = []
        for asset in self._load_knowledge_assets(context):
            evidence_lines = [
                " - ".join(
                    part
                    for part in [
                        str(evidence.get("type") or ""),
                        str(evidence.get("filePath") or ""),
                        str(evidence.get("symbolName") or ""),
                        str(evidence.get("note") or ""),
                    ]
                    if part
                )
                for evidence in asset.evidence
            ]
            text = "\n".join(
                part
                for part in [
                    f"Knowledge asset: {asset.title}",
                    f"Type: {asset.type}",
                    f"Status: {asset.status}",
                    f"Summary: {asset.summary}" if asset.summary else "",
                    f"Tags: {', '.join(asset.tags)}" if asset.tags else "",
                    asset.content,
                    "Evidence:\n" + "\n".join(evidence_lines) if evidence_lines else "",
                ]
                if part
            )
            chunks.append(
                JavaVectorChunk(
                    id=f"knowledge_asset:{asset.id}",
                    text=text,
                    metadata={
                        "source_type": "knowledge_asset",
                        "kind": asset.type,
                        "asset_id": asset.id,
                        "title": asset.title,
                        "status": asset.status,
                        "owner_user_id": asset.ownerUserId,
                        "reviewer_user_id": asset.reviewerUserId,
                        "tags": asset.tags,
                        "file_path": asset.sourcePath,
                        "start_line": 1,
                    },
                )
            )
        return chunks

    def _source_target(self, context: "AnalysisContext", payload: dict[str, Any], source: str) -> Path:
        fallback = payload.get("path", ".")
        key = "codePath" if source == "code" else "kbPath"
        return context.path(payload.get(key) or fallback)

    def _load_knowledge_assets(self, context: "AnalysisContext") -> list["KnowledgeAsset"]:
        return [KnowledgeAsset(**item) for item in self.mysql_store.list_knowledge_assets(context.project_id)]

    def _save_knowledge_assets(self, context: "AnalysisContext", assets: list["KnowledgeAsset"]) -> None:
        self.mysql_store.save_knowledge_assets(context.project_id, assets)

    def _knowledge_asset_from_payload(
        self,
        payload: dict[str, Any],
        assets: list["KnowledgeAsset"],
        user: "UserRecord",
        current: "KnowledgeAsset | None" = None,
    ) -> "KnowledgeAsset":
        title = str(payload.get("title") or "").strip()
        asset_type = str(payload.get("type") or "business_rule").strip()
        status = str(payload.get("status") or (current.status if current else "draft")).strip()
        if not title:
            raise APIError("knowledge asset title is required.", 400)
        if asset_type not in KNOWLEDGE_ASSET_TYPES:
            raise APIError("knowledge asset type is invalid.", 400)
        if status not in KNOWLEDGE_ASSET_STATUSES:
            raise APIError("knowledge asset status is invalid.", 400)

        now = utc_now()
        existing_ids = {asset.id for asset in assets if not current or asset.id != current.id}
        asset_id = current.id if current else self._unique_knowledge_asset_id(title, existing_ids)
        confirmed_at = str(payload.get("confirmedAt") or (current.confirmedAt if current else ""))
        reviewer_user_id = str(payload.get("reviewerUserId") or (current.reviewerUserId if current else ""))
        if status == "confirmed" and not confirmed_at:
            confirmed_at = now
            reviewer_user_id = reviewer_user_id or user.id

        return KnowledgeAsset(
            id=asset_id,
            type=asset_type,
            title=title,
            summary=str(payload.get("summary") or "").strip(),
            content=str(payload.get("content") or ""),
            status=status,
            ownerUserId=str(payload.get("ownerUserId") or (current.ownerUserId if current else user.id)),
            reviewerUserId=reviewer_user_id,
            tags=self._normalize_tags(payload.get("tags")),
            evidence=self._normalize_evidence(payload.get("evidence")),
            sourcePath=str(payload.get("sourcePath") or (current.sourcePath if current else "")).strip(),
            confirmedAt=confirmed_at,
            reviewDueAt=str(payload.get("reviewDueAt") or "").strip(),
            createdAt=current.createdAt if current else now,
            updatedAt=now,
            createdBy=current.createdBy if current else user.id,
            updatedBy=user.id,
        )

    def _unique_knowledge_asset_id(self, title: str, existing: set[str]) -> str:
        base = slugify(title)
        if base not in existing:
            return base
        index = 2
        while f"{base}-{index}" in existing:
            index += 1
        return f"{base}-{index}"

    def _normalize_tags(self, value: object) -> list[str]:
        if isinstance(value, str):
            raw_tags = re.split(r"[,，\s]+", value)
        elif isinstance(value, list):
            raw_tags = [str(item) for item in value]
        else:
            raw_tags = []
        tags: list[str] = []
        for raw in raw_tags:
            tag = raw.strip()
            if tag and tag not in tags:
                tags.append(tag)
        return tags[:12]

    def _normalize_evidence(self, value: object) -> list[dict[str, Any]]:
        if not isinstance(value, list):
            return []
        evidence_items: list[dict[str, Any]] = []
        for item in value:
            if not isinstance(item, dict):
                continue
            evidence_type = str(item.get("type") or item.get("evidenceType") or "file").strip()
            file_path = str(item.get("filePath") or item.get("file_path") or "").strip()
            symbol_name = str(item.get("symbolName") or item.get("symbol_name") or "").strip()
            note = str(item.get("note") or "").strip()
            if not file_path and not symbol_name and not note:
                continue
            evidence_items.append(
                {
                    "type": evidence_type,
                    "filePath": file_path,
                    "symbolName": symbol_name,
                    "startLine": int(item.get("startLine") or item.get("start_line") or 0),
                    "endLine": int(item.get("endLine") or item.get("end_line") or 0),
                    "note": note,
                }
            )
        return evidence_items[:20]

    def _query_int(self, value: object, *, default: int, minimum: int, maximum: int, field: str) -> int:
        if value in (None, ""):
            return default
        try:
            parsed = int(value)
        except (TypeError, ValueError) as error:
            raise APIError(f"{field} must be an integer.", 400) from error
        return max(minimum, min(parsed, maximum))

    def _load_kb_templates(self, context: "AnalysisContext") -> list[dict[str, str]]:
        templates = self.mysql_store.list_kb_templates(context.project_id)
        if templates:
            return templates
        now = utc_now()
        return [
            {
                **template,
                "createdAt": now,
                "updatedAt": now,
            }
            for template in DEFAULT_KB_TEMPLATES
        ]

    def _save_kb_templates(self, context: "AnalysisContext", templates: list[dict[str, str]]) -> None:
        self.mysql_store.save_kb_templates(context.project_id, templates)

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

    def _kb_prefix(self, payload: dict[str, Any]) -> str:
        raw = str(payload.get("kbPath") or "").strip().replace("\\", "/").strip("/")
        if not raw:
            return ""
        if Path(raw).is_absolute() or ".." in Path(raw).parts:
            raise APIError("knowledge root must be relative.", 400)
        return ""

    def _kb_document_path(self, value: object) -> str:
        raw = str(value or "").strip().replace("\\", "/")
        if not raw:
            raise APIError("path is required.", 400)
        if Path(raw).is_absolute():
            raise APIError("knowledge file path must be relative.", 400)
        path = Path(raw)
        if ".." in path.parts:
            raise APIError("knowledge file path must stay within the project.", 400)
        if path.suffix.lower() not in KB_EXTENSIONS:
            raise APIError("knowledge file extension must be markdown, text, rst, or asciidoc.", 400)
        return raw.strip("/")

    def _relative_to_root(self, root: Path, path: Path) -> str:
        return str(path.resolve().relative_to(root.resolve())).replace("\\", "/")

    def _workspace_path(self, value: object) -> Path:
        return workspace_path(self.workspace_root, value)

    def _relative(self, path: Path) -> str:
        return relative_to_workspace(self.workspace_root, path)

    def _analysis_context(self, payload: dict[str, Any], user: "UserRecord") -> "AnalysisContext":
        project_id = str(payload.get("projectId") or "").strip()
        if not project_id:
            raise APIError("projectId is required.", 400)

        project = self._require_project(project_id, user)
        project_root = self._project_root(project)
        return AnalysisContext(
            workspace_root=self.workspace_root,
            root=self._project_repo_path(project),
            data_root=project_root,
            project_id=project.id,
        )

    def _ensure_default_admin(self) -> None:
        self.mysql_store.ensure_admin(self.admin_username, self.admin_password, utc_now())

    def _load_users(self) -> list["UserRecord"]:
        return [UserRecord(**item) for item in self.mysql_store.list_users()]

    def _save_users(self, users: list["UserRecord"]) -> None:
        self.mysql_store.save_users(users)

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
        self.mysql_store.grant_access(user_id, project_id, utc_now())

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
        return [ProjectRecord(**item) for item in self.mysql_store.list_projects()]

    def _save_projects(self, projects: list["ProjectRecord"]) -> None:
        self.mysql_store.save_projects(projects)

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
class KnowledgeAsset:
    id: str
    type: str
    title: str
    summary: str
    content: str
    status: str
    ownerUserId: str
    reviewerUserId: str
    tags: list[str]
    evidence: list[dict[str, Any]]
    sourcePath: str
    confirmedAt: str
    reviewDueAt: str
    createdAt: str
    updatedAt: str
    createdBy: str
    updatedBy: str


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
    project_id: str

    def path(self, value: object) -> Path:
        return workspace_path(self.root, value)


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", value.strip().lower()).strip("-")
    return slug or "project"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _is_legacy_english_report(output: str) -> bool:
    legacy_markers = (
        "# Java Project Analysis Report",
        "## HTTP Endpoints",
        "## Endpoint SQL Flows",
        "## Code Call Chains",
        "## SQL References",
        "## Knowledge Base",
    )
    return any(marker in output for marker in legacy_markers)
