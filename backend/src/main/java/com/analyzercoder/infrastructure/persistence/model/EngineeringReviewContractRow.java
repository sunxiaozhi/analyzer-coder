package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record EngineeringReviewContractRow(
        UUID projectId,
        UUID contractId,
        UUID targetRepositoryId,
        String targetEvidencePath,
        UUID providerRepositoryId,
        String providerEvidencePath,
        String providerContentFingerprint,
        UUID consumerRepositoryId,
        String consumerEvidencePath,
        String consumerContentFingerprint) {}
