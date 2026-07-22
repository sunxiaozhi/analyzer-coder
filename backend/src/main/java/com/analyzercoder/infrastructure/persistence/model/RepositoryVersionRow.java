package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record RepositoryVersionRow(UUID snapshotId, String snapshotPath) {}
