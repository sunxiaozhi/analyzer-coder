package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record CodeRepository(
    CodeRepositoryId id,
    String name,
    Path path,
    String defaultBranch,
    String currentCommit,
    Path codeGraphPath,
    Instant createdAt,
    Instant updatedAt
) {

    public static CodeRepository create(String name, Path path) {
        Instant now = Instant.now();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return new CodeRepository(
            CodeRepositoryId.newId(),
            requireText(name, "name"),
            normalizedPath,
            null,
            null,
            normalizedPath.resolve(".codegraph"),
            now,
            now
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public CodeRepository {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}

