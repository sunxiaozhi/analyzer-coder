from __future__ import annotations

from pathlib import Path
from typing import Any

from web.backend.errors import APIError
from web.backend.service_models import KB_EXTENSIONS, UserRecord


class KnowledgeDocumentServiceMixin:
    def list_kb_files(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        prefix = self._kb_prefix(payload)
        # 文档内容存储在 MySQL，root 字段只保留给前端展示存储来源。
        files = self.mysql_store.list_knowledge_documents(context.project_id, prefix=prefix)
        return {"files": files, "root": "mysql://knowledge_documents", "projectId": context.project_id}

    def get_kb_file(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        document_path = self._kb_document_path(payload.get("path"))
        document = self.mysql_store.get_knowledge_document(context.project_id, document_path)
        if not document:
            raise APIError("knowledge file not found.", 404)
        return {
            "file": {
                "path": document["path"],
                "name": document["name"],
                "size": document["size"],
                "updatedAt": document["updatedAt"],
            },
            "content": document["content"],
            "root": "mysql://knowledge_documents",
            "projectId": context.project_id,
        }

    def save_kb_file(self, payload: dict[str, Any], user: "UserRecord", create: bool = False) -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        document_path = self._kb_document_path(payload.get("path"))
        content = str(payload.get("content", ""))
        if create and self.mysql_store.get_knowledge_document(context.project_id, document_path):
            raise APIError("knowledge file already exists.", 409)
        document = self.mysql_store.save_knowledge_document(
            context.project_id,
            path=document_path,
            content=content,
            updated_by=user.id,
        )
        return {
            "file": {
                "path": document["path"],
                "name": document["name"],
                "size": document["size"],
                "updatedAt": document["updatedAt"],
            },
            "content": content,
            "root": "mysql://knowledge_documents",
            "projectId": context.project_id,
        }

    def delete_kb_file(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        document_path = self._kb_document_path(payload.get("path"))
        if not self.mysql_store.delete_knowledge_document(context.project_id, document_path):
            raise APIError("knowledge file not found.", 404)
        return {"deleted": document_path, "root": "mysql://knowledge_documents", "projectId": context.project_id}

    def _kb_prefix(self, payload: dict[str, Any]) -> str:
        raw = str(payload.get("kbPath") or "").strip().replace("\\", "/").strip("/")
        if not raw:
            return ""
        if Path(raw).is_absolute() or ".." in Path(raw).parts:
            raise APIError("knowledge root must be relative.", 400)
        # 兼容旧前端传入 kbPath；当前 MySQL 存储按项目隔离，不再拼本地根目录。
        return ""

    def _kb_document_path(self, value: object) -> str:
        raw = str(value or "").strip().replace("\\", "/")
        if not raw:
            raise APIError("path is required.", 400)
        if Path(raw).is_absolute():
            raise APIError("knowledge file path must be relative.", 400)
        path = Path(raw)
        if ".." in path.parts:
            raise APIError("knowledge file path must stay within the project.", 400)
        if path.suffix.lower() not in KB_EXTENSIONS:
            raise APIError("knowledge file extension must be markdown, text, rst, or asciidoc.", 400)
        return raw.strip("/")
