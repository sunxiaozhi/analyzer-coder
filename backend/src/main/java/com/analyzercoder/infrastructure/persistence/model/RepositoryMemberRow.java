package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record RepositoryMemberRow(
    UUID accountId,
    String username,
    String displayName,
    String accountRole,
    boolean enabled,
    String relationship,
    String permissionLevel
) {}
