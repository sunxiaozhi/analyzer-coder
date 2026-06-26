from __future__ import annotations

from types import SimpleNamespace

from java_analyzer.vector_store import VectorRecord
from web.backend.external_stores import ExternalAnalysisStores, ExternalStoreError


class PartialFailureStores(ExternalAnalysisStores):
    def __init__(self, *, fail_qdrant: bool = False, fail_neo4j: bool = False) -> None:
        self.config = SimpleNamespace(
            qdrant=SimpleNamespace(collection="test_chunks"),
            neo4j=SimpleNamespace(database="neo4j"),
        )
        self.fail_qdrant = fail_qdrant
        self.fail_neo4j = fail_neo4j
        self.qdrant_called = False
        self.neo4j_called = False

    def _sync_qdrant(self, project_id: str, records: list[VectorRecord]) -> dict[str, object]:
        self.qdrant_called = True
        if self.fail_qdrant:
            raise ExternalStoreError("qdrant timed out")
        return {"ok": True, "count": len(records)}

    def _sync_neo4j(self, project_id: str, records: list[dict[str, object]]) -> dict[str, object]:
        self.neo4j_called = True
        if self.fail_neo4j:
            raise ExternalStoreError("neo4j timed out")
        return {"ok": True, "recordCount": len(records)}


def test_external_sync_continues_to_neo4j_when_qdrant_fails() -> None:
    stores = PartialFailureStores(fail_qdrant=True)

    result = stores.sync(
        project_id="demo",
        analysis_records=[{"key": "src/Demo.java::file", "type": "file"}],
        vector_records=[
            VectorRecord(
                id="chunk-1",
                text="Demo",
                metadata={"source_type": "code"},
                embedding=[1.0, 0.0],
            )
        ],
    )

    assert stores.qdrant_called is True
    assert stores.neo4j_called is True
    assert result["ok"] is False
    assert result["qdrant"]["ok"] is False
    assert result["qdrant"]["error"] == "qdrant timed out"
    assert result["neo4j"] == {"ok": True, "recordCount": 1}


def test_external_sync_reports_neo4j_failure_without_hiding_qdrant_success() -> None:
    stores = PartialFailureStores(fail_neo4j=True)

    result = stores.sync(
        project_id="demo",
        analysis_records=[{"key": "src/Demo.java::file", "type": "file"}],
        vector_records=[],
    )

    assert stores.qdrant_called is True
    assert stores.neo4j_called is True
    assert result["ok"] is False
    assert result["qdrant"] == {"ok": True, "count": 0}
    assert result["neo4j"]["ok"] is False
    assert result["neo4j"]["error"] == "neo4j timed out"
