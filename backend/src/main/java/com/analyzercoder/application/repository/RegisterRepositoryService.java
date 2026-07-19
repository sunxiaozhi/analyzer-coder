package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class RegisterRepositoryService implements RegisterRepositoryUseCase {

    private final CodeRepositoryStore repositoryStore;

    public RegisterRepositoryService(CodeRepositoryStore repositoryStore) {
        this.repositoryStore = repositoryStore;
    }

    @Override
    public CodeRepository register(RegisterRepositoryCommand command) {
        Path repositoryPath = Path.of(command.path()).toAbsolutePath().normalize();
        if (!Files.isDirectory(repositoryPath)) {
            throw new IllegalArgumentException("Repository path must be an existing directory: " + repositoryPath);
        }
        CodeRepository repository = CodeRepository.create(command.name(), repositoryPath);
        return repositoryStore.save(repository);
    }

    @Override
    public CodeRepository get(CodeRepositoryId repositoryId) {
        return repositoryStore.findById(repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId.value()));
    }

    @Override
    public List<CodeRepository> list() {
        return repositoryStore.findAll();
    }

    @Override
    public void delete(CodeRepositoryId repositoryId) {
        get(repositoryId);
        repositoryStore.delete(repositoryId);
    }
}

