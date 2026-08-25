package com.analyzercoder.application.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

        verify(gitDiff, never()).changedPaths(any(), any());
        verify(chunks).replaceRepositoryChunks(eq(repository.id()), any());
        verify(chunks, never())
                .replaceRepositoryPaths(any(), any(), any(), any(), any());
        verify(markdownSources).synchronize(repository, List.of(markdown), false, Set.of());
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
}
