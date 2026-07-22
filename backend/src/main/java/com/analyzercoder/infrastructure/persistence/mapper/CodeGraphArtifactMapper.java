package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryVersionRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CodeGraphArtifactMapper {
    RepositoryVersionRow findRepositoryVersion(@Param("repositoryId") UUID repositoryId);
    int retirePublished(@Param("repositoryId") UUID repositoryId);
    int insertPublished(CodeGraphArtifactRow row);
    CodeGraphArtifactRow findLatest(@Param("repositoryId") UUID repositoryId, @Param("snapshotId") UUID snapshotId);
    CodeGraphArtifactRow findPublished(@Param("repositoryId") UUID repositoryId, @Param("snapshotId") UUID snapshotId);
}
