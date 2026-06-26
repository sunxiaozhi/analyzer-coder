from __future__ import annotations

from pathlib import Path
from typing import Any

from flask.config import Config

from java_analyzer.analyzer import JavaAnalyzer
from java_analyzer.embedding import HashingEmbedder
from web.backend.errors import APIError
from web.backend.external_stores import (
    ExternalAnalysisStores,
    ExternalStoreConfig,
    Neo4jConfig,
    QdrantConfig,
)
from web.backend.mysql_state import MySqlStateStore
from web.backend.paths import workspace_path
from web.backend.service_mixins.analysis import AnalysisServiceMixin
from web.backend.service_mixins.auth import AuthServiceMixin
from web.backend.service_mixins.graph import GraphServiceMixin
from web.backend.service_mixins.indexing import IndexServiceMixin
from web.backend.service_mixins.knowledge import KnowledgeServiceMixin
from web.backend.service_mixins.projects import ProjectServiceMixin
from web.backend.service_mixins.shared import SharedServiceMixin
from web.backend.service_models import (
    AnalysisContext,
    KnowledgeAsset,
    ProjectRecord,
    UserRecord,
)

__all__ = [
    "AnalysisContext",
    "AnalyzerService",
    "KnowledgeAsset",
    "ProjectRecord",
    "UserRecord",
]


# 对外保留单一服务入口，路由层只依赖 AnalyzerService。
# 具体能力按 mixin 拆分，便于按功能阅读和测试。
class AnalyzerService(
    AuthServiceMixin,
    ProjectServiceMixin,
    AnalysisServiceMixin,
    GraphServiceMixin,
    IndexServiceMixin,
    KnowledgeServiceMixin,
    SharedServiceMixin,
):
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
        # 业务状态只落 MySQL；测试可注入内存实现来复用同一套服务逻辑。
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
            # 向量和图数据不再回退到本地文件，缺少外部存储应尽早失败。
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
