from __future__ import annotations

from dataclasses import asdict
from typing import Any

from werkzeug.security import check_password_hash, generate_password_hash

from web.backend.errors import APIError
from web.backend.service_models import UserRecord, utc_now


class AuthServiceMixin:
    def health(self) -> dict[str, Any]:
        return {"ok": True, "workspace": str(self.workspace_root)}

    def authenticate(self, payload: dict[str, Any]) -> dict[str, Any]:
        username = str(payload.get("username", "")).strip()
        password = str(payload.get("password", ""))
        if not username or not password:
            raise APIError("username and password are required.", 400)
        for user in self._load_users():
            if user.username == username and check_password_hash(user.passwordHash, password):
                return {"user": self._public_user(user)}
        raise APIError("invalid username or password.", 401)

    def require_user(self, user_id: object) -> "UserRecord":
        value = str(user_id or "").strip()
        if not value:
            raise APIError("login required.", 401)
        return self._require_user(value)

    def current_user(self, user_id: object) -> dict[str, Any]:
        return {"user": self._public_user(self.require_user(user_id))}

    def list_users(self, actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        return {"users": [self._public_user(user) for user in self._load_users()]}

    def create_user(self, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        username = str(payload.get("username", "")).strip()
        password = str(payload.get("password", "")).strip()
        display_name = str(payload.get("displayName", "")).strip() or username
        project_ids = [str(item).strip() for item in payload.get("projectIds", []) if str(item).strip()]
        if not username:
            raise APIError("username is required.", 400)
        if len(password) < 6:
            raise APIError("password must be at least 6 characters.", 400)
        users = self._load_users()
        if any(user.username == username for user in users):
            raise APIError("username already exists.", 409)
        self._validate_project_ids(project_ids)
        now = utc_now()
        user = UserRecord(
            id="",
            username=username,
            displayName=display_name,
            passwordHash=generate_password_hash(password),
            isAdmin=bool(payload.get("isAdmin", False)),
            lastProjectId="",
            projectIds=project_ids,
            createdAt=now,
            updatedAt=now,
        )
        user = UserRecord(**self.mysql_store.insert_user(asdict(user)))
        return {"user": self._public_user(user)}

    def update_user(self, user_id: str, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        if not user_id:
            raise APIError("user id is required.", 400)
        username = str(payload.get("username", "")).strip()
        display_name = str(payload.get("displayName", "")).strip() or username
        if not username:
            raise APIError("username is required.", 400)
        users = self._load_users()
        if any(user.id != user_id and user.username == username for user in users):
            raise APIError("username already exists.", 409)
        for user in users:
            if user.id == user_id:
                user.username = username
                user.displayName = display_name
                user.isAdmin = bool(payload.get("isAdmin", False))
                user.updatedAt = utc_now()
                self._ensure_admin_remains(users)
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def update_user_password(self, user_id: str, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        if not user_id:
            raise APIError("user id is required.", 400)
        password = str(payload.get("password", "")).strip()
        if len(password) < 6:
            raise APIError("password must be at least 6 characters.", 400)
        users = self._load_users()
        for user in users:
            if user.id == user_id:
                user.passwordHash = generate_password_hash(password)
                user.updatedAt = utc_now()
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def update_user_access(self, user_id: str, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        self._require_admin(actor)
        if not user_id:
            raise APIError("user id is required.", 400)
        project_ids = [str(item).strip() for item in payload.get("projectIds", []) if str(item).strip()]
        self._validate_project_ids(project_ids)
        users = self._load_users()
        for user in users:
            if user.id == user_id:
                user.projectIds = project_ids
                user.updatedAt = utc_now()
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)

    def update_last_project(self, payload: dict[str, Any], actor: "UserRecord") -> dict[str, Any]:
        project_id = str(payload.get("projectId") or "").strip()
        if project_id:
            self._require_project(project_id, actor)
        users = self._load_users()
        for user in users:
            if user.id == actor.id:
                user.lastProjectId = project_id
                user.updatedAt = utc_now()
                self._save_users(users)
                return {"user": self._public_user(user)}
        raise APIError("user not found.", 404)
