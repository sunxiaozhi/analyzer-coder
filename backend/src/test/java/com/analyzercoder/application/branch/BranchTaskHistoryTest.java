package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

class BranchTaskHistoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final BranchPreparationJobs service =
            new BranchPreparationJobs(
                    db,
                    mock(DataSource.class),
                    mock(RepositoryBranchService.class),
                    access,
                    mock(PlatformTransactionManager.class),
                    mock(IntelligenceService.class));

    @Test
    void inaccessibleRepositoriesNeverReachTaskStorage() {
        AuthenticatedAccount actor = mock(AuthenticatedAccount.class);
        UUID repositoryId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("denied"))
                .when(access)
                .require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        assertThatThrownBy(() -> service.history(actor, repositoryId, null, 1, 15))
                .hasMessage("denied");
        verifyNoInteractions(db);
    }

    @Test
    void invalidPaginationNeverReachesTaskStorage() {
        assertThatThrownBy(() -> service.history(null, UUID.randomUUID(), null, 0, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history(null, UUID.randomUUID(), null, 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void taskQueriesReturnPersistedTimestamps(boolean history) throws Exception {
        UUID repositoryId = UUID.randomUUID();
        if (history) service.history(null, repositoryId, null, 1, 15);
        else service.list(null, repositoryId);
        ArgumentCaptor<RowMapper<BranchPreparationJobs.Job>> mapper =
                ArgumentCaptor.forClass((Class) RowMapper.class);
        verify(db)
                .query(
                        contains("target_content_version,created_at,updated_at"),
                        mapper.capture(),
                        any(Object[].class));
        ResultSet row = mock(ResultSet.class);
        Instant created = Instant.parse("2026-10-15T00:00:00Z");
        Instant finished = created.plusSeconds(125);
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
        when(row.getTimestamp("updated_at")).thenReturn(Timestamp.from(finished));
        when(row.getString("status")).thenReturn("SUCCEEDED");
        BranchPreparationJobs.Job job = mapper.getValue().mapRow(row, 0);
        assertThat(job.createdAt()).isEqualTo(created);
        assertThat(job.updatedAt()).isEqualTo(finished);
        assertThat(job.status()).isEqualTo("SUCCEEDED");
    }
}
