package com.analyzercoder.application.repository;

import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.*;
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

    public RegisterRepositoryService(CodeRepositoryStore repositoryStore, CodeChunkStore codeChunkStore,
        IndexJobStore indexJobStore, RepositoryPathPolicy pathPolicy, LocalGitInspector gitInspector,
        RepositorySnapshotPort snapshotPort, RepositorySnapshotStore snapshotStore) {
        this.repositoryStore=repositoryStore; this.codeChunkStore=codeChunkStore; this.indexJobStore=indexJobStore;
        this.pathPolicy=pathPolicy; this.gitInspector=gitInspector; this.snapshotPort=snapshotPort; this.snapshotStore=snapshotStore;
    }

    @Override @Transactional
    public CodeRepository register(RegisterRepositoryCommand command) {
        return register(command, pathPolicy.validate(command.path()));
    }

    @Override @Transactional
    public CodeRepository registerManaged(RegisterRepositoryCommand command) {
        return register(command, pathPolicy.validateManaged(command.path()));
    }

    private CodeRepository register(RegisterRepositoryCommand command, Path source) {
        String name=command.name().trim();
        if(repositoryStore.existsByNormalizedName(command.ownerAccountId(),name)) throw new IllegalStateException("Repository name already exists for this owner");
        if(repositoryStore.existsByPath(source)) throw new IllegalStateException("Repository path is already registered");
        CodeRepositoryId id=CodeRepositoryId.newId(); GitRepositorySnapshot version=gitInspector.inspect(source);
        ManagedRepositorySnapshot snapshot=snapshotPort.create(id,source,version);
        try {
            assertSourceUnchanged(version,gitInspector.inspect(source));
            CodeRepository repository=CodeRepository.createLocalGit(id,name,source,version,snapshot);
            repositoryStore.saveOwned(repository,command.ownerAccountId());
            snapshotStore.save(snapshot); return repository;
        } catch(RuntimeException exception){snapshotPort.delete(snapshot);throw exception;}
    }
    @Override public CodeRepository get(CodeRepositoryId id){return repositoryStore.findById(id).orElseThrow(()->new IllegalArgumentException("Repository not found: "+id.value()));}
    @Override public List<CodeRepository> list(){return repositoryStore.findAll();}
    @Override @Transactional public RepositoryScanResult rescan(CodeRepositoryId id){
        CodeRepository repository=get(id); if(indexJobStore.hasActiveJob(id))throw new IllegalStateException("Repository cannot be rescanned while an index job is active");
        GitRepositorySnapshot version=gitInspector.inspect(repository.path());
        if(repository.hasSameVersion(version))return new RepositoryScanResult(false,repositoryStore.save(repository.withScanMetadata(version)));
        ManagedRepositorySnapshot snapshot=snapshotPort.create(id,repository.path(),version);
        try{assertSourceUnchanged(version,gitInspector.inspect(repository.path()));CodeRepository updated=repository.withManagedSnapshot(version,snapshot);repositoryStore.save(updated);snapshotStore.save(snapshot);return new RepositoryScanResult(true,updated);}catch(RuntimeException exception){snapshotPort.delete(snapshot);throw exception;}
    }
    @Override @Transactional public void delete(CodeRepositoryId id){get(id);if(indexJobStore.hasActiveJob(id))throw new IllegalStateException("Repository cannot be deleted while an index job is active");codeChunkStore.deleteByRepositoryId(id);indexJobStore.deleteByRepositoryId(id);snapshotStore.deleteByRepositoryId(id);repositoryStore.delete(id);snapshotPort.deleteRepository(id);}
    private static void assertSourceUnchanged(GitRepositorySnapshot expected,GitRepositorySnapshot actual){boolean same=Objects.equals(expected.branch(),actual.branch())&&Objects.equals(expected.commit(),actual.commit())&&Objects.equals(expected.worktreeDigest(),actual.worktreeDigest())&&expected.dirty()==actual.dirty();if(!same)throw new IllegalStateException("Source changed while snapshotting; retry");}
}