package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.repository.CodeRepositoryId;

public interface IndexJobUseCase {

    IndexJob start(StartIndexCommand command);

    IndexJob get(IndexJobId indexJobId);

    IndexJob getLatestStatus(CodeRepositoryId repositoryId);
}
