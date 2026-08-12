package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

/** 承载账户的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record GovernanceAccountRow(UUID id, String username, String displayName, boolean enabled) {}
