from __future__ import annotations

from pathlib import Path


def workspace_path(workspace_root: Path, value: object) -> Path:
    workspace = workspace_root.resolve()
    raw = Path(str(value or "."))
    path = raw if raw.is_absolute() else workspace / raw
    resolved = path.resolve()
    if resolved != workspace and workspace not in resolved.parents:
        raise ValueError("path must stay inside the workspace.")
    return resolved


def relative_to_workspace(workspace_root: Path, path: Path) -> str:
    try:
        return str(path.resolve().relative_to(workspace_root.resolve()))
    except ValueError:
        return str(path)
