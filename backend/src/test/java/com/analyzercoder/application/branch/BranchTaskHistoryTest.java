package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class BranchTaskHistoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final BranchPreparationJobs service = new BranchPreparationJobs(db, mock(DataSource.class), mock(RepositoryBranchService.class), access, mock(PlatformTransactionManager.class), mock(IntelligenceService.class));

    @Test
    void inaccessibleRepositoriesNeverReachTaskStorage() {
        AuthenticatedAccount actor = mock(AuthenticatedAccount.class);
        UUID repositoryId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("denied")).when(access).require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        assertThatThrownBy(() -> service.history(actor, repositoryId, null, 1, 15)).hasMessage("denied");
        verifyNoInteractions(db);
    }

    @Test
    void invalidPaginationNeverReachesTaskStorage() {
        assertThatThrownBy(() -> service.history(null, UUID.randomUUID(), null, 0, 15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history(null, UUID.randomUUID(), null, 1, 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
    }
}
