package com.analyzercoder.security;

public record AuthenticatedSession(
        String tokenHash,
        String csrfToken,
        AuthenticatedAccount account
) {
}
