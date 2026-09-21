package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BranchKnowledgeServiceTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final AccessControlService access = mock(AccessControlService.class);
    final BranchKnowledgeService service = new BranchKnowledgeService(db, access);
    final UUID repo = UUID.randomUUID(), branch = UUID.randomUUID(), version = UUID.randomUUID();
    final UUID card = UUID.randomUUID();
    final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "owner", "Owner", AccountRole.SUPER_ADMIN, false, null);
    final BranchReadContext context =
            new BranchReadContext(
                    UUID.randomUUID(), repo, branch, "main", version, "abc", Path.of("."), Instant.now());

    @Test
    void applicableKnowledgeIsSelectedByOwningBranch() {
        when(db.queryForList(anyString(), eq(UUID.class), eq(repo), eq(branch)))
                .thenReturn(List.of(card));

        assertThat(service.applicable(repo, branch)).containsExactly(card);
        verify(db).queryForList(contains("branch_id=?"), eq(UUID.class), eq(repo), eq(branch));
    }

    @Test
    void verificationRequiresTheCardToBelongToTheBranch() {
        when(db.queryForObject(anyString(), eq(Integer.class), eq(repo), eq(card), eq(2), eq(branch)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.verify(actor, context, card, 2, "CURRENT", "checked"))
                .isInstanceOf(ApiSecurityException.class)
                .hasMessageContaining("不适用于该分支");
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void verificationIsRecordedAgainstBranchAndCurrentContentToken() {
        when(db.queryForObject(anyString(), eq(Integer.class), eq(repo), eq(card), eq(2), eq(branch)))
                .thenReturn(1);

        service.verify(actor, context, card, 2, "CURRENT", "checked");

        verify(db)
                .update(
                        contains("INSERT INTO knowledge_branch_validations"),
                        eq(card),
                        eq(2),
                        eq(branch),
                        eq(version),
                        eq("CURRENT"),
                        eq("checked"),
                        eq(actor.id()));
    }
}
