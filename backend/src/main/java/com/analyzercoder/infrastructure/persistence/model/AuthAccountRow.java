package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record AuthAccountRow(
    UUID id, String username, String displayName, String passwordHash, String accountRole,
    boolean enabled, boolean mustChangePassword, int failedAttempts, Instant lockedUntil,
    Instant temporaryPasswordExpiresAt, Instant lastLoginAt, String lastLoginIp,
    UUID lastRepositoryId, Instant createdAt, Instant updatedAt, long accountVersion
) {
    public AuthAccountRow(
        UUID id, String username, String displayName, String passwordHash, String accountRole,
        boolean enabled, boolean mustChangePassword, int failedAttempts, Instant lockedUntil,
        Instant temporaryPasswordExpiresAt, Instant lastLoginAt, String lastLoginIp,
        Instant createdAt, Instant updatedAt
    ) {
        this(id, username, displayName, passwordHash, accountRole, enabled, mustChangePassword,
            failedAttempts, lockedUntil, temporaryPasswordExpiresAt, lastLoginAt, lastLoginIp,
            null, createdAt, updatedAt, 1);
    }
}