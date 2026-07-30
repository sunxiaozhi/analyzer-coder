package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VectorIndexQueryMapper {
    Map<String, Object> summary(@Param("repositoryId") UUID repositoryId);

    List<Map<String, Object>> chunks(
        @Param("repositoryId") UUID repositoryId,
        @Param("query") String query,
        @Param("status") String status,
        @Param("chunkType") String chunkType
    );

    List<Map<String, Object>> knowledge(
        @Param("repositoryId") UUID repositoryId,
        @Param("query") String query,
        @Param("status") String status
    );
}
