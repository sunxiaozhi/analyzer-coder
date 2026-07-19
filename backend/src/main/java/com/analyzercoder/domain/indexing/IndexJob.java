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

    public IndexJob start(String currentStep) {
        return new IndexJob(id, repositoryId, type, IndexJobStatus.RUNNING, currentStep, null, Instant.now(), null, createdAt);
    }

    public IndexJob succeed(String currentStep) {
        return new IndexJob(id, repositoryId, type, IndexJobStatus.SUCCEEDED, currentStep, null, startedAt, Instant.now(), createdAt);
    }

    public IndexJob fail(String currentStep, String errorMessage) {
        return new IndexJob(id, repositoryId, type, IndexJobStatus.FAILED, currentStep, errorMessage, startedAt, Instant.now(), createdAt);
    }

    public IndexJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
