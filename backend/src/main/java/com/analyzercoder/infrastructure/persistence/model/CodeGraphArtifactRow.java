package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record CodeGraphArtifactRow(UUID id, UUID repositoryId, UUID snapshotId, String cliVersion,
    String status, String artifactPath, int nodeCount, int edgeCount) {}
