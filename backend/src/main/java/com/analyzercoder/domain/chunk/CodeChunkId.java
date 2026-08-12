package com.analyzercoder.domain.chunk;

import java.util.UUID;

/** 封装代码片段标识，避免在领域模型中直接传递无语义的基础类型。 */
public record CodeChunkId(UUID value) {

    public static CodeChunkId newId() {
        return new CodeChunkId(UUID.randomUUID());
    }

    public static CodeChunkId of(UUID value) {
        return new CodeChunkId(value);
    }
}
