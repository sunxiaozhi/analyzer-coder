package com.analyzercoder.infrastructure.persistence.model;import java.time.Instant;import java.util.UUID;
public record AccountSummaryRow(UUID id,String username,String displayName,String accountRole,boolean enabled,boolean mustChangePassword,Instant lockedUntil,int permissionCount,Instant lastLoginAt,String lastLoginIp,Instant createdAt,Instant updatedAt){}
