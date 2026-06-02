import subprocess
from pathlib import Path

from web.backend.app import create_app
from web.backend.config import BackendConfig
from web.backend.services import ProjectRecord


def test_web_backend_health() -> None:
    client = create_app().test_client()

    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.get_json()["ok"] is True


def test_web_backend_report(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = workspace
        RESULTS_DIR = ".results"
        DEFAULT_STORE = ".store/default.jsonl"
        TESTING = True

    client = create_app(TestConfig).test_client()
    _login(client)

    response = client.post(
        "/api/analyze",
        json={"path": "src", "source": "code", "mode": "report"},
    )

    payload = response.get_json()
    assert response.status_code == 200
    assert "HTTP Endpoints" in payload["output"]
    assert payload["savedPath"].endswith(".md")


def test_web_backend_report_accepts_null_project_id(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = workspace
        RESULTS_DIR = ".results"
        DEFAULT_STORE = ".store/default.jsonl"
        TESTING = True

    client = create_app(TestConfig).test_client()
    _login(client)

    response = client.post(
        "/api/analyze",
        json={
            "projectId": None,
            "path": "src",
            "source": "code",
            "mode": "report",
        },
    )

    payload = response.get_json()
    assert response.status_code == 200
    assert payload["projectId"] == ""
    assert "HTTP Endpoints" in payload["output"]


def test_web_backend_mixed_index_and_query_returns_fusion_evidence(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    source_dir = workspace / "src"
    docs_dir = workspace / "docs"
    source_dir.mkdir(parents=True)
    docs_dir.mkdir()
    (source_dir / "UserController.java").write_text(
        """
        package demo;

        import org.springframework.web.bind.annotation.PostMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        class UserController {
            @PostMapping("/register")
            User register(UserRegistrationRequest request) {
                return new User(request.phone());
            }
        }

        record UserRegistrationRequest(String phone) {
        }
        record User(String phone) {
        }
        """,
        encoding="utf-8",
    )
    (docs_dir / "registration.md").write_text(
        "# Registration\n\nPhone number must be unique before registering a user.",
        encoding="utf-8",
    )

    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = workspace
        PROJECTS_DIR = ".projects"
        RESULTS_DIR = ".results"
        DEFAULT_STORE = ".store/default.jsonl"
        TESTING = True

    client = create_app(TestConfig).test_client()
    _login(client)

    index_response = client.post(
        "/api/index",
        json={
            "source": "mixed",
            "codePath": "src",
            "kbPath": "docs",
            "store": ".store/fusion.jsonl",
        },
    )
    index_payload = index_response.get_json()

    assert index_response.status_code == 200
    assert index_payload["count"] > 1

    query_response = client.post(
        "/api/query",
        json={
            "store": ".store/fusion.jsonl",
            "query": "phone unique registration register user",
            "topK": 6,
        },
    )
    query_payload = query_response.get_json()

    assert query_response.status_code == 200
    assert query_payload["results"]
    assert query_payload["evidence"]["code"]
    assert query_payload["evidence"]["knowledge"]
    assert query_payload["evidence"]["relations"]


def test_web_backend_knowledge_file_crud(tmp_path) -> None:
    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = tmp_path
        PROJECTS_DIR = ".projects"
        RESULTS_DIR = ".results"
        DEFAULT_STORE = ".store/default.jsonl"
        TESTING = True

    client = create_app(TestConfig).test_client()
    _login(client)

    create_response = client.post(
        "/api/kb/files",
        json={
            "kbPath": "docs",
            "path": "domain/user-registration.md",
            "content": "# 用户注册\n\n手机号必须唯一。",
        },
    )
    create_payload = create_response.get_json()

    assert create_response.status_code == 201
    assert create_payload["file"]["path"] == "domain/user-registration.md"
    assert (tmp_path / "docs" / "domain" / "user-registration.md").exists()

    list_response = client.get("/api/kb/files", query_string={"kbPath": "docs"})
    assert list_response.status_code == 200
    assert list_response.get_json()["files"][0]["path"] == "domain/user-registration.md"

    get_response = client.get(
        "/api/kb/file",
        query_string={"kbPath": "docs", "path": "domain/user-registration.md"},
    )
    assert get_response.status_code == 200
    assert "手机号必须唯一" in get_response.get_json()["content"]

    save_response = client.put(
        "/api/kb/file",
        json={
            "kbPath": "docs",
            "path": "domain/user-registration.md",
            "content": "# 用户注册\n\n手机号和邮箱都需要校验。",
        },
    )
    assert save_response.status_code == 200
    assert "邮箱" in (tmp_path / "docs" / "domain" / "user-registration.md").read_text(encoding="utf-8")

    delete_response = client.delete(
        "/api/kb/file",
        json={"kbPath": "docs", "path": "domain/user-registration.md"},
    )
    assert delete_response.status_code == 200
    assert not (tmp_path / "docs" / "domain" / "user-registration.md").exists()


def test_web_backend_project_clone_and_isolated_report(tmp_path) -> None:
    source_repo = tmp_path / "source"
    source_repo.mkdir()
    (source_repo / "src").mkdir()
    (source_repo / "src" / "UserController.java").write_text(
        """
        package demo;

        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @RequestMapping("/api")
        class UserController {
            @GetMapping("/users")
            String listUsers() {
                return "ok";
            }
        }
        """,
        encoding="utf-8",
    )
    subprocess.run(["git", "init"], cwd=source_repo, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=source_repo, check=True)
    subprocess.run(["git", "config", "user.name", "Test User"], cwd=source_repo, check=True)
    subprocess.run(["git", "add", "."], cwd=source_repo, check=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=source_repo, check=True, capture_output=True)

    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = tmp_path / "workspace"
        PROJECTS_DIR = ".projects"
        RESULTS_DIR = ".results"
        DEFAULT_STORE = ".store/default.jsonl"
        TESTING = True

    TestConfig.WORKSPACE_ROOT.mkdir()
    client = create_app(TestConfig).test_client()
    _login(client)

    create_response = client.post(
        "/api/projects",
        json={"name": "Demo Project", "gitUrl": str(source_repo)},
    )
    project = create_response.get_json()["project"]

    assert create_response.status_code == 201
    assert project["id"] == "demo-project"

    analyze_response = client.post(
        "/api/analyze",
        json={"projectId": project["id"], "path": "src", "source": "code", "mode": "report"},
    )
    payload = analyze_response.get_json()

    assert analyze_response.status_code == 200
    assert payload["projectId"] == project["id"]
    saved_parts = Path(payload["savedPath"]).parts
    assert saved_parts[:3] == (".projects", "demo-project", "results")
    assert "UserController" in payload["output"]


def test_web_backend_project_rejects_path_escape(tmp_path) -> None:
    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = tmp_path
        PROJECTS_DIR = ".projects"
        TESTING = True

    client = create_app(TestConfig).test_client()
    _login(client)

    response = client.post("/api/analyze", json={"path": "..", "source": "code"})

    assert response.status_code == 400
    assert "path must stay inside the workspace" in response.get_json()["error"]


def test_web_backend_project_access_is_user_scoped(tmp_path) -> None:
    class TestConfig(BackendConfig):
        WORKSPACE_ROOT = tmp_path
        PROJECTS_DIR = ".projects"
        TESTING = True

    service_app = create_app(TestConfig)
    client = service_app.test_client()
    _login(client)

    service = service_app.extensions["analyzer_service"]
    projects_dir = tmp_path / ".projects"
    project_root = projects_dir / "demo" / "repo"
    project_root.mkdir(parents=True)
    (project_root / "Demo.java").write_text("class Demo {}", encoding="utf-8")
    service._save_projects(
        [
            ProjectRecord(
                id="demo",
                name="Demo",
                gitUrl="local",
                branch="",
                path=".projects/demo/repo",
                createdAt="2026-06-02T00:00:00+00:00",
                updatedAt="2026-06-02T00:00:00+00:00",
            )
        ]
    )

    create_user_response = client.post(
        "/api/users",
        json={"username": "viewer", "displayName": "Viewer", "password": "secret1", "projectIds": []},
    )
    assert create_user_response.status_code == 201

    update_user_response = client.put(
        "/api/users/viewer",
        json={"username": "viewer2", "displayName": "Viewer Two", "isAdmin": False},
    )
    assert update_user_response.status_code == 200
    assert update_user_response.get_json()["user"]["username"] == "viewer2"

    password_response = client.put("/api/users/viewer/password", json={"password": "secret2"})
    assert password_response.status_code == 200

    client.post("/api/auth/logout")
    _login(client, username="viewer2", password="secret2")
    assert client.get("/api/projects").get_json()["projects"] == []
    denied_response = client.post(
        "/api/analyze",
        json={"projectId": "demo", "path": ".", "source": "code", "mode": "summary"},
    )
    assert denied_response.status_code == 403

    client.post("/api/auth/logout")
    _login(client)
    update_response = client.put("/api/users/viewer/access", json={"projectIds": ["demo"]})
    assert update_response.status_code == 200

    client.post("/api/auth/logout")
    _login(client, username="viewer2", password="secret2")
    visible_projects = client.get("/api/projects").get_json()["projects"]
    assert [project["id"] for project in visible_projects] == ["demo"]


def _login(client, username: str = "admin", password: str = "admin123") -> None:
    response = client.post("/api/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200


def _java_workspace(tmp_path: Path) -> Path:
    workspace = tmp_path / "workspace"
    source_dir = workspace / "src"
    source_dir.mkdir(parents=True)
    (source_dir / "UserController.java").write_text(
        """
        package demo;

        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @RequestMapping("/api")
        class UserController {
            @GetMapping("/users")
            String listUsers() {
                return "ok";
            }
        }
        """,
        encoding="utf-8",
    )
    return workspace
