package com.analyzercoder.security;

import java.time.Instant;
import java.util.UUID;

public record AuthenticatedAccount(
    UUID id,
    String username,
    String displayName,
    AccountRole role,
    boolean mustChangePassword,
    Instant lastLoginAt
) {
    public boolean isSuperAdmin() {
        return role == AccountRole.SUPER_ADMIN;
    }
}
