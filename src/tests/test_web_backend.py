import subprocess
from dataclasses import asdict
from pathlib import Path
from types import SimpleNamespace

from java_analyzer.embedding import HashingEmbedder, cosine_similarity
from java_analyzer.vector_store import SearchResult
from web.backend.app import create_app
from web.backend.config import BackendConfig
from web.backend.services import ProjectRecord, UserRecord


class InMemoryExternalStores:
    def __init__(self) -> None:
        self.config = SimpleNamespace(qdrant=SimpleNamespace(collection="test_chunks"))
        self.calls = []
        self.records_by_project = {}

    def sync(self, *, project_id, analysis_records, vector_records):
        self.calls.append(
            {
                "project_id": project_id,
                "analysis_records": analysis_records,
                "vector_records": vector_records,
            }
        )
        project_records = self.records_by_project.setdefault(project_id, {})
        for record in vector_records:
            project_records[record.id] = record
        return {
            "enabled": True,
            "qdrant": {"ok": True, "count": len(vector_records)},
            "neo4j": {"ok": True, "recordCount": len(analysis_records)},
        }

    def delete_qdrant_project_records(self, *, project_id, source_type=""):
        if not source_type:
            self.records_by_project[project_id] = {}
            return {"ok": True}
        records = self.records_by_project.setdefault(project_id, {})
        for record_id in [
            record_id
            for record_id, record in records.items()
            if record.metadata.get("source_type") == source_type
        ]:
            records.pop(record_id, None)
        return {"ok": True}

    def list_qdrant_records(self, *, project_id, limit=1000, source_type=""):
        records = list(self.records_by_project.get(project_id, {}).values())
        if source_type:
            records = [record for record in records if record.metadata.get("source_type") == source_type]
        return [
            {"id": record.id, "text": record.text, "metadata": record.metadata, "score": 0}
            for record in records[:limit]
        ]

    def search_qdrant(self, *, project_id, query_vector, top_k, source_type=""):
        records = list(self.records_by_project.get(project_id, {}).values())
        if source_type:
            records = [record for record in records if record.metadata.get("source_type") == source_type]
        results = [
            SearchResult(
                id=record.id,
                score=cosine_similarity(query_vector, record.embedding),
                text=record.text,
                metadata=record.metadata,
            )
            for record in records
        ]
        results.sort(key=lambda item: item.score, reverse=True)
        return [
            {"id": result.id, "score": result.score, "text": result.text, "metadata": result.metadata}
            for result in results[:top_k]
        ]


class InMemoryMySqlStore:
    def __init__(self) -> None:
        self.users = []
        self.projects = []
        self.analysis_records = {}
        self.analysis_outputs = {}
        self.knowledge_assets = {}
        self.knowledge_templates = {}
        self.knowledge_documents = {}

    def initialize(self) -> None:
        return None

    def ensure_admin(self, username, password, now) -> None:
        if self.users:
            return
        from werkzeug.security import generate_password_hash

        self.users.append(
            {
                "id": username,
                "username": username,
                "displayName": "系统管理员",
                "passwordHash": generate_password_hash(password),
                "isAdmin": True,
                "lastProjectId": "",
                "projectIds": [],
                "createdAt": now,
                "updatedAt": now,
            }
        )

    def list_users(self):
        return [dict(user, projectIds=list(user.get("projectIds", []))) for user in self.users]

    def insert_user(self, user):
        item = dict(user)
        item["id"] = item.get("id") or item["username"]
        item.setdefault("lastProjectId", "")
        item["projectIds"] = list(item.get("projectIds", []))
        self.users.append(item)
        return dict(item)

    def save_users(self, users):
        self.users = [asdict(user) if isinstance(user, UserRecord) else dict(user) for user in users]

    def list_projects(self):
        return [dict(project) for project in self.projects]

    def insert_project(self, project):
        item = dict(project)
        item["id"] = item.get("id") or item.get("projectKey") or item["name"]
        self.projects.append(item)
        return dict(item)

    def save_projects(self, projects):
        self.projects = [asdict(project) if isinstance(project, ProjectRecord) else dict(project) for project in projects]

    def grant_access(self, user_id, project_id, now):
        for user in self.users:
            if user["id"] == user_id and project_id not in user["projectIds"]:
                user["projectIds"].append(project_id)
                user["updatedAt"] = now

    def list_knowledge_assets(self, project_id):
        return [dict(item) for item in self.knowledge_assets.get(project_id, [])]

    def save_knowledge_assets(self, project_id, assets):
        self.knowledge_assets[project_id] = [asdict(asset) for asset in assets]

    def list_kb_templates(self, project_id):
        return [dict(item) for item in self.knowledge_templates.get(project_id, [])]

    def save_kb_templates(self, project_id, templates):
        self.knowledge_templates[project_id] = [dict(item) for item in templates]

    def list_knowledge_documents(self, project_id, prefix=""):
        documents = list(self.knowledge_documents.get(project_id, {}).values())
        if prefix:
            documents = [item for item in documents if item["path"].startswith(prefix.rstrip("/") + "/")]
        return [
            {key: item[key] for key in ("path", "name", "size", "updatedAt")}
            for item in sorted(documents, key=lambda row: row["path"])
        ]

    def get_knowledge_document(self, project_id, path):
        item = self.knowledge_documents.get(project_id, {}).get(path)
        return dict(item) if item else None

    def save_knowledge_document(self, project_id, *, path, content, updated_by):
        item = {
            "path": path,
            "name": path.rsplit("/", 1)[-1],
            "content": content,
            "size": len(content.encode("utf-8")),
            "updatedAt": "2026-06-22T00:00:00+00:00",
        }
        self.knowledge_documents.setdefault(project_id, {})[path] = item
        return dict(item)

    def delete_knowledge_document(self, project_id, path):
        return self.knowledge_documents.get(project_id, {}).pop(path, None) is not None

    def save_analysis_records(self, project_id, records):
        self.analysis_records[project_id] = list(records)

    def list_analysis_records(self, project_id, record_type="", limit=200, offset=0):
        records = self.analysis_records.get(project_id, [])
        if record_type:
            records = [record for record in records if record.get("type") == record_type]
        return records[offset : offset + limit]

    def count_analysis_records_by_type(self, project_id):
        counts = {}
        for record in self.analysis_records.get(project_id, []):
            record_type = record.get("type") or "unknown"
            counts[record_type] = counts.get(record_type, 0) + 1
        return counts

    def save_analysis_output(self, project_id, *, mode, extension, output):
        item = {
            "mode": mode,
            "extension": extension,
            "output": output,
            "uri": f"mysql://analysis_outputs/{project_id}/{mode}",
            "updatedAt": "2026-06-22T00:00:00+00:00",
        }
        self.analysis_outputs[(project_id, mode)] = item
        return dict(item)

    def get_analysis_output(self, project_id, mode):
        item = self.analysis_outputs.get((project_id, mode))
        return dict(item) if item else None


class JsonTestConfig(BackendConfig):
    TESTING = True
    MYSQL_STORE = InMemoryMySqlStore()
    EXTERNAL_STORES = InMemoryExternalStores()

    def __init_subclass__(cls) -> None:
        cls.MYSQL_STORE = InMemoryMySqlStore()
        cls.EXTERNAL_STORES = InMemoryExternalStores()


def test_web_backend_health() -> None:
    client = create_app(JsonTestConfig).test_client()

    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.get_json()["ok"] is True


def test_web_backend_http_errors_are_json() -> None:
    client = create_app(JsonTestConfig).test_client()

    response = client.get("/api/users/password")

    assert response.status_code == 405
    assert response.content_type.startswith("application/json")
    assert response.get_json()["error"]


def test_web_backend_report(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    client = app.test_client()
    _login(client)

    response = client.post(
        "/api/analyze",
        json={"projectId": project_id, "path": "src", "source": "code", "mode": "report"},
    )

    payload = response.get_json()
    assert response.status_code == 200
    assert "HTTP 接口" in payload["output"]
    assert payload["savedPath"] == f"mysql://analysis_outputs/{project_id}/report"
    assert payload["analysisRecordCount"] > 0


def test_web_backend_stores_analysis_records_by_type(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    client = app.test_client()
    _login(client)

    analyze_response = client.post(
        "/api/analyze",
        json={"projectId": project_id, "path": "src", "source": "code", "mode": "summary"},
    )
    analyze_payload = analyze_response.get_json()

    assert analyze_response.status_code == 200
    assert analyze_payload["analysisRecordCount"] > 0
    assert not (workspace / "analysis_records.json").exists()

    list_response = client.get("/api/analysis/records", query_string={"projectId": project_id, "limit": 500})
    list_payload = list_response.get_json()

    assert list_response.status_code == 200
    assert list_payload["types"]["endpoint"] == 1
    assert list_payload["types"]["method"] == 1
    assert list_payload["types"]["return"] == 1

    endpoint_response = client.get("/api/analysis/records", query_string={"projectId": project_id, "type": "endpoint"})
    endpoint_payload = endpoint_response.get_json()

    assert endpoint_response.status_code == 200
    assert endpoint_payload["records"][0]["type"] == "endpoint"
    assert endpoint_payload["records"][0]["payload"]["path"] == "/api/users"


def test_web_backend_syncs_analysis_to_external_stores_when_requested(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    service = app.extensions["analyzer_service"]
    fake_stores = service.external_stores
    client = app.test_client()
    _login(client)

    response = client.post(
        "/api/analyze",
        json={"projectId": project_id, "path": "src", "source": "code", "mode": "report", "syncExternal": True},
    )
    payload = response.get_json()

    assert response.status_code == 200
    assert payload["externalSync"]["enabled"] is True
    assert payload["externalSync"]["qdrant"]["count"] > 0
    assert payload["externalSync"]["neo4j"]["recordCount"] == payload["analysisRecordCount"]
    assert fake_stores.calls[0]["analysis_records"]
    assert fake_stores.calls[0]["vector_records"]


def test_web_backend_reads_latest_analysis_result(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    client = app.test_client()
    _login(client)

    analyze_response = client.post("/api/analyze", json={"projectId": project_id, "path": "src", "source": "code", "mode": "report"})
    saved_path = analyze_response.get_json()["savedPath"]

    result_response = client.get("/api/analysis/result", query_string={"projectId": project_id, "mode": "report"})
    result_payload = result_response.get_json()

    assert result_response.status_code == 200
    assert result_payload["exists"] is True
    assert result_payload["savedPath"] == saved_path
    assert result_payload["mode"] == "report"
    assert "HTTP 接口" in result_payload["output"]
    assert result_payload["updatedAt"]


def test_web_backend_refreshes_legacy_english_report_cache(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    service = app.extensions["analyzer_service"]
    service.mysql_store.save_analysis_output(
        project_id,
        mode="report",
        extension=".md",
        output="# Java Project Analysis Report\n\n## HTTP Endpoints\n",
    )
    client = app.test_client()
    _login(client)

    result_response = client.get("/api/analysis/result", query_string={"projectId": project_id, "mode": "report"})
    result_payload = result_response.get_json()

    assert result_response.status_code == 200
    assert result_payload["exists"] is True
    assert "Java 项目分析报告" in result_payload["output"]
    assert "HTTP 接口" in result_payload["output"]
    refreshed = service.mysql_store.get_analysis_output(project_id, "report")
    assert "Java Project Analysis Report" not in refreshed["output"]


def test_web_backend_report_accepts_null_project_id(tmp_path) -> None:
    workspace = _java_workspace(tmp_path)

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
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
    assert response.status_code == 400
    assert "projectId is required" in payload["error"]


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

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        PROJECTS_DIR = ".projects"
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    app.extensions["analyzer_service"].mysql_store.save_knowledge_document(
        project_id,
        path="docs/registration.md",
        content="# Registration\n\nPhone number must be unique before registering a user.",
        updated_by="admin",
    )
    client = app.test_client()
    _login(client)

    index_response = client.post(
        "/api/index",
        json={
            "projectId": project_id,
            "source": "mixed",
            "codePath": "src",
            "kbPath": "docs",
        },
    )
    index_payload = index_response.get_json()

    assert index_response.status_code == 200
    assert index_payload["count"] > 1

    query_response = client.post(
        "/api/query",
        json={
            "projectId": project_id,
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


def test_web_backend_api_mapping(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    frontend = workspace / "web" / "frontend" / "src"
    backend = workspace / "src" / "main" / "java" / "demo"
    frontend.mkdir(parents=True)
    backend.mkdir(parents=True)
    (frontend / "UserApi.ts").write_text(
        """
        export async function listUsers() {
          return await fetch('/api/users')
        }

        export async function saveUser(payload: unknown) {
          return await request.post('/api/users', payload)
        }
        """,
        encoding="utf-8",
    )
    (backend / "UserController.java").write_text(
        """
        package demo;

        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.PostMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @RequestMapping("/api/users")
        class UserController {
            @GetMapping
            String listUsers() {
                return "ok";
            }

            @PostMapping
            String saveUser() {
                return "ok";
            }
        }
        """,
        encoding="utf-8",
    )

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = workspace
        PROJECTS_DIR = ".projects"
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, workspace)
    client = app.test_client()
    _login(client)

    response = client.post(
        "/api/api-map",
        json={
            "projectId": project_id,
            "frontendPath": "web/frontend/src",
            "backendPath": "src/main/java",
        },
    )
    payload = response.get_json()

    assert response.status_code == 200
    assert payload["summary"]["frontendCalls"] == 2
    assert payload["summary"]["backendEndpoints"] == 2
    assert payload["summary"]["matched"] == 2
    assert payload["savedPath"] == f"mysql://analysis_outputs/{project_id}/api-map"


def test_web_backend_knowledge_file_crud(tmp_path) -> None:
    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = tmp_path
        PROJECTS_DIR = ".projects"
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, tmp_path)
    client = app.test_client()
    _login(client)

    create_response = client.post(
        "/api/kb/files",
        json={
            "projectId": project_id,
            "kbPath": "docs",
            "path": "domain/user-registration.md",
            "content": "# 用户注册\n\n手机号必须唯一。",
        },
    )
    create_payload = create_response.get_json()

    assert create_response.status_code == 201
    assert create_payload["file"]["path"] == "domain/user-registration.md"
    assert not (tmp_path / "docs" / "domain" / "user-registration.md").exists()

    list_response = client.get("/api/kb/files", query_string={"projectId": project_id, "kbPath": "docs"})
    assert list_response.status_code == 200
    assert list_response.get_json()["files"][0]["path"] == "domain/user-registration.md"

    get_response = client.get(
        "/api/kb/file",
        query_string={"projectId": project_id, "kbPath": "docs", "path": "domain/user-registration.md"},
    )
    assert get_response.status_code == 200
    assert "手机号必须唯一" in get_response.get_json()["content"]

    save_response = client.put(
        "/api/kb/file",
        json={
            "projectId": project_id,
            "kbPath": "docs",
            "path": "domain/user-registration.md",
            "content": "# 用户注册\n\n手机号和邮箱都需要校验。",
        },
    )
    assert save_response.status_code == 200
    assert "邮箱" in save_response.get_json()["content"]

    delete_response = client.delete(
        "/api/kb/file",
        json={"projectId": project_id, "kbPath": "docs", "path": "domain/user-registration.md"},
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

    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = tmp_path / "workspace"
        PROJECTS_DIR = ".projects"
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
    assert payload["savedPath"] == f"mysql://analysis_outputs/{project['id']}/report"
    assert "UserController" in payload["output"]


def test_web_backend_project_rejects_path_escape(tmp_path) -> None:
    class TestConfig(JsonTestConfig):
        WORKSPACE_ROOT = tmp_path
        PROJECTS_DIR = ".projects"
        TESTING = True

    app = create_app(TestConfig)
    project_id = _seed_project(app, tmp_path)
    client = app.test_client()
    _login(client)

    response = client.post("/api/analyze", json={"projectId": project_id, "path": "..", "source": "code"})

    assert response.status_code == 400
    assert "path must stay inside the workspace" in response.get_json()["error"]


def test_web_backend_project_access_is_user_scoped(tmp_path) -> None:
    class TestConfig(JsonTestConfig):
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
        "/api/users",
        json={"id": "viewer", "username": "viewer2", "displayName": "Viewer Two", "isAdmin": False},
    )
    assert update_user_response.status_code == 200
    assert update_user_response.get_json()["user"]["username"] == "viewer2"

    password_response = client.put("/api/users/password", json={"id": "viewer", "password": "secret2"})
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
    update_response = client.put("/api/users/access", json={"id": "viewer", "projectIds": ["demo"]})
    assert update_response.status_code == 200

    client.post("/api/auth/logout")
    _login(client, username="viewer2", password="secret2")
    visible_projects = client.get("/api/projects").get_json()["projects"]
    assert [project["id"] for project in visible_projects] == ["demo"]

    last_project_response = client.put("/api/auth/last-project", json={"projectId": "demo"})
    assert last_project_response.status_code == 200
    assert last_project_response.get_json()["user"]["lastProjectId"] == "demo"
    assert client.get("/api/auth/me").get_json()["user"]["lastProjectId"] == "demo"


def _login(client, username: str = "admin", password: str = "admin123") -> None:
    response = client.post("/api/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200


def _seed_project(app, workspace: Path, project_id: str = "demo", path: str = ".") -> str:
    service = app.extensions["analyzer_service"]
    service._save_projects(
        [
            ProjectRecord(
                id=project_id,
                name=project_id,
                gitUrl="local",
                branch="",
                path=path,
                createdAt="2026-06-22T00:00:00+00:00",
                updatedAt="2026-06-22T00:00:00+00:00",
                projectKey=project_id,
            )
        ]
    )
    store = service.mysql_store
    for user in store.users:
        if user["id"] == "admin" and project_id not in user["projectIds"]:
            user["projectIds"].append(project_id)
    return project_id


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
