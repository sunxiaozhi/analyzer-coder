package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.ProjectKnowledgeHealthRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 为工程健康总览提供只读、可审计的聚合计数。 */
@Mapper
public interface ProjectHealthMapper {
    ProjectKnowledgeHealthRow knowledgeHealth(@Param("repositoryId") UUID repositoryId);
}
