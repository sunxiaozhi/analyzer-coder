package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 定义代码仓库的持久化端口，使领域逻辑不依赖具体存储技术。 */
public interface CodeRepositoryStore {
    /**
     * 保存当前领域对象的最新状态。
     *
     * @param repository 待处理的代码仓库领域对象
     * @return 接口约定的操作结果
     */
    CodeRepository save(CodeRepository repository);

    /**
     * 保存当前领域对象的最新状态。
     *
     * @param repository 待处理的代码仓库领域对象
     * @param ownerAccountId 目标对象的唯一标识
     * @return 接口约定的操作结果
     */
    CodeRepository saveOwned(CodeRepository repository, UUID ownerAccountId);

    /**
     * 按唯一标识查询记录。
     *
     * @param repositoryId 目标对象的唯一标识
     * @return 可能为空的匹配结果
     */
    Optional<CodeRepository> findById(CodeRepositoryId repositoryId);

    /**
     * 查询全部符合当前范围的记录。
     *
     * @return 匹配结果列表；无匹配数据时返回空列表
     */
    List<CodeRepository> findAll();

    /**
     * 判断符合给定条件的记录是否存在。
     *
     * @param ownerAccountId 目标对象的唯一标识
     * @param name 对象的业务名称
     * @return 满足接口条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean existsByNormalizedName(UUID ownerAccountId, String name);

    /**
     * 判断符合给定条件的记录是否存在。
     *
     * @param path 受控范围内的资源路径
     * @return 满足接口条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean existsByPath(Path path);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     */
    void delete(CodeRepositoryId repositoryId);
}
