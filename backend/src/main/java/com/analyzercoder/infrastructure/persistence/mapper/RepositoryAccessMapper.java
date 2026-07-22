package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.RepositoryAccessRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepositoryAccessMapper {
    RepositoryAccessRow findAccess(@Param("accountId") UUID accountId, @Param("repositoryId") UUID repositoryId);
    RepositoryAccessRow findMetadata(@Param("repositoryId") UUID repositoryId);
    List<UUID> findVisibleRepositoryIds(@Param("accountId") UUID accountId);
    List<UUID> findVisibleRepositoryIdsForAdmin();
    List<RepositoryAccessRow> findGrants(@Param("repositoryId") UUID repositoryId);
    int upsertGrant(
        @Param("repositoryId") UUID repositoryId,
        @Param("accountId") UUID accountId,
        @Param("permission") String permission
    );
    int deleteGrant(@Param("repositoryId") UUID repositoryId, @Param("accountId") UUID accountId);
    int transferOwnership(
        @Param("repositoryId") UUID repositoryId,
        @Param("expectedVersion") long expectedVersion,
        @Param("newOwnerId") UUID newOwnerId,
        @Param("newName") String newName,
        @Param("normalizedName") String normalizedName
    );
    int countOwnedRepositories(@Param("accountId") UUID accountId);
}
