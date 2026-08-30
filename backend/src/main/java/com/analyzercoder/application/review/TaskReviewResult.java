package com.analyzercoder.application.review;

import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.evidence.Provenance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一次任务审查的不可变版本化结果。 */
public record TaskReviewResult(
        UUID reviewId,
        Status status,
        UUID repositoryId,
        UUID snapshotId,
        UUID createdBy,
        UUID clientRequestId,
        UUID modelConfigId,
        String task,
        String changeSource,
        String baseRef,
        String headRef,
        RepositoryChange change,
        List<ChangedSymbolResolver.ChangedSymbol> changedSymbols,
        List<KnowledgeMatch> applicableKnowledge,
        List<KnowledgeMatch.ReferenceCandidate> referenceCandidates,
        List<TaskReviewFinding> requiredTests,
        List<TaskReviewFinding> requiredApprovals,
        List<KnowledgeMatch> staleKnowledge,
        List<TaskReviewFinding> unknowns,
        String summary,
        ModelSummary modelSummary,
        ModelSummaryState modelSummaryState,
        ErrorDetail error,
        Instant createdAt,
        Instant finishedAt) {
    public TaskReviewResult {
        changedSymbols = immutable(changedSymbols);
        applicableKnowledge = immutable(applicableKnowledge);
        referenceCandidates = immutable(referenceCandidates);
        requiredTests = immutable(requiredTests);
        requiredApprovals = immutable(requiredApprovals);
        staleKnowledge = immutable(staleKnowledge);
        unknowns = immutable(unknowns);
        if (modelSummaryState == null) {
            modelSummaryState =
                    modelSummary != null
                            ? ModelSummaryState.completed()
                            : modelConfigId == null
                                    ? ModelSummaryState.notRequested()
                                    : ModelSummaryState.unavailable(
                                            "MODEL_SUMMARY_NOT_RECORDED",
                                            "该历史审查没有保存模型总结状态");
        }
        if (modelSummaryState.status() == ModelSummaryStatus.COMPLETED
                && modelSummary == null) {
            throw new IllegalArgumentException("已完成的模型总结状态必须包含总结");
        }
    }

    /** 兼容模型总结上线前的构造调用；旧结果会按 modelConfigId 推导明确状态。 */
    public TaskReviewResult(
            UUID reviewId,
            Status status,
            UUID repositoryId,
            UUID snapshotId,
            UUID createdBy,
            UUID clientRequestId,
            UUID modelConfigId,
            String task,
            String changeSource,
            String baseRef,
            String headRef,
            RepositoryChange change,
            List<ChangedSymbolResolver.ChangedSymbol> changedSymbols,
            List<KnowledgeMatch> applicableKnowledge,
            List<KnowledgeMatch.ReferenceCandidate> referenceCandidates,
            List<TaskReviewFinding> requiredTests,
            List<TaskReviewFinding> requiredApprovals,
            List<KnowledgeMatch> staleKnowledge,
            List<TaskReviewFinding> unknowns,
            String summary,
            ErrorDetail error,
            Instant createdAt,
            Instant finishedAt) {
        this(
                reviewId,
                status,
                repositoryId,
                snapshotId,
                createdBy,
                clientRequestId,
                modelConfigId,
                task,
                changeSource,
                baseRef,
                headRef,
                change,
                changedSymbols,
                applicableKnowledge,
                referenceCandidates,
                requiredTests,
                requiredApprovals,
                staleKnowledge,
                unknowns,
                summary,
                null,
                null,
                error,
                createdAt,
                finishedAt);
    }

    public TaskReviewResult withModelSummary(
            ModelSummary resolvedSummary, ModelSummaryState resolvedState) {
        return new TaskReviewResult(
                reviewId,
                status,
                repositoryId,
                snapshotId,
                createdBy,
                clientRequestId,
                modelConfigId,
                task,
                changeSource,
                baseRef,
                headRef,
                change,
                changedSymbols,
                applicableKnowledge,
                referenceCandidates,
                requiredTests,
                requiredApprovals,
                staleKnowledge,
                unknowns,
                summary,
                resolvedSummary,
                resolvedState,
                error,
                createdAt,
                finishedAt);
    }

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    public record ErrorDetail(String code, String message) {}

    public enum ModelSummaryStatus {
        NOT_REQUESTED,
        COMPLETED,
        UNAVAILABLE,
        REJECTED
    }

    public record ModelSummaryState(ModelSummaryStatus status, String code, String detail) {
        public static ModelSummaryState notRequested() {
            return new ModelSummaryState(ModelSummaryStatus.NOT_REQUESTED, null, null);
        }

        public static ModelSummaryState completed() {
            return new ModelSummaryState(ModelSummaryStatus.COMPLETED, null, null);
        }

        public static ModelSummaryState unavailable(String code, String detail) {
            return new ModelSummaryState(ModelSummaryStatus.UNAVAILABLE, code, detail);
        }

        public static ModelSummaryState rejected(String code, String detail) {
            return new ModelSummaryState(ModelSummaryStatus.REJECTED, code, detail);
        }
    }

    public record ModelSummary(
            String summary,
            List<ModelFinding> findings,
            List<String> unknowns,
            String provider,
            String sourceType,
            Instant generatedAt) {
        public ModelSummary {
            findings = immutable(findings);
            unknowns = immutable(unknowns);
        }
    }

    public record ModelFinding(
            String text,
            List<String> evidenceIds,
            List<ModelEvidence> evidence,
            List<Provenance> sources) {
        public ModelFinding {
            evidenceIds = immutable(evidenceIds);
            evidence = immutable(evidence);
            sources = immutable(sources);
        }
    }

    public record ModelEvidence(
            String id,
            String kind,
            String title,
            String detail,
            String filePath,
            Integer startLine,
            Integer endLine,
            UUID knowledgeId) {}

    public record ReviewSummary(
            UUID reviewId,
            Status status,
            UUID repositoryId,
            UUID snapshotId,
            UUID createdBy,
            UUID clientRequestId,
            String task,
            String changeSource,
            int changedFileCount,
            int changedSymbolCount,
            int applicableKnowledgeCount,
            int requiredTestCount,
            int requiredApprovalCount,
            int staleKnowledgeCount,
            int unknownCount,
            ErrorDetail error,
            Instant createdAt,
            Instant finishedAt) {}

    private static <T> List<T> immutable(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
