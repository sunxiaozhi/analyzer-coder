package com.analyzercoder.application.evidence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 跨 Git、代码、知识、图谱、检索与模型结果的统一来源描述。
 *
 * <p>字段为空表示该来源类型不适用，不能由前端据此扩大为更强的真实性结论。
 */
public record Provenance(
        UUID id,
        TruthSource sourceType,
        UUID repositoryId,
        UUID snapshotId,
        String commitSha,
        String worktreeDigest,
        String filePath,
        String symbolName,
        String symbolKind,
        Integer startLine,
        Integer endLine,
        String contentHash,
        UUID knowledgeCardId,
        Integer knowledgeRevision,
        String knowledgeReviewStatus,
        String graphArtifactId,
        List<String> relationPath,
        String retrievalChannel,
        String findingId,
        String detail,
        UUID engineeringProjectId,
        String serviceName,
        UUID contractId) {

    /** 兼容跨仓库平台事实加入前的构造调用。 */
    public Provenance(
            UUID id,
            TruthSource sourceType,
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String worktreeDigest,
            String filePath,
            String symbolName,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            String contentHash,
            UUID knowledgeCardId,
            Integer knowledgeRevision,
            String knowledgeReviewStatus,
            String graphArtifactId,
            List<String> relationPath,
            String retrievalChannel,
            String findingId,
            String detail) {
        this(
                id,
                sourceType,
                repositoryId,
                snapshotId,
                commitSha,
                worktreeDigest,
                filePath,
                symbolName,
                symbolKind,
                startLine,
                endLine,
                contentHash,
                knowledgeCardId,
                knowledgeRevision,
                knowledgeReviewStatus,
                graphArtifactId,
                relationPath,
                retrievalChannel,
                findingId,
                detail,
                null,
                null,
                null);
    }

    public Provenance {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        relationPath = relationPath == null ? List.of() : List.copyOf(relationPath);
        detail = safe(detail, "未提供来源说明");
        if (id == null) {
            id = stableId(sourceType, repositoryId, snapshotId, commitSha, worktreeDigest,
                    filePath, symbolName, knowledgeCardId, knowledgeRevision, graphArtifactId,
                    relationPath, retrievalChannel, findingId, detail, engineeringProjectId,
                    serviceName, contractId);
        }
        switch (sourceType) {
            case GIT_FACT, CODE_FACT -> requireVersion(snapshotId, commitSha, worktreeDigest);
            case PLATFORM_FACT -> {
                requireVersion(snapshotId, commitSha, worktreeDigest);
                if (repositoryId == null) {
                    throw new IllegalArgumentException("平台关系事实必须包含目标仓库");
                }
            }
            case VERIFIED_KNOWLEDGE -> requireKnowledge(
                    knowledgeCardId, knowledgeRevision, knowledgeReviewStatus);
            case GRAPH_INFERENCE -> {
                requireVersion(snapshotId, commitSha, worktreeDigest);
                if (blank(graphArtifactId) || relationPath.isEmpty()) {
                    throw new IllegalArgumentException("图谱推断必须包含图谱产物和关系路径");
                }
            }
            case RETRIEVAL_CANDIDATE -> {
                if (blank(retrievalChannel)) {
                    throw new IllegalArgumentException("检索候选必须包含检索通道");
                }
            }
            case MODEL_SUGGESTION -> {
                if (blank(findingId)) {
                    throw new IllegalArgumentException("模型建议必须引用已有 Finding");
                }
            }
            case UNKNOWN -> {
                // 稳定的来源说明就是未知项的最小事实边界。
            }
        }
    }

    public static Provenance gitFact(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String worktreeDigest,
            String filePath,
            String detail) {
        return create(
                TruthSource.GIT_FACT,
                repositoryId,
                snapshotId,
                commitSha,
                worktreeDigest,
                filePath,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                detail);
    }

    public static Provenance codeFact(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String worktreeDigest,
            String filePath,
            String symbolName,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            String contentHash,
            String detail) {
        return create(
                TruthSource.CODE_FACT,
                repositoryId,
                snapshotId,
                commitSha,
                worktreeDigest,
                filePath,
                symbolName,
                symbolKind,
                startLine,
                endLine,
                contentHash,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                detail);
    }

    public static Provenance verifiedKnowledge(
            UUID repositoryId,
            UUID knowledgeCardId,
            int knowledgeRevision,
            String knowledgeReviewStatus,
            String detail) {
        return create(
                TruthSource.VERIFIED_KNOWLEDGE,
                repositoryId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                knowledgeCardId,
                knowledgeRevision,
                knowledgeReviewStatus,
                null,
                List.of(),
                null,
                null,
                detail);
    }

    public static Provenance platformFact(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String filePath,
            UUID engineeringProjectId,
            String serviceName,
            UUID contractId,
            String detail) {
        return new Provenance(
                null,
                TruthSource.PLATFORM_FACT,
                repositoryId,
                snapshotId,
                commitSha,
                null,
                filePath,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                detail,
                engineeringProjectId,
                serviceName,
                contractId);
    }

    public static Provenance graphInference(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String graphArtifactId,
            List<String> relationPath,
            String filePath,
            String detail) {
        return create(
                TruthSource.GRAPH_INFERENCE,
                repositoryId,
                snapshotId,
                commitSha,
                null,
                filePath,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                graphArtifactId,
                relationPath,
                null,
                null,
                detail);
    }

    public static Provenance retrievalCandidate(
            UUID repositoryId,
            UUID knowledgeCardId,
            Integer knowledgeRevision,
            String knowledgeReviewStatus,
            String retrievalChannel,
            String detail) {
        return create(
                TruthSource.RETRIEVAL_CANDIDATE,
                repositoryId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                knowledgeCardId,
                knowledgeRevision,
                knowledgeReviewStatus,
                null,
                List.of(),
                retrievalChannel,
                null,
                detail);
    }

    public static Provenance modelSuggestion(
            UUID repositoryId, String findingId, String detail) {
        return create(
                TruthSource.MODEL_SUGGESTION,
                repositoryId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                findingId,
                detail);
    }

    public static Provenance unknown(
            UUID repositoryId,
            UUID knowledgeCardId,
            String filePath,
            String findingId,
            String detail) {
        return create(
                TruthSource.UNKNOWN,
                repositoryId,
                null,
                null,
                null,
                filePath,
                null,
                null,
                null,
                null,
                null,
                knowledgeCardId,
                null,
                null,
                null,
                List.of(),
                null,
                findingId,
                detail);
    }

    private static Provenance create(
            TruthSource sourceType,
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String worktreeDigest,
            String filePath,
            String symbolName,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            String contentHash,
            UUID knowledgeCardId,
            Integer knowledgeRevision,
            String knowledgeReviewStatus,
            String graphArtifactId,
            List<String> relationPath,
            String retrievalChannel,
            String findingId,
            String detail) {
        return new Provenance(
                null,
                sourceType,
                repositoryId,
                snapshotId,
                commitSha,
                worktreeDigest,
                filePath,
                symbolName,
                symbolKind,
                startLine,
                endLine,
                contentHash,
                knowledgeCardId,
                knowledgeRevision,
                knowledgeReviewStatus,
                graphArtifactId,
                relationPath,
                retrievalChannel,
                findingId,
                detail);
    }

    private static void requireVersion(
            UUID snapshotId, String commitSha, String worktreeDigest) {
        if (snapshotId == null && blank(commitSha) && blank(worktreeDigest)) {
            throw new IllegalArgumentException("Git 和代码事实必须包含版本信息");
        }
    }

    private static void requireKnowledge(
            UUID knowledgeCardId, Integer revision, String reviewStatus) {
        if (knowledgeCardId == null || revision == null || revision < 1 || blank(reviewStatus)) {
            throw new IllegalArgumentException("知识事实必须包含卡片 ID、修订号和审核状态");
        }
    }

    private static UUID stableId(Object... values) {
        return UUID.nameUUIDFromBytes(
                java.util.Arrays.deepToString(values).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value, String fallback) {
        return blank(value) ? fallback : value;
    }
}
