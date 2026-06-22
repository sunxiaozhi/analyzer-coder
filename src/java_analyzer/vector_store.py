from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

from java_analyzer.embedding import HashingEmbedder, cosine_similarity
from java_analyzer.models import JavaVectorChunk


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


def records_for_chunks(
    chunks: Iterable[JavaVectorChunk],
    embedder: HashingEmbedder | None = None,
) -> list[VectorRecord]:
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


def search_records(
    records: Iterable[VectorRecord],
    query: str,
    top_k: int = 5,
    metadata_filter: dict[str, Any] | None = None,
    embedder: HashingEmbedder | None = None,
) -> list[SearchResult]:
    embedder = embedder or HashingEmbedder()
    query_embedding = embedder.embed(query)
    results = [
        SearchResult(
            id=record.id,
            score=cosine_similarity(query_embedding, record.embedding),
            text=record.text,
            metadata=record.metadata,
        )
        for record in records
        if _matches_filter(record.metadata, metadata_filter)
    ]
    return sorted(results, key=lambda item: item.score, reverse=True)[:top_k]


def _matches_filter(metadata: dict[str, Any], metadata_filter: dict[str, Any] | None) -> bool:
    if not metadata_filter:
        return True
    return all(metadata.get(key) == value for key, value in metadata_filter.items())
