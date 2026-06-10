from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
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
                cursor.execute(
                    """
                    create table if not exists knowledge_assets (
                      id bigint unsigned not null auto_increment comment '知识资产ID',
                      project_id bigint unsigned not null comment '项目ID',
                      asset_key varchar(128) not null comment '资产业务标识',
                      asset_type varchar(64) not null comment '知识类型',
                      title varchar(255) not null comment '标题',
                      summary text not null comment '摘要',
                      content mediumtext not null comment '正文',
                      lifecycle_status varchar(32) not null comment '治理状态',
                      owner_user_id varchar(64) not null default '' comment '负责人',
                      reviewer_user_id varchar(64) not null default '' comment '复审人',
                      source_path varchar(1024) not null default '' comment '来源文档',
                      confirmed_at varchar(64) not null default '' comment '确认时间',
                      review_due_at varchar(64) not null default '' comment '复审日期',
                      created_by varchar(64) not null default '' comment '创建人',
                      updated_by varchar(64) not null default '' comment '更新人',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_knowledge_assets_project_asset (project_id, asset_key),
                      key idx_knowledge_assets_project_status (project_id, lifecycle_status),
                      key idx_knowledge_assets_project_type (project_id, asset_type)
                    ) comment='知识资产表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists knowledge_asset_tags (
                      id bigint unsigned not null auto_increment comment '标签ID',
                      project_id bigint unsigned not null comment '项目ID',
                      asset_key varchar(128) not null comment '资产业务标识',
                      tag varchar(64) not null comment '标签',
                      sort_order int not null default 0 comment '排序',
                      primary key (id),
                      unique key uk_knowledge_asset_tags (project_id, asset_key, tag),
                      key idx_knowledge_asset_tags_asset (project_id, asset_key)
                    ) comment='知识资产标签表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists knowledge_asset_evidence (
                      id bigint unsigned not null auto_increment comment '证据ID',
                      project_id bigint unsigned not null comment '项目ID',
                      asset_key varchar(128) not null comment '资产业务标识',
                      sort_order int not null default 0 comment '排序',
                      evidence_type varchar(64) not null default 'file' comment '证据类型',
                      file_path varchar(1024) not null default '' comment '文件路径',
                      symbol_name varchar(255) not null default '' comment '符号名称',
                      start_line int not null default 0 comment '开始行',
                      end_line int not null default 0 comment '结束行',
                      note text not null comment '说明',
                      primary key (id),
                      key idx_knowledge_asset_evidence_asset (project_id, asset_key)
                    ) comment='知识资产证据表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists knowledge_templates (
                      id bigint unsigned not null auto_increment comment '模板ID',
                      project_id bigint unsigned not null comment '项目ID',
                      template_key varchar(128) not null comment '模板业务标识',
                      name varchar(128) not null comment '模板名称',
                      path varchar(1024) not null comment '默认路径',
                      content mediumtext not null comment '模板内容',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_knowledge_templates_project_template (project_id, template_key)
                    ) comment='知识模板表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists vector_records (
                      id bigint unsigned not null auto_increment comment '索引记录ID',
                      project_id bigint unsigned not null comment '项目ID',
                      record_hash char(64) not null comment '记录标识哈希',
                      record_id varchar(1024) not null comment '记录业务标识',
                      source_type varchar(64) not null default '' comment '来源类型',
                      kind varchar(128) not null default '' comment '记录类型',
                      text mediumtext not null comment '检索文本',
                      metadata_json longtext not null comment '元数据JSON',
                      embedding_json longtext not null comment '向量JSON',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_vector_records_project_hash (project_id, record_hash),
                      key idx_vector_records_project_source (project_id, source_type),
                      key idx_vector_records_project_kind (project_id, kind)
                    ) comment='向量索引记录表'
                    """
                )
            connection.commit()

    def migrate_from_json(self, users_file: Path, projects_file: Path, projects_dir: Path | None = None) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("select count(*) as count from users where status = 'active'")
                has_state = int(cursor.fetchone()["count"]) > 0

                project_key_to_id: dict[str, str] = {}
                if not has_state and projects_file.exists():
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

                if not has_state and users_file.exists():
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

                cursor.execute("select id, project_key from projects where status = 'active'")
                project_key_to_id.update({str(row["project_key"]): str(row["id"]) for row in cursor.fetchall()})
                if projects_dir is not None:
                    self._migrate_project_json_files(cursor, project_key_to_id, projects_dir)
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

    def list_knowledge_assets(self, project_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select asset_key, asset_type, title, summary, content, lifecycle_status,
                           owner_user_id, reviewer_user_id, source_path, confirmed_at, review_due_at,
                           created_by, updated_by, created_at, updated_at
                    from knowledge_assets
                    where project_id = %s
                    order by updated_at desc, id desc
                    """,
                    (project_id,),
                )
                assets = cursor.fetchall()
                cursor.execute(
                    """
                    select asset_key, tag
                    from knowledge_asset_tags
                    where project_id = %s
                    order by asset_key, sort_order, id
                    """,
                    (project_id,),
                )
                tags = cursor.fetchall()
                cursor.execute(
                    """
                    select asset_key, evidence_type, file_path, symbol_name, start_line, end_line, note
                    from knowledge_asset_evidence
                    where project_id = %s
                    order by asset_key, sort_order, id
                    """,
                    (project_id,),
                )
                evidence = cursor.fetchall()

        tags_by_asset: dict[str, list[str]] = {}
        for row in tags:
            tags_by_asset.setdefault(str(row["asset_key"]), []).append(str(row["tag"]))

        evidence_by_asset: dict[str, list[dict[str, Any]]] = {}
        for row in evidence:
            evidence_by_asset.setdefault(str(row["asset_key"]), []).append(
                {
                    "type": row["evidence_type"],
                    "filePath": row["file_path"],
                    "symbolName": row["symbol_name"],
                    "startLine": int(row["start_line"] or 0),
                    "endLine": int(row["end_line"] or 0),
                    "note": row["note"],
                }
            )

        return [
            {
                "id": str(row["asset_key"]),
                "type": row["asset_type"],
                "title": row["title"],
                "summary": row["summary"],
                "content": row["content"],
                "status": row["lifecycle_status"],
                "ownerUserId": str(row["owner_user_id"] or ""),
                "reviewerUserId": str(row["reviewer_user_id"] or ""),
                "tags": tags_by_asset.get(str(row["asset_key"]), []),
                "evidence": evidence_by_asset.get(str(row["asset_key"]), []),
                "sourcePath": row["source_path"],
                "confirmedAt": row["confirmed_at"],
                "reviewDueAt": row["review_due_at"],
                "createdAt": iso_datetime(row["created_at"]),
                "updatedAt": iso_datetime(row["updated_at"]),
                "createdBy": str(row["created_by"] or ""),
                "updatedBy": str(row["updated_by"] or ""),
            }
            for row in assets
        ]

    def save_knowledge_assets(self, project_id: str, assets: list[Any]) -> None:
        now = datetime.now(timezone.utc).isoformat()
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("delete from knowledge_asset_tags where project_id = %s", (project_id,))
                cursor.execute("delete from knowledge_asset_evidence where project_id = %s", (project_id,))
                cursor.execute("delete from knowledge_assets where project_id = %s", (project_id,))
                for asset in assets:
                    data = asdict(asset)
                    created_at = data.get("createdAt") or now
                    updated_at = data.get("updatedAt") or now
                    cursor.execute(
                        """
                        insert into knowledge_assets
                          (project_id, asset_key, asset_type, title, summary, content, lifecycle_status,
                           owner_user_id, reviewer_user_id, source_path, confirmed_at, review_due_at,
                           created_by, updated_by, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            project_id,
                            data["id"],
                            data["type"],
                            data["title"],
                            data["summary"],
                            data["content"],
                            data["status"],
                            data["ownerUserId"],
                            data["reviewerUserId"],
                            data["sourcePath"],
                            data["confirmedAt"],
                            data["reviewDueAt"],
                            data["createdBy"],
                            data["updatedBy"],
                            mysql_datetime(created_at),
                            mysql_datetime(updated_at),
                        ),
                    )
                    for index, tag in enumerate(data.get("tags", [])):
                        cursor.execute(
                            """
                            insert into knowledge_asset_tags (project_id, asset_key, tag, sort_order)
                            values (%s, %s, %s, %s)
                            """,
                            (project_id, data["id"], str(tag), index),
                        )
                    for index, evidence in enumerate(data.get("evidence", [])):
                        cursor.execute(
                            """
                            insert into knowledge_asset_evidence
                              (project_id, asset_key, sort_order, evidence_type, file_path, symbol_name, start_line, end_line, note)
                            values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                            """,
                            (
                                project_id,
                                data["id"],
                                index,
                                str(evidence.get("type") or "file"),
                                str(evidence.get("filePath") or ""),
                                str(evidence.get("symbolName") or ""),
                                int(evidence.get("startLine") or 0),
                                int(evidence.get("endLine") or 0),
                                str(evidence.get("note") or ""),
                            ),
                        )
            connection.commit()

    def list_kb_templates(self, project_id: str) -> list[dict[str, str]]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select template_key, name, path, content, created_at, updated_at
                    from knowledge_templates
                    where project_id = %s
                    order by id
                    """,
                    (project_id,),
                )
                rows = cursor.fetchall()
        return [
            {
                "id": str(row["template_key"]),
                "name": row["name"],
                "path": row["path"],
                "content": row["content"],
                "createdAt": iso_datetime(row["created_at"]),
                "updatedAt": iso_datetime(row["updated_at"]),
            }
            for row in rows
        ]

    def save_kb_templates(self, project_id: str, templates: list[dict[str, str]]) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("delete from knowledge_templates where project_id = %s", (project_id,))
                for template in templates:
                    cursor.execute(
                        """
                        insert into knowledge_templates
                          (project_id, template_key, name, path, content, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            project_id,
                            template["id"],
                            template["name"],
                            template["path"],
                            template["content"],
                            mysql_datetime(template["createdAt"]),
                            mysql_datetime(template["updatedAt"]),
                        ),
                    )
            connection.commit()

    def list_vector_records(self, project_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select record_id, text, metadata_json, embedding_json
                    from vector_records
                    where project_id = %s
                    order by id
                    """,
                    (project_id,),
                )
                rows = cursor.fetchall()
        return [
            {
                "id": row["record_id"],
                "text": row["text"],
                "metadata": json.loads(row["metadata_json"] or "{}"),
                "embedding": json.loads(row["embedding_json"] or "[]"),
            }
            for row in rows
        ]

    def save_vector_records(self, project_id: str, records: list[Any], append: bool) -> None:
        now = mysql_datetime(datetime.now(timezone.utc).isoformat())
        with self.connect() as connection:
            with connection.cursor() as cursor:
                if not append:
                    cursor.execute("delete from vector_records where project_id = %s", (project_id,))
                for record in records:
                    data = asdict(record)
                    metadata = data.get("metadata", {})
                    cursor.execute(
                        """
                        insert into vector_records
                          (project_id, record_hash, record_id, source_type, kind, text, metadata_json, embedding_json, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        on duplicate key update
                          record_id = values(record_id),
                          source_type = values(source_type),
                          kind = values(kind),
                          text = values(text),
                          metadata_json = values(metadata_json),
                          embedding_json = values(embedding_json),
                          updated_at = values(updated_at)
                        """,
                        (
                            project_id,
                            hashlib.sha256(str(data["id"]).encode("utf-8")).hexdigest(),
                            data["id"],
                            str(metadata.get("source_type") or ""),
                            str(metadata.get("kind") or ""),
                            data["text"],
                            json.dumps(metadata, ensure_ascii=False),
                            json.dumps(data.get("embedding", []), ensure_ascii=False),
                            now,
                            now,
                        ),
                    )
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

    def _migrate_project_json_files(self, cursor: Any, project_key_to_id: dict[str, str], projects_dir: Path) -> None:
        for project_key, project_id in project_key_to_id.items():
            data_root = projects_dir / project_key
            assets_file = data_root / "knowledge_assets.json"
            cursor.execute("select count(*) as count from knowledge_assets where project_id = %s", (project_id,))
            if assets_file.exists() and int(cursor.fetchone()["count"]) == 0:
                data = json.loads(assets_file.read_text(encoding="utf-8"))
                assets = data.get("assets", [])
                for asset in assets:
                    cursor.execute(
                        """
                        insert into knowledge_assets
                          (project_id, asset_key, asset_type, title, summary, content, lifecycle_status,
                           owner_user_id, reviewer_user_id, source_path, confirmed_at, review_due_at,
                           created_by, updated_by, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            project_id,
                            str(asset.get("id") or ""),
                            str(asset.get("type") or "business_rule"),
                            str(asset.get("title") or ""),
                            str(asset.get("summary") or ""),
                            str(asset.get("content") or ""),
                            str(asset.get("status") or "draft"),
                            str(asset.get("ownerUserId") or ""),
                            str(asset.get("reviewerUserId") or ""),
                            str(asset.get("sourcePath") or ""),
                            str(asset.get("confirmedAt") or ""),
                            str(asset.get("reviewDueAt") or ""),
                            str(asset.get("createdBy") or ""),
                            str(asset.get("updatedBy") or ""),
                            mysql_datetime(str(asset.get("createdAt") or "")),
                            mysql_datetime(str(asset.get("updatedAt") or "")),
                        ),
                    )
                    for index, tag in enumerate(asset.get("tags", [])):
                        cursor.execute(
                            "insert ignore into knowledge_asset_tags (project_id, asset_key, tag, sort_order) values (%s, %s, %s, %s)",
                            (project_id, str(asset.get("id") or ""), str(tag), index),
                        )
                    for index, evidence in enumerate(asset.get("evidence", [])):
                        if not isinstance(evidence, dict):
                            continue
                        cursor.execute(
                            """
                            insert into knowledge_asset_evidence
                              (project_id, asset_key, sort_order, evidence_type, file_path, symbol_name, start_line, end_line, note)
                            values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                            """,
                            (
                                project_id,
                                str(asset.get("id") or ""),
                                index,
                                str(evidence.get("type") or "file"),
                                str(evidence.get("filePath") or ""),
                                str(evidence.get("symbolName") or ""),
                                int(evidence.get("startLine") or 0),
                                int(evidence.get("endLine") or 0),
                                str(evidence.get("note") or ""),
                            ),
                        )

            templates_file = data_root / "knowledge_templates.json"
            cursor.execute("select count(*) as count from knowledge_templates where project_id = %s", (project_id,))
            if templates_file.exists() and int(cursor.fetchone()["count"]) == 0:
                data = json.loads(templates_file.read_text(encoding="utf-8"))
                for template in data.get("templates", []):
                    cursor.execute(
                        """
                        insert into knowledge_templates
                          (project_id, template_key, name, path, content, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            project_id,
                            str(template.get("id") or ""),
                            str(template.get("name") or ""),
                            str(template.get("path") or ""),
                            str(template.get("content") or ""),
                            mysql_datetime(str(template.get("createdAt") or "")),
                            mysql_datetime(str(template.get("updatedAt") or "")),
                        ),
                    )
