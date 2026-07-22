package com.analyzercoder.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepositorySnapshotMapper {
    int insert(@Param("id") UUID id,@Param("repositoryId") UUID repositoryId,@Param("sourceCommit") String sourceCommit,
        @Param("worktreeDigest") String worktreeDigest,@Param("storagePath") String storagePath,@Param("createdAt") Instant createdAt);
    int deleteByRepositoryId(@Param("repositoryId") UUID repositoryId);
}
