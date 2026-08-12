package com.analyzercoder.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义问答会话数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface QaSessionMapper {
    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @param repoId 目标对象的唯一标识
     * @param repositoryIds 限定操作范围的代码仓库标识集合
     * @param title 知识卡片、会话或证据的显示标题
     * @return 本次操作影响的记录数
     */
    int insert(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("repoId") UUID repoId,
            @Param("repositoryIds") UUID[] repositoryIds,
            @Param("title") String title);

    /**
     * 按当前访问范围和筛选条件查询记录列表。
     *
     * @param accountId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> list(@Param("accountId") UUID accountId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param id 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    Map<String, Object> find(@Param("id") UUID id, @Param("accountId") UUID accountId);

    /**
     * 按时间顺序查询问答会话消息。
     *
     * @param id 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<Map<String, Object>> messages(@Param("id") UUID id);

    /**
     * 按标识查询问答会话中的单条消息。
     *
     * @param id 目标对象的唯一标识
     * @param sessionId 目标对象的唯一标识
     * @param role 账户角色或会话消息角色
     * @param content 待保存、索引或渲染的正文内容
     * @param citations 回答关联的可追溯证据引用
     * @param conversationId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int message(
            @Param("id") UUID id,
            @Param("sessionId") UUID sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("citations") String citations,
            @Param("conversationId") UUID conversationId);

    /**
     * 刷新记录的最后活动时间。
     *
     * @param id 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int touch(@Param("id") UUID id);

    /**
     * 修改问答会话标题并更新时间。
     *
     * @param id 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @param title 知识卡片、会话或证据的显示标题
     * @return 本次操作影响的记录数
     */
    int rename(
            @Param("id") UUID id, @Param("accountId") UUID accountId, @Param("title") String title);

    /**
     * 删除符合给定条件的数据。
     *
     * @param id 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int delete(@Param("id") UUID id, @Param("accountId") UUID accountId);
}
