package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeRevisionRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义知识历史数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface KnowledgeHistoryMapper {
    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param cardId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<KnowledgeRevisionRow> findHistory(
            @Param("repositoryId") UUID repositoryId, @Param("cardId") UUID cardId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param cardId 目标对象的唯一标识
     * @param revision 知识内容的修订版本号
     * @return 接口约定的操作结果
     */
    KnowledgeRevisionRow findRevision(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("revision") int revision);

    /**
     * 恢复指定历史版本并生成新的当前版本。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param cardId 目标对象的唯一标识
     * @param source 记录或内容的来源标识
     * @param actorId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int restore(
            @Param("repositoryId") UUID repositoryId,
            @Param("cardId") UUID cardId,
            @Param("source") KnowledgeRevisionRow source,
            @Param("actorId") UUID actorId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param cardId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    KnowledgeCardRow findCard(
            @Param("repositoryId") UUID repositoryId, @Param("cardId") UUID cardId);
}
