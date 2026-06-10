from __future__ import annotations

import re
from typing import Any, Iterable

from java_analyzer.vector_store import SearchResult, VectorRecord

TOKEN_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+|[\u4e00-\u9fff]{2,}")
STOP_WORDS = {
    "api",
    "code",
    "document",
    "file",
    "java",
    "knowledge",
    "method",
    "package",
    "section",
    "type",
}


def serialize_result(result: SearchResult) -> dict[str, Any]:
    return {
        "id": result.id,
        "score": result.score,
        "text": result.text,
        "metadata": result.metadata,
    }


def build_evidence(
    query: str,
    results: list[SearchResult],
    records: Iterable[VectorRecord],
    max_related: int = 5,
) -> dict[str, Any]:
    record_list = list(records)
    result_ids = {result.id for result in results}
    code_results = [serialize_result(result) for result in results if _source(result.metadata) == "code"]
    knowledge_results = [serialize_result(result) for result in results if _is_knowledge(result.metadata)]

    related_code: list[dict[str, Any]] = []
    related_knowledge: list[dict[str, Any]] = []
    relations: list[dict[str, Any]] = []
    seen_related: set[str] = set()

    for result in results:
        result_is_code = _source(result.metadata) == "code"
        opposite_source = "knowledge" if result_is_code else "code"
        anchors = _anchor_terms(query, result.text, result.metadata)
        candidates = [
            (record, _overlap(anchors, _record_terms(record)))
            for record in record_list
            if record.id not in result_ids
            and ((_is_knowledge(record.metadata) if opposite_source == "knowledge" else _source(record.metadata) == opposite_source))
        ]
        candidates = [(record, score) for record, score in candidates if score > 0]
        candidates.sort(key=lambda item: item[1], reverse=True)

        for record, score in candidates[:max_related]:
            if record.id not in seen_related:
                serialized = _serialize_record(record, score)
                if opposite_source == "code":
                    related_code.append(serialized)
                else:
                    related_knowledge.append(serialized)
                seen_related.add(record.id)
            relations.append(
                {
                    "from": result.id,
                    "to": record.id,
                    "reason": "shared_terms",
                    "score": score,
                    "terms": sorted(anchors.intersection(_record_terms(record)))[:8],
                }
            )

    return {
        "code": code_results,
        "knowledge": knowledge_results,
        "relatedCode": related_code[:max_related],
        "relatedKnowledge": related_knowledge[:max_related],
        "relations": relations[: max_related * 2],
    }


def _serialize_record(record: VectorRecord, relation_score: int) -> dict[str, Any]:
    return {
        "id": record.id,
        "score": relation_score,
        "text": record.text,
        "metadata": record.metadata,
    }


def _source(metadata: dict[str, Any]) -> str:
    return str(metadata.get("source_type", ""))


def _is_knowledge(metadata: dict[str, Any]) -> bool:
    return _source(metadata) in {"kb", "knowledge_asset"}


def _anchor_terms(query: str, text: str, metadata: dict[str, Any]) -> set[str]:
    fields = [
        query,
        text,
        str(metadata.get("symbol_name", "")),
        str(metadata.get("type_name", "")),
        str(metadata.get("path", "")),
        str(metadata.get("section", "")),
        str(metadata.get("statement", "")),
    ]
    return _tokens("\n".join(fields))


def _record_terms(record: VectorRecord) -> set[str]:
    metadata_text = " ".join(str(value) for value in record.metadata.values())
    return _tokens(f"{record.text}\n{metadata_text}")


def _tokens(text: str) -> set[str]:
    tokens: set[str] = set()
    for raw in TOKEN_PATTERN.findall(text):
        for token in _split_identifier(raw):
            normalized = token.lower()
            if len(normalized) < 2 or normalized in STOP_WORDS:
                continue
            tokens.add(normalized)
    return tokens


def _split_identifier(value: str) -> list[str]:
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", value)
    return re.split(r"[_\-\s./:{}()]+", spaced)


def _overlap(left: set[str], right: set[str]) -> int:
    return len(left.intersection(right))
