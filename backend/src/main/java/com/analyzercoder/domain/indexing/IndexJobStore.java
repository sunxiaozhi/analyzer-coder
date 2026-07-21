package com.analyzercoder.domain.indexing;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.Optional;

public interface IndexJobStore {

    IndexJob save(IndexJob indexJob);

    Optional<IndexJob> findById(IndexJobId indexJobId);

    Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId repositoryId);

    List<IndexJob> findByRepositoryId(CodeRepositoryId repositoryId);

    List<IndexJob> findAll();

    boolean hasActiveJob(CodeRepositoryId repositoryId);

    Optional<IndexJob> findNextQueued();

    Optional<IndexJob> claimNextQueued();

    void deleteByRepositoryId(CodeRepositoryId repositoryId);
}
