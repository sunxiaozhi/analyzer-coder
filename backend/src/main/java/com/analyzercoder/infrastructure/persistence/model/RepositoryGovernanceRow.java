package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

/** 承载仓库治理的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record RepositoryGovernanceRow(
        UUID repositoryId,
        String repositoryName,
        String normalizedName,
        UUID ownerAccountId,
        long ownershipVersion,
        String repositoryStatus) {}
