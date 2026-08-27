package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.indexing.InMemoryIndexJobStore;
import org.junit.jupiter.api.Test;

class CodeGraphTaskServiceTest {
    @Test
    void buildRequestOnlyQueuesWorkAndReturnsImmediately() {
        InMemoryIndexJobStore jobs = new InMemoryIndexJobStore();
        CodeGraphTaskService service = new CodeGraphTaskService(jobs);

        IndexJob result = service.start(CodeRepositoryId.newId());

        assertThat(result.type()).isEqualTo(IndexJobType.CODEGRAPH);
        assertThat(result.status()).isEqualTo(IndexJobStatus.QUEUED);
        assertThat(result.startedAt()).isNull();
        assertThat(result.heartbeatAt()).isNull();
    }
}
