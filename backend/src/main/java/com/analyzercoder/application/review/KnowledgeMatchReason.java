package com.analyzercoder.application.review;

import java.util.UUID;

/** 描述一条知识适用范围为何命中，并携带可回溯到代码或 Git 的事实证据。 */
public record KnowledgeMatchReason(
        MatchKind kind, String rule, String target, ScopeEvidence evidence) {
    public enum MatchKind {
        CODE_REFERENCE,
        PATH_PATTERN,
        SYMBOL,
        MODULE
    }

    public enum EvidenceSource {
        GIT_FACT,
        CODE_FACT,
        GRAPH_INFERENCE
    }

    public record ScopeEvidence(
            EvidenceSource sourceType,
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String filePath,
            String symbolName,
            String moduleId,
            UUID knowledgeChunkId,
            String detail) {}
}
