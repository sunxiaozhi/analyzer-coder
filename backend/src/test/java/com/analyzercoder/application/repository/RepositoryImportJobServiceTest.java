package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryImportJobMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class RepositoryImportJobServiceTest {
    @Test
    void recordsInnerTransactionalFailureWithoutLeakingUnexpectedRollback() {
        RepositoryImportJobMapper mapper = mock(RepositoryImportJobMapper.class);
        RepositoryCredentialService credentials = mock(RepositoryCredentialService.class);
        RepositorySourceImportService imports = mock(RepositorySourceImportService.class);
        RepositoryProjectDraftService drafts = mock(RepositoryProjectDraftService.class);
        RepositoryImportJobStateService jobState = mock(RepositoryImportJobStateService.class);
        RepositoryImportJobService service =
                new RepositoryImportJobService(mapper, credentials, imports, drafts, jobState);
        UUID jobId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Map<String, Object> row =
                Map.of(
                        "id",
                        jobId,
                        "repository_name",
                        "duplicate",
                        "remote_url",
                        "https://example.test/repository.git",
                        "source_type",
                        RepositorySourceType.REMOTE_GIT.name(),
                        "account_id",
                        accountId);
        when(jobState.claim()).thenReturn(row);
        when(imports.importRemoteQueued(
                        "duplicate",
                        "https://example.test/repository.git",
                        null,
                        RepositorySourceType.REMOTE_GIT,
                        null,
                        accountId))
                .thenThrow(new IllegalStateException("当前所有者下已存在同名仓库"));

        assertThat(service.processNext()).isFalse();

        verify(jobState).step(jobId, "cloning");
        verify(jobState).fail(jobId, "当前所有者下已存在同名仓库");
        verify(jobState).failDraft(isNull(), org.mockito.ArgumentMatchers.eq("当前所有者下已存在同名仓库"));
    }

    @Test
    void workerOrchestrationHasNoLongRunningTransaction() throws Exception {
        assertThat(
                        RepositoryImportJobService.class
                                .getMethod("processNext")
                                .getAnnotation(Transactional.class))
                .isNull();
        assertRequiresNew("claim");
        assertRequiresNew("step", UUID.class, String.class);
        assertRequiresNew("cancel", UUID.class);
        assertRequiresNew("succeed", UUID.class, UUID.class, UUID.class);
        assertRequiresNew("fail", UUID.class, String.class);
        assertRequiresNew("failDraft", UUID.class, String.class);
    }

    private static void assertRequiresNew(String method, Class<?>... parameterTypes)
            throws Exception {
        Transactional annotation =
                RepositoryImportJobStateService.class
                        .getMethod(method, parameterTypes)
                        .getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
