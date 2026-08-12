package com.analyzercoder.infrastructure.persistence.model;

import java.time.Instant;
import java.util.UUID;

/** 承载知识卡片的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record KnowledgeCardRow(
        UUID id,
        UUID repositoryId,
        String title,
        String cardType,
        String content,
        String[] tags,
        String status,
        int revision,
        Instant createdAt,
        Instant updatedAt,
        String verifiedCommit,
        String codeReviewStatus,
        Instant codeReviewedAt) {}
