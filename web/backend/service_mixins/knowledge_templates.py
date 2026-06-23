from __future__ import annotations

from pathlib import Path
from typing import Any

from web.backend.errors import APIError
from web.backend.knowledge_defaults import DEFAULT_KB_TEMPLATES
from web.backend.service_models import KB_EXTENSIONS, AnalysisContext, UserRecord, slugify, utc_now


class KnowledgeTemplateServiceMixin:
    def list_kb_templates(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        return {
            "templates": self._load_kb_templates(context),
            "projectId": context.project_id,
        }

    def create_kb_template(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        templates = self._load_kb_templates(context)
        template = self._template_from_payload(payload, templates)
        templates.append(template)
        self._save_kb_templates(context, templates)
        return {"template": template, "templates": templates, "projectId": context.project_id}

    def update_kb_template(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        template_id = str(payload.get("id") or "").strip()
        if not template_id:
            raise APIError("template id is required.", 400)
        templates = self._load_kb_templates(context)
        for index, template in enumerate(templates):
            if template["id"] == template_id:
                updated = self._template_from_payload(payload, templates, current_id=template_id)
                templates[index] = updated
                self._save_kb_templates(context, templates)
                return {"template": updated, "templates": templates, "projectId": context.project_id}
        raise APIError("knowledge template not found.", 404)

    def delete_kb_template(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        template_id = str(payload.get("id") or "").strip()
        if not template_id:
            raise APIError("template id is required.", 400)
        templates = self._load_kb_templates(context)
        remaining = [template for template in templates if template["id"] != template_id]
        if len(remaining) == len(templates):
            raise APIError("knowledge template not found.", 404)
        self._save_kb_templates(context, remaining)
        return {"deleted": template_id, "templates": remaining, "projectId": context.project_id}

    def _load_kb_templates(self, context: "AnalysisContext") -> list[dict[str, str]]:
        templates = self.mysql_store.list_kb_templates(context.project_id)
        if templates:
            return templates
        # 未保存过模板时返回内置默认值；真正的模板变更仍写入 MySQL。
        now = utc_now()
        return [
            {
                **template,
                "createdAt": now,
                "updatedAt": now,
            }
            for template in DEFAULT_KB_TEMPLATES
        ]

    def _save_kb_templates(self, context: "AnalysisContext", templates: list[dict[str, str]]) -> None:
        self.mysql_store.save_kb_templates(context.project_id, templates)

    def _template_from_payload(
        self,
        payload: dict[str, Any],
        templates: list[dict[str, str]],
        current_id: str = "",
    ) -> dict[str, str]:
        name = str(payload.get("name") or "").strip()
        template_path = str(payload.get("path") or "").strip().replace("\\", "/")
        content = str(payload.get("content") or "")
        if not name:
            raise APIError("template name is required.", 400)
        if not template_path:
            raise APIError("template path is required.", 400)
        if Path(template_path).is_absolute():
            raise APIError("template path must be relative.", 400)
        if Path(template_path).suffix.lower() not in KB_EXTENSIONS:
            raise APIError("template path extension must be markdown, text, rst, or asciidoc.", 400)
        now = utc_now()
        existing_ids = {template["id"] for template in templates if template["id"] != current_id}
        # 模板 id 用于前端定位，更新已有模板时不能随名称变化。
        template_id = current_id or self._unique_template_id(name, existing_ids)
        created_at = now
        if current_id:
            for template in templates:
                if template["id"] == current_id:
                    created_at = template.get("createdAt") or now
                    break
        return {
            "id": template_id,
            "name": name,
            "path": template_path,
            "content": content,
            "createdAt": created_at,
            "updatedAt": now,
        }

    def _unique_template_id(self, name: str, existing: set[str]) -> str:
        base = slugify(name)
        if base not in existing:
            return base
        index = 2
        while f"{base}-{index}" in existing:
            index += 1
        return f"{base}-{index}"
