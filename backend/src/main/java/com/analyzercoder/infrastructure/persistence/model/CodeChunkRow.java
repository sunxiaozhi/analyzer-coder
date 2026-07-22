package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record CodeChunkRow(UUID id,UUID repositoryId,UUID snapshotId,String commitSha,String filePath,
    String symbolId,String symbolName,String symbolKind,String language,String chunkType,Integer startLine,
    Integer endLine,String content,String contentHash,Instant createdAt) {}
