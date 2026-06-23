from __future__ import annotations

from web.backend.service_mixins.knowledge_assets import KnowledgeAssetServiceMixin
from web.backend.service_mixins.knowledge_documents import KnowledgeDocumentServiceMixin
from web.backend.service_mixins.knowledge_templates import KnowledgeTemplateServiceMixin


class KnowledgeServiceMixin(
    KnowledgeAssetServiceMixin,
    KnowledgeTemplateServiceMixin,
    KnowledgeDocumentServiceMixin,
):
    """Knowledge asset, template, and document operations."""
