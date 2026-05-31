from java_ts_analyzer import JavaAnalyzer, build_chunks, build_kb_chunks
from java_ts_analyzer.vector_store import JsonlVectorStore


def test_build_chunks_creates_method_level_documents() -> None:
    result = JavaAnalyzer().analyze_source(
        """
        package com.example;

        class Demo {
            String name;

            String names() {
                return name.toString();
            }
        }
        """,
        file_path="Demo.java",
    )

    chunks = build_chunks(result)

    method_chunk = next(chunk for chunk in chunks if chunk.metadata["kind"] == "method")
    assert method_chunk.metadata["file_path"] == "Demo.java"
    assert method_chunk.metadata["type_name"] == "Demo"
    assert method_chunk.metadata["symbol_name"] == "names"
    assert "Java method names" in method_chunk.text
    assert "Calls: toString" in method_chunk.text


def test_jsonl_vector_store_indexes_and_searches_chunks(tmp_path) -> None:
    result = JavaAnalyzer().analyze_source(
        """
        class Demo {
            void run() {
                helper();
            }

            void helper() {}
        }
        """,
        file_path="Demo.java",
    )
    store = JsonlVectorStore(tmp_path / "vectors.jsonl")

    records = store.write_chunks(build_chunks(result))
    search_results = store.search("helper method call", top_k=1)

    assert records
    assert search_results
    assert search_results[0].metadata["file_path"] == "Demo.java"


def test_kb_chunks_can_be_combined_with_code_chunks(tmp_path) -> None:
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    kb_file = docs_dir / "registration.md"
    kb_file.write_text(
        "# Registration\n\nPhone number must be unique during user registration.",
        encoding="utf-8",
    )

    analysis = JavaAnalyzer().analyze_source(
        """
        class UserService {
            void createUser() {
                existsByPhone();
            }
        }
        """,
        file_path="UserService.java",
    )
    chunks = [*build_chunks(analysis), *build_kb_chunks(docs_dir)]
    store = JsonlVectorStore(tmp_path / "combined.jsonl")

    store.write_chunks(chunks)
    kb_results = store.search(
        "phone number unique registration",
        top_k=3,
        metadata_filter={"source_type": "kb"},
    )
    code_results = store.search(
        "create user exists phone",
        top_k=3,
        metadata_filter={"source_type": "code"},
    )

    assert kb_results
    assert kb_results[0].metadata["source_type"] == "kb"
    assert code_results
    assert code_results[0].metadata["source_type"] == "code"


def test_kb_chunks_skip_saved_results_directory(tmp_path) -> None:
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "guide.md").write_text("# Guide\n\nReal project knowledge.", encoding="utf-8")
    results_dir = tmp_path / ".java_ts_results"
    results_dir.mkdir()
    (results_dir / "20260530-report.md").write_text("# Generated\n\nOld output.", encoding="utf-8")

    chunks = build_kb_chunks(tmp_path)

    file_paths = {chunk.metadata["file_path"] for chunk in chunks}
    assert str(docs_dir / "guide.md") in file_paths
    assert str(results_dir / "20260530-report.md") not in file_paths
