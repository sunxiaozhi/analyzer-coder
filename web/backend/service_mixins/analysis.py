from __future__ import annotations

import json
from typing import Any

from java_analyzer.api_mapper import build_api_mapping
from java_analyzer.chunker import build_chunks
from java_analyzer.cli import _build_graph, _build_report, _format_summary
from java_analyzer.models import JavaFileAnalysis

from web.backend.analysis_records import build_analysis_records
from web.backend.errors import APIError
from web.backend.external_stores import ExternalStoreError
from web.backend.service_models import AnalysisContext, _is_legacy_english_report, utc_now


class AnalysisServiceMixin:
    def analyze(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        mode = str(payload.get("mode", "report"))
        context = self._analysis_context(payload, user)

        code_target = self._source_target(context, payload, "code")
        code_results = self.analyzer.analyze_path(code_target)
        output, extension, normalized_mode = self._analysis_output(context, payload, mode, code_results)
        # 分析结果先落 MySQL，随后按需同步给 Qdrant/Neo4j 支撑检索和图谱。
        analysis_records = build_analysis_records(context, code_results, self._relative_to_root, self._relative)
        analysis_record_count = self._save_analysis_records(context, analysis_records)
        external_sync = self._sync_external_analysis_stores(context, payload, analysis_records, code_results)
        knowledge_links = self._refresh_knowledge_asset_record_links(context)
        snapshot = self._save_analysis_output(context, normalized_mode, extension, output)
        return {
            "output": output,
            "savedPath": snapshot["uri"],
            "mode": normalized_mode,
            "source": "code",
            "projectId": context.project_id,
            "analysisRecordCount": analysis_record_count,
            "externalSync": external_sync,
            "knowledgeAssetLinks": knowledge_links,
        }

    def analysis_result(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        mode = str(payload.get("mode", "report"))
        context = self._analysis_context(payload, user)
        extension, normalized_mode = self._analysis_result_extension(mode)
        snapshot = self._load_analysis_output(context, normalized_mode)
        if not snapshot:
            return {
                "exists": False,
                "output": "",
                "savedPath": "",
                "mode": normalized_mode,
                "source": "code",
                "projectId": context.project_id,
                "updatedAt": "",
            }

        output = str(snapshot.get("output") or "")
        if normalized_mode == "report" and _is_legacy_english_report(output):
            # 旧版本曾缓存英文报告，读取时懒刷新为当前中文报告。
            output, extension, normalized_mode = self._analysis_output(context, payload, "report")
            snapshot = self._save_analysis_output(context, normalized_mode, extension, output)

        return {
            "exists": True,
            "output": output,
            "savedPath": str(snapshot.get("uri") or ""),
            "mode": normalized_mode,
            "source": "code",
            "projectId": context.project_id,
            "updatedAt": str(snapshot.get("updatedAt") or ""),
        }

    def api_mapping(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        frontend_path = self._relative_to_root(
            context.root,
            context.path(payload.get("frontendPath") or payload.get("path") or "."),
        )
        backend_path = self._relative_to_root(
            context.root,
            context.path(payload.get("backendPath") or payload.get("path") or "."),
        )
        result = build_api_mapping(
            root=context.root,
            frontend_path=frontend_path,
            backend_path=backend_path,
            analyzer=self.analyzer,
        )
        snapshot = self._save_analysis_output(
            context,
            "api-map",
            ".json",
            json.dumps(result.to_dict(), ensure_ascii=False, indent=2),
        )
        return {
            **result.to_dict(),
            "savedPath": snapshot["uri"],
            "projectId": context.project_id,
        }

    def analysis_records(self, payload: dict[str, Any], user: "UserRecord") -> dict[str, Any]:
        context = self._analysis_context(payload, user)
        record_type = str(payload.get("type") or "").strip()
        limit = self._query_int(payload.get("limit"), default=200, minimum=1, maximum=1000, field="limit")
        offset = self._query_int(payload.get("offset"), default=0, minimum=0, maximum=1000000, field="offset")
        records = self._load_analysis_records(context, record_type, limit, offset)
        type_counts = self._analysis_record_counts(context)
        return {
            "records": records,
            "total": sum(type_counts.values()) if not record_type else type_counts.get(record_type, len(records)),
            "limit": limit,
            "offset": offset,
            "types": type_counts,
            "projectId": context.project_id,
        }

    def _save_analysis_records(self, context: "AnalysisContext", records: list[dict[str, Any]]) -> int:
        self.mysql_store.save_analysis_records(context.project_id, records)
        return len(records)

    def _sync_external_analysis_stores(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        analysis_records: list[dict[str, Any]],
        results: list[JavaFileAnalysis],
    ) -> dict[str, Any]:
        # 外部存储同步失败不影响 MySQL 主记录，前端通过 externalSync 展示状态。
        chunks = [chunk for result in results for chunk in build_chunks(result)]
        vector_records = self._vector_records_for_chunks(chunks)
        try:
            return self.external_stores.sync(
                project_id=context.project_id,
                analysis_records=analysis_records,
                vector_records=vector_records,
            )
        except ExternalStoreError as exc:
            return {
                "enabled": True,
                "ok": False,
                "error": str(exc),
            }

    def _load_analysis_records(
        self,
        context: "AnalysisContext",
        record_type: str,
        limit: int,
        offset: int,
    ) -> list[dict[str, Any]]:
        return self.mysql_store.list_analysis_records(context.project_id, record_type, limit, offset)

    def _analysis_record_counts(self, context: "AnalysisContext") -> dict[str, int]:
        return self.mysql_store.count_analysis_records_by_type(context.project_id)

    def _analysis_output(
        self,
        context: "AnalysisContext",
        payload: dict[str, Any],
        mode: str,
        code_results: list[JavaFileAnalysis] | None = None,
    ) -> tuple[str, str, str]:
        code_target = self._source_target(context, payload, "code")
        results = code_results if code_results is not None else self.analyzer.analyze_path(code_target)

        if mode == "json":
            output = json.dumps([result.to_dict() for result in results], ensure_ascii=False, indent=2)
            return output, ".json", "json"

        if mode == "graph":
            return _build_graph(results, code_target), ".mmd", "graph"

        if mode == "chunks":
            chunks = [chunk for result in results for chunk in build_chunks(result)]
            return json.dumps([chunk.__dict__ for chunk in chunks], ensure_ascii=False, indent=2), ".json", "chunks"

        if mode == "summary":
            output = "\n".join(_format_summary(result) for result in results)
            return output, ".txt", "summary"

        return _build_report(code_target, "code", results, []), ".md", "report"

    def _analysis_result_extension(self, mode: str) -> tuple[str, str]:
        if mode == "json":
            return ".json", "json"
        if mode == "graph":
            return ".mmd", "graph"
        if mode == "chunks":
            return ".json", "chunks"
        if mode == "summary":
            return ".txt", "summary"
        return ".md", "report"

    def _save_analysis_output(
        self,
        context: "AnalysisContext",
        mode: str,
        extension: str,
        output: str,
    ) -> dict[str, Any]:
        return self.mysql_store.save_analysis_output(
            context.project_id,
            mode=mode,
            extension=extension,
            output=output.rstrip("\n") + "\n",
        )

    def _load_analysis_output(self, context: "AnalysisContext", mode: str) -> dict[str, Any] | None:
        return self.mysql_store.get_analysis_output(context.project_id, mode)
