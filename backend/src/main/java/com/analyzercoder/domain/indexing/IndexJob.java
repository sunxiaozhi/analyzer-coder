package com.analyzercoder.domain.indexing;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.Objects;

public record IndexJob(
    IndexJobId id,
    CodeRepositoryId repositoryId,
    IndexJobType type,
    IndexJobStatus status,
    String currentStep,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt
) {

    public static IndexJob create(CodeRepositoryId repositoryId, IndexJobType type) {
        return new IndexJob(
            IndexJobId.newId(),
            repositoryId,
            type,
            IndexJobStatus.QUEUED,
            "queued",
            null,
            null,
            null,
            Instant.now()
        );
    }

    public static IndexJob retry(IndexJob failedJob) {
        if (failedJob.status != IndexJobStatus.FAILED) {
            throw new IllegalStateException("只有失败的任务可以重试");
        }
        return create(failedJob.repositoryId, failedJob.type);
    }

    public IndexJob start(String currentStep) {
        if (status != IndexJobStatus.QUEUED && status != IndexJobStatus.RUNNING) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能开始执行");
        }
        Instant effectiveStartedAt = startedAt == null ? Instant.now() : startedAt;
        return new IndexJob(
            id, repositoryId, type, IndexJobStatus.RUNNING, currentStep, null,
            effectiveStartedAt, null, createdAt
        );
    }

    public IndexJob requestCancel() {
        if (status == IndexJobStatus.QUEUED) {
            return new IndexJob(
                id, repositoryId, type, IndexJobStatus.CANCELED, "canceled", null,
                startedAt, Instant.now(), createdAt
            );
        }
        if (status == IndexJobStatus.RUNNING) {
            return new IndexJob(
                id, repositoryId, type, IndexJobStatus.CANCEL_REQUESTED, "cancel_requested", null,
                startedAt, null, createdAt
            );
        }
        throw new IllegalStateException("只有排队中或运行中的任务可以取消");
    }

    public IndexJob cancel() {
        if (status != IndexJobStatus.CANCEL_REQUESTED && status != IndexJobStatus.QUEUED) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能取消");
        }
        return new IndexJob(
            id, repositoryId, type, IndexJobStatus.CANCELED, "canceled", null,
            startedAt, Instant.now(), createdAt
        );
    }

    public IndexJob succeed(String currentStep) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能标记为成功");
        }
        return new IndexJob(
            id, repositoryId, type, IndexJobStatus.SUCCEEDED, currentStep, null,
            startedAt, Instant.now(), createdAt
        );
    }

    public IndexJob fail(String currentStep, String errorMessage) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能标记为失败");
        }
        return new IndexJob(
            id, repositoryId, type, IndexJobStatus.FAILED, currentStep, errorMessage,
            startedAt, Instant.now(), createdAt
        );
    }

    public boolean isCancellationRequested() {
        return status == IndexJobStatus.CANCEL_REQUESTED;
    }

    public IndexJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
