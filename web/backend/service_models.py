from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from web.backend.paths import workspace_path

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
