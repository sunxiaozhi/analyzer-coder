from __future__ import annotations

import shutil
from dataclasses import asdict
from typing import Any

from web.backend.errors import APIError
from web.backend.service_models import ProjectRecord, utc_now


class ProjectServiceMixin:
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
