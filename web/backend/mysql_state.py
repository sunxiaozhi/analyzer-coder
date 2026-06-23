from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
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
                    create table if not exists knowledge_documents (
                      id bigint unsigned not null auto_increment comment '知识文档ID',
                      project_id bigint unsigned not null comment '项目ID',
                      document_path varchar(1024) not null comment '文档路径',
                      name varchar(255) not null comment '文件名',
                      content mediumtext not null comment '正文',
                      size_bytes int unsigned not null default 0 comment '字节数',
                      updated_by varchar(64) not null default '' comment '更新人',
                      status varchar(32) not null default 'active' comment '状态',
                      deleted_at datetime(6) null comment '删除时间',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_knowledge_documents_project_path (project_id, document_path(255)),
                      key idx_knowledge_documents_project_status (project_id, status)
                    ) comment='知识文档表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists analysis_outputs (
                      id bigint unsigned not null auto_increment comment '分析输出ID',
                      project_id bigint unsigned not null comment '项目ID',
                      output_mode varchar(64) not null comment '输出模式',
                      extension varchar(16) not null default '' comment '扩展名',
                      output_text longtext not null comment '输出内容',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_analysis_outputs_project_mode (project_id, output_mode)
                    ) comment='分析输出快照表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists analysis_records (
                      id bigint unsigned not null auto_increment comment '解析记录ID',
                      project_id bigint unsigned not null comment '项目ID',
                      record_hash char(64) not null comment '记录标识哈希',
                      record_key varchar(1024) not null comment '记录业务标识',
                      record_type varchar(64) not null comment '解析数据类型',
                      file_path varchar(1024) not null default '' comment '文件路径',
                      package_name varchar(512) not null default '' comment '包名',
                      symbol_name varchar(255) not null default '' comment '符号名称',
                      enclosing_type varchar(255) not null default '' comment '所属类型',
                      enclosing_method varchar(255) not null default '' comment '所属方法',
                      start_line int not null default 0 comment '开始行',
                      start_column int not null default 0 comment '开始列',
                      end_line int not null default 0 comment '结束行',
                      end_column int not null default 0 comment '结束列',
                      payload_json longtext not null comment '解析数据JSON',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_analysis_records_project_hash (project_id, record_hash),
                      key idx_analysis_records_project_type (project_id, record_type),
                      key idx_analysis_records_project_file (project_id, file_path(255)),
                      key idx_analysis_records_project_symbol (project_id, symbol_name)
                    ) comment='代码解析类型化记录表'
                    """
                )
                cursor.execute(
                    """
                    create table if not exists knowledge_asset_record_links (
                      id bigint unsigned not null auto_increment comment '知识资产解析记录关联ID',
                      project_id bigint unsigned not null comment '项目ID',
                      asset_key varchar(128) not null comment '资产业务标识',
                      analysis_record_hash char(64) not null comment '解析记录哈希',
                      analysis_record_key varchar(1024) not null comment '解析记录业务标识',
                      analysis_record_type varchar(64) not null comment '解析记录类型',
                      link_type varchar(64) not null default 'evidence' comment '关联类型',
                      evidence_type varchar(64) not null default 'file' comment '证据类型',
                      evidence_file_path varchar(1024) not null default '' comment '证据文件路径',
                      evidence_symbol_name varchar(255) not null default '' comment '证据符号名称',
                      note text not null comment '说明',
                      created_at datetime(6) not null comment '创建时间',
                      updated_at datetime(6) not null comment '更新时间',
                      primary key (id),
                      unique key uk_knowledge_asset_record_links (
                        project_id, asset_key, analysis_record_hash, link_type
                      ),
                      key idx_knowledge_asset_record_links_asset (project_id, asset_key),
                      key idx_knowledge_asset_record_links_record (project_id, analysis_record_hash)
                    ) comment='知识资产解析记录关联表'
                    """
                )
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

    def list_knowledge_documents(self, project_id: str, prefix: str = "") -> list[dict[str, Any]]:
        where = ["project_id = %s", "status = 'active'"]
        params: list[Any] = [project_id]
        if prefix:
            where.append("document_path like %s")
            params.append(f"{prefix.rstrip('/')}/%")
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    f"""
                    select document_path, name, content, size_bytes, updated_at
                    from knowledge_documents
                    where {' and '.join(where)}
                    order by document_path
                    """,
                    params,
                )
                rows = cursor.fetchall()
        return [
            {
                "path": row["document_path"],
                "name": row["name"],
                "size": int(row["size_bytes"] or 0),
                "updatedAt": iso_datetime(row["updated_at"]),
            }
            for row in rows
        ]

    def get_knowledge_document(self, project_id: str, path: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select document_path, name, content, size_bytes, updated_at
                    from knowledge_documents
                    where project_id = %s and document_path = %s and status = 'active'
                    """,
                    (project_id, path),
                )
                row = cursor.fetchone()
        if not row:
            return None
        return {
            "path": row["document_path"],
            "name": row["name"],
            "content": row["content"],
            "size": int(row["size_bytes"] or 0),
            "updatedAt": iso_datetime(row["updated_at"]),
        }

    def save_knowledge_document(self, project_id: str, *, path: str, content: str, updated_by: str) -> dict[str, Any]:
        now = mysql_datetime(datetime.now(timezone.utc).isoformat())
        name = path.rsplit("/", 1)[-1]
        size = len(content.encode("utf-8"))
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    insert into knowledge_documents
                      (project_id, document_path, name, content, size_bytes, updated_by, status, deleted_at, created_at, updated_at)
                    values (%s, %s, %s, %s, %s, %s, 'active', null, %s, %s)
                    on duplicate key update
                      name = values(name),
                      content = values(content),
                      size_bytes = values(size_bytes),
                      updated_by = values(updated_by),
                      status = 'active',
                      deleted_at = null,
                      updated_at = values(updated_at)
                    """,
                    (project_id, path, name, content, size, updated_by, now, now),
                )
            connection.commit()
        return {
            "path": path,
            "name": name,
            "content": content,
            "size": size,
            "updatedAt": iso_datetime(now),
        }

    def delete_knowledge_document(self, project_id: str, path: str) -> bool:
        now = mysql_datetime(datetime.now(timezone.utc).isoformat())
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    update knowledge_documents
                    set status = 'deleted', deleted_at = %s, updated_at = %s
                    where project_id = %s and document_path = %s and status = 'active'
                    """,
                    (now, now, project_id, path),
                )
                deleted = cursor.rowcount > 0
            connection.commit()
        return deleted

    def save_analysis_output(self, project_id: str, *, mode: str, extension: str, output: str) -> dict[str, Any]:
        now = mysql_datetime(datetime.now(timezone.utc).isoformat())
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    insert into analysis_outputs
                      (project_id, output_mode, extension, output_text, created_at, updated_at)
                    values (%s, %s, %s, %s, %s, %s)
                    on duplicate key update
                      extension = values(extension),
                      output_text = values(output_text),
                      updated_at = values(updated_at)
                    """,
                    (project_id, mode, extension, output, now, now),
                )
            connection.commit()
        return {
            "mode": mode,
            "extension": extension,
            "output": output,
            "uri": f"mysql://analysis_outputs/{project_id}/{mode}",
            "updatedAt": iso_datetime(now),
        }

    def get_analysis_output(self, project_id: str, mode: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select output_mode, extension, output_text, updated_at
                    from analysis_outputs
                    where project_id = %s and output_mode = %s
                    """,
                    (project_id, mode),
                )
                row = cursor.fetchone()
        if not row:
            return None
        return {
            "mode": row["output_mode"],
            "extension": row["extension"],
            "output": row["output_text"],
            "uri": f"mysql://analysis_outputs/{project_id}/{row['output_mode']}",
            "updatedAt": iso_datetime(row["updated_at"]),
        }

    def list_analysis_records(
        self,
        project_id: str,
        record_type: str = "",
        limit: int = 200,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        where = ["project_id = %s"]
        params: list[Any] = [project_id]
        if record_type:
            where.append("record_type = %s")
            params.append(record_type)
        params.extend([limit, offset])
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    f"""
                    select record_key, record_type, file_path, package_name, symbol_name,
                           enclosing_type, enclosing_method, start_line, start_column,
                           end_line, end_column, payload_json, updated_at
                    from analysis_records
                    where {' and '.join(where)}
                    order by record_type, file_path, start_line, id
                    limit %s offset %s
                    """,
                    params,
                )
                rows = cursor.fetchall()
        return [
            {
                "key": row["record_key"],
                "type": row["record_type"],
                "filePath": row["file_path"],
                "package": row["package_name"],
                "symbolName": row["symbol_name"],
                "enclosingType": row["enclosing_type"],
                "enclosingMethod": row["enclosing_method"],
                "startLine": int(row["start_line"] or 0),
                "startColumn": int(row["start_column"] or 0),
                "endLine": int(row["end_line"] or 0),
                "endColumn": int(row["end_column"] or 0),
                "payload": json.loads(row["payload_json"] or "{}"),
                "updatedAt": iso_datetime(row["updated_at"]),
            }
            for row in rows
        ]

    def count_analysis_records_by_type(self, project_id: str) -> dict[str, int]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select record_type, count(*) as count
                    from analysis_records
                    where project_id = %s
                    group by record_type
                    order by record_type
                    """,
                    (project_id,),
                )
                rows = cursor.fetchall()
        return {str(row["record_type"]): int(row["count"]) for row in rows}

    def refresh_knowledge_asset_record_links(self, project_id: str) -> list[dict[str, Any]]:
        assets = self.list_knowledge_assets(project_id)
        records = self.list_analysis_records(project_id, limit=1000000, offset=0)
        links = _match_knowledge_asset_record_links(assets, records)
        now = mysql_datetime(datetime.now(timezone.utc).isoformat())
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("delete from knowledge_asset_record_links where project_id = %s", (project_id,))
                for link in links:
                    record_key = str(link["analysisRecordKey"])
                    cursor.execute(
                        """
                        insert into knowledge_asset_record_links
                          (project_id, asset_key, analysis_record_hash, analysis_record_key,
                           analysis_record_type, link_type, evidence_type, evidence_file_path,
                           evidence_symbol_name, note, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            project_id,
                            link["assetId"],
                            hashlib.sha256(record_key.encode("utf-8")).hexdigest(),
                            record_key,
                            link["analysisRecordType"],
                            link["linkType"],
                            link["evidenceType"],
                            link["evidenceFilePath"],
                            link["evidenceSymbolName"],
                            link["note"],
                            now,
                            now,
                        ),
                    )
            connection.commit()
        return links

    def list_knowledge_asset_record_links(self, project_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    select asset_key, analysis_record_key, analysis_record_type, link_type,
                           evidence_type, evidence_file_path, evidence_symbol_name, note, updated_at
                    from knowledge_asset_record_links
                    where project_id = %s
                    order by asset_key, analysis_record_type, analysis_record_key
                    """,
                    (project_id,),
                )
                rows = cursor.fetchall()
        return [
            {
                "assetId": str(row["asset_key"]),
                "analysisRecordKey": str(row["analysis_record_key"]),
                "analysisRecordType": str(row["analysis_record_type"]),
                "linkType": str(row["link_type"]),
                "evidenceType": str(row["evidence_type"]),
                "evidenceFilePath": str(row["evidence_file_path"]),
                "evidenceSymbolName": str(row["evidence_symbol_name"]),
                "note": str(row["note"]),
                "updatedAt": iso_datetime(row["updated_at"]),
            }
            for row in rows
        ]

    def save_analysis_records(self, project_id: str, records: list[dict[str, Any]]) -> None:
        now = mysql_datetime(datetime.now(timezone.utc).isoformat())
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("delete from analysis_records where project_id = %s", (project_id,))
                for record in records:
                    cursor.execute(
                        """
                        insert into analysis_records
                          (project_id, record_hash, record_key, record_type, file_path, package_name,
                           symbol_name, enclosing_type, enclosing_method, start_line, start_column,
                           end_line, end_column, payload_json, created_at, updated_at)
                        values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            project_id,
                            hashlib.sha256(str(record["key"]).encode("utf-8")).hexdigest(),
                            str(record["key"]),
                            str(record["type"]),
                            str(record.get("filePath") or ""),
                            str(record.get("package") or ""),
                            str(record.get("symbolName") or ""),
                            str(record.get("enclosingType") or ""),
                            str(record.get("enclosingMethod") or ""),
                            int(record.get("startLine") or 0),
                            int(record.get("startColumn") or 0),
                            int(record.get("endLine") or 0),
                            int(record.get("endColumn") or 0),
                            json.dumps(record.get("payload", {}), ensure_ascii=False),
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


def _match_knowledge_asset_record_links(
    assets: list[dict[str, Any]],
    records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    links: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for asset in assets:
        asset_id = str(asset.get("id") or "")
        for evidence in asset.get("evidence") or []:
            if not isinstance(evidence, dict):
                continue
            matched = _records_for_evidence(evidence, records)
            for record in matched:
                key = (asset_id, str(record.get("key") or ""), "evidence")
                if key in seen:
                    continue
                seen.add(key)
                links.append(
                    {
                        "assetId": asset_id,
                        "analysisRecordKey": str(record.get("key") or ""),
                        "analysisRecordType": str(record.get("type") or "unknown"),
                        "linkType": "evidence",
                        "evidenceType": str(evidence.get("type") or "file"),
                        "evidenceFilePath": str(evidence.get("filePath") or ""),
                        "evidenceSymbolName": str(evidence.get("symbolName") or ""),
                        "note": str(evidence.get("note") or ""),
                    }
                )
    return links


def _records_for_evidence(evidence: dict[str, Any], records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    file_path = _normalized_path(str(evidence.get("filePath") or ""))
    symbol_name = str(evidence.get("symbolName") or "").strip().lower()
    start_line = int(evidence.get("startLine") or 0)
    end_line = int(evidence.get("endLine") or 0)
    if not file_path and not symbol_name:
        return []

    matched: list[dict[str, Any]] = []
    for record in records:
        if file_path and not _path_matches(file_path, _normalized_path(str(record.get("filePath") or ""))):
            continue
        if symbol_name and not _symbol_matches(symbol_name, record):
            continue
        if start_line and not _line_matches(start_line, end_line, record):
            continue
        if file_path and not symbol_name and str(record.get("type") or "") != "file":
            continue
        matched.append(record)
    return matched


def _path_matches(evidence_path: str, record_path: str) -> bool:
    if not evidence_path or not record_path:
        return False
    return (
        evidence_path == record_path
        or record_path.endswith(f"/{evidence_path}")
        or evidence_path.endswith(f"/{record_path}")
    )


def _symbol_matches(symbol_name: str, record: dict[str, Any]) -> bool:
    candidates = {
        str(record.get("symbolName") or "").lower(),
        str(record.get("enclosingType") or "").lower(),
        str(record.get("enclosingMethod") or "").lower(),
    }
    payload = record.get("payload") or {}
    if isinstance(payload, dict):
        candidates.update(
            {
                str(payload.get("name") or "").lower(),
                str(payload.get("method_name") or "").lower(),
                str(payload.get("methodName") or "").lower(),
                str(payload.get("enclosing_type") or "").lower(),
                str(payload.get("enclosingType") or "").lower(),
            }
        )
    return symbol_name in candidates


def _line_matches(start_line: int, end_line: int, record: dict[str, Any]) -> bool:
    record_start = int(record.get("startLine") or 0)
    record_end = int(record.get("endLine") or record_start or 0)
    if record_start <= 0:
        return False
    evidence_end = end_line or start_line
    return record_start <= evidence_end and record_end >= start_line


def _normalized_path(value: str) -> str:
    return value.strip().replace("\\", "/").strip("/")

