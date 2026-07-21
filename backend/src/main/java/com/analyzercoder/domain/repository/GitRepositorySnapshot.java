package com.analyzercoder.domain.repository;

import java.time.Instant;
import java.util.Objects;

public record GitRepositorySnapshot(
    String branch,
    String commit,
    String worktreeDigest,
    boolean dirty,
    Instant scannedAt
) {
    public GitRepositorySnapshot {
        commit = requireText(commit, "commit");
        worktreeDigest = requireText(worktreeDigest, "worktreeDigest");
        Objects.requireNonNull(scannedAt, "scannedAt must not be null");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
