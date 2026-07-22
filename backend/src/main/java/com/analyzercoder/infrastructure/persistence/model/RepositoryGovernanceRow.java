package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record RepositoryGovernanceRow(
    UUID repositoryId,
    String repositoryName,
    String normalizedName,
    UUID ownerAccountId,
    long ownershipVersion,
    String repositoryStatus
) {}
