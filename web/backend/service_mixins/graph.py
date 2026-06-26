from __future__ import annotations

from typing import Any


class GraphServiceMixin:
    def graph_overview(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        overview = self.external_stores.graph_overview(project_id=context.project_id)
        return {
            **overview,
            "projectId": context.project_id,
        }

    def graph_records(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        record_type = str(payload.get("type") or "").strip()
        query = str(payload.get("query") or "").strip()
        limit = self._query_int(payload.get("limit"), default=100, minimum=1, maximum=500, field="limit")
        offset = self._query_int(payload.get("offset"), default=0, minimum=0, maximum=1000000, field="offset")
        result = self.external_stores.graph_records(
            project_id=context.project_id,
            record_type=record_type,
            query=query,
            limit=limit,
            offset=offset,
        )
        return {
            **result,
            "projectId": context.project_id,
        }

    def graph_relations(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        limit = self._query_int(payload.get("limit"), default=500, minimum=1, maximum=2000, field="limit")
        result = self.external_stores.graph_relations(project_id=context.project_id, limit=limit)
        return {
            **result,
            "projectId": context.project_id,
        }

    def graph_chains(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        chain_type = str(payload.get("type") or "").strip()
        query = str(payload.get("query") or "").strip()
        limit = self._query_int(payload.get("limit"), default=100, minimum=1, maximum=300, field="limit")
        result = self.external_stores.graph_chains(
            project_id=context.project_id,
            chain_type=chain_type,
            query=query,
            limit=limit,
        )
        return {
            **result,
            "projectId": context.project_id,
        }

    def graph_chain(self, chain_id: str, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        result = self.external_stores.graph_chain(project_id=context.project_id, chain_id=chain_id)
        return {
            **result,
            "projectId": context.project_id,
        }
