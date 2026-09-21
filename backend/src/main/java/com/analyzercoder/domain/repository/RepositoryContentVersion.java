package com.analyzercoder.domain.repository;

import java.util.Objects;
import java.util.UUID;

/** 封装仓库内容版本标识，避免在领域模型中直接传递无语义的基础类型。 */
public record RepositoryContentVersion(UUID value) {
    public RepositoryContentVersion {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RepositoryContentVersion newId() {
        return new RepositoryContentVersion(UUID.randomUUID());
    }

    public static RepositoryContentVersion of(UUID value) {
        return new RepositoryContentVersion(value);
    }
}
