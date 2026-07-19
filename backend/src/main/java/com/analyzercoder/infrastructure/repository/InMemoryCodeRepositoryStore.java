package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryCodeRepositoryStore implements CodeRepositoryStore {

    private final Map<UUID, CodeRepository> repositories = new ConcurrentHashMap<>();

    @Override
    public CodeRepository save(CodeRepository repository) {
        repositories.put(repository.id().value(), repository);
        return repository;
    }

    @Override
    public Optional<CodeRepository> findById(CodeRepositoryId repositoryId) {
        return Optional.ofNullable(repositories.get(repositoryId.value()));
    }

    @Override
    public List<CodeRepository> findAll() {
        return repositories.values().stream()
            .sorted(Comparator.comparing(CodeRepository::createdAt))
            .toList();
    }

    @Override
    public void delete(CodeRepositoryId repositoryId) {
        repositories.remove(repositoryId.value());
    }
}

