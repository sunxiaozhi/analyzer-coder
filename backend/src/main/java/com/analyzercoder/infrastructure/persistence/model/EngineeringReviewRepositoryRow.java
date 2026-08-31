package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record EngineeringReviewRepositoryRow(
        UUID projectId, UUID sourceRepositoryId, String targetServiceName) {}
