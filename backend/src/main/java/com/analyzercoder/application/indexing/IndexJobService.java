package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import org.springframework.stereotype.Service;

@Service
public class IndexJobService implements IndexJobUseCase {

    private final CodeRepositoryStore repositoryStore;
    private final IndexJobStore indexJobStore;

    public IndexJobService(CodeRepositoryStore repositoryStore, IndexJobStore indexJobStore) {
        this.repositoryStore = repositoryStore;
        this.indexJobStore = indexJobStore;
    }

    @Override
    public IndexJob start(StartIndexCommand command) {
        repositoryStore.findById(command.repositoryId())
            .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + command.repositoryId().value()));
        return indexJobStore.save(IndexJob.create(command.repositoryId(), command.type()));
    }

    @Override
    public IndexJob get(IndexJobId indexJobId) {
        return indexJobStore.findById(indexJobId)
            .orElseThrow(() -> new IllegalArgumentException("Index job not found: " + indexJobId.value()));
    }

    @Override
    public IndexJob getLatestStatus(CodeRepositoryId repositoryId) {
        repositoryStore.findById(repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId.value()));
        return indexJobStore.findLatestByRepositoryId(repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("Index job not found for repository: " + repositoryId.value()));
    }
}
