package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

/** 自动知识漂移检查所需的最小持久化内容版本。 */
public record KnowledgeDriftCandidateRow(
        UUID id,
        int revision,
        String scopePayload,
        UUID lastVerifiedContentVersion,
        String verifiedCommit,
        String sourceVersionStatus) {}
