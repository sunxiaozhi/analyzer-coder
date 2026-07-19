package com.analyzercoder.domain.chunk;

import java.util.UUID;

public record CodeChunkId(UUID value) {

    public static CodeChunkId newId() {
        return new CodeChunkId(UUID.randomUUID());
    }

    public static CodeChunkId of(UUID value) {
        return new CodeChunkId(value);
    }
}
