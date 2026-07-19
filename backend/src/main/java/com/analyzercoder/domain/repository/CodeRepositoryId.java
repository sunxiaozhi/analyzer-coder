package com.analyzercoder.domain.repository;

import java.util.UUID;

public record CodeRepositoryId(UUID value) {

    public static CodeRepositoryId newId() {
        return new CodeRepositoryId(UUID.randomUUID());
    }

    public static CodeRepositoryId of(UUID value) {
        return new CodeRepositoryId(value);
    }
}

