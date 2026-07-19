package com.analyzercoder.domain.rag;

import com.analyzercoder.domain.repository.CodeRepositoryId;

public record VectorSearchHit(
    CodeRepositoryId repositoryId,
    String chunkId,
    String filePath,
    String symbolId,
    String content,
    double score
) {
}

