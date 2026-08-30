package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.application.indexing.StartIndexCommand;
import com.analyzercoder.application.indexing.VectorIndexQueryService;
import com.analyzercoder.application.intelligence.CodeGraphTaskService;
import com.analyzercoder.application.knowledge.KnowledgeDriftTaskService;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RepositoryPreparationServiceTest {

    @Test
    void startsFullIndexWhenPublishedSnapshotHasNoChunks() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        RepositoryRemoteSyncService remoteSync = mock(RepositoryRemoteSyncService.class);
        RepositoryCodeBrowserService browser = mock(RepositoryCodeBrowserService.class);
        VectorIndexQueryService vectors = mock(VectorIndexQueryService.class);
        CodeGraphArtifactMapper graphArtifacts = mock(CodeGraphArtifactMapper.class);
        IndexJobUseCase indexJobs = mock(IndexJobUseCase.class);
        IndexJobStore jobStore = mock(IndexJobStore.class);
        CodeGraphTaskService graphTasks = mock(CodeGraphTaskService.class);
        KnowledgeDriftTaskService driftTasks = mock(KnowledgeDriftTaskService.class);
        RepositoryPreparationService service =
                new RepositoryPreparationService(
                        repositories,
                        remoteSync,
                        browser,
                        vectors,
                        graphArtifacts,
                        indexJobs,
                        jobStore,
                        graphTasks,
                        driftTasks);

        CodeRepository repository = repository();
        VectorIndexQueryService.Summary empty =
                new VectorIndexQueryService.Summary(
                        repository.id().value(),
                        repository.currentSnapshotId().value(),
                        repository.currentCommit(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        "local-hash-64",
                        64,
                        "CHARACTER_HASH",
                        "字符相似度",
                        null);
        IndexJob queued = IndexJob.create(repository.id(), IndexJobType.FULL);
        when(repositories.get(repository.id())).thenReturn(repository);
        when(repositories.rescan(repository.id()))
                .thenReturn(new RepositoryScanResult(false, repository));
        when(jobStore.findByRepositoryId(repository.id())).thenReturn(List.of());
        when(vectors.summary(repository.id().value())).thenReturn(empty);
        when(indexJobs.start(any())).thenReturn(queued);
        when(browser.list(repository.id()))
                .thenReturn(
                        new RepositoryCodeBrowserService.SnapshotFiles(
                                repository.currentSnapshotId().value().toString(),
                                "main",
                                repository.currentCommit(),
                                List.of()));

        RepositoryPreparationService.PreparationView result =
                service.prepare(mock(AuthenticatedAccount.class), repository.id());

        ArgumentCaptor<StartIndexCommand> command =
                ArgumentCaptor.forClass(StartIndexCommand.class);
        verify(indexJobs).start(command.capture());
        assertThat(command.getValue().type()).isEqualTo(IndexJobType.FULL);
        assertThat(result.state()).isEqualTo("PROCESSING");
        assertThat(result.activeJobId()).isEqualTo(queued.id().value());
        assertThat(result.stages())
                .extracting(RepositoryPreparationService.PreparationStage::state)
                .containsExactly("READY", "RUNNING", "RUNNING", "PENDING", "PENDING");
    }

    @Test
    void startsIncrementalRepairWhenPublishedChunksAreMissingVectors() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        RepositoryRemoteSyncService remoteSync = mock(RepositoryRemoteSyncService.class);
        RepositoryCodeBrowserService browser = mock(RepositoryCodeBrowserService.class);
        VectorIndexQueryService vectors = mock(VectorIndexQueryService.class);
        CodeGraphArtifactMapper graphArtifacts = mock(CodeGraphArtifactMapper.class);
        IndexJobUseCase indexJobs = mock(IndexJobUseCase.class);
        IndexJobStore jobStore = mock(IndexJobStore.class);
        RepositoryPreparationService service =
                new RepositoryPreparationService(
                        repositories,
                        remoteSync,
                        browser,
                        vectors,
                        graphArtifacts,
                        indexJobs,
                        jobStore,
                        mock(CodeGraphTaskService.class),
                        mock(KnowledgeDriftTaskService.class));
        CodeRepository repository = repository();
        VectorIndexQueryService.Summary incomplete = summary(repository, 8, 6, 2);
        IndexJob queued = IndexJob.create(repository.id(), IndexJobType.INCREMENTAL);
        when(repositories.get(repository.id())).thenReturn(repository);
        when(repositories.rescan(repository.id()))
                .thenReturn(new RepositoryScanResult(false, repository));
        when(jobStore.findByRepositoryId(repository.id())).thenReturn(List.of());
        when(vectors.summary(repository.id().value())).thenReturn(incomplete);
        when(indexJobs.start(any())).thenReturn(queued);
        when(browser.list(repository.id()))
                .thenReturn(
                        new RepositoryCodeBrowserService.SnapshotFiles(
                                repository.currentSnapshotId().value().toString(),
                                "main",
                                repository.currentCommit(),
                                List.of()));

        RepositoryPreparationService.PreparationView result =
                service.prepare(mock(AuthenticatedAccount.class), repository.id());

        ArgumentCaptor<StartIndexCommand> command =
                ArgumentCaptor.forClass(StartIndexCommand.class);
        verify(indexJobs).start(command.capture());
        assertThat(command.getValue().type()).isEqualTo(IndexJobType.INCREMENTAL);
        assertThat(result.activeJobType()).isEqualTo("INCREMENTAL");
    }

    @Test
    void buildsDeterministicProfileFromPublishedFiles() {
        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        VectorIndexQueryService.Summary summary =
                new VectorIndexQueryService.Summary(
                        repositoryId,
                        snapshotId,
                        "abc",
                        12,
                        10,
                        2,
                        3,
                        3,
                        "local-hash-64",
                        64,
                        "CHARACTER_HASH",
                        "字符相似度",
                        Instant.now());
        List<RepositoryCodeBrowserService.FileEntry> files =
                List.of(
                        new RepositoryCodeBrowserService.FileEntry(
                                "src/Main.java", "Main.java", "java", 100),
                        new RepositoryCodeBrowserService.FileEntry(
                                "src/App.vue", "App.vue", "vue", 200),
                        new RepositoryCodeBrowserService.FileEntry(
                                "backend/server.py", "server.py", "python", 300),
                        new RepositoryCodeBrowserService.FileEntry(
                                "package.json", "package.json", "json", 50),
                        new RepositoryCodeBrowserService.FileEntry(
                                "README.md", "README.md", "markdown", 25));

        RepositoryPreparationService.ProjectProfile profile =
                RepositoryPreparationService.profile(files, summary, null);

        assertThat(profile.fileCount()).isEqualTo(5);
        assertThat(profile.totalBytes()).isEqualTo(675);
        assertThat(profile.modules())
                .extracting(RepositoryPreparationService.ProfileCount::name)
                .containsExactly("src", "backend");
        assertThat(profile.entryPoints())
                .containsExactly(
                        "package.json", "backend/server.py", "src/App.vue", "src/Main.java");
        assertThat(profile.assets())
                .extracting(RepositoryPreparationService.ProfileCount::name)
                .contains("CODE", "DOCUMENT", "CONFIG");
        assertThat(profile.keyAssets())
                .extracting(RepositoryPreparationService.KeyAsset::path)
                .containsExactly("README.md");
    }

    private static CodeRepository repository() {
        Instant now = Instant.now();
        return new CodeRepository(
                CodeRepositoryId.newId(),
                "sample",
                Path.of("sample"),
                RepositorySourceType.LOCAL_GIT,
                "main",
                "abc",
                "digest",
                false,
                RepositorySnapshotId.newId(),
                Path.of("snapshot"),
                Path.of("snapshot/.codegraph"),
                now,
                now,
                now,
                now);
    }

    @Test
    void reportsReadyOnlyAfterCurrentSnapshotKnowledgeDriftCompleted() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        RepositoryCodeBrowserService browser = mock(RepositoryCodeBrowserService.class);
        VectorIndexQueryService vectors = mock(VectorIndexQueryService.class);
        CodeGraphArtifactMapper graphArtifacts = mock(CodeGraphArtifactMapper.class);
        IndexJobStore jobStore = mock(IndexJobStore.class);
        CodeRepository repository = repository();
        IndexJob drift =
                IndexJob.create(repository.id(), IndexJobType.KNOWLEDGE_DRIFT)
                        .start("check_knowledge_drift")
                        .succeed(
                                "knowledge_drift_completed:"
                                        + repository.currentSnapshotId().value()
                                        + ":ready");
        when(repositories.get(repository.id())).thenReturn(repository);
        when(vectors.summary(repository.id().value())).thenReturn(summary(repository, 8, 8, 0));
        when(graphArtifacts.findPublished(
                        repository.id().value(), repository.currentSnapshotId().value()))
                .thenReturn(
                        new CodeGraphArtifactRow(
                                UUID.randomUUID(),
                                repository.id().value(),
                                repository.currentSnapshotId().value(),
                                "test",
                                "PUBLISHED",
                                "artifact",
                                12,
                                18));
        when(jobStore.findByRepositoryId(repository.id())).thenReturn(List.of(drift));
        when(jobStore.findLatestByRepositoryId(repository.id())).thenReturn(java.util.Optional.of(drift));
        when(browser.list(repository.id()))
                .thenReturn(
                        new RepositoryCodeBrowserService.SnapshotFiles(
                                repository.currentSnapshotId().value().toString(),
                                "main",
                                repository.currentCommit(),
                                List.of()));
        RepositoryPreparationService service =
                new RepositoryPreparationService(
                        repositories,
                        mock(RepositoryRemoteSyncService.class),
                        browser,
                        vectors,
                        graphArtifacts,
                        mock(IndexJobUseCase.class),
                        jobStore,
                        mock(CodeGraphTaskService.class),
                        mock(KnowledgeDriftTaskService.class));

        RepositoryPreparationService.PreparationView result = service.view(repository.id());

        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.progress()).isEqualTo(100);
        assertThat(result.stages())
                .extracting(RepositoryPreparationService.PreparationStage::state)
                .containsExactly("READY", "READY", "READY", "READY", "READY");
    }

    private static VectorIndexQueryService.Summary summary(
            CodeRepository repository, long chunks, long vectorized, long missing) {
        return new VectorIndexQueryService.Summary(
                repository.id().value(),
                repository.currentSnapshotId().value(),
                repository.currentCommit(),
                chunks,
                vectorized,
                missing,
                0,
                0,
                "local-hash-64",
                64,
                "CHARACTER_HASH",
                "字符相似度",
                Instant.now());
    }
}
