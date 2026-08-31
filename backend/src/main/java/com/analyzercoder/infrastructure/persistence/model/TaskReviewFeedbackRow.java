package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record TaskReviewFeedbackRow(
        UUID id,
        UUID outcomeId,
        String kind,
        String targetType,
        String targetKey,
        UUID knowledgeId,
        String knowledgeUpdateAssessment,
        String comment,
        String evidenceUrlsPayload,
        Instant createdAt) {}
