package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record EngineeringProjectRow(
        UUID id,
        String name,
        String description,
        UUID createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
