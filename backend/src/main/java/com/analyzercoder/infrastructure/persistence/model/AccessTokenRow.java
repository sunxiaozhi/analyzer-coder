package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 持久化令牌摘要，原始令牌不入库。 */
public record AccessTokenRow(
        UUID id,
        UUID accountId,
        String name,
        String tokenHash,
        String tokenPrefix,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt) {}
