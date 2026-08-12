package com.analyzercoder.security;

import java.util.UUID;

/** 描述或执行仓库访问授权相关安全规则，供接口层与应用服务统一复用。 */
public record RepositoryAccess(
        UUID ownerAccountId,
        String ownerDisplayName,
        String relationship,
        long ownershipVersion,
        String repositoryStatus,
        Capabilities capabilities) {
    public record Capabilities(
            boolean canRead,
            boolean canEditRepository,
            boolean canUpdate,
            boolean canIndex,
            boolean canBuildCodeGraph,
            boolean canConfigure,
            boolean canGrant,
            boolean canManageCredential,
            boolean canTransferOwnership,
            boolean canDelete) {}
}
