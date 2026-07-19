package com.analyzercoder.domain.codegraph;

import com.analyzercoder.domain.repository.CodeRepositoryId;

public record CodeSymbol(
    CodeRepositoryId repositoryId,
    String symbolId,
    String name,
    String kind,
    String filePath,
    int startLine,
    int endLine,
    String language
) {
}

