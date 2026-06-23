from __future__ import annotations

import subprocess
from dataclasses import asdict
from pathlib import Path
from typing import Any

from web.backend.errors import APIError
from web.backend.external_stores import ExternalStoreError
from web.backend.paths import relative_to_workspace, workspace_path
from web.backend.service_models import AnalysisContext, ProjectRecord, UserRecord, slugify, utc_now


class SharedServiceMixin:
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

        # 每个请求先解析项目权限，再把项目仓库根和项目数据根封装到上下文。
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

    def _refresh_knowledge_asset_record_links(self, context: "AnalysisContext") -> dict[str, Any]:
        links = self.mysql_store.refresh_knowledge_asset_record_links(context.project_id)
        assets = [asdict(asset) for asset in self._load_knowledge_assets(context)]
        try:
            neo4j = self.external_stores.sync_knowledge_links(
                project_id=context.project_id,
                assets=assets,
                links=links,
            )
        except ExternalStoreError as exc:
            neo4j = {"ok": False, "error": str(exc)}
        return {
            "count": len(links),
            "links": links,
            "neo4j": neo4j,
        }

    def _query_int(self, value: object, *, default: int, minimum: int, maximum: int, field: str) -> int:
        # 查询参数统一在服务层截断范围，避免路由层散落分页校验。
        if value in (None, ""):
            return default
        try:
            parsed = int(value)
        except (TypeError, ValueError) as error:
            raise APIError(f"{field} must be an integer.", 400) from error
        return max(minimum, min(parsed, maximum))

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
        # 项目数据根与仓库路径分开，便于存放分析输出等项目级状态。
        return workspace_path(self.workspace_root, self.projects_dir / (project.projectKey or project.id))

    def _project_repo_path(self, project: "ProjectRecord") -> Path:
        # 仓库路径必须经过 workspace_path，防止项目记录把请求带出工作区。
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
