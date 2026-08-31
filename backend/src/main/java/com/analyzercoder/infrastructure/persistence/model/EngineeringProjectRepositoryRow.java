package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record EngineeringProjectRepositoryRow(
        UUID projectId, UUID repositoryId, String repositoryName, String serviceName) {}
