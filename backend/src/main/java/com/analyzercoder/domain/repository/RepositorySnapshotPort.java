package com.analyzercoder.domain.repository;

import java.nio.file.Path;

/** 定义仓库快照的领域端口，由基础设施层提供具体适配实现。 */
public interface RepositorySnapshotPort {
    /**
     * 创建并持久化一条新记录。
     *
     * @param repositoryId 目标对象的唯一标识
     * @param sourceRoot 用于创建快照的受控源码根目录
     * @param sourceVersion 创建快照时对应的源码版本
     * @return 接口约定的操作结果
     */
    ManagedRepositorySnapshot create(
            CodeRepositoryId repositoryId, Path sourceRoot, GitRepositorySnapshot sourceVersion);

    /**
     * 删除符合给定条件的数据。
     *
     * @param snapshot 待保存或读取的不可变仓库快照
     */
    void delete(ManagedRepositorySnapshot snapshot);

    /**
     * 删除符合给定条件的数据。
     *
     * @param repositoryId 目标对象的唯一标识
     */
    void deleteRepository(CodeRepositoryId repositoryId);
}
