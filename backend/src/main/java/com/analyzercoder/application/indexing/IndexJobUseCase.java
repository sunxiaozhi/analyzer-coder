package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;

public interface IndexJobUseCase {

    IndexJob start(StartIndexCommand command);

    IndexJob get(IndexJobId indexJobId);

    IndexJob getLatestStatus(CodeRepositoryId repositoryId);

    List<IndexJob> list(CodeRepositoryId repositoryId);

    IndexJob cancel(IndexJobId indexJobId);

    IndexJob retry(IndexJobId indexJobId);
}
