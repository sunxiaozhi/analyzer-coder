package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.RepositoryRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepositoryMapper {
    int insertOwned(RepositoryRow row);
    int update(RepositoryRow row);
    RepositoryRow findById(@Param("id") UUID id);
    List<RepositoryRow> findAll();
    List<RepositoryRow> findVisiblePage(@Param("accountId") UUID accountId,@Param("admin") boolean admin,@Param("query") String query);
    int countByOwnerAndNormalizedName(@Param("ownerId") UUID ownerId, @Param("normalizedName") String normalizedName);
    int countByPath(@Param("path") String path);
    int countByOwnerAndNormalizedNameExcludingId(@Param("ownerId") UUID ownerId,@Param("normalizedName") String normalizedName,@Param("id") UUID id);
    int updateEditableMetadata(@Param("id") UUID id,@Param("name") String name,@Param("normalizedName") String normalizedName,@Param("description") String description,@Param("defaultBranch") String defaultBranch,@Param("expectedVersion") long expectedVersion);
    int updateManagedSource(@Param("id") UUID id, @Param("path") String path, @Param("sourceType") String sourceType,
        @Param("remoteUrl") String remoteUrl, @Param("hideGitVersion") boolean hideGitVersion);
    String findRemoteUrl(@Param("id") UUID id);
    int updateSourceMetadata(@Param("id") UUID id, @Param("sourceType") String sourceType,
        @Param("hideGitVersion") boolean hideGitVersion);
    int delete(@Param("id") UUID id);
}
