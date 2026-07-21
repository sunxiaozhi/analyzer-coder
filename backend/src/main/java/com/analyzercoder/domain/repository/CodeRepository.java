package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record CodeRepository(
    CodeRepositoryId id,
    String name,
    Path path,
    RepositorySourceType sourceType,
    String defaultBranch,
    String currentCommit,
    String worktreeDigest,
    boolean worktreeDirty,
    RepositorySnapshotId currentSnapshotId,
    Path currentSnapshotPath,
    Path codeGraphPath,
    Instant snapshotCreatedAt,
    Instant lastScannedAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static CodeRepository create(String name, Path path) {
        Instant now = Instant.now();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return new CodeRepository(
            CodeRepositoryId.newId(), normalizeName(name), normalizedPath, RepositorySourceType.LOCAL_GIT,
            null, null, null, false, null, null, normalizedPath.resolve(".codegraph"), null, null, now, now
        );
    }

    public static CodeRepository createLocalGit(
        CodeRepositoryId id, String name, Path path, GitRepositorySnapshot sourceVersion,
        ManagedRepositorySnapshot managedSnapshot
    ) {
        Instant now = Instant.now();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return new CodeRepository(
            id, normalizeName(name), normalizedPath, RepositorySourceType.LOCAL_GIT,
            sourceVersion.branch(), sourceVersion.commit(), sourceVersion.worktreeDigest(), sourceVersion.dirty(),
            managedSnapshot.id(), managedSnapshot.contentPath(), managedSnapshot.contentPath().resolve(".codegraph"),
            managedSnapshot.createdAt(), sourceVersion.scannedAt(), now, now
        );
    }

    public boolean hasSameVersion(GitRepositorySnapshot snapshot) {
        return currentSnapshotId != null
            && Objects.equals(defaultBranch, snapshot.branch())
            && Objects.equals(currentCommit, snapshot.commit())
            && Objects.equals(worktreeDigest, snapshot.worktreeDigest())
            && worktreeDirty == snapshot.dirty();
    }

    public CodeRepository withScanMetadata(GitRepositorySnapshot snapshot) {
        return new CodeRepository(
            id, name, path, sourceType, snapshot.branch(), snapshot.commit(), snapshot.worktreeDigest(),
            snapshot.dirty(), currentSnapshotId, currentSnapshotPath, codeGraphPath, snapshotCreatedAt,
            snapshot.scannedAt(), createdAt, Instant.now()
        );
    }

    public CodeRepository withManagedSnapshot(GitRepositorySnapshot sourceVersion, ManagedRepositorySnapshot managedSnapshot) {
        return new CodeRepository(
            id, name, path, sourceType, sourceVersion.branch(), sourceVersion.commit(), sourceVersion.worktreeDigest(),
            sourceVersion.dirty(), managedSnapshot.id(), managedSnapshot.contentPath(),
            managedSnapshot.contentPath().resolve(".codegraph"), managedSnapshot.createdAt(),
            sourceVersion.scannedAt(), createdAt, Instant.now()
        );
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("name must not be blank");
        String normalized = value.trim();
        if (normalized.length() > 100) throw new IllegalArgumentException("name must not exceed 100 characters");
        return normalized;
    }

    public CodeRepository {
        Objects.requireNonNull(id, "id must not be null");
        name = normalizeName(name);
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(codeGraphPath, "codeGraphPath must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
