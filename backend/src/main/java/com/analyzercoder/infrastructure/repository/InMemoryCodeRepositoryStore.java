package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryCodeRepositoryStore implements CodeRepositoryStore {
    private final Map<UUID, CodeRepository> repositories = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> owners = new ConcurrentHashMap<>();

    @Override public CodeRepository save(CodeRepository repository) { repositories.put(repository.id().value(), repository); return repository; }
    @Override public CodeRepository saveOwned(CodeRepository repository, UUID ownerAccountId) {
        if (ownerAccountId != null) owners.put(repository.id().value(), ownerAccountId);
        return save(repository);
    }
    @Override public Optional<CodeRepository> findById(CodeRepositoryId id) { return Optional.ofNullable(repositories.get(id.value())); }
    @Override public List<CodeRepository> findAll() { return repositories.values().stream().sorted(Comparator.comparing(CodeRepository::createdAt)).toList(); }
    @Override public boolean existsByNormalizedName(UUID ownerId, String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return repositories.values().stream().anyMatch(repository ->
            (ownerId == null || ownerId.equals(owners.get(repository.id().value())))
                && repository.name().trim().toLowerCase(Locale.ROOT).equals(normalized));
    }
    @Override public boolean existsByPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return repositories.values().stream().anyMatch(repository -> repository.path().equals(normalized));
    }
    @Override public void delete(CodeRepositoryId id) { repositories.remove(id.value()); owners.remove(id.value()); }
}
