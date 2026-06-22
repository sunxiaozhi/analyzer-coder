from __future__ import annotations

from typing import Any

from java_analyzer.vector_store import SearchResult

from web.backend.fusion import serialize_result

LOW_SCORE_THRESHOLD = 0.15
MAX_CITATIONS = 8


def build_rag_flow(query: str, results: list[SearchResult], evidence: dict[str, Any]) -> dict[str, Any]:
    direct_results = [serialize_result(result) for result in results]
    context_items = _context_items(direct_results, evidence)
    citations = [_citation(item) for item in context_items[:MAX_CITATIONS]]
    risks = _risks(direct_results, evidence)
    answer = _answer_draft(query, direct_results, evidence, citations, risks)
    context_package = _context_package(query, answer, citations, risks)

    return {
        "query": query,
        "steps": _steps(query, direct_results, evidence, citations, risks),
        "answerDraft": answer,
        "citations": citations,
        "risks": risks,
        "contextPackage": context_package,
    }


def _steps(
    query: str,
    direct_results: list[dict[str, Any]],
    evidence: dict[str, Any],
    citations: list[dict[str, Any]],
    risks: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    return [
        {
            "key": "plan",
            "title": "检索计划",
            "status": "done" if query else "warning",
            "summary": "基于问题文本召回代码、知识文档和知识资产，并保留来源追溯。",
            "count": 1 if query else 0,
        },
        {
            "key": "retrieve",
            "title": "向量召回",
            "status": "done" if direct_results else "warning",
            "summary": f"命中 {len(direct_results)} 条直接结果。",
            "count": len(direct_results),
        },
        {
            "key": "fuse",
            "title": "证据融合",
            "status": "done" if _evidence_count(evidence) else "warning",
            "summary": f"生成 {_evidence_count(evidence)} 条关联证据或关系。",
            "count": _evidence_count(evidence),
        },
        {
            "key": "compose",
            "title": "上下文组织",
            "status": "done" if citations else "warning",
            "summary": f"整理 {len(citations)} 条可引用来源。",
            "count": len(citations),
        },
        {
            "key": "guard",
            "title": "风险检查",
            "status": "warning" if risks else "done",
            "summary": "发现风险提示。" if risks else "未发现明显召回风险。",
            "count": len(risks),
        },
    ]


def _context_items(direct_results: list[dict[str, Any]], evidence: dict[str, Any]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    seen: set[str] = set()
    groups = [
        direct_results,
        list(evidence.get("knowledge", [])),
        list(evidence.get("code", [])),
        list(evidence.get("relatedKnowledge", [])),
        list(evidence.get("relatedCode", [])),
    ]
    for group in groups:
        for item in group:
            item_id = str(item.get("id", ""))
            if not item_id or item_id in seen:
                continue
            seen.add(item_id)
            items.append(item)
    return items


def _citation(item: dict[str, Any]) -> dict[str, Any]:
    metadata = dict(item.get("metadata") or {})
    return {
        "id": str(item.get("id", "")),
        "title": _title(metadata),
        "sourceType": str(metadata.get("source_type") or ""),
        "kind": str(metadata.get("kind") or ""),
        "status": str(metadata.get("status") or ""),
        "location": _location(metadata),
        "score": float(item.get("score") or 0),
        "excerpt": _excerpt(str(item.get("text") or "")),
    }


def _risks(direct_results: list[dict[str, Any]], evidence: dict[str, Any]) -> list[dict[str, Any]]:
    risks: list[dict[str, Any]] = []
    if not direct_results:
        risks.append(
            {
                "level": "high",
                "message": "没有命中可用上下文，建议先构建索引或扩大检索范围。",
            }
        )
        return risks

    top_score = float(direct_results[0].get("score") or 0)
    if top_score < LOW_SCORE_THRESHOLD:
        risks.append(
            {
                "level": "medium",
                "message": "最高相似度偏低，答案草稿只能作为线索。",
            }
        )

    confirmed = [
        item
        for item in direct_results
        if (item.get("metadata") or {}).get("source_type") == "knowledge_asset"
        and (item.get("metadata") or {}).get("status") == "confirmed"
    ]
    if not confirmed:
        risks.append(
            {
                "level": "medium",
                "message": "未命中已确认知识资产，需要人工核对代码和知识文档。",
            }
        )

    risky_statuses = {"draft", "pending_review", "stale"}
    if any((item.get("metadata") or {}).get("status") in risky_statuses for item in direct_results):
        risks.append(
            {
                "level": "low",
                "message": "结果中包含草稿或待复审知识，引用时需要保留风险标识。",
            }
        )

    if not evidence.get("relatedCode") and not evidence.get("code"):
        risks.append(
            {
                "level": "low",
                "message": "没有形成代码侧证据，建议补充代码索引或调整问题描述。",
            }
        )
    return risks


def _answer_draft(
    query: str,
    direct_results: list[dict[str, Any]],
    evidence: dict[str, Any],
    citations: list[dict[str, Any]],
    risks: list[dict[str, Any]],
) -> str:
    if not direct_results:
        return f"未能从当前索引中找到与“{query}”直接相关的内容。请先确认项目索引已构建，或放宽检索范围后重试。"

    knowledge = [
        _citation(item)
        for item in direct_results
        if (item.get("metadata") or {}).get("source_type") in {"knowledge_asset", "kb"}
    ]
    code = [
        _citation(item)
        for item in direct_results
        if (item.get("metadata") or {}).get("source_type") == "code"
    ]

    lines = [f"围绕“{query}”，当前索引给出的可追溯结论如下："]
    if knowledge:
        lines.append(f"1. 优先参考知识侧内容：{_join_titles(knowledge[:3])}。")
    if code:
        lines.append(f"2. 代码侧证据集中在：{_join_titles(code[:3])}。")
    if evidence.get("relations"):
        lines.append(f"3. 系统找到 {len(evidence.get('relations', []))} 条代码与知识之间的关联线索。")
    if citations:
        lines.append("4. 生成结果应引用下方来源清单，不应脱离证据直接下结论。")
    if risks:
        lines.append("5. 存在风险提示，使用前需要人工复核。")
    return "\n".join(lines)


def _context_package(
    query: str,
    answer: str,
    citations: list[dict[str, Any]],
    risks: list[dict[str, Any]],
) -> str:
    lines = [
        "# RAG 搜索上下文包",
        "",
        "## 问题",
        query,
        "",
        "## 答案草稿",
        answer,
        "",
        "## 引用来源",
    ]
    if citations:
        for index, item in enumerate(citations, start=1):
            lines.extend(
                [
                    f"{index}. {item['title']}",
                    f"   - 来源：{_source_label(item['sourceType'])} / {item['kind'] or '片段'}",
                    f"   - 位置：{item['location'] or '未知'}",
                    f"   - 摘要：{item['excerpt']}",
                ]
            )
    else:
        lines.append("无可引用来源。")

    lines.extend(["", "## 风险提示"])
    if risks:
        for item in risks:
            lines.append(f"- {item['message']}")
    else:
        lines.append("未发现明显召回风险。")
    return "\n".join(lines)


def _evidence_count(evidence: dict[str, Any]) -> int:
    return (
        len(evidence.get("relatedCode", []))
        + len(evidence.get("relatedKnowledge", []))
        + len(evidence.get("relations", []))
    )


def _title(metadata: dict[str, Any]) -> str:
    return str(
        metadata.get("title")
        or metadata.get("symbol_name")
        or metadata.get("doc_name")
        or metadata.get("section")
        or metadata.get("kind")
        or "检索片段"
    )


def _location(metadata: dict[str, Any]) -> str:
    path = str(metadata.get("file_path") or metadata.get("path") or "")
    line = metadata.get("start_line")
    if path and line:
        return f"{path}:{line}"
    return path


def _excerpt(text: str, limit: int = 180) -> str:
    collapsed = " ".join(text.split())
    if len(collapsed) <= limit:
        return collapsed
    return f"{collapsed[:limit].rstrip()}..."


def _join_titles(items: list[dict[str, Any]]) -> str:
    return "、".join(item["title"] for item in items)


def _source_label(source_type: str) -> str:
    if source_type == "knowledge_asset":
        return "知识资产"
    if source_type == "kb":
        return "知识文档"
    if source_type == "code":
        return "代码"
    return source_type or "未知"
