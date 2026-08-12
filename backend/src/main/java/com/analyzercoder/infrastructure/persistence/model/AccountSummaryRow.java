package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 承载账户摘要的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record AccountSummaryRow(
        UUID id,
        String username,
        String displayName,
        String accountRole,
        boolean enabled,
        boolean mustChangePassword,
        Instant lockedUntil,
        int permissionCount,
        Instant lastLoginAt,
        String lastLoginIp,
        Instant createdAt,
        Instant updatedAt,
        long accountVersion) {}
