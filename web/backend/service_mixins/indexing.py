from __future__ import annotations

from pathlib import Path
from typing import Any

from java_analyzer.chunker import build_chunks
from java_analyzer.models import JavaVectorChunk
from java_analyzer.vector_store import SearchResult, VectorRecord

from web.backend.errors import APIError
from web.backend.fusion import build_evidence, serialize_result
from web.backend.rag import build_rag_flow
from web.backend.service_models import AnalysisContext, KB_EXTENSIONS, utc_now
from web.backend.validation import source_value


class IndexServiceMixin:
    def index_project(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        source = source_value(payload)
        context = self._analysis_context(payload, user)
        append = bool(payload.get("append", False))

        # 代码、知识文档和知识资产统一转成向量记录后写入 Qdrant。
        chunks = self._build_source_chunks(context, payload, source)
        records = self._vector_records_for_chunks(chunks)
        if not append:
            # 非追加索引代表重建当前项目向量，先清掉 Qdrant 中的项目旧数据。
            self.external_stores.delete_qdrant_project_records(project_id=context.project_id)
        sync_result = self.external_stores.sync(project_id=context.project_id, analysis_records=[], vector_records=records)
        store_label = self._vector_store_label()
        message = f"已将 {len(records)} 个切块写入 {store_label}"
        return {
            "message": message,
            "count": len(records),
            "store": store_label,
            "savedPath": "",
            "projectId": context.project_id,
            "externalSync": sync_result,
        }

    def index_status(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        records = self._load_vector_records(context)
        if not records:
            return {
                "exists": False,
                "store": self._vector_store_label(),
                "size": 0,
                "updatedAt": "",
                "total": 0,
                "sources": {},
                "kinds": {},
                "projectId": context.project_id,
            }

        sources: dict[str, int] = {}
        kinds: dict[str, int] = {}
        for record in records:
            source_type = str(record.metadata.get("source_type") or "unknown")
            kind = str(record.metadata.get("kind") or "unknown")
            sources[source_type] = sources.get(source_type, 0) + 1
            kinds[kind] = kinds.get(kind, 0) + 1
        return {
            "exists": True,
            "store": self._vector_store_label(),
            "size": 0,
            "updatedAt": utc_now(),
            "total": len(records),
            "sources": sources,
            "kinds": kinds,
            "projectId": context.project_id,
        }

    def index_records(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        limit = max(1, min(int(payload.get("limit") or 50), 200))
        offset = max(0, int(payload.get("offset") or 0))
        source_filter = str(payload.get("source") or "").strip()
        kind_filter = str(payload.get("kind") or "").strip()
        query = str(payload.get("query") or "").strip().lower()

        records = self._load_vector_records(context)
        if not records:
            return {
                "records": [],
                "total": 0,
                "limit": limit,
                "offset": offset,
                "store": self._vector_store_label(),
                "projectId": context.project_id,
            }

        filtered = []
        for record in records:
            metadata = record.metadata
            source_type = str(metadata.get("source_type") or "")
            kind = str(metadata.get("kind") or "")
            if source_filter and source_type != source_filter:
                continue
            if kind_filter and kind != kind_filter:
                continue
            haystack = "\n".join(
                [
                    record.id,
                    record.text,
                    str(metadata.get("file_path") or ""),
                    str(metadata.get("symbol_name") or ""),
                    str(metadata.get("type_name") or ""),
                ]
            ).lower()
            if query and query not in haystack:
                continue
            filtered.append(record)

        page = filtered[offset : offset + limit]
        return {
            "records": [
                {
                    "id": record.id,
                    "text": record.text,
                    "metadata": record.metadata,
                }
                for record in page
            ],
            "total": len(filtered),
            "limit": limit,
            "offset": offset,
            "store": self._vector_store_label(),
            "projectId": context.project_id,
        }

    def query(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        return self._query(payload, user, include_rag=True)

    def rag_search(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        return self._query(payload, user, include_rag=True)

    def _query(self, payload: dict[str, Any], user: "UserRecord", *, include_rag: bool) -> dict[str, Any]:
        query_text = str(payload.get("query", "")).strip()
        if not query_text:
            raise APIError("query is required.", 400)

        context = self._analysis_context(payload, user)
        top_k = int(payload.get("topK", 5))
        filter_source = payload.get("filterSource")
        source_type = str(filter_source) if filter_source in {"code", "kb", "knowledge_asset"} else ""
        query_embedding = self.embedder.embed(query_text)
        # 搜索只命中 Qdrant；MySQL 记录用于补充证据和调试展示。
        qdrant_results = self.external_stores.search_qdrant(
            project_id=context.project_id,
            query_vector=query_embedding,
            top_k=top_k,
            source_type=source_type,
        )
        results = [
            SearchResult(
                id=str(item.get("id") or ""),
                score=float(item.get("score") or 0),
                text=str(item.get("text") or ""),
                metadata=dict(item.get("metadata") or {}),
            )
            for item in qdrant_results
        ]
        records = self._load_vector_records(context)
        response = [serialize_result(result) for result in results]
        evidence = build_evidence(query_text, results, records)
        rag = build_rag_flow(query_text, results, evidence) if include_rag else None
        output = {
            "results": response,
            "evidence": evidence,
            "savedPath": "",
            "store": self._vector_store_label(),
            "projectId": context.project_id,
        }
        if rag is not None:
            output["rag"] = rag
        return output

    def _vector_records_for_chunks(self, chunks: list[Any]) -> list[VectorRecord]:
        chunk_list = list(chunks)
        embeddings = self.embedder.embed_many([chunk.text for chunk in chunk_list])
        return [
            VectorRecord(
                id=chunk.id,
                text=chunk.text,
                metadata=chunk.metadata,
                embedding=embedding,
            )
            for chunk, embedding in zip(chunk_list, embeddings, strict=True)
        ]

    def _load_vector_records(self, context: "AnalysisContext", limit: int = 5000) -> list[VectorRecord]:
        return [
            VectorRecord(
                id=str(item.get("id") or ""),
                text=str(item.get("text") or ""),
                metadata=dict(item.get("metadata") or {}),
                embedding=[],
            )
            for item in self.external_stores.list_qdrant_records(project_id=context.project_id, limit=limit)
        ]

    def _vector_store_label(self) -> str:
        collection = self.external_stores.config.qdrant.collection
        return f"qdrant://{collection}"

    def _build_source_chunks(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        source: str,
    ) -> list[Any]:
        chunks: list[Any] = []
        if source in {"code", "mixed"}:
            code_target = self._source_target(context, payload, "code")
            chunks.extend(
                chunk
                for result in self.analyzer.analyze_path(code_target)
                for chunk in build_chunks(result)
            )
        if source in {"kb", "mixed"}:
            # 知识输入来自 MySQL，不再扫描本地知识库目录。
            chunks.extend(self._build_knowledge_document_chunks(context, payload))
            chunks.extend(self._build_knowledge_asset_chunks(context))
        return chunks

    def _build_knowledge_document_chunks(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
    ) -> list[JavaVectorChunk]:
        prefix = self._kb_prefix(payload)
        documents = self.mysql_store.list_knowledge_documents(context.project_id, prefix=prefix)
        chunks: list[JavaVectorChunk] = []
        for document in documents:
            loaded = self.mysql_store.get_knowledge_document(context.project_id, str(document.get("path") or ""))
            if not loaded:
                continue
            document_path = str(loaded["path"])
            chunks.append(
                JavaVectorChunk(
                    id=f"kb:{document_path}",
                    text=str(loaded["content"]),
                    metadata={
                        "source_type": "kb",
                        "kind": "knowledge_document",
                        "file_path": document_path,
                        "title": str(loaded["name"]),
                        "start_line": 1,
                    },
                )
            )
        return chunks

    def _build_knowledge_asset_chunks(self, context: "AnalysisContext") -> list[JavaVectorChunk]:
        chunks: list[JavaVectorChunk] = []
        for asset in self._load_knowledge_assets(context):
            # 将结构化证据并入文本，保证只靠向量命中也能看到依据线索。
            evidence_lines = [
                " - ".join(
                    part
                    for part in [
                        str(evidence.get("type") or ""),
                        str(evidence.get("filePath") or ""),
                        str(evidence.get("symbolName") or ""),
                        str(evidence.get("note") or ""),
                    ]
                    if part
                )
                for evidence in asset.evidence
            ]
            text = "\n".join(
                part
                for part in [
                    f"Knowledge asset: {asset.title}",
                    f"Type: {asset.type}",
                    f"Status: {asset.status}",
                    f"Summary: {asset.summary}" if asset.summary else "",
                    f"Tags: {', '.join(asset.tags)}" if asset.tags else "",
                    asset.content,
                    "Evidence:\n" + "\n".join(evidence_lines) if evidence_lines else "",
                ]
                if part
            )
            chunks.append(
                JavaVectorChunk(
                    id=f"knowledge_asset:{asset.id}",
                    text=text,
                    metadata={
                        "source_type": "knowledge_asset",
                        "kind": asset.type,
                        "asset_id": asset.id,
                        "title": asset.title,
                        "status": asset.status,
                        "owner_user_id": asset.ownerUserId,
                        "reviewer_user_id": asset.reviewerUserId,
                        "tags": asset.tags,
                        "file_path": asset.sourcePath,
                        "start_line": 1,
                    },
                )
            )
        return chunks

    def _source_target(self, context: "AnalysisContext", payload: dict[str, Any], source: str) -> Path:
        fallback = payload.get("path", ".")
        key = "codePath" if source == "code" else "kbPath"
        # 所有入口路径最终都经过 AnalysisContext.path 的 workspace 限制。
        return context.path(payload.get(key) or fallback)
