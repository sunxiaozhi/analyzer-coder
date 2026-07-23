package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryRow;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class PostgresCodeRepositoryStore implements CodeRepositoryStore {
    private final RepositoryMapper mapper;
    public PostgresCodeRepositoryStore(RepositoryMapper mapper) { this.mapper = mapper; }

    @Override public CodeRepository saveOwned(CodeRepository repository, UUID ownerAccountId) {
        mapper.insertOwned(RepositoryRow.forInsert(repository, ownerAccountId));
        return repository;
    }
    @Override public CodeRepository save(CodeRepository repository) {
        if (mapper.update(RepositoryRow.forUpdate(repository)) != 1) throw new IllegalArgumentException("Repository not found");
        return repository;
    }
    @Override public Optional<CodeRepository> findById(CodeRepositoryId id) { return Optional.ofNullable(mapper.findById(id.value())).map(PostgresCodeRepositoryStore::toDomain); }
    @Override public List<CodeRepository> findAll() { return mapper.findAll().stream().map(PostgresCodeRepositoryStore::toDomain).toList(); }
    @Override public boolean existsByNormalizedName(UUID ownerId, String name) {
        if (ownerId == null) return false;
        return mapper.countByOwnerAndNormalizedName(ownerId, name.trim().toLowerCase(Locale.ROOT)) > 0;
    }
    @Override public boolean existsByPath(Path path) { return mapper.countByPath(path.toAbsolutePath().normalize().toString()) > 0; }
    @Override public void delete(CodeRepositoryId id) { mapper.delete(id.value()); }

    public static CodeRepository toDomain(RepositoryRow row) {
        return new CodeRepository(
            CodeRepositoryId.of(row.id()), row.name(), Path.of(row.path()), RepositorySourceType.valueOf(row.sourceType()),
            row.defaultBranch(), row.currentCommit(), row.worktreeDigest(), row.worktreeDirty(),
            row.currentSnapshotId() == null ? null : RepositorySnapshotId.of(row.currentSnapshotId()),
            row.currentSnapshotPath() == null ? null : Path.of(row.currentSnapshotPath()), Path.of(row.codegraphPath()),
            row.snapshotCreatedAt(), row.lastScannedAt(), row.createdAt(), row.updatedAt()
        );
    }
}
