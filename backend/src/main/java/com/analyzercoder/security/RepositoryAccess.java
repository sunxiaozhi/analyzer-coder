package com.analyzercoder.security;

import java.util.UUID;

public record RepositoryAccess(
        UUID ownerAccountId,
        String ownerDisplayName,
        String relationship,
        long ownershipVersion,
        String repositoryStatus,
        Capabilities capabilities
) {
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
            boolean canDelete
    ) {
    }
}
