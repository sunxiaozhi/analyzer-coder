package com.analyzercoder.application.repository;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.security.AccessControlService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RepositoryProjectDraftServiceTest {
    @Test
    void completingImportPublishesPersistedProjectDescriptionToRepository() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        UUID draftId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(db.update(
                        contains("UPDATE repositories r SET description=d.description"),
                        eq(draftId),
                        eq(repositoryId)))
                .thenReturn(1);
        var service = new RepositoryProjectDraftService(db, mock(AccessControlService.class));

        service.complete(draftId, repositoryId);

        verify(db)
                .update(
                        contains("r.owner_account_id=d.owner_account_id"),
                        eq(draftId),
                        eq(repositoryId));
        verify(db).update(contains("lifecycle_status='READY'"), eq(repositoryId), eq(draftId));
    }
}
