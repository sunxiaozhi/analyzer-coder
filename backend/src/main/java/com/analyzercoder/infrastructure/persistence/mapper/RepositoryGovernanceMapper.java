package com.analyzercoder.infrastructure.persistence.mapper;

import com.analyzercoder.infrastructure.persistence.model.GovernanceAccountRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryGovernanceRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryMemberRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定义仓库治理数据访问操作的 MyBatis 映射接口，集中维护持久化层查询边界。 */
@Mapper
public interface RepositoryGovernanceMapper {
    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    RepositoryGovernanceRow findForUpdate(@Param("repositoryId") UUID repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<RepositoryMemberRow> findMembers(@Param("repositoryId") UUID repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<GovernanceAccountRow> findEnabledAccounts();

    /**
     * 按给定条件查询匹配数据。
     *
     * @param accountId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    GovernanceAccountRow findAccount(@Param("accountId") UUID accountId);

    /**
     * 统计符合给定条件的记录数。
     *
     * @param ownerAccountId 目标对象的唯一标识
     * @param normalizedName 用于大小写无关比较的规范化名称
     * @param excludeRepositoryId 目标对象的唯一标识
     * @return 符合条件的记录数
     */
    int countNameConflict(
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("normalizedName") String normalizedName,
            @Param("excludeRepositoryId") UUID excludeRepositoryId);

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
     * 原子递增记录版本号，用于并发修改检测。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @return 本次操作影响的记录数
     */
    int incrementVersion(
            @Param("repositoryId") UUID repositoryId,
            @Param("expectedVersion") long expectedVersion);

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
     * 更新符合给定条件的记录状态或内容。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param expectedVersion 执行乐观锁更新时预期的当前版本号
     * @return 本次操作影响的记录数
     */
    int markDeleting(
            @Param("repositoryId") UUID repositoryId,
            @Param("expectedVersion") long expectedVersion);

    /**
     * 创建并持久化一条新记录。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param deletedBy 执行删除操作的账户标识
     * @param deletedAt 发起或完成逻辑删除的时间点
     * @return 本次操作影响的记录数
     */
    int insertDeletionTombstone(
            @Param("repositoryId") UUID repositoryId,
            @Param("deletedBy") UUID deletedBy,
            @Param("deletedAt") Instant deletedAt);

    /**
     * 以排他方式领取下一条待清理仓库任务。
     *
     * @return 接口约定的操作结果
     */
    UUID claimNextCleanup();

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteQaConversations(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteKnowledgeCards(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteHeuristicCallEdges(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteCodeGraphArtifacts(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteChunkEmbeddings(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteCodeChunks(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteIndexJobs(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteRepositoryGrants(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteRepositoryCredentials(@Param("repositoryId") UUID repositoryId);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int deleteGovernanceLock(@Param("repositoryId") UUID repositoryId);

    /**
     * 完成仓库删除状态迁移并清除待删除标记。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int finalizeDeletion(@Param("repositoryId") UUID repositoryId);

    /**
     * 标记仓库清理完成并记录完成时间。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 本次操作影响的记录数
     */
    int completeCleanup(@Param("repositoryId") UUID repositoryId);

    /**
     * 标记仓库清理失败并记录可重试信息。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param errorCode 可供调用方稳定识别的错误码
     * @return 本次操作影响的记录数
     */
    int failCleanup(@Param("repositoryId") UUID repositoryId, @Param("errorCode") String errorCode);

    /**
     * 创建并持久化一条新记录。
     *
     * @param id 目标对象的唯一标识
     * @param actorId 目标对象的唯一标识
     * @param targetAccountId 目标对象的唯一标识
     * @param repositoryId 目标对象的唯一标识
     * @param eventType 审计日志记录的事件类型
     * @param requestId 目标对象的唯一标识
     * @param sourceIp 按可信代理规则解析的客户端来源地址
     * @param createdAt 记录首次创建的时间点
     * @return 本次操作影响的记录数
     */
    int insertAudit(
            @Param("id") UUID id,
            @Param("actorId") UUID actorId,
            @Param("targetAccountId") UUID targetAccountId,
            @Param("repositoryId") UUID repositoryId,
            @Param("eventType") String eventType,
            @Param("requestId") UUID requestId,
            @Param("sourceIp") String sourceIp,
            @Param("createdAt") Instant createdAt);
}
