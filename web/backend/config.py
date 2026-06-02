from __future__ import annotations

import os
from pathlib import Path


class BackendConfig:
    WORKSPACE_ROOT = Path(os.getenv("ANALYZER_WORKSPACE_ROOT", Path(__file__).resolve().parents[2])).resolve()
    DEFAULT_STORE = Path(os.getenv("ANALYZER_DEFAULT_STORE", ".vector_store/web-project.jsonl"))
    RESULTS_DIR = Path(os.getenv("ANALYZER_RESULTS_DIR", ".java_ts_results"))
    PROJECTS_DIR = Path(os.getenv("ANALYZER_PROJECTS_DIR", ".analzer_projects"))
    SECRET_KEY = os.getenv("ANALYZER_SECRET_KEY", "local-dev-secret")
    ADMIN_USERNAME = os.getenv("ANALYZER_ADMIN_USERNAME", "admin")
    ADMIN_PASSWORD = os.getenv("ANALYZER_ADMIN_PASSWORD", "admin123")
    HOST = os.getenv("ANALYZER_HOST", "127.0.0.1")
    PORT = int(os.getenv("ANALYZER_PORT", "5050"))
    DEBUG = os.getenv("ANALYZER_DEBUG", "").lower() in {"1", "true", "yes"}
    JSON_SORT_KEYS = False
