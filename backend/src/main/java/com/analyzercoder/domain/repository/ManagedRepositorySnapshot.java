package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record ManagedRepositorySnapshot(
    RepositorySnapshotId id,
    CodeRepositoryId repositoryId,
    Path contentPath,
    String sourceCommit,
    String worktreeDigest,
    Instant createdAt
) {
    public ManagedRepositorySnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(contentPath, "contentPath must not be null");
        Objects.requireNonNull(sourceCommit, "sourceCommit must not be null");
        Objects.requireNonNull(worktreeDigest, "worktreeDigest must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
