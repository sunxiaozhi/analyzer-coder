package com.analyzercoder.domain.rag;

import com.analyzercoder.domain.repository.CodeRepositoryId;

/** 描述向量检索的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record VectorSearchHit(
        CodeRepositoryId repositoryId,
        String chunkId,
        String filePath,
        String symbolId,
        String content,
        double score) {}
