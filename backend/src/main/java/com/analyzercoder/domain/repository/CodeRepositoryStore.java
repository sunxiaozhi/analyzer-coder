package com.analyzercoder.domain.repository;

import java.util.List;
import java.util.Optional;

public interface CodeRepositoryStore {

    CodeRepository save(CodeRepository repository);

    Optional<CodeRepository> findById(CodeRepositoryId repositoryId);

    List<CodeRepository> findAll();

    void delete(CodeRepositoryId repositoryId);
}

