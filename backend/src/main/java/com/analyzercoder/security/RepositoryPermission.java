package com.analyzercoder.security;

public enum RepositoryPermission {
    READ,
    MAINTAIN,
    MANAGE;

    public boolean includes(RepositoryPermission required) {
        return ordinal() >= required.ordinal();
    }
}
