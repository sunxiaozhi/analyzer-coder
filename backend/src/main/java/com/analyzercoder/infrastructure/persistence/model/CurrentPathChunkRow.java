package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

public record CurrentPathChunkRow(
        UUID snapshotId, String filePath, int startLine, String contentHash) {}
