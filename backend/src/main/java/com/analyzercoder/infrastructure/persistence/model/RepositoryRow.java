package com.analyzercoder.infrastructure.persistence.model;

import com.analyzercoder.domain.repository.CodeRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

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
    UUID currentSnapshotId,
    String currentSnapshotPath,
    String codegraphPath,
    Instant snapshotCreatedAt,
    Instant lastScannedAt,
    UUID ownerAccountId,
    long ownershipVersion,
    String repositoryStatus,
    Instant createdAt,
    Instant updatedAt
) {
    public static RepositoryRow forInsert(CodeRepository repository, UUID ownerAccountId) {
        if (ownerAccountId == null) throw new IllegalArgumentException("ownerAccountId is required");
        return from(repository, ownerAccountId, 0, "READY");
    }

    public static RepositoryRow forUpdate(CodeRepository repository) {
        return from(repository, null, 0, "READY");
    }

    private static RepositoryRow from(CodeRepository repository, UUID ownerAccountId, long version, String status) {
        return new RepositoryRow(
            repository.id().value(), repository.name(), repository.name().trim().toLowerCase(Locale.ROOT),
            repository.path().toString(), repository.sourceType().name(), repository.defaultBranch(),
            repository.currentCommit(), repository.worktreeDigest(), repository.worktreeDirty(),
            repository.currentSnapshotId() == null ? null : repository.currentSnapshotId().value(),
            repository.currentSnapshotPath() == null ? null : repository.currentSnapshotPath().toString(),
            repository.codeGraphPath().toString(), repository.snapshotCreatedAt(), repository.lastScannedAt(),
            ownerAccountId, version, status, repository.createdAt(), repository.updatedAt()
        );
    }
}
