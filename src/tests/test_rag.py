from java_analyzer.vector_store import SearchResult

from web.backend.fusion import build_evidence
from web.backend.rag import build_rag_flow


def test_build_rag_flow_groups_citations_and_context_package() -> None:
    results = [
        SearchResult(
            id="asset-1",
            score=0.82,
            text="Registration requires a unique phone number.",
            metadata={
                "source_type": "knowledge_asset",
                "kind": "business_rule",
                "title": "Registration rule",
                "status": "confirmed",
                "file_path": "docs/rules.md",
                "start_line": 3,
            },
        ),
        SearchResult(
            id="code-1",
            score=0.71,
            text="UserService.register validates phone uniqueness.",
            metadata={
                "source_type": "code",
                "kind": "method",
                "symbol_name": "register",
                "file_path": "src/UserService.java",
                "start_line": 42,
            },
        ),
    ]
    evidence = build_evidence("registration unique phone", results, [])

    flow = build_rag_flow("registration unique phone", results, evidence)

    assert flow["steps"][0]["title"] == "检索计划"
    assert "Registration rule" in flow["answerDraft"]
    assert flow["citations"][0]["title"] == "Registration rule"
    assert flow["contextPackage"].startswith("# RAG 搜索上下文包")
    assert not any(risk["level"] == "high" for risk in flow["risks"])
