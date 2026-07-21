package com.analyzercoder.domain.repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface CodeRepositoryStore {

    CodeRepository save(CodeRepository repository);

    Optional<CodeRepository> findById(CodeRepositoryId repositoryId);

    List<CodeRepository> findAll();

    boolean existsByNormalizedName(String name);

    boolean existsByPath(Path path);

    void delete(CodeRepositoryId repositoryId);
}
