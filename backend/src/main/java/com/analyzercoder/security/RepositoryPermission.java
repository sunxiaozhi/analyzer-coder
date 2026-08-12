package com.analyzercoder.security;

/** 定义代码仓库在领域内允许使用的有限取值。 */
public enum RepositoryPermission {
    READ,
    MAINTAIN,
    MANAGE;

    public boolean includes(RepositoryPermission required) {
        return ordinal() >= required.ordinal();
    }
}
