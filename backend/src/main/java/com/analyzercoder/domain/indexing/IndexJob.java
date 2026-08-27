package com.analyzercoder.domain.indexing;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.Objects;

/** 索引任务聚合，维护任务类型、处理进度和状态迁移等核心不变量。 */
public record IndexJob(
        IndexJobId id,
        CodeRepositoryId repositoryId,
        IndexJobType type,
        IndexJobStatus status,
        String currentStep,
        String executionMode,
        String fallbackReason,
        String failureCode,
        String errorMessage,
        Instant startedAt,
        Instant heartbeatAt,
        Instant timeoutAt,
        Instant finishedAt,
        Instant createdAt) {

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
                null,
                null,
                null,
                null,
                null,
                Instant.now());
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
                id,
                repositoryId,
                type,
                IndexJobStatus.RUNNING,
                currentStep,
                executionMode,
                fallbackReason,
                null,
                null,
                effectiveStartedAt,
                Instant.now(),
                timeoutAt,
                null,
                createdAt);
    }

    public IndexJob withTimeout(Instant deadline) {
        if (status != IndexJobStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的任务可以设置超时截止时间");
        }
        return new IndexJob(
                id, repositoryId, type, status, currentStep, executionMode, fallbackReason,
                failureCode, errorMessage,
                startedAt, heartbeatAt, deadline, finishedAt, createdAt);
    }

    public IndexJob heartbeat(String step) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("只有活动任务可以写入心跳");
        }
        return new IndexJob(
                id, repositoryId, type, status, step, executionMode, fallbackReason,
                failureCode, errorMessage,
                startedAt, Instant.now(), timeoutAt, finishedAt, createdAt);
    }

    public IndexJob withExecutionPlan(String mode, String reason) {
        if (status != IndexJobStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的任务可以记录执行计划");
        }
        if (!"FULL".equals(mode) && !"INCREMENTAL".equals(mode)) {
            throw new IllegalArgumentException("未知索引执行模式: " + mode);
        }
        return new IndexJob(
                id, repositoryId, type, status, currentStep, mode, reason, failureCode,
                errorMessage, startedAt, Instant.now(), timeoutAt, finishedAt, createdAt);
    }

    public IndexJob requestCancel() {
        if (status == IndexJobStatus.QUEUED) {
            return new IndexJob(
                    id,
                    repositoryId,
                    type,
                    IndexJobStatus.CANCELED,
                    "canceled",
                    executionMode,
                    fallbackReason,
                    null,
                    null,
                    startedAt,
                    heartbeatAt,
                    timeoutAt,
                    Instant.now(),
                    createdAt);
        }
        if (status == IndexJobStatus.RUNNING) {
            return new IndexJob(
                    id,
                    repositoryId,
                    type,
                    IndexJobStatus.CANCEL_REQUESTED,
                    "cancel_requested",
                    executionMode,
                    fallbackReason,
                    null,
                    null,
                    startedAt,
                    heartbeatAt,
                    timeoutAt,
                    null,
                    createdAt);
        }
        throw new IllegalStateException("只有排队中或运行中的任务可以取消");
    }

    public IndexJob cancel() {
        if (status != IndexJobStatus.CANCEL_REQUESTED && status != IndexJobStatus.QUEUED) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能取消");
        }
        return new IndexJob(
                id,
                repositoryId,
                type,
                IndexJobStatus.CANCELED,
                "canceled",
                executionMode,
                fallbackReason,
                null,
                null,
                startedAt,
                heartbeatAt,
                timeoutAt,
                Instant.now(),
                createdAt);
    }

    public IndexJob succeed(String currentStep) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能标记为成功");
        }
        return new IndexJob(
                id,
                repositoryId,
                type,
                IndexJobStatus.SUCCEEDED,
                currentStep,
                executionMode,
                fallbackReason,
                null,
                null,
                startedAt,
                Instant.now(),
                timeoutAt,
                Instant.now(),
                createdAt);
    }

    public IndexJob fail(String currentStep, String errorMessage) {
        return fail(currentStep, "TASK_FAILED", errorMessage);
    }

    public IndexJob fail(String currentStep, String failureCode, String errorMessage) {
        if (status != IndexJobStatus.RUNNING && status != IndexJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("任务当前状态为“" + status + "”，不能标记为失败");
        }
        return new IndexJob(
                id,
                repositoryId,
                type,
                IndexJobStatus.FAILED,
                currentStep,
                executionMode,
                fallbackReason,
                failureCode,
                errorMessage,
                startedAt,
                Instant.now(),
                timeoutAt,
                Instant.now(),
                createdAt);
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
