package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.indexing.InMemoryIndexJobStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeDriftJobProcessorTest {
    @Test
    void recordsCurrentSnapshotAndReadyResult() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        CodeRepository repository = repository(RepositorySnapshotId.newId());
        IndexJob queued =
                jobs.save(IndexJob.create(repository.id(), IndexJobType.KNOWLEDGE_DRIFT));
        CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
        KnowledgeDriftService drift = mock(KnowledgeDriftService.class);
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(drift.inspect(repository))
                .thenReturn(new KnowledgeDriftService.InspectionReport(3, 0, 3, 0, false));

        boolean processed =
                new KnowledgeDriftJobProcessor(jobs, repositories, drift, 5)
                        .processNextQueuedJob();

        IndexJob result = jobs.findById(queued.id()).orElseThrow();
        assertThat(processed).isTrue();
        assertThat(result.status()).isEqualTo(IndexJobStatus.SUCCEEDED);
        assertThat(result.currentStep())
                .isEqualTo(
                        "knowledge_drift_completed:"
                                + repository.currentSnapshotId().value()
                                + ":ready");
    }

    @Test
    void rejectsResultWhenSnapshotChangesDuringInspection() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        CodeRepository first = repository(RepositorySnapshotId.newId());
        CodeRepository changed = repository(first.id(), RepositorySnapshotId.newId());
        IndexJob queued = jobs.save(IndexJob.create(first.id(), IndexJobType.KNOWLEDGE_DRIFT));
        CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
        KnowledgeDriftService drift = mock(KnowledgeDriftService.class);
        when(repositories.findById(first.id()))
                .thenReturn(Optional.of(first), Optional.of(changed));
        when(drift.inspect(first))
                .thenReturn(new KnowledgeDriftService.InspectionReport(1, 0, 1, 0, false));

        boolean processed =
                new KnowledgeDriftJobProcessor(jobs, repositories, drift, 5)
                        .processNextQueuedJob();

        IndexJob result = jobs.findById(queued.id()).orElseThrow();
        assertThat(processed).isFalse();
        assertThat(result.status()).isEqualTo(IndexJobStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("KNOWLEDGE_DRIFT_FAILED");
        assertThat(result.errorMessage()).contains("Snapshot 已切换");
    }

    @Test
    void recoveryMarksAbandonedKnowledgeCheckTimedOut() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        IndexJob running =
                IndexJob.create(CodeRepositoryId.newId(), IndexJobType.KNOWLEDGE_DRIFT)
                        .start("check_knowledge_drift")
                        .withTimeout(Instant.now().minusSeconds(1));
        jobs.save(running);

        int expired =
                new KnowledgeDriftJobProcessor(
                                jobs,
                                mock(CodeRepositoryStore.class),
                                mock(KnowledgeDriftService.class),
                                5)
                        .expireTimedOutJobs();

        IndexJob result = jobs.findById(running.id()).orElseThrow();
        assertThat(expired).isEqualTo(1);
        assertThat(result.status()).isEqualTo(IndexJobStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("KNOWLEDGE_DRIFT_TIMEOUT");
    }

    private static CodeRepository repository(RepositorySnapshotId snapshotId) {
        return repository(CodeRepositoryId.newId(), snapshotId);
    }

    private static CodeRepository repository(
            CodeRepositoryId repositoryId, RepositorySnapshotId snapshotId) {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        Path path = Path.of("repository").toAbsolutePath().normalize();
        return new CodeRepository(
                repositoryId,
                "sample",
                path,
                RepositorySourceType.LOCAL_GIT,
                "main",
                "abc",
                "digest",
                false,
                snapshotId,
                path,
                path.resolve(".codegraph"),
                now,
                now,
                now,
                now);
    }
}
