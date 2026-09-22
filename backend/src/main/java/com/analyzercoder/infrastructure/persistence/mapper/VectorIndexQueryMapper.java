package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义向量索引查询数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface VectorIndexQueryMapper {
    /**
     * 查询账户摘要及其角色、状态和最近活动信息。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> summary(@Param("repositoryId") UUID repositoryId);

    Map<String, Object> summaryForBranch(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion,
            @Param("commitSha") String commitSha);

    /**
     * 查询与指定条件匹配的代码片段。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param status 用于筛选或更新的目标状态
     * @param chunkType 代码片段的结构类型
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> chunks(
            @Param("repositoryId") UUID repositoryId,
            @Param("query") String query,
            @Param("status") String status,
            @Param("chunkType") String chunkType);

    List<Map<String, Object>> chunksForBranch(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion,
            @Param("query") String query,
            @Param("status") String status,
            @Param("chunkType") String chunkType);

    /**
     * 查询可参与检索的知识内容。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param query 经过规范化的查询条件
     * @param status 用于筛选或更新的目标状态
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> knowledge(
            @Param("repositoryId") UUID repositoryId,
            @Param("query") String query,
            @Param("status") String status);

    List<Map<String, Object>> knowledgeForBranch(
            @Param("repositoryId") UUID repositoryId,
            @Param("branchId") UUID branchId,
            @Param("contentVersion") UUID contentVersion,
            @Param("query") String query,
            @Param("status") String status);
}
