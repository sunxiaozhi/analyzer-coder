package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 承载代码片段的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record CodeChunkRow(
        UUID id,
        UUID repositoryId,
        UUID snapshotId,
        String commitSha,
        String filePath,
        String symbolId,
        String symbolName,
        String symbolKind,
        String language,
        String chunkType,
        Integer startLine,
        Integer endLine,
        String content,
        String contentHash,
        Instant createdAt) {}
