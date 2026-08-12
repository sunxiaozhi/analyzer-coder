package com.analyzercoder.application.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.infrastructure.indexing.InMemoryIndexJobStore;
import com.analyzercoder.infrastructure.repository.InMemoryCodeRepositoryStore;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IndexJobServiceTest {

    private InMemoryCodeRepositoryStore repositoryStore;
    private InMemoryIndexJobStore jobStore;
    private IndexJobService service;
    private CodeRepository repository;

    @BeforeEach
    void setUp() {
        repositoryStore = new InMemoryCodeRepositoryStore();
        jobStore = new InMemoryIndexJobStore();
        service = new IndexJobService(repositoryStore, jobStore);
        repository = repositoryStore.save(CodeRepository.create("sample", Path.of(".")));
    }

    @Test
    void listsStartedJobs() {
        IndexJob job = service.start(new StartIndexCommand(repository.id(), IndexJobType.FULL));

        assertThat(service.list(null)).containsExactly(job);
        assertThat(service.list(repository.id())).containsExactly(job);
    }

    @Test
    void duplicateStartReturnsExistingActiveJob() {
        IndexJob first = service.start(new StartIndexCommand(repository.id(), IndexJobType.FULL));
        IndexJob duplicate =
                service.start(new StartIndexCommand(repository.id(), IndexJobType.FULL));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(service.list(repository.id())).hasSize(1);
    }

    @Test
    void cancelsQueuedJobImmediately() {
        IndexJob queued = service.start(new StartIndexCommand(repository.id(), IndexJobType.FULL));

        assertThat(service.cancel(queued.id()).status()).isEqualTo(IndexJobStatus.CANCELED);
    }

    @Test
    void retryCreatesNewJobWithoutChangingFailedHistory() {
        IndexJob failed =
                jobStore.save(
                        IndexJob.create(repository.id(), IndexJobType.FULL)
                                .start("scan_repository")
                                .fail("failed", "test"));

        IndexJob retry = service.retry(failed.id());

        assertThat(retry.id()).isNotEqualTo(failed.id());
        assertThat(retry.status()).isEqualTo(IndexJobStatus.QUEUED);
        assertThat(service.get(failed.id()).status()).isEqualTo(IndexJobStatus.FAILED);
    }
}
