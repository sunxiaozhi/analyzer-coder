package com.analyzercoder.domain.repository;

import java.nio.file.Path;

public interface RepositorySnapshotPort {
    ManagedRepositorySnapshot create(
        CodeRepositoryId repositoryId,
        Path sourceRoot,
        GitRepositorySnapshot sourceVersion
    );

    void delete(ManagedRepositorySnapshot snapshot);

    void deleteRepository(CodeRepositoryId repositoryId);
}
