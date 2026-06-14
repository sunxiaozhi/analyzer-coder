from __future__ import annotations

import os
from pathlib import Path


class BackendConfig:
    WORKSPACE_ROOT = Path(os.getenv("ANALYZER_WORKSPACE_ROOT", Path(__file__).resolve().parents[2])).resolve()
    DEFAULT_STORE = Path(os.getenv("ANALYZER_DEFAULT_STORE", ".vector_store/web-project.jsonl"))
    RESULTS_DIR = Path(os.getenv("ANALYZER_RESULTS_DIR", ".analzer_results"))
    PROJECTS_DIR = Path(os.getenv("ANALYZER_PROJECTS_DIR", ".analzer_projects"))
    STATE_BACKEND = os.getenv("ANALYZER_STATE_BACKEND", "mysql")
    MYSQL_HOST = os.getenv("ANALYZER_MYSQL_HOST", "192.168.0.100")
    MYSQL_PORT = int(os.getenv("ANALYZER_MYSQL_PORT", "3806"))
    MYSQL_USER = os.getenv("ANALYZER_MYSQL_USER", "root")
    MYSQL_PASSWORD = os.getenv("ANALYZER_MYSQL_PASSWORD", "root")
    MYSQL_DATABASE = os.getenv("ANALYZER_MYSQL_DATABASE", "analyzer_coder")
    EXTERNAL_STORES_ENABLED = os.getenv("ANALYZER_EXTERNAL_STORES_ENABLED", "").lower() in {"1", "true", "yes"}
    QDRANT_URL = os.getenv("ANALYZER_QDRANT_URL", "http://192.168.0.100:6333")
    QDRANT_COLLECTION = os.getenv("ANALYZER_QDRANT_COLLECTION", "analyzer_coder")
    NEO4J_URL = os.getenv("ANALYZER_NEO4J_URL", "http://192.168.0.100:7474")
    NEO4J_DATABASE = os.getenv("ANALYZER_NEO4J_DATABASE", "neo4j")
    NEO4J_USERNAME = os.getenv("ANALYZER_NEO4J_USERNAME", "neo4j")
    NEO4J_PASSWORD = os.getenv("ANALYZER_NEO4J_PASSWORD", "analyzer_coder")
    EXTERNAL_STORE_TIMEOUT = float(os.getenv("ANALYZER_EXTERNAL_STORE_TIMEOUT", "5"))
    SECRET_KEY = os.getenv("ANALYZER_SECRET_KEY", "local-dev-secret")
    ADMIN_USERNAME = os.getenv("ANALYZER_ADMIN_USERNAME", "admin")
    ADMIN_PASSWORD = os.getenv("ANALYZER_ADMIN_PASSWORD", "admin123")
    HOST = os.getenv("ANALYZER_HOST", "127.0.0.1")
    PORT = int(os.getenv("ANALYZER_PORT", "5050"))
    DEBUG = os.getenv("ANALYZER_DEBUG", "").lower() in {"1", "true", "yes"}
    JSON_SORT_KEYS = False
