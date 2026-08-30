package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 任务审查持久化行，完整保留请求、版本边界、终态结果和稳定错误。 */
public record TaskReviewRow(
        UUID id,
        UUID repositoryId,
        UUID createdBy,
        UUID clientRequestId,
        String task,
        String changeSource,
        String baseRef,
        String headRef,
        UUID modelConfigId,
        String baseCommit,
        String headCommit,
        UUID snapshotId,
        String worktreeDigest,
        String status,
        String resultPayload,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant finishedAt) {}
