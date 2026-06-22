from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def test_cli_report_includes_spring_and_sql_surfaces(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")
    mapper_xml = tmp_path / "UserMapper.xml"
    mapper_xml.write_text(_mapper_xml(), encoding="utf-8")

    result = _run_cli(tmp_path, "--report")

    assert result.returncode == 0
    assert "HTTP 接口" in result.stdout
    assert "`/api/users/{id}`" in result.stdout
    assert "`UserController.getUser`" in result.stdout
    assert "接口 SQL 流向" in result.stdout
    assert "`GET /api/users/{id}`" in result.stdout
    assert "`users`" in result.stdout
    assert "XML Mapper：" in result.stdout
    assert "代码调用链" in result.stdout
    assert "`UserController.getUser` -> `UserService.findById` -> `UserMapper.findById`" in result.stdout
    assert "SQL 引用" in result.stdout
    assert "select * from users" in result.stdout


def test_cli_graph_renders_endpoint_call_chains(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")
    mapper_xml = tmp_path / "UserMapper.xml"
    mapper_xml.write_text(_mapper_xml(), encoding="utf-8")

    result = _run_cli(tmp_path, "--graph")

    assert result.returncode == 0
    assert "flowchart LR" in result.stdout
    assert 'subgraph chains["接口调用链"]' in result.stdout
    assert "POST /api/users" in result.stdout
    assert "GET /api/users/{id}" in result.stdout
    assert "method_UserController_getUser -->|调用| method_UserService_findById" in result.stdout
    assert "method_UserService_findById -->|调用| method_UserMapper_findById" in result.stdout
    assert "method_UserMapper_findById -->|执行 SQL| sql_" in result.stdout
    assert "table_users" in result.stdout
    assert "-->|访问表| table_users" in result.stdout
    assert "XML Mapper" in result.stdout


def test_cli_does_not_save_command_results(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")

    result = _run_cli(tmp_path, "--json")

    assert result.returncode == 0
    assert not (tmp_path / ".java_results").exists()
    assert "saved result to" not in result.stderr

    removed_option = _run_cli(tmp_path, "--json", "--results-dir", tmp_path / "results")
    assert removed_option.returncode != 0
    assert "unrecognized arguments" in removed_option.stderr


def test_cli_generates_obsidian_notes(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "registration.md").write_text(
        "# Registration\n\nPhone number must be unique during registration.",
        encoding="utf-8",
    )
    vault_dir = tmp_path / "vault"

    result = _run_cli(tmp_path, "--source", "mixed", "--obsidian", vault_dir)

    assert result.returncode == 0
    assert "generated" in result.stdout
    index = vault_dir / "Java Analysis.md"
    controller = vault_dir / "Components" / "UserController.md"
    endpoint = vault_dir / "Endpoints" / "POST -api-users.md"
    assert index.exists()
    assert controller.exists()
    assert endpoint.exists()
    assert "[[UserController]]" in index.read_text(encoding="utf-8")
    assert "相关知识" in controller.read_text(encoding="utf-8")


def test_cli_rejects_removed_local_vector_store_options(tmp_path: Path) -> None:
    java_file = tmp_path / "UserController.java"
    java_file.write_text(_spring_source(), encoding="utf-8")

    index_result = _run_cli(
        tmp_path,
        "--source",
        "code",
        "--index",
        str(tmp_path / "removed-store"),
    )
    assert index_result.returncode != 0
    assert "本地 JSONL 向量索引已移除" in index_result.stderr

    query_result = _run_cli(
        "--query",
        "GET users endpoint",
    )
    assert query_result.returncode != 0
    assert "本地 JSONL 向量检索已移除" in query_result.stderr


def _run_cli(*args: object) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    src_path = str(Path.cwd() / "src")
    env["PYTHONPATH"] = f"{src_path}{os.pathsep}{env.get('PYTHONPATH', '')}"
    return subprocess.run(
        [sys.executable, "-m", "java_analyzer.cli", *(str(arg) for arg in args)],
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


def _mapper_xml() -> str:
    return """
        <mapper namespace="com.example.UserMapper">
            <select id="findById" resultType="User">
                select * from users where id = #{id}
            </select>
        </mapper>
    """
