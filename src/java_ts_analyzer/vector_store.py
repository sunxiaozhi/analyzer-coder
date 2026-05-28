from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable

from java_ts_analyzer.embedding import HashingEmbedder, cosine_similarity
from java_ts_analyzer.models import JavaVectorChunk


@dataclass(frozen=True)
class VectorRecord:
    id: str
    text: str
    metadata: dict[str, Any]
    embedding: list[float]


@dataclass(frozen=True)
class SearchResult:
    id: str
    score: float
    text: str
    metadata: dict[str, Any]


class JsonlVectorStore:
    """用于确定性开发和测试的本地向量库。"""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def write_chunks(
        self,
        chunks: Iterable[JavaVectorChunk],
        embedder: HashingEmbedder | None = None,
    ) -> list[VectorRecord]:
        # 覆盖写入适合完整重建索引。
        records = self._records_for_chunks(chunks, embedder=embedder)
        self._write_records(records)
        return records

    def upsert_chunks(
        self,
        chunks: Iterable[JavaVectorChunk],
        embedder: HashingEmbedder | None = None,
    ) -> list[VectorRecord]:
        # upsert 会保留当前索引轮次没有重新生成、但 ID 仍存在的旧记录。
        new_records = self._records_for_chunks(chunks, embedder=embedder)
        records_by_id = {record.id: record for record in self.read_records()}
        records_by_id.update({record.id: record for record in new_records})
        self._write_records(records_by_id.values())
        return new_records

    def _write_records(self, records: Iterable[VectorRecord]) -> None:
        # JSONL 便于人工检查，也方便测试直接读取。
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("w", encoding="utf-8") as file:
            for record in records:
                file.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")

    def _records_for_chunks(
        self,
        chunks: Iterable[JavaVectorChunk],
        embedder: HashingEmbedder | None = None,
    ) -> list[VectorRecord]:
        # 先把 chunks 物化一次，因为向量化和记录构建都需要遍历同一组数据。
        embedder = embedder or HashingEmbedder()
        chunk_list = list(chunks)
        embeddings = embedder.embed_many([chunk.text for chunk in chunk_list])
        return [
            VectorRecord(
                id=chunk.id,
                text=chunk.text,
                metadata=chunk.metadata,
                embedding=embedding,
            )
            for chunk, embedding in zip(chunk_list, embeddings, strict=True)
        ]

    def read_records(self) -> list[VectorRecord]:
        if not self.path.exists():
            return []
        records: list[VectorRecord] = []
        with self.path.open("r", encoding="utf-8") as file:
            for line in file:
                if not line.strip():
                    continue
                data = json.loads(line)
                records.append(
                    VectorRecord(
                        id=data["id"],
                        text=data["text"],
                        metadata=data["metadata"],
                        embedding=data["embedding"],
                    )
                )
        return records

    def search(
        self,
        query: str,
        top_k: int = 5,
        metadata_filter: dict[str, Any] | None = None,
        embedder: HashingEmbedder | None = None,
    ) -> list[SearchResult]:
        # 排序前先应用 metadata 过滤，以支持只查代码或只查知识库。
        embedder = embedder or HashingEmbedder()
        query_embedding = embedder.embed(query)
        results = [
            SearchResult(
                id=record.id,
                score=cosine_similarity(query_embedding, record.embedding),
                text=record.text,
                metadata=record.metadata,
            )
            for record in self.read_records()
            if _matches_filter(record.metadata, metadata_filter)
        ]
        return sorted(results, key=lambda item: item.score, reverse=True)[:top_k]


def _matches_filter(metadata: dict[str, Any], metadata_filter: dict[str, Any] | None) -> bool:
    if not metadata_filter:
        return True
    return all(metadata.get(key) == value for key, value in metadata_filter.items())
