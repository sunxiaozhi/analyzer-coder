package com.analyzercoder.infrastructure.persistence.model;

import java.util.UUID;

/** 承载代码图谱产物的数据库查询结果，避免持久化字段直接泄漏到领域层。 */
public record CodeGraphArtifactRow(
        UUID id,
        UUID repositoryId,
        UUID snapshotId,
        String cliVersion,
        String status,
        String artifactPath,
        int nodeCount,
        int edgeCount) {}
