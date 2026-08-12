package com.analyzercoder.domain.repository;

import java.util.Objects;
import java.util.UUID;

/** 封装仓库快照标识，避免在领域模型中直接传递无语义的基础类型。 */
public record RepositorySnapshotId(UUID value) {
    public RepositorySnapshotId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RepositorySnapshotId newId() {
        return new RepositorySnapshotId(UUID.randomUUID());
    }

    public static RepositorySnapshotId of(UUID value) {
        return new RepositorySnapshotId(value);
    }
}
