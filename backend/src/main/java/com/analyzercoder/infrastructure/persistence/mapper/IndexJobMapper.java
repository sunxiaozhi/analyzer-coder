package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.IndexJobRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义索引任务数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface IndexJobMapper {
    /**
     * 按业务唯一键新增记录或更新其当前内容。
     *
     * @param row 待持久化的数据库映射数据
     * @return 本次操作影响的记录数
     */
    int upsert(IndexJobRow row);

    /**
     * 按唯一标识查询记录。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    IndexJobRow findById(@Param("id") UUID id);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    IndexJobRow findLatest(@Param("repositoryId") UUID repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<IndexJobRow> findByRepositoryId(@Param("repositoryId") UUID repositoryId);

    /**
     * 查询全部符合当前范围的记录。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<IndexJobRow> findAll();

    /**
     * 按给定条件查询匹配数据。
     *
     * @param accountId 目标对象的唯一标识
     * @param admin 是否以管理员权限范围执行查询
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<IndexJobRow> findVisiblePage(
            @Param("accountId") UUID accountId, @Param("admin") boolean admin);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 符合条件的记录数
     */
    int countActive(@Param("repositoryId") UUID repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @return 接口约定的操作结果
     */
    IndexJobRow findNextQueued();

    /**
     * 以排他方式领取下一条排队任务，避免并发重复消费。
     *
     * @return 接口约定的操作结果
     */
    IndexJobRow claimNextQueued();

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteByRepositoryId(@Param("repositoryId") UUID repositoryId);
}
