package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BranchKnowledgeServiceTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final AccessControlService access = mock(AccessControlService.class);
    final BranchKnowledgeService service = new BranchKnowledgeService(db, access);
    final UUID repo = UUID.randomUUID(), card = UUID.randomUUID();
    final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "owner", "Owner", AccountRole.SUPER_ADMIN, false, null);

    @Test
    void scopeRevisionCopiesEvidenceAndAttachmentsButNotValidation() {
        when(db.queryForList(anyString(), eq(Integer.class), eq(card), eq(repo)))
                .thenReturn(List.of(3));
        service.apply(actor, repo, card, 3, "ALL_BRANCHES", List.of());
        verify(db).update(contains("INSERT INTO knowledge_code_refs"), eq(card), eq(3));
        verify(db).update(contains("INSERT INTO knowledge_card_attachment_refs"), eq(card), eq(3));
        verify(db, never())
                .update(contains("INSERT INTO knowledge_branch_validations"), any(Object[].class));
    }

    @Test
    void staleRevisionCannotChangeScope() {
        when(db.queryForList(anyString(), eq(Integer.class), eq(card), eq(repo)))
                .thenReturn(List.of(4));
        assertThatThrownBy(() -> service.apply(actor, repo, card, 3, "ALL_BRANCHES", List.of()))
                .isInstanceOf(ApiSecurityException.class);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void nullAndEmptyScopesAreRejectedBeforeWriting() {
        assertThatThrownBy(() -> service.apply(actor, repo, card, 1, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> service.apply(actor, repo, card, 1, "SELECTED_BRANCHES", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        actor,
                                        repo,
                                        card,
                                        1,
                                        "ALL_BRANCHES",
                                        List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
    }
}
