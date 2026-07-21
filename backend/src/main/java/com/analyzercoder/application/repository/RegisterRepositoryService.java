package com.analyzercoder.application.repository;

import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.GitRepositorySnapshot;
import com.analyzercoder.domain.repository.LocalGitInspector;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import com.analyzercoder.domain.repository.RepositorySnapshotStore;
import com.analyzercoder.infrastructure.repository.RepositoryPathPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class RegisterRepositoryService implements RegisterRepositoryUseCase {

    private final CodeRepositoryStore repositoryStore;
    private final CodeChunkStore codeChunkStore;
    private final IndexJobStore indexJobStore;
    private final RepositoryPathPolicy pathPolicy;
    private final LocalGitInspector gitInspector;
    private final RepositorySnapshotPort snapshotPort;
    private final RepositorySnapshotStore snapshotStore;

    public RegisterRepositoryService(
        CodeRepositoryStore repositoryStore,
        CodeChunkStore codeChunkStore,
        IndexJobStore indexJobStore,
        RepositoryPathPolicy pathPolicy,
        LocalGitInspector gitInspector,
        RepositorySnapshotPort snapshotPort,
        RepositorySnapshotStore snapshotStore
    ) {
        this.repositoryStore = repositoryStore;
        this.codeChunkStore = codeChunkStore;
        this.indexJobStore = indexJobStore;
        this.pathPolicy = pathPolicy;
        this.gitInspector = gitInspector;
        this.snapshotPort = snapshotPort;
        this.snapshotStore = snapshotStore;
    }

    @Override
    @Transactional
    public CodeRepository register(RegisterRepositoryCommand command) {
        String name = command.name().trim();
        Path repositoryPath = pathPolicy.validate(command.path());
        if (repositoryStore.existsByNormalizedName(name)) throw new IllegalStateException("Repository name already exists");
        if (repositoryStore.existsByPath(repositoryPath)) throw new IllegalStateException("Repository path is already registered");

        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        GitRepositorySnapshot sourceVersion = gitInspector.inspect(repositoryPath);
        ManagedRepositorySnapshot managed = snapshotPort.create(repositoryId, repositoryPath, sourceVersion);
        try {
            assertSourceUnchanged(sourceVersion, gitInspector.inspect(repositoryPath));
            CodeRepository repository = CodeRepository.createLocalGit(repositoryId, name, repositoryPath, sourceVersion, managed);
            repositoryStore.save(repository);
            snapshotStore.save(managed);
            return repository;
        } catch (RuntimeException exception) {
            snapshotPort.delete(managed);
            throw exception;
        }
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
    @Transactional
    public RepositoryScanResult rescan(CodeRepositoryId repositoryId) {
        CodeRepository repository = get(repositoryId);
        if (indexJobStore.hasActiveJob(repositoryId)) {
            throw new IllegalStateException("Repository cannot be rescanned while an index job is active");
        }
        GitRepositorySnapshot sourceVersion = gitInspector.inspect(repository.path());
        if (repository.hasSameVersion(sourceVersion)) {
            return new RepositoryScanResult(false, repositoryStore.save(repository.withScanMetadata(sourceVersion)));
        }

        ManagedRepositorySnapshot managed = snapshotPort.create(repositoryId, repository.path(), sourceVersion);
        try {
            assertSourceUnchanged(sourceVersion, gitInspector.inspect(repository.path()));
            CodeRepository updated = repository.withManagedSnapshot(sourceVersion, managed);
            repositoryStore.save(updated);
            snapshotStore.save(managed);
            return new RepositoryScanResult(true, updated);
        } catch (RuntimeException exception) {
            snapshotPort.delete(managed);
            throw exception;
        }
    }

    @Override
    @Transactional
    public void delete(CodeRepositoryId repositoryId) {
        get(repositoryId);
        if (indexJobStore.hasActiveJob(repositoryId)) {
            throw new IllegalStateException("Repository cannot be deleted while an index job is active");
        }
        codeChunkStore.deleteByRepositoryId(repositoryId);
        indexJobStore.deleteByRepositoryId(repositoryId);
        snapshotStore.deleteByRepositoryId(repositoryId);
        repositoryStore.delete(repositoryId);
        snapshotPort.deleteRepository(repositoryId);
    }

    private static void assertSourceUnchanged(GitRepositorySnapshot expected, GitRepositorySnapshot actual) {
        boolean same = Objects.equals(expected.branch(), actual.branch())
            && Objects.equals(expected.commit(), actual.commit())
            && Objects.equals(expected.worktreeDigest(), actual.worktreeDigest())
            && expected.dirty() == actual.dirty();
        if (!same) throw new IllegalStateException("Source changed while snapshotting; retry");
    }
}
