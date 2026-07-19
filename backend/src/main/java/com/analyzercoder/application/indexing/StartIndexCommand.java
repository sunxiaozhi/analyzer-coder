package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;

public record StartIndexCommand(
    CodeRepositoryId repositoryId,
    IndexJobType type
) {
}
