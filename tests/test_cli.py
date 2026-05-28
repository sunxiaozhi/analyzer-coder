from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def test_cli_report_includes_spring_and_sql_surfaces(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")

    result = _run_cli(tmp_path, "--report")

    assert result.returncode == 0
    assert "HTTP Endpoints" in result.stdout
    assert "`/api/users/{id}`" in result.stdout
    assert "`UserController.getUser`" in result.stdout
    assert "SQL References" in result.stdout
    assert "select * from users" in result.stdout


def test_cli_graph_includes_endpoints_and_component_dependencies(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")

    result = _run_cli(tmp_path, "--graph")

    assert result.returncode == 0
    assert "flowchart LR" in result.stdout
    assert "POST /api/users" in result.stdout
    assert "GET /api/users/{id}" in result.stdout
    assert "component_UserController" in result.stdout
    assert "component_UserService -->|uses| component_UserMapper" in result.stdout
    assert "component_UserController -->|uses| component_UserService" in result.stdout
    assert "component_UserMapper -->|executes| sql_1" in result.stdout


def test_cli_indexes_queries_and_prints_match_terms(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "registration.md").write_text(
        "# Registration\n\nPhone number must be unique during registration.",
        encoding="utf-8",
    )
    store_path = tmp_path / "vectors.jsonl"

    index_result = _run_cli(
        tmp_path,
        "--source",
        "mixed",
        "--index",
        str(store_path),
        "--embedding-dimensions",
        "128",
    )
    assert index_result.returncode == 0
    assert "indexed" in index_result.stdout

    code_result = _run_cli(
        "--store",
        str(store_path),
        "--query",
        "GET users endpoint",
        "--filter-source",
        "code",
        "--top-k",
        "1",
        "--embedding-dimensions",
        "128",
    )
    assert code_result.returncode == 0
    assert "endpoint getUser" in code_result.stdout
    assert "matched:" in code_result.stdout

    kb_result = _run_cli(
        "--store",
        str(store_path),
        "--query",
        "phone unique registration",
        "--filter-source",
        "kb",
        "--top-k",
        "1",
        "--embedding-dimensions",
        "128",
    )
    assert kb_result.returncode == 0
    assert "Registration" in kb_result.stdout


def _run_cli(*args: object) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    src_path = str(Path.cwd() / "src")
    env["PYTHONPATH"] = f"{src_path}{os.pathsep}{env.get('PYTHONPATH', '')}"
    return subprocess.run(
        [sys.executable, "-m", "java_ts_analyzer.cli", *(str(arg) for arg in args)],
        cwd=Path.cwd(),
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


def _spring_source() -> str:
    return """
        package com.example;

        @RestController
        @RequestMapping("/api/users")
        class UserController {
            private final UserService service;

            UserController(UserService service) {
                this.service = service;
            }

            @PostMapping
            User create(UserRegistrationRequest request) {
                return service.createUser(request);
            }

            @GetMapping("/{id}")
            User getUser(String id) {
                return service.findById(id);
            }
        }

        @Service
        class UserService {
            private final UserMapper mapper;

            UserService(UserMapper mapper) {
                this.mapper = mapper;
            }

            User createUser(UserRegistrationRequest request) {
                return mapper.findByPhone(request.phone());
            }

            User findById(String id) {
                return mapper.findById(id);
            }
        }

        @Mapper
        interface UserMapper {
            @Select("select * from users where phone = #{phone}")
            User findByPhone(String phone);

            User findById(String id);
        }

        record UserRegistrationRequest(String phone) {
        }
    """
