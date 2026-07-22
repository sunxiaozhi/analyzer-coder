package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.IndexJobRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IndexJobMapper {
    int upsert(IndexJobRow row);
    IndexJobRow findById(@Param("id") UUID id);
    IndexJobRow findLatest(@Param("repositoryId") UUID repositoryId);
    List<IndexJobRow> findByRepositoryId(@Param("repositoryId") UUID repositoryId);
    List<IndexJobRow> findAll();
    int countActive(@Param("repositoryId") UUID repositoryId);
    IndexJobRow findNextQueued();
    IndexJobRow claimNextQueued();
    int deleteByRepositoryId(@Param("repositoryId") UUID repositoryId);
}
