package com.analyzercoder.security;

import java.time.Instant;
import java.util.UUID;

public record AuthenticatedAccount(
    UUID id,
    String username,
    String displayName,
    AccountRole role,
    boolean mustChangePassword,
    Instant lastLoginAt,
    UUID lastRepositoryId
) {
    public AuthenticatedAccount(
        UUID id, String username, String displayName, AccountRole role,
        boolean mustChangePassword, Instant lastLoginAt
    ) {
        this(id, username, displayName, role, mustChangePassword, lastLoginAt, null);
    }

    public boolean isSuperAdmin() {
        return role == AccountRole.SUPER_ADMIN;
    }
}
