package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.RepositoryAccessRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义仓库访问授权数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface RepositoryAccessMapper {
    /**
     * 按给定条件查询匹配数据。
     *
     * @param accountId 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    RepositoryAccessRow findAccess(
            @Param("accountId") UUID accountId, @Param("repositoryId") UUID repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    RepositoryAccessRow findMetadata(@Param("repositoryId") UUID repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param accountId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<UUID> findVisibleRepositoryIds(@Param("accountId") UUID accountId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<UUID> findVisibleRepositoryIdsForAdmin();

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<RepositoryAccessRow> findGrants(@Param("repositoryId") UUID repositoryId);

    /**
     * 新增或更新账户的仓库授权。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @param permission 需要授予或校验的仓库权限
     * @return 本次操作影响的记录数
     */
    int upsertGrant(
            @Param("repositoryId") UUID repositoryId,
            @Param("accountId") UUID accountId,
            @Param("permission") String permission);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param accountId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteGrant(@Param("repositoryId") UUID repositoryId, @Param("accountId") UUID accountId);

    /**
     * 将仓库所有权原子转移给目标账户。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @param newOwnerId 目标对象的唯一标识
     * @param newName 重命名后使用的新名称
     * @param normalizedName 用于大小写无关比较的规范化名称
     * @return 本次操作影响的记录数
     */
    int transferOwnership(
            @Param("repositoryId") UUID repositoryId,
            @Param("expectedVersion") long expectedVersion,
            @Param("newOwnerId") UUID newOwnerId,
            @Param("newName") String newName,
            @Param("normalizedName") String normalizedName);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param accountId 目标对象的唯一标识
     * @return 符合条件的记录数
     */
    int countOwnedRepositories(@Param("accountId") UUID accountId);
}
