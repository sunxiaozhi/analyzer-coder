package com.analyzercoder.application.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.application.intelligence.CodeGraphTaskService;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.indexing.ScannedRepositoryFile;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IndexJobProcessorTest {

    @Test
    void dirtyWorktreeForcesFullScanForRequestedIncrementalJob() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
        RepositoryScannerPort scanner = mock(RepositoryScannerPort.class);
        CodeChunkStore chunks = mock(CodeChunkStore.class);
        MarkdownKnowledgeSourceService markdownSources = mock(MarkdownKnowledgeSourceService.class);
        GitDiffService gitDiff = mock(GitDiffService.class);

        CodeRepository repository = dirtyRepository();
        IndexJob running = IndexJob.create(repository.id(), IndexJobType.INCREMENTAL).start("scan");
        ScannedRepositoryFile markdown =
                new ScannedRepositoryFile(
                        "README.md",
                        "markdown",
                        RepositoryAssetType.DOCUMENT,
                        "# Project\n\nUseful knowledge.",
                        3);

        when(jobs.claimNextQueued()).thenReturn(Optional.of(running));
        when(jobs.findById(running.id())).thenReturn(Optional.of(running));
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(chunks.latestIndexedCommit(repository.id())).thenReturn("previous-commit");
        when(scanner.scan(repository)).thenReturn(List.of(markdown));

        IndexJobProcessor processor =
                new IndexJobProcessor(
                        jobs,
                        repositories,
                        scanner,
                        chunks,
                        null,
                        markdownSources,
                        gitDiff);

        processor.processNextQueuedJob();

        verify(gitDiff, never()).diff(any(), any());
        verify(chunks).replaceRepositoryChunks(eq(repository.id()), any());
        verify(chunks, never())
                .replaceRepositoryPaths(any(), any(), any(), any(), any());
        verify(markdownSources).synchronize(repository, List.of(markdown), false, Set.of());
        assertExecutionPlan(jobs, "FULL", "DIRTY_WORKTREE");
    }

    @Test
    void missingBaselineFallsBackToFull() {
        Fixture fixture = fixture(cleanRepository(), null, List.of(file("README.md")));

        fixture.processor().processNextQueuedJob();

        verify(fixture.gitDiff(), never()).diff(any(), any());
        verify(fixture.chunks()).replaceRepositoryChunks(eq(fixture.repository().id()), any());
        assertExecutionPlan(fixture.jobs(), "FULL", "BASELINE_MISSING");
    }

    @Test
    void gitDiffFailureFallsBackToFull() {
        Fixture fixture = fixture(cleanRepository(), "previous-commit", List.of(file("README.md")));
        when(fixture.gitDiff().diff(fixture.repository(), "previous-commit"))
                .thenThrow(new IllegalStateException("bad revision"));

        fixture.processor().processNextQueuedJob();

        verify(fixture.chunks()).replaceRepositoryChunks(eq(fixture.repository().id()), any());
        assertExecutionPlan(fixture.jobs(), "FULL", "GIT_DIFF_FAILED");
    }

    @Test
    void highChangeRatioFallsBackToFull() {
        Fixture fixture =
                fixture(
                        cleanRepository(),
                        "previous-commit",
                        List.of(file("one.java"), file("two.java")));
        when(fixture.gitDiff().diff(fixture.repository(), "previous-commit"))
                .thenReturn(
                        new GitDiffService.DiffResult(
                                List.of(
                                        new GitDiffService.FileChange(
                                                GitDiffService.ChangeType.MODIFIED,
                                                "one.java",
                                                "one.java"))));

        fixture.processor().processNextQueuedJob();

        verify(fixture.chunks()).replaceRepositoryChunks(eq(fixture.repository().id()), any());
        assertExecutionPlan(fixture.jobs(), "FULL", "CHANGE_RATIO_EXCEEDED");
    }

    @Test
    void renameAndDeleteRemoveOldPathsAndOnlyIndexCurrentPath() {
        List<ScannedRepositoryFile> files = new java.util.ArrayList<>();
        files.add(file("new-name.java"));
        for (int index = 0; index < 9; index++) files.add(file("unchanged-" + index + ".java"));
        Fixture fixture = fixture(cleanRepository(), "previous-commit", files);
        when(fixture.gitDiff().diff(fixture.repository(), "previous-commit"))
                .thenReturn(
                        new GitDiffService.DiffResult(
                                List.of(
                                        new GitDiffService.FileChange(
                                                GitDiffService.ChangeType.RENAMED,
                                                "old-name.java",
                                                "new-name.java"),
                                        new GitDiffService.FileChange(
                                                GitDiffService.ChangeType.DELETED,
                                                "removed.java",
                                                null))));

        fixture.processor().processNextQueuedJob();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.analyzercoder.domain.chunk.CodeChunk>> chunkCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.chunks())
                .replaceRepositoryPaths(
                        eq(fixture.repository().id()),
                        eq(Set.of("old-name.java", "new-name.java", "removed.java")),
                        chunkCaptor.capture(),
                        eq(fixture.repository().currentSnapshotId()),
                        eq(fixture.repository().currentCommit()));
        assertThat(chunkCaptor.getValue()).extracting(chunk -> chunk.filePath()).containsOnly("new-name.java");
        assertExecutionPlan(fixture.jobs(), "INCREMENTAL", null);
    }

    @Test
    void queuesCodeGraphAfterChunksAndVectorsAreReady() {
        CodeRepository repository = cleanRepository();
        IndexJobStore jobs = mock(IndexJobStore.class);
        CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
        RepositoryScannerPort scanner = mock(RepositoryScannerPort.class);
        CodeChunkStore chunks = mock(CodeChunkStore.class);
        CodeGraphArtifactMapper graphArtifacts = mock(CodeGraphArtifactMapper.class);
        CodeGraphTaskService graphTasks = mock(CodeGraphTaskService.class);
        IndexJob running = IndexJob.create(repository.id(), IndexJobType.FULL).start("scan");
        when(jobs.claimNextQueued()).thenReturn(Optional.of(running));
        when(jobs.findById(running.id())).thenReturn(Optional.of(running));
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(scanner.scan(repository)).thenReturn(List.of(file("Sample.java")));
        IndexJobProcessor processor =
                new IndexJobProcessor(
                        jobs,
                        repositories,
                        scanner,
                        chunks,
                        null,
                        null,
                        mock(GitDiffService.class),
                        new com.analyzercoder.application.code.CodeSymbolExtractor(),
                        graphArtifacts,
                        graphTasks);

        processor.processNextQueuedJob();

        var order = org.mockito.Mockito.inOrder(chunks, graphTasks);
        order.verify(chunks).replaceRepositoryChunks(eq(repository.id()), any());
        order.verify(graphTasks).start(repository.id());
        verify(graphArtifacts)
                .findPublished(
                        repository.id().value(), repository.currentSnapshotId().value());
    }

    private static Fixture fixture(
            CodeRepository repository,
            String baseline,
            List<ScannedRepositoryFile> files) {
        IndexJobStore jobs = mock(IndexJobStore.class);
        CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
        RepositoryScannerPort scanner = mock(RepositoryScannerPort.class);
        CodeChunkStore chunks = mock(CodeChunkStore.class);
        GitDiffService gitDiff = mock(GitDiffService.class);
        IndexJob running = IndexJob.create(repository.id(), IndexJobType.INCREMENTAL).start("scan");
        when(jobs.claimNextQueued()).thenReturn(Optional.of(running));
        when(jobs.findById(running.id())).thenReturn(Optional.of(running));
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(chunks.latestIndexedCommit(repository.id())).thenReturn(baseline);
        when(scanner.scan(repository)).thenReturn(files);
        return new Fixture(
                jobs,
                chunks,
                gitDiff,
                repository,
                new IndexJobProcessor(jobs, repositories, scanner, chunks, null, null, gitDiff));
    }

    private static void assertExecutionPlan(
            IndexJobStore jobs, String expectedMode, String expectedReason) {
        ArgumentCaptor<IndexJob> captor = ArgumentCaptor.forClass(IndexJob.class);
        verify(jobs, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(
                        job -> {
                            assertThat(job.executionMode()).isEqualTo(expectedMode);
                            assertThat(job.fallbackReason()).isEqualTo(expectedReason);
                        });
    }

    private static ScannedRepositoryFile file(String path) {
        return new ScannedRepositoryFile(
                path,
                path.endsWith(".md") ? "markdown" : "java",
                path.endsWith(".md") ? RepositoryAssetType.DOCUMENT : RepositoryAssetType.CODE,
                path.endsWith(".md") ? "# Project" : "class Sample {}",
                1);
    }

    private static CodeRepository dirtyRepository() {
        Instant now = Instant.parse("2026-08-23T10:00:00Z");
        Path path = Path.of("repository").toAbsolutePath().normalize();
        return new CodeRepository(
                CodeRepositoryId.of(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                "repository",
                path,
                RepositorySourceType.LOCAL_GIT,
                "main",
                "current-commit",
                "worktree-digest",
                true,
                RepositorySnapshotId.of(
                        UUID.fromString("20000000-0000-0000-0000-000000000002")),
                path,
                path.resolve(".codegraph"),
                now,
                now,
                now,
                now);
    }

    private static CodeRepository cleanRepository() {
        CodeRepository dirty = dirtyRepository();
        return new CodeRepository(
                dirty.id(),
                dirty.name(),
                dirty.path(),
                dirty.sourceType(),
                dirty.defaultBranch(),
                dirty.currentCommit(),
                dirty.worktreeDigest(),
                false,
                dirty.currentSnapshotId(),
                dirty.currentSnapshotPath(),
                dirty.codeGraphPath(),
                dirty.snapshotCreatedAt(),
                dirty.lastScannedAt(),
                dirty.createdAt(),
                dirty.updatedAt());
    }

    private record Fixture(
            IndexJobStore jobs,
            CodeChunkStore chunks,
            GitDiffService gitDiff,
            CodeRepository repository,
            IndexJobProcessor processor) {}
}
