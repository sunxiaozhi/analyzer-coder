package com.analyzercoder.application.review;

import com.analyzercoder.application.evidence.Provenance;
import java.util.List;
import java.util.UUID;

/** 任务审查中的确定性义务或无法判断项；每项要么有证据，要么有明确未知原因。 */
public record TaskReviewFinding(
        FindingKind kind,
        String key,
        String title,
        FindingStatus status,
        List<UUID> knowledgeIds,
        List<KnowledgeMatchReason> evidence,
        UnknownReason unknownReason,
        List<Provenance> sources) {
    public TaskReviewFinding {
        knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (kind == FindingKind.UNKNOWN && unknownReason == null) {
            throw new IllegalArgumentException("未知项必须包含原因");
        }
        if (kind != FindingKind.UNKNOWN && evidence.isEmpty()) {
            throw new IllegalArgumentException("确定性审查项必须包含证据");
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("每条审查项必须包含来源");
        }
    }

    public enum FindingKind {
        REQUIRED_TEST,
        REQUIRED_APPROVAL,
        UNKNOWN
    }

    public enum FindingStatus {
        REQUIRED_NOT_REPORTED,
        REQUIRED,
        UNKNOWN
    }

    public record UnknownReason(
            String code, UUID knowledgeId, String filePath, String rule, String detail) {}
}
