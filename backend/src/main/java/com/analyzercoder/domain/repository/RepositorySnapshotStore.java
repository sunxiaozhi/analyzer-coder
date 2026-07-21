package com.analyzercoder.domain.repository;

public interface RepositorySnapshotStore {
    ManagedRepositorySnapshot save(ManagedRepositorySnapshot snapshot);

    void deleteByRepositoryId(CodeRepositoryId repositoryId);
}
