package com.analyzercoder.domain.indexing;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.Optional;

/** 定义索引任务的持久化端口，使领域逻辑不依赖具体存储技术。 */
public interface IndexJobStore {

    /**
     * 保存当前领域对象的最新状态。
     *
     * @param indexJob 待保存的索引任务领域对象
     * @return 接口约定的操作结果
     */
    IndexJob save(IndexJob indexJob);

    /**
     * 按唯一标识查询记录。
     *
     * @param indexJobId 目标对象的唯一标识
     * @return 可能为空的匹配结果
     */
    Optional<IndexJob> findById(IndexJobId indexJobId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 可能为空的匹配结果
     */
    Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<IndexJob> findByRepositoryId(CodeRepositoryId repositoryId);

    /**
     * 查询全部符合当前范围的记录。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<IndexJob> findAll();

    /**
     * 判断仓库是否存在排队中或执行中的索引任务。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 满足接口条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean hasActiveJob(CodeRepositoryId repositoryId);

    /**
     * 按给定条件查询匹配数据。
     *
     * @return 可能为空的匹配结果
     */
    Optional<IndexJob> findNextQueued();

    /**
     * 以排他方式领取下一条排队任务，避免并发重复消费。
     *
     * @return 可能为空的匹配结果
     */
    Optional<IndexJob> claimNextQueued();

    Optional<IndexJob> claimNextQueued(
            IndexJobType type, String initialStep, long timeoutSeconds);

    Optional<IndexJob> heartbeat(IndexJobId id, String currentStep);

    int expireTimedOut(IndexJobType type);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     */
    void deleteByRepositoryId(CodeRepositoryId repositoryId);
}
