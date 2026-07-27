package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.GitRepositorySnapshot;
import com.analyzercoder.domain.repository.LocalGitInspector;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import com.analyzercoder.infrastructure.chunk.InMemoryCodeChunkStore;
import com.analyzercoder.infrastructure.indexing.InMemoryIndexJobStore;
import com.analyzercoder.infrastructure.repository.InMemoryCodeRepositoryStore;
import com.analyzercoder.infrastructure.repository.RepositoryPathPolicy;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisterRepositoryServiceTest {
    @TempDir Path root;

    @Test
    void registersLocalGitMetadataAndManagedSnapshot() {
        MutableInspector inspector = new MutableInspector(snapshot("main", "a".repeat(40), "1".repeat(64), false));
        RegisterRepositoryService service = service(inspector);
        var repository = service.register(new RegisterRepositoryCommand(" sample ", root.toString()));
        assertThat(repository.name()).isEqualTo("sample");
        assertThat(repository.defaultBranch()).isEqualTo("main");
        assertThat(repository.currentSnapshotId()).isNotNull();
        assertThat(repository.currentSnapshotPath()).isNotEqualTo(repository.path());
        assertThatThrownBy(() -> service.register(new RegisterRepositoryCommand("other", root.toString())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("路径");
    }

    @Test
    void rescanKeepsSnapshotWhenUnchangedAndPublishesNewSnapshotWhenChanged() {
        MutableInspector inspector = new MutableInspector(snapshot("main", "a".repeat(40), "1".repeat(64), false));
        RegisterRepositoryService service = service(inspector);
        var repository = service.register(new RegisterRepositoryCommand("sample", root.toString()));
        var originalSnapshotId = repository.currentSnapshotId();
        assertThat(service.rescan(repository.id()).repository().currentSnapshotId()).isEqualTo(originalSnapshotId);

        inspector.snapshot = snapshot("feature", "b".repeat(40), "2".repeat(64), true);
        var changed = service.rescan(repository.id());
        assertThat(changed.changed()).isTrue();
        assertThat(changed.repository().currentSnapshotId()).isNotEqualTo(originalSnapshotId);
        assertThat(changed.repository().worktreeDirty()).isTrue();
    }

    private RegisterRepositoryService service(LocalGitInspector inspector) {
        return new RegisterRepositoryService(
            new InMemoryCodeRepositoryStore(), new InMemoryCodeChunkStore(), new InMemoryIndexJobStore(),
            new RepositoryPathPolicy(root.toString()), inspector, new FakeSnapshotPort(root.resolve("managed"))
        );
    }

    private static GitRepositorySnapshot snapshot(String branch, String commit, String digest, boolean dirty) {
        return new GitRepositorySnapshot(branch, commit, digest, dirty, Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static final class MutableInspector implements LocalGitInspector {
        private GitRepositorySnapshot snapshot;
        private MutableInspector(GitRepositorySnapshot snapshot) { this.snapshot = snapshot; }
        @Override public GitRepositorySnapshot inspect(Path repositoryRoot) { return snapshot; }
    }

    private static final class FakeSnapshotPort implements RepositorySnapshotPort {
        private final Path managedRoot;
        private FakeSnapshotPort(Path managedRoot) { this.managedRoot = managedRoot; }
        @Override
        public ManagedRepositorySnapshot create(CodeRepositoryId repositoryId, Path sourceRoot, GitRepositorySnapshot sourceVersion) {
            RepositorySnapshotId id = RepositorySnapshotId.newId();
            return new ManagedRepositorySnapshot(
                id, repositoryId, managedRoot.resolve(id.value().toString()), sourceVersion.commit(),
                sourceVersion.worktreeDigest(), Instant.now()
            );
        }
        @Override public void delete(ManagedRepositorySnapshot snapshot) {}
        @Override public void deleteRepository(CodeRepositoryId repositoryId) {}
    }
}
