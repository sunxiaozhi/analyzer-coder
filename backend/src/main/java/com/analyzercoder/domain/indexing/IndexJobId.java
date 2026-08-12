package com.analyzercoder.domain.indexing;

import java.util.UUID;

/** 封装索引任务标识，避免在领域模型中直接传递无语义的基础类型。 */
public record IndexJobId(UUID value) {

    public static IndexJobId newId() {
        return new IndexJobId(UUID.randomUUID());
    }

    public static IndexJobId of(UUID value) {
        return new IndexJobId(value);
    }
}
