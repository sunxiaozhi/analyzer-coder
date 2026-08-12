package com.analyzercoder.domain.repository;

/** 定义代码仓库在领域内允许使用的有限取值。 */
public enum RepositorySourceType {
    LOCAL_GIT,
    REMOTE_GIT,
    GITLAB,
    ZIP
}
