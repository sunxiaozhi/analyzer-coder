package com.analyzercoder.application.review;

import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import java.util.List;
import java.util.UUID;

/** 一条经确定性 Scope 规则命中的工程知识及其全部事实证据。 */
public record KnowledgeMatch(
        UUID knowledgeId,
        String title,
        KnowledgeKind kind,
        KnowledgeSeverity severity,
        KnowledgeEnforcement enforcement,
        UUID ownerAccountId,
        int revision,
        String sourceVersionStatus,
        KnowledgeObligations obligations,
        List<KnowledgeMatchReason> reasons,
        List<Provenance> sources) {
    public KnowledgeMatch {
        obligations = obligations == null ? KnowledgeObligations.empty() : obligations;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 审查匹配所需的知识快照，不包含正文，避免规则层依赖渲染或模型内容。 */
    public record Candidate(
            UUID knowledgeId,
            UUID repositoryId,
            String title,
            KnowledgeKind kind,
            KnowledgeSeverity severity,
            KnowledgeEnforcement enforcement,
            UUID ownerAccountId,
            KnowledgeScope scope,
            KnowledgeObligations obligations,
            int revision,
            String publicationStatus,
            String reviewStatus,
            String sourceVersionStatus,
            List<KnowledgeScopeMatcher.BoundCodeReference> codeReferences) {
        public Candidate {
            scope = scope == null ? KnowledgeScope.empty() : scope;
            obligations = obligations == null ? KnowledgeObligations.empty() : obligations;
            codeReferences = codeReferences == null ? List.of() : List.copyOf(codeReferences);
        }
    }

    /** 仅由关键词或向量召回的参考候选，永远不产生测试或审批义务。 */
    public record RetrievalReference(UUID knowledgeId, String source, String detail) {}

    public record ReferenceCandidate(
            UUID knowledgeId,
            String title,
            KnowledgeKind kind,
            String sourceVersionStatus,
            String retrievalSource,
            String detail,
            Provenance provenance) {}
}
