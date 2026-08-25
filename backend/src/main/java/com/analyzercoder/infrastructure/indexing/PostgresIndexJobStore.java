package com.analyzercoder.infrastructure.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.IndexJobMapper;
import com.analyzercoder.infrastructure.persistence.model.IndexJobRow;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 提供索引任务的存储实现，并负责领域对象与持久化数据之间的转换。 */
@Primary
@Repository
public class PostgresIndexJobStore implements IndexJobStore {
    private final IndexJobMapper mapper;

    public PostgresIndexJobStore(IndexJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public IndexJob save(IndexJob job) {
        mapper.upsert(row(job));
        return job;
    }

    @Override
    public Optional<IndexJob> findById(IndexJobId id) {
        return Optional.ofNullable(toDomain(mapper.findById(id.value())));
    }

    @Override
    public Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId id) {
        return Optional.ofNullable(toDomain(mapper.findLatest(id.value())));
    }

    @Override
    public List<IndexJob> findByRepositoryId(CodeRepositoryId id) {
        return mapper.findByRepositoryId(id.value()).stream()
                .map(PostgresIndexJobStore::toDomain)
                .toList();
    }

    @Override
    public List<IndexJob> findAll() {
        return mapper.findAll().stream().map(PostgresIndexJobStore::toDomain).toList();
    }

    @Override
    public boolean hasActiveJob(CodeRepositoryId id) {
        return mapper.countActive(id.value()) > 0;
    }

    @Override
    public Optional<IndexJob> findNextQueued() {
        return Optional.ofNullable(toDomain(mapper.findNextQueued()));
    }

    @Override
    @Transactional
    public Optional<IndexJob> claimNextQueued() {
        return Optional.ofNullable(toDomain(mapper.claimNextQueued()));
    }

    @Override
    @Transactional
    public Optional<IndexJob> claimNextQueued(
            IndexJobType type, String initialStep, long timeoutSeconds) {
        return Optional.ofNullable(
                toDomain(
                        mapper.claimNextQueuedByType(
                                type.name(), initialStep, Math.max(1, timeoutSeconds))));
    }

    @Override
    public Optional<IndexJob> heartbeat(IndexJobId id, String currentStep) {
        mapper.heartbeat(id.value(), currentStep);
        return findById(id);
    }

    @Override
    public int expireTimedOut(IndexJobType type) {
        return mapper.expireTimedOut(type.name());
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId id) {
        mapper.deleteByRepositoryId(id.value());
    }

    private static IndexJobRow row(IndexJob job) {
        return new IndexJobRow(
                job.id().value(),
                job.repositoryId().value(),
                job.type().name(),
                job.status().name(),
                job.currentStep(),
                job.executionMode(),
                job.fallbackReason(),
                job.failureCode(),
                job.errorMessage(),
                job.startedAt(),
                job.heartbeatAt(),
                job.timeoutAt(),
                job.finishedAt(),
                job.createdAt());
    }

    public static IndexJob toDomain(IndexJobRow row) {
        return row == null
                ? null
                : new IndexJob(
                        IndexJobId.of(row.id()),
                        CodeRepositoryId.of(row.repositoryId()),
                        IndexJobType.valueOf(row.jobType()),
                        IndexJobStatus.valueOf(row.status()),
                        row.currentStep(),
                        row.executionMode(),
                        row.fallbackReason(),
                        row.failureCode(),
                        row.errorMessage(),
                        row.startedAt(),
                        row.heartbeatAt(),
                        row.timeoutAt(),
                        row.finishedAt(),
                        row.createdAt());
    }
}
