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
    int countByOwnerAndNormalizedName(@Param("ownerId") UUID ownerId, @Param("normalizedName") String normalizedName);
    int countByPath(@Param("path") String path);
    int updateManagedSource(@Param("id") UUID id, @Param("path") String path, @Param("sourceType") String sourceType,
        @Param("hideGitVersion") boolean hideGitVersion);    int updateSourceMetadata(@Param("id") UUID id, @Param("sourceType") String sourceType,
        @Param("hideGitVersion") boolean hideGitVersion);
    int delete(@Param("id") UUID id);
}
