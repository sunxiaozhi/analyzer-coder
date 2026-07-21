package com.analyzercoder.domain.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import org.junit.jupiter.api.Test;

class IndexJobTest {

    @Test
    void queuedCancellationIsImmediate() {
        IndexJob canceled = IndexJob.create(CodeRepositoryId.newId(), IndexJobType.FULL).requestCancel();

        assertThat(canceled.status()).isEqualTo(IndexJobStatus.CANCELED);
        assertThat(canceled.finishedAt()).isNotNull();
    }

    @Test
    void runningCancellationUsesRequestedStateBeforeSafeStop() {
        IndexJob running = IndexJob.create(CodeRepositoryId.newId(), IndexJobType.FULL).start("scan_repository");

        IndexJob requested = running.requestCancel();
        assertThat(requested.status()).isEqualTo(IndexJobStatus.CANCEL_REQUESTED);
        assertThat(requested.cancel().status()).isEqualTo(IndexJobStatus.CANCELED);
    }

    @Test
    void retryCreatesNewQueuedJobAndKeepsOriginalTerminal() {
        IndexJob failed = IndexJob.create(CodeRepositoryId.newId(), IndexJobType.FULL)
            .start("scan_repository")
            .fail("failed", "test");

        IndexJob retry = IndexJob.retry(failed);

        assertThat(retry.id()).isNotEqualTo(failed.id());
        assertThat(retry.status()).isEqualTo(IndexJobStatus.QUEUED);
        assertThat(failed.status()).isEqualTo(IndexJobStatus.FAILED);
    }

    @Test
    void successfulJobCannotBeCanceled() {
        IndexJob succeeded = IndexJob.create(CodeRepositoryId.newId(), IndexJobType.FULL)
            .start("scan_repository")
            .succeed("completed");

        assertThatThrownBy(succeeded::requestCancel).isInstanceOf(IllegalStateException.class);
    }
}
