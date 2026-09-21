package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** 描述仓库内容版本的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record ManagedRepositoryContentVersion(
        RepositoryContentVersion id,
        CodeRepositoryId repositoryId,
        Path contentPath,
        String sourceCommit,
        String worktreeDigest,
        Instant createdAt) {
    public ManagedRepositoryContentVersion {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(contentPath, "contentPath must not be null");
        Objects.requireNonNull(sourceCommit, "sourceCommit must not be null");
        Objects.requireNonNull(worktreeDigest, "worktreeDigest must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
