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

from java_ts_analyzer.analyzer import JavaAnalyzer
from java_ts_analyzer.embedding import HashingEmbedder
from java_ts_analyzer.kb_loader import build_kb_chunks
from java_ts_analyzer.vector_store import JsonlVectorStore
from java_ts_analyzer.cli import (
    _build_graph,
    _build_report,
    _build_source_chunks,
    _format_summary,
    _save_result,
)
from web.backend.errors import APIError
from web.backend.paths import relative_to_workspace, workspace_path
from web.backend.validation import source_value


class AnalyzerService:
    def __init__(
        self,
        workspace_root: Path,
        default_store: Path,
        results_dir: Path,
        projects_dir: Path,
        analyzer: JavaAnalyzer | None = None,
        embedder: HashingEmbedder | None = None,
    ) -> None:
        self.workspace_root = workspace_root
        self.default_store = default_store
        self.results_dir = results_dir
        self.projects_dir = workspace_path(workspace_root, projects_dir)
        self.projects_file = self.projects_dir / "projects.json"
        self.analyzer = analyzer or JavaAnalyzer()
        self.embedder = embedder or HashingEmbedder()

    @classmethod
    def from_config(cls, config: Config) -> "AnalyzerService":
        return cls(
            workspace_root=Path(config["WORKSPACE_ROOT"]),
            default_store=Path(config["DEFAULT_STORE"]),
            results_dir=Path(config["RESULTS_DIR"]),
            projects_dir=Path(config["PROJECTS_DIR"]),
        )

    def health(self) -> dict[str, Any]:
        return {"ok": True, "workspace": str(self.workspace_root)}

    def list_projects(self) -> dict[str, Any]:
        return {"projects": [asdict(project) for project in self._load_projects()]}

    def create_project(self, payload: dict[str, Any]) -> dict[str, Any]:
        name = str(payload.get("name", "")).strip()
        git_url = str(payload.get("gitUrl", "")).strip()
        branch = str(payload.get("branch", "")).strip() or None
        if not name:
            raise APIError("name is required.", 400)
        if not git_url:
            raise APIError("gitUrl is required.", 400)

        projects = self._load_projects()
        project_id = self._unique_project_id(name, projects)
        project_root = self.projects_dir / project_id
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
            id=project_id,
            name=name,
            gitUrl=git_url,
            branch=branch or "",
            path=self._relative(repo_path),
            createdAt=utc_now(),
            updatedAt=utc_now(),
        )
        projects.append(project)
        self._save_projects(projects)
        return {"project": asdict(project)}

    def pull_project(self, project_id: str) -> dict[str, Any]:
        project = self._require_project(project_id)
        repo_path = self._project_repo_path(project)
        self._run_git(["git", "pull", "--ff-only"], cwd=repo_path)
        project.updatedAt = utc_now()
        projects = [project if item.id == project.id else item for item in self._load_projects()]
        self._save_projects(projects)
        return {"project": asdict(project)}

    def analyze(self, payload: dict[str, Any]) -> dict[str, Any]:
        source = source_value(payload)
        mode = str(payload.get("mode", "report"))
        context = self._analysis_context(payload)
        target = context.path(payload.get("path", "."))

        output, extension, normalized_mode = self._analysis_output(target, source, mode)
        saved_path = _save_result(
            context.results_dir,
            f"web-{normalized_mode}",
            extension,
            output,
        )
        return {
            "output": output,
            "savedPath": self._relative(saved_path),
            "mode": normalized_mode,
            "source": source,
            "projectId": context.project_id,
        }

    def index_project(self, payload: dict[str, Any]) -> dict[str, Any]:
        source = source_value(payload)
        context = self._analysis_context(payload)
        target = context.path(payload.get("path", "."))
        store_path = context.store_path(payload.get("store"))
        append = bool(payload.get("append", False))

        chunks = _build_source_chunks(self.analyzer, target, source)
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

    def query(self, payload: dict[str, Any]) -> dict[str, Any]:
        query_text = str(payload.get("query", "")).strip()
        if not query_text:
            raise APIError("query is required.", 400)

        context = self._analysis_context(payload)
        store_path = context.store_path(payload.get("store"))
        top_k = int(payload.get("topK", 5))
        filter_source = payload.get("filterSource")
        metadata_filter = {"source_type": filter_source} if filter_source in {"code", "kb"} else None
        results = JsonlVectorStore(store_path).search(
            query=query_text,
            top_k=top_k,
            metadata_filter=metadata_filter,
            embedder=self.embedder,
        )
        response = [
            {
                "id": result.id,
                "score": result.score,
                "text": result.text,
                "metadata": result.metadata,
            }
            for result in results
        ]
        saved_path = _save_result(
            context.results_dir,
            "web-query",
            ".json",
            json.dumps(response, ensure_ascii=False, indent=2),
        )
        return {
            "results": response,
            "savedPath": self._relative(saved_path),
            "store": self._relative(store_path),
            "projectId": context.project_id,
        }

    def _analysis_output(self, target: Path, source: str, mode: str) -> tuple[str, str, str]:
        if mode == "json":
            if source == "code":
                results = self.analyzer.analyze_path(target)
                output = json.dumps([result.to_dict() for result in results], ensure_ascii=False, indent=2)
            else:
                chunks = _build_source_chunks(self.analyzer, target, source)
                output = json.dumps([chunk.__dict__ for chunk in chunks], ensure_ascii=False, indent=2)
            return output, ".json", "json"

        if mode == "graph":
            if source == "kb":
                raise APIError("--graph requires source code or mixed.", 400)
            return _build_graph(self.analyzer.analyze_path(target)), ".mmd", "graph"

        if mode == "chunks":
            chunks = _build_source_chunks(self.analyzer, target, source)
            return json.dumps([chunk.__dict__ for chunk in chunks], ensure_ascii=False, indent=2), ".json", "chunks"

        if mode == "summary":
            if source != "code":
                chunks = _build_source_chunks(self.analyzer, target, source)
                output = f"{target}: {len(chunks)} chunks"
            else:
                output = "\n".join(_format_summary(result) for result in self.analyzer.analyze_path(target))
            return output, ".txt", "summary"

        code_results = self.analyzer.analyze_path(target) if source in {"code", "mixed"} else []
        kb_chunks = build_kb_chunks(target) if source in {"kb", "mixed"} else []
        return _build_report(target, source, code_results, kb_chunks), ".md", "report"

    def _workspace_path(self, value: object) -> Path:
        return workspace_path(self.workspace_root, value)

    def _relative(self, path: Path) -> str:
        return relative_to_workspace(self.workspace_root, path)

    def _analysis_context(self, payload: dict[str, Any]) -> "AnalysisContext":
        project_id = str(payload.get("projectId", "")).strip()
        if not project_id:
            return AnalysisContext(
                workspace_root=self.workspace_root,
                root=self.workspace_root,
                data_root=self.workspace_root,
                default_store=self._workspace_path(self.default_store),
                results_dir=self.workspace_root / self.results_dir,
                project_id="",
            )

        project = self._require_project(project_id)
        project_root = self._project_root(project)
        return AnalysisContext(
            workspace_root=self.workspace_root,
            root=self._project_repo_path(project),
            data_root=project_root,
            default_store=project_root / "vector_store" / "project.jsonl",
            results_dir=project_root / "results",
            project_id=project.id,
        )

    def _load_projects(self) -> list["ProjectRecord"]:
        if not self.projects_file.exists():
            return []
        data = json.loads(self.projects_file.read_text(encoding="utf-8"))
        return [ProjectRecord(**item) for item in data.get("projects", [])]

    def _save_projects(self, projects: list["ProjectRecord"]) -> None:
        self.projects_dir.mkdir(parents=True, exist_ok=True)
        payload = {"projects": [asdict(project) for project in projects]}
        self.projects_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def _require_project(self, project_id: str) -> "ProjectRecord":
        for project in self._load_projects():
            if project.id == project_id:
                return project
        raise APIError("project not found.", 404)

    def _project_root(self, project: "ProjectRecord") -> Path:
        return workspace_path(self.workspace_root, self.projects_dir / project.id)

    def _project_repo_path(self, project: "ProjectRecord") -> Path:
        return workspace_path(self.workspace_root, self._project_root(project) / "repo")

    def _unique_project_id(self, name: str, projects: list["ProjectRecord"]) -> str:
        base = slugify(name)
        existing = {project.id for project in projects}
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
