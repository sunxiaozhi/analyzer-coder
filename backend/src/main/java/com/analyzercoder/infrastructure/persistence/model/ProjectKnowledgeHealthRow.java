package com.analyzercoder.infrastructure.persistence.model;

/** 项目总览使用的知识治理聚合，所有计数都直接来自知识卡持久化状态。 */
public record ProjectKnowledgeHealthRow(
        long total,
        long current,
        long suspect,
        long stale,
        long unverified,
        long trusted,
        long requiredWithoutOwner,
        long unreviewed) {
    public static ProjectKnowledgeHealthRow empty() {
        return new ProjectKnowledgeHealthRow(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
