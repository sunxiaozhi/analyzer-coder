import subprocess
from pathlib import Path

from web.backend.app import create_app
from web.backend.config import BackendConfig


def test_web_backend_health() -> None:
    client = create_app().test_client()

    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.get_json()["ok"] is True


def test_web_backend_report() -> None:
    client = create_app().test_client()

    response = client.post(
        "/api/analyze",
        json={"path": "java/src/main/java", "source": "code", "mode": "report"},
    )

    payload = response.get_json()
    assert response.status_code == 200
    assert "HTTP Endpoints" in payload["output"]
    assert payload["savedPath"].endswith(".md")


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

    response = client.post("/api/analyze", json={"path": "..", "source": "code"})

    assert response.status_code == 400
    assert "path must stay inside the workspace" in response.get_json()["error"]
