package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 承载知识修订版本的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record KnowledgeRevisionRow(
        UUID cardId,
        int revision,
        UUID repositoryId,
        String title,
        String cardType,
        String content,
        String[] tags,
        String knowledgeKind,
        String severity,
        String enforcement,
        UUID ownerAccountId,
        String scopePayload,
        String obligationsPayload,
        UUID lastVerifiedSnapshotId,
        String verificationNote,
        String publicationStatus,
        UUID changedBy,
        Instant changedAt) {}
