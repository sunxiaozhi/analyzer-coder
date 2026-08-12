package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 承载索引任务的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record IndexJobRow(
        UUID id,
        UUID repositoryId,
        String jobType,
        String status,
        String currentStep,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {}
