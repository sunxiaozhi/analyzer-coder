package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.RepositoryRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义代码仓库数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface RepositoryMapper {
    /**
     * 创建并持久化一条新记录。
     *
     * @param row 待持久化的数据库映射数据
     * @return 本次操作影响的记录数
     */
    int insertOwned(RepositoryRow row);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param row 待持久化的数据库映射数据
     * @return 本次操作影响的记录数
     */
    int update(RepositoryRow row);

    /**
     * 按唯一标识查询记录。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    RepositoryRow findById(@Param("id") UUID id);

    /**
     * 查询全部符合当前范围的记录。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<RepositoryRow> findAll();

    /**
     * 按给定条件查询匹配数据。
     *
     * @param accountId 目标对象的唯一标识
     * @param admin 是否以管理员权限范围执行查询
     * @param query 经过规范化的查询条件
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<RepositoryRow> findVisiblePage(
            @Param("accountId") UUID accountId,
            @Param("admin") boolean admin,
            @Param("query") String query);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param ownerId 目标对象的唯一标识
     * @param normalizedName 用于大小写无关比较的规范化名称
     * @return 符合条件的记录数
     */
    int countByOwnerAndNormalizedName(
            @Param("ownerId") UUID ownerId, @Param("normalizedName") String normalizedName);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param path 受控范围内的资源路径
     * @return 符合条件的记录数
     */
    int countByPath(@Param("path") String path);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param ownerId 目标对象的唯一标识
     * @param normalizedName 用于大小写无关比较的规范化名称
     * @param id 目标对象的唯一标识
     * @return 符合条件的记录数
     */
    int countByOwnerAndNormalizedNameExcludingId(
            @Param("ownerId") UUID ownerId,
            @Param("normalizedName") String normalizedName,
            @Param("id") UUID id);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param name 对象的业务名称
     * @param normalizedName 用于大小写无关比较的规范化名称
     * @param description 供用户识别对象用途的说明文本
     * @param defaultBranch 仓库默认分支名称
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @return 本次操作影响的记录数
     */
    int updateEditableMetadata(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("normalizedName") String normalizedName,
            @Param("description") String description,
            @Param("defaultBranch") String defaultBranch,
            @Param("expectedVersion") long expectedVersion);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param path 受控范围内的资源路径
     * @param sourceType 仓库、知识或证据的来源类型
     * @param remoteUrl 通过目标安全策略校验的远程仓库地址
     * @param hideGitVersion 是否在响应中隐藏 Git 版本信息
     * @return 本次操作影响的记录数
     */
    int updateManagedSource(
            @Param("id") UUID id,
            @Param("path") String path,
            @Param("sourceType") String sourceType,
            @Param("remoteUrl") String remoteUrl,
            @Param("hideGitVersion") boolean hideGitVersion);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param id 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    String findRemoteUrl(@Param("id") UUID id);

    /**
     * 更新符合给定条件的记录状态或内容。
     *
     * @param id 目标对象的唯一标识
     * @param sourceType 仓库、知识或证据的来源类型
     * @param hideGitVersion 是否在响应中隐藏 Git 版本信息
     * @return 本次操作影响的记录数
     */
    int updateSourceMetadata(
            @Param("id") UUID id,
            @Param("sourceType") String sourceType,
            @Param("hideGitVersion") boolean hideGitVersion);

    /**
     * 删除符合给定条件的数据。
     *
     * @param id 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int delete(@Param("id") UUID id);
}
