from __future__ import annotations

import re
from dataclasses import asdict
from typing import Any

from web.backend.errors import APIError
from web.backend.service_models import (
    KNOWLEDGE_ASSET_STATUSES,
    KNOWLEDGE_ASSET_TYPES,
    AnalysisContext,
    KnowledgeAsset,
    UserRecord,
    slugify,
    utc_now,
)


class KnowledgeAssetServiceMixin:
    def list_knowledge_assets(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        asset_type = str(payload.get("type") or "").strip()
        status = str(payload.get("status") or "").strip()
        query = str(payload.get("query") or "").strip().lower()
        page = self._query_int(payload.get("page"), default=1, minimum=1, maximum=100000, field="page")
        page_size = self._query_int(payload.get("pageSize"), default=20, minimum=1, maximum=100, field="pageSize")
        assets = self._load_knowledge_assets(context)
        filtered = []
        for asset in assets:
            if asset_type and asset.type != asset_type:
                continue
            if status and asset.status != status:
                continue
            haystack = "\n".join(
                [
                    asset.title,
                    asset.summary,
                    asset.content,
                    " ".join(asset.tags),
                    " ".join(str(value) for evidence in asset.evidence for value in evidence.values()),
                ]
            ).lower()
            if query and query not in haystack:
                continue
            filtered.append(asset)
        total = len(filtered)
        offset = (page - 1) * page_size
        paged = filtered[offset : offset + page_size]
        return {
            "assets": [asdict(asset) for asset in paged],
            "total": total,
            "page": page,
            "pageSize": page_size,
            "types": sorted(KNOWLEDGE_ASSET_TYPES),
            "statuses": sorted(KNOWLEDGE_ASSET_STATUSES),
            "projectId": context.project_id,
        }

    def create_knowledge_asset(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        assets = self._load_knowledge_assets(context)
        asset = self._knowledge_asset_from_payload(payload, assets, user)
        assets.append(asset)
        self._save_knowledge_assets(context, assets)
        link_sync = self._refresh_knowledge_asset_record_links(context)
        return {
            "asset": asdict(asset),
            "assets": [asdict(item) for item in assets],
            "projectId": context.project_id,
            "knowledgeAssetLinks": link_sync,
        }

    def update_knowledge_asset(self, asset_id: str, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        asset_id = asset_id.strip()
        if not asset_id:
            raise APIError("knowledge asset id is required.", 400)
        assets = self._load_knowledge_assets(context)
        for index, asset in enumerate(assets):
            if asset.id == asset_id:
                updated = self._knowledge_asset_from_payload(payload, assets, user, current=asset)
                assets[index] = updated
                self._save_knowledge_assets(context, assets)
                link_sync = self._refresh_knowledge_asset_record_links(context)
                return {
                    "asset": asdict(updated),
                    "assets": [asdict(item) for item in assets],
                    "projectId": context.project_id,
                    "knowledgeAssetLinks": link_sync,
                }
        raise APIError("knowledge asset not found.", 404)

    def delete_knowledge_asset(self, asset_id: str, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        asset_id = asset_id.strip()
        assets = self._load_knowledge_assets(context)
        remaining = [asset for asset in assets if asset.id != asset_id]
        if len(remaining) == len(assets):
            raise APIError("knowledge asset not found.", 404)
        self._save_knowledge_assets(context, remaining)
        link_sync = self._refresh_knowledge_asset_record_links(context)
        return {
            "deleted": asset_id,
            "assets": [asdict(item) for item in remaining],
            "projectId": context.project_id,
            "knowledgeAssetLinks": link_sync,
        }

    def transition_knowledge_asset(
        self,
        asset_id: str,
        payload: dict[str, Any],
        user: "UserRecord",
        status: str,
    ) -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        assets = self._load_knowledge_assets(context)
        for asset in assets:
            if asset.id == asset_id:
                asset.status = status
                asset.updatedAt = utc_now()
                if status == "confirmed":
                    asset.reviewerUserId = user.id
                    asset.confirmedAt = asset.updatedAt
                self._save_knowledge_assets(context, assets)
                link_sync = self._refresh_knowledge_asset_record_links(context)
                return {
                    "asset": asdict(asset),
                    "assets": [asdict(item) for item in assets],
                    "projectId": context.project_id,
                    "knowledgeAssetLinks": link_sync,
                }
        raise APIError("knowledge asset not found.", 404)

    def _load_knowledge_assets(self, context: "AnalysisContext") -> list["KnowledgeAsset"]:
        return [KnowledgeAsset(**item) for item in self.mysql_store.list_knowledge_assets(context.project_id)]

    def _save_knowledge_assets(self, context: "AnalysisContext", assets: list["KnowledgeAsset"]) -> None:
        self.mysql_store.save_knowledge_assets(context.project_id, assets)

    def _knowledge_asset_from_payload(
        self,
        payload: dict[str, Any],
        assets: list["KnowledgeAsset"],
        user: "UserRecord",
        current: "KnowledgeAsset | None" = None,
    ) -> "KnowledgeAsset":
        title = str(payload.get("title") or "").strip()
        asset_type = str(payload.get("type") or "business_rule").strip()
        status = str(payload.get("status") or (current.status if current else "draft")).strip()
        if not title:
            raise APIError("knowledge asset title is required.", 400)
        if asset_type not in KNOWLEDGE_ASSET_TYPES:
            raise APIError("knowledge asset type is invalid.", 400)
        if status not in KNOWLEDGE_ASSET_STATUSES:
            raise APIError("knowledge asset status is invalid.", 400)

        now = utc_now()
        existing_ids = {asset.id for asset in assets if not current or asset.id != current.id}
        # 资产 id 基于标题生成，仅在创建时确定；更新时保持原 id 稳定。
        asset_id = current.id if current else self._unique_knowledge_asset_id(title, existing_ids)
        confirmed_at = str(payload.get("confirmedAt") or (current.confirmedAt if current else ""))
        reviewer_user_id = str(payload.get("reviewerUserId") or (current.reviewerUserId if current else ""))
        if status == "confirmed" and not confirmed_at:
            confirmed_at = now
            reviewer_user_id = reviewer_user_id or user.id

        return KnowledgeAsset(
            id=asset_id,
            type=asset_type,
            title=title,
            summary=str(payload.get("summary") or "").strip(),
            content=str(payload.get("content") or ""),
            status=status,
            ownerUserId=str(payload.get("ownerUserId") or (current.ownerUserId if current else user.id)),
            reviewerUserId=reviewer_user_id,
            tags=self._normalize_tags(payload.get("tags")),
            evidence=self._normalize_evidence(payload.get("evidence")),
            sourcePath=str(payload.get("sourcePath") or (current.sourcePath if current else "")).strip(),
            confirmedAt=confirmed_at,
            reviewDueAt=str(payload.get("reviewDueAt") or "").strip(),
            createdAt=current.createdAt if current else now,
            updatedAt=now,
            createdBy=current.createdBy if current else user.id,
            updatedBy=user.id,
        )

    def _unique_knowledge_asset_id(self, title: str, existing: set[str]) -> str:
        base = slugify(title)
        if base not in existing:
            return base
        index = 2
        while f"{base}-{index}" in existing:
            index += 1
        return f"{base}-{index}"

    def _normalize_tags(self, value: object) -> list[str]:
        # 前端可能提交数组，也可能提交逗号/空白分隔的字符串。
        if isinstance(value, str):
            raw_tags = re.split(r"[,，\s]+", value)
        elif isinstance(value, list):
            raw_tags = [str(item) for item in value]
        else:
            raw_tags = []
        tags: list[str] = []
        for raw in raw_tags:
            tag = raw.strip()
            if tag and tag not in tags:
                tags.append(tag)
        return tags[:12]

    def _normalize_evidence(self, value: object) -> list[dict[str, Any]]:
        # 只保留检索和审查需要的证据字段，避免任意表单字段写入存储层。
        if not isinstance(value, list):
            return []
        evidence_items: list[dict[str, Any]] = []
        for item in value:
            if not isinstance(item, dict):
                continue
            evidence_type = str(item.get("type") or item.get("evidenceType") or "file").strip()
            file_path = str(item.get("filePath") or item.get("file_path") or "").strip()
            symbol_name = str(item.get("symbolName") or item.get("symbol_name") or "").strip()
            note = str(item.get("note") or "").strip()
            if not file_path and not symbol_name and not note:
                continue
            evidence_items.append(
                {
                    "type": evidence_type,
                    "filePath": file_path,
                    "symbolName": symbol_name,
                    "startLine": int(item.get("startLine") or item.get("start_line") or 0),
                    "endLine": int(item.get("endLine") or item.get("end_line") or 0),
                    "note": note,
                }
            )
        return evidence_items[:20]
