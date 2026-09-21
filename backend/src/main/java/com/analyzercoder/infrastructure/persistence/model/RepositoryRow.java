package com.analyzercoder.infrastructure.persistence.model;

import com.analyzercoder.domain.repository.CodeRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** 承载代码仓库的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record RepositoryRow(
        UUID id,
        String name,
        String normalizedName,
        String path,
        String sourceType,
        String defaultBranch,
        String currentCommit,
        String worktreeDigest,
        boolean worktreeDirty,
        UUID currentContentVersion,
        String currentContentVersionPath,
        String codegraphPath,
        Instant contentVersionCreatedAt,
        Instant lastScannedAt,
        UUID ownerAccountId,
        long ownershipVersion,
        String repositoryStatus,
        Instant createdAt,
        Instant updatedAt,
        String description,
        long repositoryVersion) {
    public static RepositoryRow forInsert(CodeRepository r, UUID owner) {
        if (owner == null) {
            throw new IllegalArgumentException("仓库所有者账号不能为空");
        }
        return from(r, owner);
    }

    public static RepositoryRow forUpdate(CodeRepository r) {
        return from(r, null);
    }

    private static RepositoryRow from(CodeRepository r, UUID owner) {
        return new RepositoryRow(
                r.id().value(),
                r.name(),
                r.name().trim().toLowerCase(Locale.ROOT),
                r.path().toString(),
                r.sourceType().name(),
                r.defaultBranch(),
                r.currentCommit(),
                r.worktreeDigest(),
                r.worktreeDirty(),
                r.currentContentVersion() == null ? null : r.currentContentVersion().value(),
                r.currentContentVersionPath() == null ? null : r.currentContentVersionPath().toString(),
                r.codeGraphPath().toString(),
                r.contentVersionCreatedAt(),
                r.lastScannedAt(),
                owner,
                0,
                "READY",
                r.createdAt(),
                r.updatedAt(),
                "",
                1);
    }
}
