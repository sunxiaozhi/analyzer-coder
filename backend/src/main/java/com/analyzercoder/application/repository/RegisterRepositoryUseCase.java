package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;

public interface RegisterRepositoryUseCase {

    CodeRepository register(RegisterRepositoryCommand command);

    CodeRepository get(CodeRepositoryId repositoryId);

    List<CodeRepository> list();

    void delete(CodeRepositoryId repositoryId);
}

