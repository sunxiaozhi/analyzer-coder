package com.analyzercoder.infrastructure.persistence.mapper;
import java.util.*;import org.apache.ibatis.annotations.*;
@Mapper public interface QaSessionMapper {
 int insert(@Param("id")UUID id,@Param("accountId")UUID accountId,@Param("repoId")UUID repoId,@Param("repositoryIds")UUID[] repositoryIds,@Param("title")String title);
 List<Map<String,Object>> list(@Param("accountId")UUID accountId);Map<String,Object> find(@Param("id")UUID id,@Param("accountId")UUID accountId);List<Map<String,Object>> messages(@Param("id")UUID id);
 int message(@Param("id")UUID id,@Param("sessionId")UUID sessionId,@Param("role")String role,@Param("content")String content,@Param("citations")String citations,@Param("conversationId")UUID conversationId);
 int touch(@Param("id")UUID id);int rename(@Param("id")UUID id,@Param("accountId")UUID accountId,@Param("title")String title);int delete(@Param("id")UUID id,@Param("accountId")UUID accountId);
}
