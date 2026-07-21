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
            throw new IllegalStateException("Only failed jobs can be retried");
        }
        return create(failedJob.repositoryId, failedJob.type);
    }

    public IndexJob start(String currentStep) {
        if (status != IndexJobStatus.QUEUED && status != IndexJobStatus.RUNNING) {
            throw new IllegalStateException("Job cannot start from status " + status);
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
        throw new IllegalStateException("Only queued or running jobs can be canceled");
    }

    public IndexJob cancel() {
        if (status != IndexJobStatus.CANCEL_REQUESTED && status != IndexJobStatus.QUEUED) {
            throw new IllegalStateException("Job is not cancelable from status " + status);
        }
        return new IndexJob(
            id, repositoryId, type, IndexJobStatus.CANCELED, "canceled", null,
            startedAt, Instant.now(), createdAt
        );
    }

    public IndexJob succeed(String currentStep) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("Job cannot succeed from status " + status);
        }
        return new IndexJob(
            id, repositoryId, type, IndexJobStatus.SUCCEEDED, currentStep, null,
            startedAt, Instant.now(), createdAt
        );
    }

    public IndexJob fail(String currentStep, String errorMessage) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("Job cannot fail from status " + status);
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
