package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record EngineeringContractRow(
        UUID id,
        UUID projectId,
        String contractKey,
        String name,
        UUID providerRepositoryId,
        UUID consumerRepositoryId,
        UUID providerSnapshotId,
        UUID consumerSnapshotId,
        String providerEvidencePath,
        String consumerEvidencePath,
        String providerContentFingerprint,
        String consumerContentFingerprint,
        Instant createdAt,
        Instant updatedAt) {}
