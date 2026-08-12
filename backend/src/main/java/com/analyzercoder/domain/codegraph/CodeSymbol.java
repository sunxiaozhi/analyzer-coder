package com.analyzercoder.domain.codegraph;

import com.analyzercoder.domain.repository.CodeRepositoryId;

/** 描述代码符号的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record CodeSymbol(
        CodeRepositoryId repositoryId,
        String symbolId,
        String name,
        String kind,
        String filePath,
        int startLine,
        int endLine,
        String language) {}
