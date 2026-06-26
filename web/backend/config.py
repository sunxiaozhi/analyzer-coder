from __future__ import annotations

import os
from pathlib import Path


class BackendConfig:
    # 工作区路径配置：限制项目仓库、代码分析和文件访问的根目录。
    WORKSPACE_ROOT = Path(os.getenv("ANALYZER_WORKSPACE_ROOT", Path(__file__).resolve().parents[2])).resolve()
    PROJECTS_DIR = Path(os.getenv("ANALYZER_PROJECTS_DIR", ".analzer_projects"))

    # MySQL 配置：保存用户、项目、知识、分析记录和分析输出。
    MYSQL_HOST = os.getenv("ANALYZER_MYSQL_HOST", "127.0.0.1")
    MYSQL_PORT = int(os.getenv("ANALYZER_MYSQL_PORT", "3806"))
    MYSQL_USER = os.getenv("ANALYZER_MYSQL_USER", "analyzer_coder")
    MYSQL_PASSWORD = os.getenv("ANALYZER_MYSQL_PASSWORD", "analyzer_coder")
    MYSQL_DATABASE = os.getenv("ANALYZER_MYSQL_DATABASE", "analyzer_coder")

    # Qdrant 配置：保存语义向量和可过滤检索 payload。
    QDRANT_URL = os.getenv("ANALYZER_QDRANT_URL", "http://127.0.0.1:6333")
    QDRANT_COLLECTION = os.getenv("ANALYZER_QDRANT_COLLECTION", "analyzer_coder")
    QDRANT_API_KEY = os.getenv("ANALYZER_QDRANT_API_KEY", "analyzer_coder")

    # Neo4j 配置：保存代码、知识和证据之间的图关系。
    NEO4J_URL = os.getenv("ANALYZER_NEO4J_URL", "http://127.0.0.1:7474")
    NEO4J_DATABASE = os.getenv("ANALYZER_NEO4J_DATABASE", "neo4j")
    NEO4J_USERNAME = os.getenv("ANALYZER_NEO4J_USERNAME", "neo4j")
    NEO4J_PASSWORD = os.getenv("ANALYZER_NEO4J_PASSWORD", "analyzer_coder")

    # 外部存储请求超时：统一控制 Qdrant 和 Neo4j HTTP 调用等待时间。
    EXTERNAL_STORE_TIMEOUT = float(os.getenv("ANALYZER_EXTERNAL_STORE_TIMEOUT", "30"))

    # Flask 与默认管理员配置。
    SECRET_KEY = os.getenv("ANALYZER_SECRET_KEY", "local-dev-secret")
    ADMIN_USERNAME = os.getenv("ANALYZER_ADMIN_USERNAME", "admin")
    ADMIN_PASSWORD = os.getenv("ANALYZER_ADMIN_PASSWORD", "admin123")

    # 后端服务监听配置。
    HOST = os.getenv("ANALYZER_HOST", "127.0.0.1")
    PORT = int(os.getenv("ANALYZER_PORT", "5050"))
    DEBUG = os.getenv("ANALYZER_DEBUG", "").lower() in {"1", "true", "yes"}
