from __future__ import annotations

import base64
import json
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen

from java_analyzer.vector_store import VectorRecord


class ExternalStoreError(RuntimeError):
    pass


@dataclass(frozen=True)
class QdrantConfig:
    url: str
    collection: str
    api_key: str = ""
    timeout: float = 5.0


@dataclass(frozen=True)
class Neo4jConfig:
    url: str
    database: str
    username: str
    password: str
    timeout: float = 5.0


@dataclass(frozen=True)
class ExternalStoreConfig:
    qdrant: QdrantConfig
    neo4j: Neo4jConfig


class ExternalAnalysisStores:
    def __init__(self, config: ExternalStoreConfig) -> None:
        self.config = config

    def sync(
        self,
        *,
        project_id: str,
        analysis_records: list[dict[str, Any]],
        vector_records: list[VectorRecord],
    ) -> dict[str, Any]:
        qdrant = self._sync_qdrant(project_id, vector_records)
        neo4j = self._sync_neo4j(project_id, analysis_records)
        return {
            "enabled": True,
            "qdrant": qdrant,
            "neo4j": neo4j,
        }

    def _sync_qdrant(self, project_id: str, records: list[VectorRecord]) -> dict[str, Any]:
        if not records:
            return {"ok": True, "count": 0, "collection": self.config.qdrant.collection}

        vector_size = len(records[0].embedding)
        collection = quote(self.config.qdrant.collection, safe="")
        self._ensure_qdrant_collection(collection, vector_size)
        points = [
            {
                "id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{project_id}:{record.id}")),
                "vector": record.embedding,
                "payload": {
                    "projectId": project_id,
                    "recordId": record.id,
                    "text": record.text,
                    **record.metadata,
                },
            }
            for record in records
        ]
        self._qdrant_request(
            "PUT",
            f"/collections/{collection}/points?wait=true",
            {"points": points},
        )
        return {"ok": True, "count": len(points), "collection": self.config.qdrant.collection}

    def delete_qdrant_project_records(self, *, project_id: str, source_type: str = "") -> dict[str, Any]:
        collection = quote(self.config.qdrant.collection, safe="")
        must = [{"key": "projectId", "match": {"value": project_id}}]
        if source_type:
            must.append({"key": "source_type", "match": {"value": source_type}})
        try:
            self._qdrant_request(
                "POST",
                f"/collections/{collection}/points/delete?wait=true",
                {"filter": {"must": must}},
            )
        except ExternalStoreError as exc:
            if "HTTP 404" not in str(exc):
                raise
        return {"ok": True, "collection": self.config.qdrant.collection}

    def search_qdrant(
        self,
        *,
        project_id: str,
        query_vector: list[float],
        top_k: int,
        source_type: str = "",
    ) -> list[dict[str, Any]]:
        collection = quote(self.config.qdrant.collection, safe="")
        must = [{"key": "projectId", "match": {"value": project_id}}]
        if source_type:
            must.append({"key": "source_type", "match": {"value": source_type}})
        data = self._qdrant_request(
            "POST",
            f"/collections/{collection}/points/search",
            {
                "vector": query_vector,
                "limit": top_k,
                "with_payload": True,
                "with_vector": False,
                "filter": {"must": must},
            },
        )
        points = data.get("result", []) if isinstance(data, dict) else []
        return [_record_from_qdrant_point(point) for point in points]

    def list_qdrant_records(
        self,
        *,
        project_id: str,
        limit: int = 1000,
        source_type: str = "",
    ) -> list[dict[str, Any]]:
        collection = quote(self.config.qdrant.collection, safe="")
        must = [{"key": "projectId", "match": {"value": project_id}}]
        if source_type:
            must.append({"key": "source_type", "match": {"value": source_type}})
        data = self._qdrant_request(
            "POST",
            f"/collections/{collection}/points/scroll",
            {
                "limit": limit,
                "with_payload": True,
                "with_vector": False,
                "filter": {"must": must},
            },
        )
        result = data.get("result", {}) if isinstance(data, dict) else {}
        points = result.get("points", []) if isinstance(result, dict) else []
        return [_record_from_qdrant_point(point) for point in points]

    def _ensure_qdrant_collection(self, collection: str, vector_size: int) -> None:
        try:
            self._qdrant_request("GET", f"/collections/{collection}")
            return
        except ExternalStoreError as exc:
            if "HTTP 404" not in str(exc):
                raise
        self._qdrant_request(
            "PUT",
            f"/collections/{collection}",
            {"vectors": {"size": vector_size, "distance": "Cosine"}},
        )

    def _sync_neo4j(self, project_id: str, records: list[dict[str, Any]]) -> dict[str, Any]:
        normalized = [_neo4j_record(record) for record in records]
        file_records = [
            {"filePath": record["filePath"], "key": record["key"]}
            for record in normalized
            if record["filePath"]
        ]
        files = sorted({item["filePath"] for item in file_records})
        statements = [
            {
                "statement": """
MERGE (p:AnalyzerProject {id: $projectId})
SET p.updatedAt = datetime()
""",
                "parameters": {"projectId": project_id},
            },
            {
                "statement": """
UNWIND $records AS record
MATCH (p:AnalyzerProject {id: $projectId})
MERGE (r:AnalysisRecord {projectId: $projectId, key: record.key})
SET r.type = record.type,
    r.filePath = record.filePath,
    r.package = record.package,
    r.symbolName = record.symbolName,
    r.enclosingType = record.enclosingType,
    r.enclosingMethod = record.enclosingMethod,
    r.startLine = record.startLine,
    r.startColumn = record.startColumn,
    r.endLine = record.endLine,
    r.endColumn = record.endColumn,
    r.payloadJson = record.payloadJson,
    r.updatedAt = datetime()
MERGE (p)-[:HAS_RECORD]->(r)
""",
                "parameters": {"projectId": project_id, "records": normalized},
            },
            {
                "statement": """
UNWIND $files AS filePath
MERGE (f:JavaFile {projectId: $projectId, path: filePath})
SET f.updatedAt = datetime()
""",
                "parameters": {"projectId": project_id, "files": files},
            },
            {
                "statement": """
UNWIND $items AS item
MATCH (f:JavaFile {projectId: $projectId, path: item.filePath})
MATCH (r:AnalysisRecord {projectId: $projectId, key: item.key})
MERGE (f)-[:CONTAINS]->(r)
""",
                "parameters": {"projectId": project_id, "items": file_records},
            },
            {
                "statement": """
UNWIND $records AS record
WITH record
WHERE record.enclosingType <> ''
MATCH (parent:AnalysisRecord {
  projectId: $projectId,
  filePath: record.filePath,
  type: 'type',
  symbolName: record.enclosingType
})
MATCH (child:AnalysisRecord {projectId: $projectId, key: record.key})
MERGE (parent)-[:ENCLOSES]->(child)
""",
                "parameters": {"projectId": project_id, "records": normalized},
            },
            {
                "statement": """
UNWIND $records AS record
WITH record
WHERE record.enclosingMethod <> ''
MATCH (method:AnalysisRecord {
  projectId: $projectId,
  filePath: record.filePath,
  type: 'method',
  symbolName: record.enclosingMethod
})
MATCH (child:AnalysisRecord {projectId: $projectId, key: record.key})
MERGE (method)-[:OWNS_BEHAVIOR]->(child)
""",
                "parameters": {"projectId": project_id, "records": normalized},
            },
        ]
        self._neo4j_request(statements)
        return {"ok": True, "recordCount": len(normalized), "fileCount": len(files)}

    def _qdrant_request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        url = urljoin(self.config.qdrant.url.rstrip("/") + "/", path.lstrip("/"))
        headers = {"api-key": self.config.qdrant.api_key} if self.config.qdrant.api_key else None
        return _json_request(method, url, payload, headers=headers, timeout=self.config.qdrant.timeout)

    def _neo4j_request(self, statements: list[dict[str, Any]]) -> Any:
        database = quote(self.config.neo4j.database, safe="")
        url = urljoin(self.config.neo4j.url.rstrip("/") + "/", f"db/{database}/tx/commit")
        token = base64.b64encode(
            f"{self.config.neo4j.username}:{self.config.neo4j.password}".encode("utf-8")
        ).decode("ascii")
        data = _json_request(
            "POST",
            url,
            {"statements": statements},
            headers={"Authorization": f"Basic {token}"},
            timeout=self.config.neo4j.timeout,
        )
        errors = data.get("errors") if isinstance(data, dict) else None
        if errors:
            raise ExternalStoreError(f"Neo4j returned errors: {errors}")
        return data


def _json_request(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    *,
    headers: dict[str, str] | None = None,
    timeout: float,
) -> Any:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request_headers = {"Accept": "application/json"}
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)
    request = Request(url, data=body, headers=request_headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            content = response.read().decode("utf-8")
            return json.loads(content) if content else {}
    except HTTPError as exc:
        message = exc.read().decode("utf-8", errors="replace")
        raise ExternalStoreError(f"HTTP {exc.code} from {url}: {message}") from exc
    except (OSError, URLError, TimeoutError) as exc:
        raise ExternalStoreError(f"Cannot reach {url}: {exc}") from exc


def _neo4j_record(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "key": str(record.get("key") or ""),
        "type": str(record.get("type") or "unknown"),
        "filePath": str(record.get("filePath") or ""),
        "package": str(record.get("package") or ""),
        "symbolName": str(record.get("symbolName") or ""),
        "enclosingType": str(record.get("enclosingType") or ""),
        "enclosingMethod": str(record.get("enclosingMethod") or ""),
        "startLine": int(record.get("startLine") or 0),
        "startColumn": int(record.get("startColumn") or 0),
        "endLine": int(record.get("endLine") or 0),
        "endColumn": int(record.get("endColumn") or 0),
        "payloadJson": json.dumps(record.get("payload") or {}, ensure_ascii=False, sort_keys=True),
    }


def _record_from_qdrant_point(point: dict[str, Any]) -> dict[str, Any]:
    payload = dict(point.get("payload") or {})
    record_id = str(payload.pop("recordId", point.get("id", "")))
    text = str(payload.pop("text", ""))
    payload.pop("projectId", None)
    return {
        "id": record_id,
        "score": float(point.get("score") or 0),
        "text": text,
        "metadata": payload,
    }
