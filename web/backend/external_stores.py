from __future__ import annotations

import base64
import json
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen

from java_analyzer.vector_store import VectorRecord


class ExternalStoreError(RuntimeError):
    pass


QDRANT_POINT_BATCH_SIZE = 128
NEO4J_RECORD_BATCH_SIZE = 250


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
        qdrant = self._sync_one("qdrant", lambda: self._sync_qdrant(project_id, vector_records))
        neo4j = self._sync_one("neo4j", lambda: self._sync_neo4j(project_id, analysis_records))
        return {
            "enabled": True,
            "ok": bool(qdrant.get("ok")) and bool(neo4j.get("ok")),
            "qdrant": qdrant,
            "neo4j": neo4j,
        }

    def _sync_one(self, store_name: str, operation: Callable[[], dict[str, Any]]) -> dict[str, Any]:
        try:
            return operation()
        except ExternalStoreError as exc:
            return {"ok": False, "store": store_name, "error": str(exc)}

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
        batch_count = 0
        for batch in _batched(points, QDRANT_POINT_BATCH_SIZE):
            batch_count += 1
            self._qdrant_request(
                "PUT",
                f"/collections/{collection}/points?wait=true",
                {"points": batch},
            )
        return {
            "ok": True,
            "count": len(points),
            "batches": batch_count,
            "collection": self.config.qdrant.collection,
        }

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
        self._ensure_neo4j_schema()
        self._neo4j_request(
            [
                {
                    "statement": """
MERGE (p:AnalyzerProject {id: $projectId})
SET p.updatedAt = datetime()
""",
                    "parameters": {"projectId": project_id},
                }
            ]
        )
        batch_count = 0
        for batch in _batched(normalized, NEO4J_RECORD_BATCH_SIZE):
            batch_count += 1
            batch_file_records = [
                {"filePath": record["filePath"], "key": record["key"]}
                for record in batch
                if record["filePath"]
            ]
            batch_files = sorted({item["filePath"] for item in batch_file_records})
            statements = [
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
                    "parameters": {"projectId": project_id, "records": batch},
                },
                {
                    "statement": """
UNWIND $files AS filePath
MERGE (f:JavaFile {projectId: $projectId, path: filePath})
SET f.updatedAt = datetime()
""",
                    "parameters": {"projectId": project_id, "files": batch_files},
                },
                {
                    "statement": """
UNWIND $items AS item
MATCH (f:JavaFile {projectId: $projectId, path: item.filePath})
MATCH (r:AnalysisRecord {projectId: $projectId, key: item.key})
MERGE (f)-[:CONTAINS]->(r)
""",
                    "parameters": {"projectId": project_id, "items": batch_file_records},
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
                    "parameters": {"projectId": project_id, "records": batch},
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
                    "parameters": {"projectId": project_id, "records": batch},
                },
            ]
            self._neo4j_request(statements)
        return {
            "ok": True,
            "recordCount": len(normalized),
            "fileCount": len(files),
            "batches": batch_count,
        }

    def _ensure_neo4j_schema(self) -> None:
        statements = [
            {
                "statement": """
CREATE CONSTRAINT analyzer_project_id IF NOT EXISTS
FOR (p:AnalyzerProject)
REQUIRE p.id IS UNIQUE
"""
            },
            {
                "statement": """
CREATE CONSTRAINT analysis_record_project_key IF NOT EXISTS
FOR (r:AnalysisRecord)
REQUIRE (r.projectId, r.key) IS UNIQUE
"""
            },
            {
                "statement": """
CREATE CONSTRAINT java_file_project_path IF NOT EXISTS
FOR (f:JavaFile)
REQUIRE (f.projectId, f.path) IS UNIQUE
"""
            },
            {
                "statement": """
CREATE INDEX analysis_record_parent_lookup IF NOT EXISTS
FOR (r:AnalysisRecord)
ON (r.projectId, r.filePath, r.type, r.symbolName)
"""
            },
        ]
        self._neo4j_request(statements)

    def sync_knowledge_links(
        self,
        *,
        project_id: str,
        assets: list[dict[str, Any]],
        links: list[dict[str, Any]],
    ) -> dict[str, Any]:
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
MATCH (asset:KnowledgeAsset {projectId: $projectId})
WHERE NOT asset.id IN $assetIds
DETACH DELETE asset
""",
                "parameters": {
                    "projectId": project_id,
                    "assetIds": [str(asset.get("id") or "") for asset in assets],
                },
            },
            {
                "statement": """
UNWIND $assets AS asset
MATCH (p:AnalyzerProject {id: $projectId})
MERGE (a:KnowledgeAsset {projectId: $projectId, id: asset.id})
SET a.type = asset.type,
    a.title = asset.title,
    a.status = asset.status,
    a.summary = asset.summary,
    a.sourcePath = asset.sourcePath,
    a.tags = asset.tags,
    a.updatedAt = datetime()
MERGE (p)-[:HAS_KNOWLEDGE_ASSET]->(a)
""",
                "parameters": {
                    "projectId": project_id,
                    "assets": [_neo4j_asset(asset) for asset in assets],
                },
            },
            {
                "statement": """
MATCH (asset:KnowledgeAsset {projectId: $projectId})-[rel:EVIDENCED_BY]->()
DELETE rel
""",
                "parameters": {"projectId": project_id},
            },
            {
                "statement": """
UNWIND $links AS link
MATCH (asset:KnowledgeAsset {projectId: $projectId, id: link.assetId})
MATCH (record:AnalysisRecord {projectId: $projectId, key: link.analysisRecordKey})
MERGE (asset)-[rel:EVIDENCED_BY]->(record)
SET rel.linkType = link.linkType,
    rel.evidenceType = link.evidenceType,
    rel.filePath = link.evidenceFilePath,
    rel.symbolName = link.evidenceSymbolName,
    rel.note = link.note,
    rel.updatedAt = datetime()
""",
                "parameters": {"projectId": project_id, "links": links},
            },
        ]
        self._neo4j_request(statements)
        return {"ok": True, "assetCount": len(assets), "linkCount": len(links)}

    def graph_overview(self, *, project_id: str) -> dict[str, Any]:
        data = self._neo4j_request(
            [
                {
                    "statement": """
MATCH (p:AnalyzerProject {id: $projectId})
OPTIONAL MATCH (p)-[:HAS_RECORD]->(record:AnalysisRecord)
WITH count(DISTINCT record) AS recordCount
OPTIONAL MATCH (file:JavaFile {projectId: $projectId})
WITH recordCount, count(DISTINCT file) AS fileCount
OPTIONAL MATCH (:AnalysisRecord {projectId: $projectId})-[rel]->(:AnalysisRecord {projectId: $projectId})
WITH recordCount, fileCount, count(rel) AS behaviorRelationCount
OPTIONAL MATCH (:JavaFile {projectId: $projectId})-[contains:CONTAINS]->(:AnalysisRecord {projectId: $projectId})
WITH recordCount, fileCount, behaviorRelationCount, count(contains) AS containsCount
RETURN recordCount, fileCount, behaviorRelationCount + containsCount AS relationCount
""",
                    "parameters": {"projectId": project_id},
                },
                {
                    "statement": """
MATCH (record:AnalysisRecord {projectId: $projectId})
RETURN record.type AS type, count(record) AS count
ORDER BY count DESC, type
""",
                    "parameters": {"projectId": project_id},
                },
                {
                    "statement": """
MATCH (left)-[rel]->(right {projectId: $projectId})
WHERE (left:AnalyzerProject OR left:JavaFile OR left:AnalysisRecord)
  AND coalesce(left.projectId, left.id) = $projectId
RETURN type(rel) AS type, count(rel) AS count
ORDER BY count DESC, type
""",
                    "parameters": {"projectId": project_id},
                },
            ]
        )
        summary = _neo4j_first_row(data, 0)
        return {
            "summary": {
                "analysisRecords": int(summary.get("recordCount") or 0),
                "javaFiles": int(summary.get("fileCount") or 0),
                "relations": int(summary.get("relationCount") or 0),
            },
            "recordTypes": _neo4j_rows(data, 1),
            "relationTypes": _neo4j_rows(data, 2),
        }

    def graph_records(
        self,
        *,
        project_id: str,
        record_type: str = "",
        query: str = "",
        limit: int = 100,
        offset: int = 0,
    ) -> dict[str, Any]:
        where = ["record.projectId = $projectId"]
        if record_type:
            where.append("record.type = $recordType")
        if query:
            where.append(
                """
(
  toLower(record.key) CONTAINS $query
  OR toLower(record.filePath) CONTAINS $query
  OR toLower(record.symbolName) CONTAINS $query
  OR toLower(record.enclosingType) CONTAINS $query
  OR toLower(record.enclosingMethod) CONTAINS $query
)
"""
            )
        where_clause = " AND ".join(where)
        parameters = {
            "projectId": project_id,
            "recordType": record_type,
            "query": query.lower(),
            "limit": limit,
            "offset": offset,
        }
        data = self._neo4j_request(
            [
                {
                    "statement": f"""
MATCH (record:AnalysisRecord)
WHERE {where_clause}
RETURN count(record) AS total
""",
                    "parameters": parameters,
                },
                {
                    "statement": f"""
MATCH (record:AnalysisRecord)
WHERE {where_clause}
RETURN record.key AS key,
       record.type AS type,
       record.filePath AS filePath,
       record.package AS package,
       record.symbolName AS symbolName,
       record.enclosingType AS enclosingType,
       record.enclosingMethod AS enclosingMethod,
       record.startLine AS startLine,
       record.endLine AS endLine
ORDER BY record.type, record.filePath, record.startLine, record.key
SKIP $offset
LIMIT $limit
""",
                    "parameters": parameters,
                },
            ]
        )
        total_row = _neo4j_first_row(data, 0)
        return {
            "records": _neo4j_rows(data, 1),
            "total": int(total_row.get("total") or 0),
            "limit": limit,
            "offset": offset,
        }

    def graph_relations(self, *, project_id: str, limit: int = 500) -> dict[str, Any]:
        data = self._neo4j_request(
            [
                {
                    "statement": """
MATCH (source)-[rel]->(target)
WHERE (source:AnalyzerProject OR source:JavaFile OR source:AnalysisRecord)
  AND (target:JavaFile OR target:AnalysisRecord)
  AND coalesce(source.projectId, source.id) = $projectId
  AND target.projectId = $projectId
RETURN
  labels(source)[0] AS sourceLabel,
  coalesce(source.key, source.path, source.id) AS sourceId,
  coalesce(source.symbolName, source.path, source.id) AS sourceName,
  type(rel) AS type,
  labels(target)[0] AS targetLabel,
  coalesce(target.key, target.path) AS targetId,
  coalesce(target.symbolName, target.path) AS targetName
ORDER BY type, sourceName, targetName
LIMIT $limit
""",
                    "parameters": {"projectId": project_id, "limit": limit},
                }
            ]
        )
        return {"relations": _neo4j_rows(data, 0), "limit": limit}

    def graph_chains(
        self,
        *,
        project_id: str,
        chain_type: str = "",
        query: str = "",
        limit: int = 100,
    ) -> dict[str, Any]:
        chain_type = chain_type if chain_type in {"endpoint", "method", "file"} else ""
        query_text = query.lower()
        pool_limit = max(limit * 3, 100)
        statements: list[dict[str, Any]] = []

        if not chain_type or chain_type == "endpoint":
            statements.append(
                {
                    "statement": """
MATCH (endpoint:AnalysisRecord {projectId: $projectId, type: 'endpoint'})
OPTIONAL MATCH (method:AnalysisRecord {
  projectId: $projectId,
  type: 'method',
  filePath: endpoint.filePath,
  symbolName: endpoint.symbolName
})
OPTIONAL MATCH (method)-[:OWNS_BEHAVIOR]->(child:AnalysisRecord {projectId: $projectId})
RETURN 'endpoint' AS type,
       'endpoint:' + endpoint.key AS id,
       endpoint.key AS key,
       endpoint.symbolName AS title,
       endpoint.filePath AS subtitle,
       endpoint.payloadJson AS payloadJson,
       endpoint.startLine AS startLine,
       1 + count(DISTINCT method) + count(DISTINCT child) AS nodeCount,
       count(DISTINCT method) + count(DISTINCT child) AS relationCount
ORDER BY endpoint.filePath, endpoint.startLine, endpoint.key
LIMIT $poolLimit
""",
                    "parameters": {"projectId": project_id, "poolLimit": pool_limit},
                }
            )

        if not chain_type or chain_type == "method":
            statements.append(
                {
                    "statement": """
MATCH (method:AnalysisRecord {projectId: $projectId, type: 'method'})
OPTIONAL MATCH (method)-[:OWNS_BEHAVIOR]->(child:AnalysisRecord {projectId: $projectId})
RETURN 'method' AS type,
       'method:' + method.key AS id,
       method.key AS key,
       method.symbolName AS title,
       method.filePath AS subtitle,
       method.payloadJson AS payloadJson,
       method.startLine AS startLine,
       1 + count(DISTINCT child) AS nodeCount,
       count(DISTINCT child) AS relationCount
ORDER BY relationCount DESC, method.filePath, method.startLine, method.key
LIMIT $poolLimit
""",
                    "parameters": {"projectId": project_id, "poolLimit": pool_limit},
                }
            )

        if not chain_type or chain_type == "file":
            statements.append(
                {
                    "statement": """
MATCH (file:JavaFile {projectId: $projectId})
OPTIONAL MATCH (file)-[:CONTAINS]->(record:AnalysisRecord {projectId: $projectId})
RETURN 'file' AS type,
       'file:' + file.path AS id,
       file.path AS key,
       file.path AS title,
       '' AS subtitle,
       '' AS payloadJson,
       0 AS startLine,
       1 + count(DISTINCT record) AS nodeCount,
       count(DISTINCT record) AS relationCount
ORDER BY relationCount DESC, file.path
LIMIT $poolLimit
""",
                    "parameters": {"projectId": project_id, "poolLimit": pool_limit},
                }
            )

        data = self._neo4j_request(statements) if statements else {"results": []}
        chains: list[dict[str, Any]] = []
        for index in range(len(statements)):
            for row in _neo4j_rows(data, index):
                chain = _graph_chain_summary(row)
                if query_text and query_text not in _graph_chain_search_text(chain):
                    continue
                chains.append(chain)

        chains.sort(key=lambda item: (-int(item.get("relationCount") or 0), str(item.get("title") or "")))
        return {
            "chains": chains[:limit],
            "limit": limit,
            "filters": {"type": chain_type, "query": query},
        }

    def graph_chain(self, *, project_id: str, chain_id: str) -> dict[str, Any]:
        kind, _, raw_key = chain_id.partition(":")
        if not raw_key or kind not in {"endpoint", "method", "file"}:
            raise ExternalStoreError(f"Unsupported graph chain id: {chain_id}")

        if kind == "endpoint":
            return self._graph_endpoint_chain(project_id=project_id, chain_id=chain_id, key=raw_key)
        if kind == "method":
            return self._graph_method_chain(project_id=project_id, chain_id=chain_id, key=raw_key)
        return self._graph_file_chain(project_id=project_id, chain_id=chain_id, path=raw_key)

    def _graph_endpoint_chain(self, *, project_id: str, chain_id: str, key: str) -> dict[str, Any]:
        data = self._neo4j_request(
            [
                {
                    "statement": """
MATCH (endpoint:AnalysisRecord {projectId: $projectId, key: $key})
OPTIONAL MATCH (file:JavaFile {projectId: $projectId, path: endpoint.filePath})
OPTIONAL MATCH (method:AnalysisRecord {
  projectId: $projectId,
  type: 'method',
  filePath: endpoint.filePath,
  symbolName: endpoint.symbolName
})
OPTIONAL MATCH (method)-[:OWNS_BEHAVIOR]->(child:AnalysisRecord {projectId: $projectId})
RETURN file.path AS filePath,
       endpoint.key AS endpointKey,
       endpoint.type AS endpointType,
       endpoint.filePath AS endpointFilePath,
       endpoint.symbolName AS endpointSymbolName,
       endpoint.enclosingType AS endpointEnclosingType,
       endpoint.enclosingMethod AS endpointEnclosingMethod,
       endpoint.startLine AS endpointStartLine,
       endpoint.endLine AS endpointEndLine,
       endpoint.payloadJson AS endpointPayloadJson,
       method.key AS methodKey,
       method.type AS methodType,
       method.filePath AS methodFilePath,
       method.symbolName AS methodSymbolName,
       method.enclosingType AS methodEnclosingType,
       method.enclosingMethod AS methodEnclosingMethod,
       method.startLine AS methodStartLine,
       method.endLine AS methodEndLine,
       method.payloadJson AS methodPayloadJson,
       child.key AS childKey,
       child.type AS childType,
       child.filePath AS childFilePath,
       child.symbolName AS childSymbolName,
       child.enclosingType AS childEnclosingType,
       child.enclosingMethod AS childEnclosingMethod,
       child.startLine AS childStartLine,
       child.endLine AS childEndLine,
       child.payloadJson AS childPayloadJson
ORDER BY child.startLine, child.key
LIMIT 120
""",
                    "parameters": {"projectId": project_id, "key": key},
                }
            ]
        )
        rows = _neo4j_rows(data, 0)
        nodes: dict[str, dict[str, Any]] = {}
        edges: dict[str, dict[str, str]] = {}
        first = rows[0] if rows else {}
        if first.get("filePath"):
            nodes[f"file:{first['filePath']}"] = _graph_file_node(str(first["filePath"]))
        if first.get("endpointKey"):
            endpoint_node = _graph_prefixed_record_node(first, "endpoint", "endpoint")
            nodes[endpoint_node["id"]] = endpoint_node
            if first.get("filePath"):
                _add_graph_edge(edges, f"file:{first['filePath']}", endpoint_node["id"], "CONTAINS")
        for row in rows:
            if row.get("methodKey"):
                method_node = _graph_prefixed_record_node(row, "method", "method")
                nodes[method_node["id"]] = method_node
                if first.get("endpointKey"):
                    _add_graph_edge(edges, f"record:{first['endpointKey']}", method_node["id"], "HANDLES")
            if row.get("childKey"):
                child_node = _graph_prefixed_record_node(row, "child", "behavior")
                nodes[child_node["id"]] = child_node
                if row.get("methodKey"):
                    _add_graph_edge(edges, f"record:{row['methodKey']}", child_node["id"], "OWNS_BEHAVIOR")

        return _graph_chain_detail(chain_id, "endpoint", first, list(nodes.values()), list(edges.values()))

    def _graph_method_chain(self, *, project_id: str, chain_id: str, key: str) -> dict[str, Any]:
        data = self._neo4j_request(
            [
                {
                    "statement": """
MATCH (method:AnalysisRecord {projectId: $projectId, key: $key})
OPTIONAL MATCH (file:JavaFile {projectId: $projectId, path: method.filePath})
OPTIONAL MATCH (method)-[:OWNS_BEHAVIOR]->(child:AnalysisRecord {projectId: $projectId})
RETURN file.path AS filePath,
       method.key AS methodKey,
       method.type AS methodType,
       method.filePath AS methodFilePath,
       method.symbolName AS methodSymbolName,
       method.enclosingType AS methodEnclosingType,
       method.enclosingMethod AS methodEnclosingMethod,
       method.startLine AS methodStartLine,
       method.endLine AS methodEndLine,
       method.payloadJson AS methodPayloadJson,
       child.key AS childKey,
       child.type AS childType,
       child.filePath AS childFilePath,
       child.symbolName AS childSymbolName,
       child.enclosingType AS childEnclosingType,
       child.enclosingMethod AS childEnclosingMethod,
       child.startLine AS childStartLine,
       child.endLine AS childEndLine,
       child.payloadJson AS childPayloadJson
ORDER BY child.startLine, child.key
LIMIT 120
""",
                    "parameters": {"projectId": project_id, "key": key},
                }
            ]
        )
        rows = _neo4j_rows(data, 0)
        nodes: dict[str, dict[str, Any]] = {}
        edges: dict[str, dict[str, str]] = {}
        first = rows[0] if rows else {}
        if first.get("filePath"):
            nodes[f"file:{first['filePath']}"] = _graph_file_node(str(first["filePath"]))
        if first.get("methodKey"):
            method_node = _graph_prefixed_record_node(first, "method", "method")
            nodes[method_node["id"]] = method_node
            if first.get("filePath"):
                _add_graph_edge(edges, f"file:{first['filePath']}", method_node["id"], "CONTAINS")
        for row in rows:
            if row.get("childKey"):
                child_node = _graph_prefixed_record_node(row, "child", "behavior")
                nodes[child_node["id"]] = child_node
                if row.get("methodKey"):
                    _add_graph_edge(edges, f"record:{row['methodKey']}", child_node["id"], "OWNS_BEHAVIOR")

        return _graph_chain_detail(chain_id, "method", first, list(nodes.values()), list(edges.values()))

    def _graph_file_chain(self, *, project_id: str, chain_id: str, path: str) -> dict[str, Any]:
        data = self._neo4j_request(
            [
                {
                    "statement": """
MATCH (file:JavaFile {projectId: $projectId, path: $path})
OPTIONAL MATCH (file)-[:CONTAINS]->(record:AnalysisRecord {projectId: $projectId})
RETURN file.path AS filePath,
       record.key AS recordKey,
       record.type AS recordType,
       record.filePath AS recordFilePath,
       record.symbolName AS recordSymbolName,
       record.enclosingType AS recordEnclosingType,
       record.enclosingMethod AS recordEnclosingMethod,
       record.startLine AS recordStartLine,
       record.endLine AS recordEndLine,
       record.payloadJson AS recordPayloadJson
ORDER BY record.startLine, record.type, record.key
LIMIT 160
""",
                    "parameters": {"projectId": project_id, "path": path},
                }
            ]
        )
        rows = _neo4j_rows(data, 0)
        nodes: dict[str, dict[str, Any]] = {}
        edges: dict[str, dict[str, str]] = {}
        first = rows[0] if rows else {"filePath": path}
        nodes[f"file:{path}"] = _graph_file_node(path)
        for row in rows:
            if row.get("recordKey"):
                record_node = _graph_prefixed_record_node(row, "record", "record")
                nodes[record_node["id"]] = record_node
                _add_graph_edge(edges, f"file:{path}", record_node["id"], "CONTAINS")

        return _graph_chain_detail(chain_id, "file", first, list(nodes.values()), list(edges.values()))

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


def _batched(items: Iterable[Any], size: int) -> Iterable[list[Any]]:
    batch: list[Any] = []
    for item in items:
        batch.append(item)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def _neo4j_rows(data: Any, result_index: int) -> list[dict[str, Any]]:
    if not isinstance(data, dict):
        return []
    results = data.get("results")
    if not isinstance(results, list) or result_index >= len(results):
        return []
    result = results[result_index]
    if not isinstance(result, dict):
        return []
    columns = result.get("columns")
    rows = result.get("data")
    if not isinstance(columns, list) or not isinstance(rows, list):
        return []
    output: list[dict[str, Any]] = []
    for item in rows:
        if not isinstance(item, dict) or not isinstance(item.get("row"), list):
            continue
        output.append({str(column): item["row"][index] for index, column in enumerate(columns)})
    return output


def _neo4j_first_row(data: Any, result_index: int) -> dict[str, Any]:
    rows = _neo4j_rows(data, result_index)
    return rows[0] if rows else {}


def _graph_chain_summary(row: dict[str, Any]) -> dict[str, Any]:
    payload = _json_object(row.get("payloadJson"))
    chain_type = str(row.get("type") or "")
    title = str(row.get("title") or row.get("key") or "")
    if chain_type == "endpoint":
        method = payload.get("method") or payload.get("methods") or ""
        path = payload.get("path") or payload.get("value") or ""
        endpoint_text = " ".join(str(item) for item in [method, path] if item)
        if endpoint_text:
            title = endpoint_text
    return {
        "id": str(row.get("id") or ""),
        "type": chain_type,
        "title": title,
        "subtitle": str(row.get("subtitle") or ""),
        "nodeCount": int(row.get("nodeCount") or 0),
        "relationCount": int(row.get("relationCount") or 0),
        "startLine": int(row.get("startLine") or 0),
    }


def _graph_chain_search_text(chain: dict[str, Any]) -> str:
    return "\n".join(
        [
            str(chain.get("id") or ""),
            str(chain.get("type") or ""),
            str(chain.get("title") or ""),
            str(chain.get("subtitle") or ""),
        ]
    ).lower()


def _graph_file_node(path: str) -> dict[str, Any]:
    return {
        "id": f"file:{path}",
        "label": path.rsplit("/", 1)[-1] or path,
        "type": "file",
        "role": "file",
        "subtitle": path,
        "filePath": path,
        "symbolName": "",
        "startLine": 0,
        "endLine": 0,
    }


def _graph_prefixed_record_node(row: dict[str, Any], prefix: str, role: str) -> dict[str, Any]:
    key = str(row.get(f"{prefix}Key") or "")
    record_type = str(row.get(f"{prefix}Type") or role)
    file_path = str(row.get(f"{prefix}FilePath") or "")
    symbol_name = str(row.get(f"{prefix}SymbolName") or "")
    start_line = int(row.get(f"{prefix}StartLine") or 0)
    end_line = int(row.get(f"{prefix}EndLine") or 0)
    payload = _json_object(row.get(f"{prefix}PayloadJson"))
    label = symbol_name or key
    if record_type == "endpoint":
        endpoint = " ".join(
            str(item)
            for item in [payload.get("method") or payload.get("methods"), payload.get("path") or payload.get("value")]
            if item
        )
        if endpoint:
            label = endpoint
    subtitle_parts = [record_type]
    if file_path:
        subtitle_parts.append(file_path)
    if start_line:
        subtitle_parts.append(f"L{start_line}")
    return {
        "id": f"record:{key}",
        "label": label,
        "type": record_type,
        "role": role,
        "subtitle": " · ".join(subtitle_parts),
        "filePath": file_path,
        "symbolName": symbol_name,
        "enclosingType": str(row.get(f"{prefix}EnclosingType") or ""),
        "enclosingMethod": str(row.get(f"{prefix}EnclosingMethod") or ""),
        "startLine": start_line,
        "endLine": end_line,
    }


def _add_graph_edge(edges: dict[str, dict[str, str]], source: str, target: str, edge_type: str) -> None:
    if not source or not target or source == target:
        return
    edge_id = f"{source}->{edge_type}->{target}"
    edges[edge_id] = {
        "id": edge_id,
        "source": source,
        "target": target,
        "type": edge_type,
    }


def _graph_chain_detail(
    chain_id: str,
    chain_type: str,
    row: dict[str, Any],
    nodes: list[dict[str, Any]],
    edges: list[dict[str, str]],
) -> dict[str, Any]:
    first_node = next((node for node in nodes if node.get("role") == chain_type), nodes[0] if nodes else {})
    return {
        "chain": {
            "id": chain_id,
            "type": chain_type,
            "title": str(first_node.get("label") or row.get("filePath") or chain_id),
            "subtitle": str(first_node.get("subtitle") or ""),
            "nodeCount": len(nodes),
            "relationCount": len(edges),
            "startLine": int(row.get("startLine") or row.get("endpointStartLine") or row.get("methodStartLine") or 0),
        },
        "nodes": nodes,
        "edges": edges,
    }


def _json_object(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str) or not raw:
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


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


def _neo4j_asset(asset: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": str(asset.get("id") or ""),
        "type": str(asset.get("type") or ""),
        "title": str(asset.get("title") or ""),
        "status": str(asset.get("status") or ""),
        "summary": str(asset.get("summary") or ""),
        "sourcePath": str(asset.get("sourcePath") or ""),
        "tags": [str(tag) for tag in asset.get("tags") or []],
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
