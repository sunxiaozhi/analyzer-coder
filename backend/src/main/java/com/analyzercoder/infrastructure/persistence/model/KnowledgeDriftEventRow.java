package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 知识漂移审计事件的数据库映射。 */
public record KnowledgeDriftEventRow(
        UUID id,
        UUID repositoryId,
        UUID cardId,
        int cardRevision,
        UUID fromSnapshotId,
        UUID toSnapshotId,
        String fromCommit,
        String toCommit,
        String previousStatus,
        String resultStatus,
        String triggerType,
        String reasonsPayload,
        String note,
        UUID actorId,
        Instant createdAt) {}
