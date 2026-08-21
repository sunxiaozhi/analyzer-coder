package com.analyzercoder.domain.indexing;

/** 描述仓库扫描文件的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record ScannedRepositoryFile(
        String relativePath,
        String language,
        RepositoryAssetType assetType,
        String content,
        int lineCount) {}
