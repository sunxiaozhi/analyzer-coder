package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.knowledge.KnowledgeDriftTaskService;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.indexing.InMemoryIndexJobStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeGraphJobProcessorTest {
    @Test
    void workerClaimsHeartbeatsAndCompletesQueuedGraphJob() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        IndexJob queued = jobs.save(IndexJob.create(repositoryId, IndexJobType.CODEGRAPH));
        CodeGraphService codeGraph = mock(CodeGraphService.class);
        KnowledgeDriftTaskService driftTasks = mock(KnowledgeDriftTaskService.class);
        when(codeGraph.build(eq(repositoryId.value()), any()))
                .thenAnswer(
                        invocation -> {
                            CodeGraphService.BuildControl control = invocation.getArgument(1);
                            control.checkpoint("building_codegraph");
                            control.checkpoint("publish_codegraph");
                            return artifact(repositoryId.value());
                        });

        boolean processed =
                new CodeGraphJobProcessor(jobs, codeGraph, driftTasks, 12)
                        .processNextQueuedJob();
        IndexJob result = jobs.findById(queued.id()).orElseThrow();

        assertThat(processed).isTrue();
        assertThat(result.status()).isEqualTo(IndexJobStatus.SUCCEEDED);
        assertThat(result.currentStep()).startsWith("codegraph_published:");
        assertThat(result.heartbeatAt()).isNotNull();
        assertThat(result.timeoutAt()).isAfter(result.startedAt());
        verify(driftTasks).start(repositoryId);
    }

    @Test
    void cancelRequestStopsBuildAtNextCheckpoint() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        IndexJob queued = jobs.save(IndexJob.create(repositoryId, IndexJobType.CODEGRAPH));
        CodeGraphService codeGraph = mock(CodeGraphService.class);
        when(codeGraph.build(eq(repositoryId.value()), any()))
                .thenAnswer(
                        invocation -> {
                            IndexJob running = jobs.findById(queued.id()).orElseThrow();
                            jobs.save(running.requestCancel());
                            CodeGraphService.BuildControl control = invocation.getArgument(1);
                            control.checkpoint("building_codegraph");
                            return artifact(repositoryId.value());
                        });

        boolean processed = new CodeGraphJobProcessor(jobs, codeGraph, 12).processNextQueuedJob();

        assertThat(processed).isTrue();
        assertThat(jobs.findById(queued.id()).orElseThrow().status())
                .isEqualTo(IndexJobStatus.CANCELED);
    }

    @Test
    void buildFailureKeepsStructuredFailureReason() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        IndexJob queued = jobs.save(IndexJob.create(repositoryId, IndexJobType.CODEGRAPH));
        CodeGraphService codeGraph = mock(CodeGraphService.class);
        when(codeGraph.build(eq(repositoryId.value()), any()))
                .thenThrow(new IllegalStateException("CLI exited with code 2"));

        boolean processed = new CodeGraphJobProcessor(jobs, codeGraph, 12).processNextQueuedJob();
        IndexJob result = jobs.findById(queued.id()).orElseThrow();

        assertThat(processed).isFalse();
        assertThat(result.status()).isEqualTo(IndexJobStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("CODEGRAPH_BUILD_FAILED");
        assertThat(result.errorMessage()).contains("code 2");
    }

    @Test
    void recoveryMarksAbandonedGraphTaskTimedOut() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        IndexJob running =
                IndexJob.create(CodeRepositoryId.newId(), IndexJobType.CODEGRAPH)
                        .start("building_codegraph")
                        .withTimeout(Instant.now().minusSeconds(1));
        jobs.save(running);

        int expired = new CodeGraphJobProcessor(jobs, mock(CodeGraphService.class), 12)
                .expireTimedOutJobs();
        IndexJob result = jobs.findById(running.id()).orElseThrow();

        assertThat(expired).isEqualTo(1);
        assertThat(result.status()).isEqualTo(IndexJobStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("CODEGRAPH_TIMEOUT");
    }

    private static CodeGraphService.Artifact artifact(UUID repositoryId) {
        return new CodeGraphService.Artifact(
                UUID.randomUUID(),
                repositoryId,
                UUID.randomUUID(),
                "test",
                "PUBLISHED",
                "artifact",
                1,
                1);
    }
}
