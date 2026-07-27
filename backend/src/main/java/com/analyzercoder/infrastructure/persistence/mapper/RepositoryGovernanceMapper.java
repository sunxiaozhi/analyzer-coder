package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.GovernanceAccountRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryGovernanceRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryMemberRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepositoryGovernanceMapper {
    RepositoryGovernanceRow findForUpdate(@Param("repositoryId") UUID repositoryId);
    List<RepositoryMemberRow> findMembers(@Param("repositoryId") UUID repositoryId);
    List<GovernanceAccountRow> findEnabledAccounts();
    GovernanceAccountRow findAccount(@Param("accountId") UUID accountId);
    int countNameConflict(@Param("ownerAccountId") UUID ownerAccountId,
        @Param("normalizedName") String normalizedName, @Param("excludeRepositoryId") UUID excludeRepositoryId);
    int upsertGrant(@Param("repositoryId") UUID repositoryId, @Param("accountId") UUID accountId,
        @Param("permission") String permission);
    int deleteGrant(@Param("repositoryId") UUID repositoryId, @Param("accountId") UUID accountId);
    int incrementVersion(@Param("repositoryId") UUID repositoryId, @Param("expectedVersion") long expectedVersion);
    int transferOwnership(@Param("repositoryId") UUID repositoryId, @Param("expectedVersion") long expectedVersion,
        @Param("newOwnerId") UUID newOwnerId, @Param("newName") String newName,
        @Param("normalizedName") String normalizedName);
    int markDeleting(@Param("repositoryId") UUID repositoryId, @Param("expectedVersion") long expectedVersion);
    int insertDeletionTombstone(@Param("repositoryId") UUID repositoryId, @Param("deletedBy") UUID deletedBy,
        @Param("deletedAt") Instant deletedAt);
    UUID claimNextCleanup();
    int deleteQaConversations(@Param("repositoryId") UUID repositoryId);
    int deleteQaMessages(@Param("repositoryId") UUID repositoryId);
    int deleteQaSessions(@Param("repositoryId") UUID repositoryId);
    int deleteKnowledgeCards(@Param("repositoryId") UUID repositoryId);
    int deleteCodeGraphEdges(@Param("repositoryId") UUID repositoryId);
    int deleteCodeGraphArtifacts(@Param("repositoryId") UUID repositoryId);
    int deleteChunkEmbeddings(@Param("repositoryId") UUID repositoryId);
    int deleteCodeChunks(@Param("repositoryId") UUID repositoryId);
    int deleteIndexJobs(@Param("repositoryId") UUID repositoryId);
    int deleteRepositoryGrants(@Param("repositoryId") UUID repositoryId);
    int deleteRepositoryCredentials(@Param("repositoryId") UUID repositoryId);
    int deleteGovernanceLock(@Param("repositoryId") UUID repositoryId);
    int finalizeDeletion(@Param("repositoryId") UUID repositoryId);
    int completeCleanup(@Param("repositoryId") UUID repositoryId);
    int failCleanup(@Param("repositoryId") UUID repositoryId, @Param("errorCode") String errorCode);    int insertAudit(@Param("id") UUID id, @Param("actorId") UUID actorId, @Param("targetAccountId") UUID targetAccountId,
        @Param("repositoryId") UUID repositoryId, @Param("eventType") String eventType,
        @Param("requestId") UUID requestId, @Param("sourceIp") String sourceIp, @Param("createdAt") Instant createdAt);
}
