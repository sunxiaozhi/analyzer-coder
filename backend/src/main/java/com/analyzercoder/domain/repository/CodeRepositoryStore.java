package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeRepositoryStore {
    CodeRepository save(CodeRepository repository);
    CodeRepository saveOwned(CodeRepository repository, UUID ownerAccountId);
    Optional<CodeRepository> findById(CodeRepositoryId repositoryId);
    List<CodeRepository> findAll();
    boolean existsByNormalizedName(UUID ownerAccountId, String name);
    boolean existsByPath(Path path);
    void delete(CodeRepositoryId repositoryId);
}
