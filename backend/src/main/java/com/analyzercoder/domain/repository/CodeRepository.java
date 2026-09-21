package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** 代码仓库聚合，保存来源、默认分支、当前版本及生命周期状态。 */
public record CodeRepository(
        CodeRepositoryId id,
        String name,
        Path path,
        RepositorySourceType sourceType,
        String defaultBranch,
        String currentCommit,
        String worktreeDigest,
        boolean worktreeDirty,
        RepositoryContentVersion currentContentVersion,
        Path currentContentVersionPath,
        Path codeGraphPath,
        Instant contentVersionCreatedAt,
        Instant lastScannedAt,
        Instant createdAt,
        Instant updatedAt) {
    public static CodeRepository create(String name, Path path) {
        Instant now = Instant.now();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return new CodeRepository(
                CodeRepositoryId.newId(),
                normalizeName(name),
                normalizedPath,
                RepositorySourceType.LOCAL_GIT,
                null,
                null,
                null,
                false,
                null,
                null,
                normalizedPath.resolve(".codegraph"),
                null,
                null,
                now,
                now);
    }

    public static CodeRepository createLocalGit(
            CodeRepositoryId id,
            String name,
            Path path,
            GitRepositoryContentVersion sourceVersion) {
        Instant now = Instant.now();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return new CodeRepository(
                id,
                normalizeName(name),
                normalizedPath,
                RepositorySourceType.LOCAL_GIT,
                sourceVersion.branch(),
                sourceVersion.commit(),
                sourceVersion.worktreeDigest(),
                sourceVersion.dirty(),
                null,
                null,
                normalizedPath.resolve(".codegraph"),
                null,
                sourceVersion.scannedAt(),
                now,
                now);
    }

    public boolean hasSameVersion(GitRepositoryContentVersion contentVersion) {
        return Objects.equals(defaultBranch, contentVersion.branch())
                && Objects.equals(currentCommit, contentVersion.commit())
                && Objects.equals(worktreeDigest, contentVersion.worktreeDigest())
                && worktreeDirty == contentVersion.dirty();
    }

    public CodeRepository withScanMetadata(GitRepositoryContentVersion contentVersion) {
        return new CodeRepository(
                id,
                name,
                path,
                sourceType,
                contentVersion.branch(),
                contentVersion.commit(),
                contentVersion.worktreeDigest(),
                contentVersion.dirty(),
                currentContentVersion,
                currentContentVersionPath,
                codeGraphPath,
                contentVersionCreatedAt,
                contentVersion.scannedAt(),
                createdAt,
                Instant.now());
    }

    public CodeRepository withManagedContentVersion(
            GitRepositoryContentVersion sourceVersion, ManagedRepositoryContentVersion managedContentVersion) {
        return new CodeRepository(
                id,
                name,
                path,
                sourceType,
                sourceVersion.branch(),
                sourceVersion.commit(),
                sourceVersion.worktreeDigest(),
                sourceVersion.dirty(),
                managedContentVersion.id(),
                managedContentVersion.contentPath(),
                managedContentVersion.contentPath().resolve(".codegraph"),
                managedContentVersion.createdAt(),
                sourceVersion.scannedAt(),
                createdAt,
                Instant.now());
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("仓库名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("仓库名称不能超过 100 个字符");
        }
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
