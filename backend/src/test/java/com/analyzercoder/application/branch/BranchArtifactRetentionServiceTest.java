package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.repository.GitBranchSnapshotFactory;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class BranchArtifactRetentionServiceTest {
    @Test
    void keepsSnapshotWhenQuestionStillReferencesItsExactVersion() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("content_path", "C:/managed/snapshot")));
        when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0, 0, 0, 0, 1, 0, 0, 0);
        var service =
                new BranchArtifactRetentionService(
                        db,
                        mock(AccessControlService.class),
                        mock(GitBranchSnapshotFactory.class),
                        mock(PlatformTransactionManager.class));
        UUID repositoryId = UUID.randomUUID();
        var actor =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "maintainer",
                        "Maintainer",
                        AccountRole.NORMAL,
                        false,
                        null);

        var result = service.inspect(actor, repositoryId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.removable()).isFalse();
        assertThat(result.questionReferences()).isEqualTo(1);
    }

    @Test
    void removesThePersistedCodegraphArtifactTableAfterReferenceChecksPass() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("content_path", "C:/managed/snapshot")));
        when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var service =
                new BranchArtifactRetentionService(
                        db,
                        mock(AccessControlService.class),
                        mock(GitBranchSnapshotFactory.class),
                        transactions);
        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        var actor =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "maintainer",
                        "Maintainer",
                        AccountRole.NORMAL,
                        false,
                        null);

        service.remove(actor, repositoryId, UUID.randomUUID(), snapshotId);

        verify(db)
                .update(
                        eq("DELETE FROM codegraph_artifacts WHERE repo_id=? AND snapshot_id=?"),
                        eq(repositoryId),
                        eq(snapshotId));
    }
}
