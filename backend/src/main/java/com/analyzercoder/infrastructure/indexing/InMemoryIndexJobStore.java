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
import java.time.Instant;

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
                .filter(
                        job ->
                                job.status() == IndexJobStatus.QUEUED
                                        && (job.type()
                                                        == com.analyzercoder.domain.indexing
                                                                .IndexJobType.FULL
                                                || job.type()
                                                        == com.analyzercoder.domain.indexing
                                                                .IndexJobType.INCREMENTAL))
                .min(Comparator.comparing(IndexJob::createdAt));
    }

    @Override
    public synchronized Optional<IndexJob> claimNextQueued() {
        Optional<IndexJob> queued = findNextQueued();
        queued.ifPresent(job -> save(job.start("scan_repository")));
        return queued.map(job -> findById(job.id()).orElseThrow());
    }

    @Override
    public synchronized Optional<IndexJob> claimNextQueued(
            com.analyzercoder.domain.indexing.IndexJobType type,
            String initialStep,
            long timeoutSeconds) {
        Optional<IndexJob> queued =
                indexJobs.values().stream()
                        .filter(job -> job.status() == IndexJobStatus.QUEUED && job.type() == type)
                        .min(Comparator.comparing(IndexJob::createdAt));
        queued.ifPresent(
                job ->
                        save(
                                job.start(initialStep)
                                        .withTimeout(
                                                Instant.now()
                                                        .plusSeconds(Math.max(1, timeoutSeconds)))));
        return queued.map(job -> findById(job.id()).orElseThrow());
    }

    @Override
    public synchronized Optional<IndexJob> heartbeat(IndexJobId id, String currentStep) {
        IndexJob current = indexJobs.get(id.value());
        if (current == null) return Optional.empty();
        if (current.status() == IndexJobStatus.RUNNING) save(current.heartbeat(currentStep));
        return findById(id);
    }

    @Override
    public synchronized int expireTimedOut(
            com.analyzercoder.domain.indexing.IndexJobType type) {
        int[] count = {0};
        indexJobs.replaceAll(
                (id, job) -> {
                    if (job.type() == type
                            && (job.status() == IndexJobStatus.RUNNING
                                    || job.status() == IndexJobStatus.CANCEL_REQUESTED)
                            && job.timeoutAt() != null
                            && job.timeoutAt().isBefore(Instant.now())) {
                        count[0]++;
                        String failureCode =
                                type
                                                == com.analyzercoder.domain.indexing.IndexJobType
                                                        .CODEGRAPH
                                        ? "CODEGRAPH_TIMEOUT"
                                        : "KNOWLEDGE_DRIFT_TIMEOUT";
                        String errorMessage =
                                type
                                                == com.analyzercoder.domain.indexing.IndexJobType
                                                        .CODEGRAPH
                                        ? "CodeGraph 后台任务超过固定执行时限"
                                        : "知识失效检查超过固定执行时限";
                        return job.fail(
                                "timed_out", failureCode, errorMessage);
                    }
                    return job;
                });
        return count[0];
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId repositoryId) {
        indexJobs
                .entrySet()
                .removeIf(entry -> entry.getValue().repositoryId().equals(repositoryId));
    }
}
