package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record GovernanceAccountRow(UUID id, String username, String displayName, boolean enabled) {}
