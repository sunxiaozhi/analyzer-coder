package com.analyzercoder.domain.repository;

import java.util.UUID;

/** 封装代码仓库标识，避免在领域模型中直接传递无语义的基础类型。 */
public record CodeRepositoryId(UUID value) {

    public static CodeRepositoryId newId() {
        return new CodeRepositoryId(UUID.randomUUID());
    }

    public static CodeRepositoryId of(UUID value) {
        return new CodeRepositoryId(value);
    }
}
