package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

public record IndexJobRow(UUID id,UUID repositoryId,String jobType,String status,String currentStep,
    String errorMessage,Instant startedAt,Instant finishedAt,Instant createdAt) {}
