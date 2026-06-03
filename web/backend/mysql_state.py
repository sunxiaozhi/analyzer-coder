from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pymysql
from pymysql.cursors import DictCursor
from werkzeug.security import generate_password_hash

from web.backend.errors import APIError


def iso_datetime(value: Any) -> str:
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.isoformat()
    return str(value)


def mysql_datetime(value: str) -> str:
    if not value:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")
    return value.replace("T", " ").replace("+00:00", "")


class MySqlStateStore:
    def __init__(
        self,
        host: str,
        port: int,
        user: str,
        password: str,
        database: str,
    ) -> None:
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.database = database

    def connect(self, database: bool = True):
        return pymysql.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.database if database else None,
            charset="utf8mb4",
            cursorclass=DictCursor,
            autocommit=False,
        )

    def initialize(self) -> None:
        with self.connect(database=False) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    f"create database if not exists `{self.database}` "
                    "default character set utf8mb4 collate utf8mb4_unicode_ci"
                )
            connection.commit()

        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    create table if not exists users (
                      id bigint unsigned not null auto_increment comment '用户ID',
                      username varchar(64) not null comment '登录账号',
                      display_name varchar(128) not null comment '显示名称',
                      password_hash varchar(255) not null comment '密码哈希',
                      is_admin tinyint(1) not null default 0 comment '是否管理员',
                      last_project_id bigint unsigned null comment '最后选择项目ID',
                      status varchar(32) not null default 'active' comment '状态',
                      deleted_at datetime(6) null comment '删除时间',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_users_username (username),
                      key idx_users_status (status)
                    ) comment='用户表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists projects (
                      id bigint unsigned not null auto_increment comment '项目ID',
                      project_key varchar(64) not null comment '项目业务标识',
                      name varchar(128) not null comment '项目名称',
                      git_url varchar(512) not null comment 'Git地址',
                      branch varchar(128) not null default '' comment '分支',
                      path varchar(1024) not null comment '本地项目路径',
                      status varchar(32) not null default 'active' comment '状态',
                      deleted_at datetime(6) null comment '删除时间',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_projects_project_key (project_key),
                      key idx_projects_status (status)
                    ) comment='项目表'
                    """
                )
                self._ensure_column(
                    cursor,
                    "users",
                    "last_project_id",
                    "bigint unsigned null comment '最后选择项目ID'",
                )
                cursor.execute(
                    """
                    create table if not exists user_project_access (
                      id bigint unsigned not null auto_increment comment '授权ID',
                      user_id bigint unsigned not null comment '用户ID',
                      project_id bigint unsigned not null comment '项目ID',
                      status varchar(32) not null default 'active' comment '状态',
                      deleted_at datetime(6) null comment '删除时间',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_user_project_access (user_id, project_id),
                      key idx_user_project_access_user_id (user_id),
                      key idx_user_project_access_project_id (project_id),
                      key idx_user_project_access_status (status)
                    ) comment='用户项目授权表'
                    """
                )
            connection.commit()

    def migrate_from_json(self, users_file: Path, projects_file: Path) -> None:
        import json

        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("select count(*) as count from users where status = 'active'")
                if int(cursor.fetchone()["count"]) > 0:
                    return

                project_key_to_id: dict[str, str] = {}
                if projects_file.exists():
                    data = json.loads(projects_file.read_text(encoding="utf-8"))
                    for item in data.get("projects", []):
                        cursor.execute(
                            """
                            insert into projects
                              (project_key, name, git_url, branch, path, status, created_at, updated_at)
                            values (%s, %s, %s, %s, %s, 'active', %s, %s)
                            """,
                            (
                                item["id"],
                                item["name"],
                                item["gitUrl"],
                                item.get("branch", ""),
                                item["path"],
                                mysql_datetime(item.get("createdAt", "")),
                                mysql_datetime(item.get("updatedAt", "")),
                            ),
                        )
                        project_key_to_id[item["id"]] = str(cursor.lastrowid)

                if users_file.exists():
                    data = json.loads(users_file.read_text(encoding="utf-8"))
                    for item in data.get("users", []):
                        cursor.execute(
                            """
                            insert into users
                              (username, display_name, password_hash, is_admin, status, created_at, updated_at)
                            values (%s, %s, %s, %s, 'active', %s, %s)
                            """,
                            (
                                item["username"],
                                item.get("displayName") or item["username"],
                                item["passwordHash"],
                                1 if item.get("isAdmin") else 0,
                                mysql_datetime(item.get("createdAt", "")),
                                mysql_datetime(item.get("updatedAt", "")),
                            ),
                        )
                        user_id = str(cursor.lastrowid)
                        for project_key in item.get("projectIds", []):
                            project_id = project_key_to_id.get(str(project_key))
                            if project_id:
                                self._grant_access(cursor, user_id, project_id, mysql_datetime(item.get("updatedAt", "")))
            connection.commit()

    def ensure_admin(self, username: str, password: str, now: str) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("select count(*) as count from users where status = 'active'")
                if int(cursor.fetchone()["count"]) == 0:
                    cursor.execute(
                        """
                        insert into users
                          (username, display_name, password_hash, is_admin, status, created_at, updated_at)
                        values (%s, %s, %s, 1, 'active', %s, %s)
                        """,
                        (username, "系统管理员", generate_password_hash(password), mysql_datetime(now), mysql_datetime(now)),
                    )
            connection.commit()

    def list_users(self) -> list[dict[str, Any]]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, username, display_name, password_hash, is_admin,
                           last_project_id, created_at, updated_at
                    from users
                    where status = 'active'
                    order by id
                    """
                )
                users = cursor.fetchall()
                cursor.execute(
                    """
                    select user_id, project_id
                    from user_project_access
                    where status = 'active'
                    order by id
                    """
                )
                access = cursor.fetchall()

        project_ids_by_user: dict[str, list[str]] = {}
        for row in access:
            project_ids_by_user.setdefault(str(row["user_id"]), []).append(str(row["project_id"]))

        return [
            {
                "id": str(row["id"]),
                "username": row["username"],
                "displayName": row["display_name"],
                "passwordHash": row["password_hash"],
                "isAdmin": bool(row["is_admin"]),
                "lastProjectId": str(row["last_project_id"] or ""),
                "projectIds": project_ids_by_user.get(str(row["id"]), []),
                "createdAt": iso_datetime(row["created_at"]),
                "updatedAt": iso_datetime(row["updated_at"]),
            }
            for row in users
        ]

    def insert_user(self, data: dict[str, Any]) -> dict[str, Any]:
        with self.connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        insert into users
                          (username, display_name, password_hash, is_admin, status, created_at, updated_at)
                        values (%s, %s, %s, %s, 'active', %s, %s)
                        """,
                        (
                            data["username"],
                            data["displayName"],
                            data["passwordHash"],
                            1 if data["isAdmin"] else 0,
                            mysql_datetime(data["createdAt"]),
                            mysql_datetime(data["updatedAt"]),
                        ),
                    )
                    user_id = str(cursor.lastrowid)
                    self._sync_access(cursor, user_id, data.get("projectIds", []), data["updatedAt"])
                connection.commit()
            except pymysql.err.IntegrityError as exc:
                connection.rollback()
                raise APIError("username already exists.", 409) from exc
        return self.get_user(user_id)

    def save_users(self, users: list[Any]) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                for user in users:
                    data = asdict(user)
                    cursor.execute(
                        """
                        update users
                        set username = %s, display_name = %s, password_hash = %s,
                            is_admin = %s, last_project_id = %s, updated_at = %s
                        where id = %s and status = 'active'
                        """,
                        (
                            data["username"],
                            data["displayName"],
                            data["passwordHash"],
                            1 if data["isAdmin"] else 0,
                            data.get("lastProjectId") or None,
                            mysql_datetime(data["updatedAt"]),
                            data["id"],
                        ),
                    )
                    self._sync_access(cursor, data["id"], data.get("projectIds", []), data["updatedAt"])
            connection.commit()

    def list_projects(self) -> list[dict[str, Any]]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select id, project_key, name, git_url, branch, path, created_at, updated_at
                    from projects
                    where status = 'active'
                    order by id
                    """
                )
                rows = cursor.fetchall()
        return [
            {
                "id": str(row["id"]),
                "projectKey": row["project_key"],
                "name": row["name"],
                "gitUrl": row["git_url"],
                "branch": row["branch"],
                "path": row["path"],
                "createdAt": iso_datetime(row["created_at"]),
                "updatedAt": iso_datetime(row["updated_at"]),
            }
            for row in rows
        ]

    def insert_project(self, data: dict[str, Any]) -> dict[str, Any]:
        with self.connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        insert into projects
                          (project_key, name, git_url, branch, path, status, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, 'active', %s, %s)
                        """,
                        (
                            data["projectKey"],
                            data["name"],
                            data["gitUrl"],
                            data["branch"],
                            data["path"],
                            mysql_datetime(data["createdAt"]),
                            mysql_datetime(data["updatedAt"]),
                        ),
                    )
                    project_id = str(cursor.lastrowid)
                connection.commit()
            except pymysql.err.IntegrityError as exc:
                connection.rollback()
                raise APIError("project already exists.", 409) from exc
        return self.get_project(project_id)

    def save_projects(self, projects: list[Any]) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                for project in projects:
                    data = asdict(project)
                    cursor.execute(
                        """
                        update projects
                        set project_key = %s, name = %s, git_url = %s, branch = %s,
                            path = %s, updated_at = %s
                        where id = %s and status = 'active'
                        """,
                        (
                            data.get("projectKey") or data["id"],
                            data["name"],
                            data["gitUrl"],
                            data["branch"],
                            data["path"],
                            mysql_datetime(data["updatedAt"]),
                            data["id"],
                        ),
                    )
            connection.commit()

    def get_user(self, user_id: str) -> dict[str, Any]:
        users = [user for user in self.list_users() if user["id"] == str(user_id)]
        if not users:
            raise APIError("user not found.", 404)
        return users[0]

    def get_project(self, project_id: str) -> dict[str, Any]:
        projects = [project for project in self.list_projects() if project["id"] == str(project_id)]
        if not projects:
            raise APIError("project not found.", 404)
        return projects[0]

    def update_last_project(self, user_id: str, project_id: str) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    update users
                    set last_project_id = %s, updated_at = %s
                    where id = %s and status = 'active'
                    """,
                    (project_id or None, mysql_datetime(datetime.now(timezone.utc).isoformat()), user_id),
                )
            connection.commit()

    def grant_access(self, user_id: str, project_id: str, now: str) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                self._grant_access(cursor, user_id, project_id, mysql_datetime(now))
            connection.commit()

    def _sync_access(self, cursor: Any, user_id: str, project_ids: list[str], now: str) -> None:
        cursor.execute(
            "update user_project_access set status = 'deleted', deleted_at = %s, updated_at = %s where user_id = %s",
            (mysql_datetime(now), mysql_datetime(now), user_id),
        )
        for project_id in project_ids:
            self._grant_access(cursor, user_id, project_id, mysql_datetime(now))

    def _grant_access(self, cursor: Any, user_id: str, project_id: str, now: str) -> None:
        cursor.execute(
            """
            insert into user_project_access
              (user_id, project_id, status, deleted_at, created_at, updated_at)
            values (%s, %s, 'active', null, %s, %s)
            on duplicate key update status = 'active', deleted_at = null, updated_at = values(updated_at)
            """,
            (user_id, project_id, now, now),
        )

    def _ensure_column(self, cursor: Any, table: str, column: str, definition: str) -> None:
        cursor.execute(
            """
            select count(*) as count
            from information_schema.columns
            where table_schema = %s and table_name = %s and column_name = %s
            """,
            (self.database, table, column),
        )
        if int(cursor.fetchone()["count"]) == 0:
            cursor.execute(f"alter table `{table}` add column `{column}` {definition}")
