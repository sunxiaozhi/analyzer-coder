package com.analyzercoder.infrastructure.persistence.model;import java.time.Instant;import java.util.UUID;
public record AuthSessionRow(String tokenHash,String csrfToken,Instant createdAt,Instant lastSeenAt,Instant expiresAt,UUID accountId,String username,String displayName,String accountRole,boolean enabled,boolean mustChangePassword,Instant lastLoginAt,UUID lastRepositoryId){}
