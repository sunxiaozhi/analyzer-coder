package com.analyzercoder.domain.repository;

import java.nio.file.Path;

/** 描述Git 仓库的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public interface LocalGitInspector {
    /**
     * 读取本地 Git 仓库的分支、提交和工作区状态。
     *
     * @param repositoryRoot 已经过路径策略校验的本地仓库根目录
     * @return 接口约定的操作结果
     */
    GitRepositorySnapshot inspect(Path repositoryRoot);
}
