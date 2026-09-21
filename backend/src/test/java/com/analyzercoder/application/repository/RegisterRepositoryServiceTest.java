package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.domain.repository.GitRepositoryContentVersion;
import com.analyzercoder.domain.repository.LocalGitInspector;
import com.analyzercoder.infrastructure.chunk.InMemoryCodeChunkStore;
import com.analyzercoder.infrastructure.indexing.InMemoryIndexJobStore;
import com.analyzercoder.infrastructure.repository.InMemoryCodeRepositoryStore;
import com.analyzercoder.infrastructure.repository.RepositoryPathPolicy;
import com.analyzercoder.infrastructure.repository.GitBranchContentVersionFactory;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisterRepositoryServiceTest {
    @TempDir Path root;

    @Test
    void registersLocalGitMetadataWithoutPublishingCode() {
        MutableInspector inspector =
                new MutableInspector(contentVersion("main", "a".repeat(40), "1".repeat(64), false));
        RegisterRepositoryService service = service(inspector);
        var repository =
                service.register(new RegisterRepositoryCommand(" sample ", root.toString()));
        assertThat(repository.name()).isEqualTo("sample");
        assertThat(repository.defaultBranch()).isEqualTo("main");
        assertThat(repository.currentContentVersion()).isNull();
        assertThat(repository.currentContentVersionPath()).isNull();
        assertThatThrownBy(
                        () ->
                                service.register(
                                        new RegisterRepositoryCommand("other", root.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("路径");
    }

    @Test
    void rescanOnlyRefreshesSourceMetadataAndLeavesPublishingToTheBranch() {
        MutableInspector inspector =
                new MutableInspector(contentVersion("main", "a".repeat(40), "1".repeat(64), false));
        RegisterRepositoryService service = service(inspector);
        var repository = service.register(new RegisterRepositoryCommand("sample", root.toString()));
        var originalContentVersion = repository.currentContentVersion();
        assertThat(service.rescan(repository.id()).repository().currentContentVersion())
                .isEqualTo(originalContentVersion);

        inspector.contentVersion = contentVersion("feature", "b".repeat(40), "2".repeat(64), true);
        var changed = service.rescan(repository.id());
        assertThat(changed.changed()).isTrue();
        assertThat(changed.repository().currentContentVersion()).isEqualTo(originalContentVersion);
        assertThat(changed.repository().worktreeDirty()).isTrue();
    }

    private RegisterRepositoryService service(LocalGitInspector inspector) {
        return new RegisterRepositoryService(
                new InMemoryCodeRepositoryStore(),
                new InMemoryCodeChunkStore(),
                new InMemoryIndexJobStore(),
                new RepositoryPathPolicy(root.toString()),
                inspector,
                new GitBranchContentVersionFactory(root.resolve("managed").toString(), 1000, 10_000_000));
    }

    private static GitRepositoryContentVersion contentVersion(
            String branch, String commit, String digest, boolean dirty) {
        return new GitRepositoryContentVersion(
                branch, commit, digest, dirty, Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static final class MutableInspector implements LocalGitInspector {
        private GitRepositoryContentVersion contentVersion;

        private MutableInspector(GitRepositoryContentVersion contentVersion) {
            this.contentVersion = contentVersion;
        }

        @Override
        public GitRepositoryContentVersion inspect(Path repositoryRoot) {
            return contentVersion;
        }
    }

}
