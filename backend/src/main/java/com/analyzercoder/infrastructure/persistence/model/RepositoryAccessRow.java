package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record RepositoryAccessRow(
    UUID repositoryId,
    UUID ownerAccountId,
    String ownerDisplayName,
    String permissionLevel,
    long ownershipVersion,
    String repositoryStatus
) {}
