package com.analyzercoder.infrastructure.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 提供索引任务的存储实现，并负责领域对象与持久化数据之间的转换。 */
public class InMemoryIndexJobStore implements IndexJobStore {

    private final Map<UUID, IndexJob> indexJobs = new ConcurrentHashMap<>();

    @Override
    public IndexJob save(IndexJob indexJob) {
        indexJobs.put(indexJob.id().value(), indexJob);
        return indexJob;
    }

    @Override
    public Optional<IndexJob> findById(IndexJobId indexJobId) {
        return Optional.ofNullable(indexJobs.get(indexJobId.value()));
    }

    @Override
    public Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId repositoryId) {
        return indexJobs.values().stream()
                .filter(job -> job.repositoryId().equals(repositoryId))
                .max(Comparator.comparing(IndexJob::createdAt));
    }

    @Override
    public List<IndexJob> findByRepositoryId(CodeRepositoryId repositoryId) {
        return indexJobs.values().stream()
                .filter(job -> job.repositoryId().equals(repositoryId))
                .sorted(Comparator.comparing(IndexJob::createdAt).reversed())
                .toList();
    }

    @Override
    public List<IndexJob> findAll() {
        return indexJobs.values().stream()
                .sorted(Comparator.comparing(IndexJob::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean hasActiveJob(CodeRepositoryId repositoryId) {
        return indexJobs.values().stream()
                .anyMatch(
                        job ->
                                job.repositoryId().equals(repositoryId)
                                        && (job.status() == IndexJobStatus.QUEUED
                                                || job.status() == IndexJobStatus.RUNNING
                                                || job.status()
                                                        == IndexJobStatus.CANCEL_REQUESTED));
    }

    @Override
    public Optional<IndexJob> findNextQueued() {
        return indexJobs.values().stream()
                .filter(job -> job.status() == IndexJobStatus.QUEUED)
                .min(Comparator.comparing(IndexJob::createdAt));
    }

    @Override
    public synchronized Optional<IndexJob> claimNextQueued() {
        Optional<IndexJob> queued = findNextQueued();
        queued.ifPresent(job -> save(job.start("scan_repository")));
        return queued.map(job -> findById(job.id()).orElseThrow());
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId repositoryId) {
        indexJobs
                .entrySet()
                .removeIf(entry -> entry.getValue().repositoryId().equals(repositoryId));
    }
}
