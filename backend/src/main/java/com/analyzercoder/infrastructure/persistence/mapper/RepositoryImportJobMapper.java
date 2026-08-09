package com.analyzercoder.infrastructure.persistence.mapper;
import java.util.*;import org.apache.ibatis.annotations.*;
@Mapper public interface RepositoryImportJobMapper{
 int insert(@Param("id")UUID id,@Param("actorId")UUID actorId,@Param("credentialId")UUID credentialId,
  @Param("sourceType")String sourceType,@Param("name")String name,@Param("url")String url,@Param("branch")String branch);
 Map<String,Object> find(@Param("id")UUID id);List<Map<String,Object>> list(@Param("actorId")UUID actorId,@Param("admin")boolean admin);
 Map<String,Object> claim();int step(@Param("id")UUID id,@Param("step")String step);int succeed(@Param("id")UUID id,@Param("repositoryId")UUID repositoryId);
 int fail(@Param("id")UUID id,@Param("error")String error);int requestCancel(@Param("id")UUID id,@Param("actorId")UUID actorId,@Param("admin")boolean admin);
 int cancel(@Param("id")UUID id);
}
