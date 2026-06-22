from java_analyzer import JavaAnalyzer, build_chunks, build_kb_chunks
from java_analyzer.vector_store import records_for_chunks, search_records


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


def test_vector_records_can_be_ranked_without_local_store() -> None:
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
    records = records_for_chunks(build_chunks(result))
    search_results = search_records(records, "helper method call", top_k=1)

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
    records = records_for_chunks(chunks)
    kb_results = search_records(
        records,
        "phone number unique registration",
        top_k=3,
        metadata_filter={"source_type": "kb"},
    )
    code_results = search_records(
        records,
        "create user exists phone",
        top_k=3,
        metadata_filter={"source_type": "code"},
    )

    assert kb_results
    assert kb_results[0].metadata["source_type"] == "kb"
    assert code_results
    assert code_results[0].metadata["source_type"] == "code"


def test_kb_chunks_skip_generated_directories(tmp_path) -> None:
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "guide.md").write_text("# Guide\n\nReal project knowledge.", encoding="utf-8")
    generated_dir = tmp_path / "dist"
    generated_dir.mkdir()
    (generated_dir / "report.md").write_text("# Generated\n\nOld output.", encoding="utf-8")

    chunks = build_kb_chunks(tmp_path)

    file_paths = {chunk.metadata["file_path"] for chunk in chunks}
    assert str(docs_dir / "guide.md") in file_paths
    assert str(generated_dir / "report.md") not in file_paths
