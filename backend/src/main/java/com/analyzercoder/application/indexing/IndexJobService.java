package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.util.List;
import org.springframework.stereotype.Service;

/** 编排索引任务相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class IndexJobService implements IndexJobUseCase {

    private final CodeRepositoryStore repositoryStore;
    private final IndexJobStore indexJobStore;

    public IndexJobService(CodeRepositoryStore repositoryStore, IndexJobStore indexJobStore) {
        this.repositoryStore = repositoryStore;
        this.indexJobStore = indexJobStore;
    }

    @Override
    public synchronized IndexJob start(StartIndexCommand command) {
        repositoryStore
                .findById(command.repositoryId())
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Repository not found: " + command.repositoryId().value()));

        IndexJob activeJob = findActiveJob(command.repositoryId());
        if (activeJob != null) {
            return activeJob;
        }
        return indexJobStore.save(IndexJob.create(command.repositoryId(), command.type()));
    }

    @Override
    public IndexJob get(IndexJobId indexJobId) {
        return indexJobStore
                .findById(indexJobId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Index job not found: " + indexJobId.value()));
    }

    @Override
    public IndexJob getLatestStatus(CodeRepositoryId repositoryId) {
        repositoryStore
                .findById(repositoryId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Repository not found: " + repositoryId.value()));
        return indexJobStore
                .findLatestByRepositoryId(repositoryId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Index job not found for repository: "
                                                + repositoryId.value()));
    }

    @Override
    public List<IndexJob> list(CodeRepositoryId repositoryId) {
        if (repositoryId == null) {
            return indexJobStore.findAll();
        }
        repositoryStore
                .findById(repositoryId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Repository not found: " + repositoryId.value()));
        return indexJobStore.findByRepositoryId(repositoryId);
    }

    @Override
    public IndexJob cancel(IndexJobId indexJobId) {
        return indexJobStore.save(get(indexJobId).requestCancel());
    }

    @Override
    public synchronized IndexJob retry(IndexJobId indexJobId) {
        IndexJob failedJob = get(indexJobId);
        IndexJob activeJob = findActiveJob(failedJob.repositoryId());
        if (activeJob != null) {
            return activeJob;
        }
        return indexJobStore.save(IndexJob.retry(failedJob));
    }

    private IndexJob findActiveJob(CodeRepositoryId repositoryId) {
        return indexJobStore.findByRepositoryId(repositoryId).stream()
                .filter(
                        job ->
                                job.status() == IndexJobStatus.QUEUED
                                        || job.status() == IndexJobStatus.RUNNING
                                        || job.status() == IndexJobStatus.CANCEL_REQUESTED)
                .findFirst()
                .orElse(null);
    }
}
