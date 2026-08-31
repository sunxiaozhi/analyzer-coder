package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record TaskReviewOutcomeRow(
        UUID id,
        UUID repositoryId,
        UUID reviewId,
        UUID reportedBy,
        String reporterDisplayName,
        UUID clientRequestId,
        String finalCommit,
        String commitBinding,
        String summary,
        String testsPayload,
        String approvalsPayload,
        String payloadHash,
        Instant createdAt) {}
