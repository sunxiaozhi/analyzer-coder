package com.analyzercoder.domain.indexing;

public record ScannedRepositoryFile(
    String relativePath,
    String language,
    String content,
    int lineCount
) {
}
