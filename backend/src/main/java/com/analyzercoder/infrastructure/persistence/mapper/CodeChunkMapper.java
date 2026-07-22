package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.CodeChunkRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CodeChunkMapper {
    int deleteByRepositoryId(@Param("repositoryId") UUID repositoryId);
    int insertBatch(@Param("rows") List<CodeChunkRow> rows);
    List<CodeChunkRow> find(@Param("repositoryId") UUID repositoryId,@Param("query") String query,
        @Param("limit") Integer limit,@Param("offset") Integer offset);
    long count(@Param("repositoryId") UUID repositoryId,@Param("query") String query);
}
