package com.analyzercoder.domain.indexing;

import java.util.UUID;

public record IndexJobId(UUID value) {

    public static IndexJobId newId() {
        return new IndexJobId(UUID.randomUUID());
    }

    public static IndexJobId of(UUID value) {
        return new IndexJobId(value);
    }
}
