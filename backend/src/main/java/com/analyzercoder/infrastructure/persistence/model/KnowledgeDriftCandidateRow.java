package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

/** 自动知识漂移检查所需的最小持久化快照。 */
public record KnowledgeDriftCandidateRow(
        UUID id,
        int revision,
        String scopePayload,
        UUID lastVerifiedSnapshotId,
        String verifiedCommit,
        String sourceVersionStatus) {}
