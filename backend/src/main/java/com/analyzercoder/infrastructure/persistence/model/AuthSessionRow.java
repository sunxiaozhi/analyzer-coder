package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 承载认证会话的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record AuthSessionRow(
        String tokenHash,
        String csrfToken,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        UUID accountId,
        String username,
        String displayName,
        String accountRole,
        boolean enabled,
        boolean mustChangePassword,
        Instant lastLoginAt,
        UUID lastRepositoryId) {}
