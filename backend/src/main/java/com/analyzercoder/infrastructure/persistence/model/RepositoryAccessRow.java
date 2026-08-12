package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

/** 承载仓库访问授权的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record RepositoryAccessRow(
        UUID repositoryId,
        UUID ownerAccountId,
        String ownerDisplayName,
        String permissionLevel,
        long ownershipVersion,
        String repositoryStatus) {}
