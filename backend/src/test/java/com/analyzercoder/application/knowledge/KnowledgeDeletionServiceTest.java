package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeDeletionServiceTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final AccessControlService access = mock(AccessControlService.class);
    final KnowledgeDeletionService service = new KnowledgeDeletionService(db, access);
    final UUID repo = UUID.randomUUID(), card = UUID.randomUUID(), branch = UUID.randomUUID();
    final BranchReadContext context =
            new BranchReadContext(
                    UUID.randomUUID(),
                    repo,
                    branch,
                    "main",
                    UUID.randomUUID(),
                    "commit",
                    Path.of("."),
                    Instant.now());
    final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "manager", "Manager", AccountRole.NORMAL, false, null);

    void row(String status, int revision) {
        when(db.queryForList(anyString(), eq(card), eq(repo), eq(branch)))
                .thenReturn(List.of(Map.of("publication_status", status, "revision", revision)));
    }

    @Test
    void deletesOnlyLockedDraftWithMatchingRevisionAndRequiresManagePermission() {
        row("DRAFT", 2);
        when(db.update(anyString(), eq(card), eq(repo), eq(2))).thenReturn(1);
        service.delete(actor, context, card, 2);
        var order = inOrder(access, db);
        order.verify(access).require(actor, CodeRepositoryId.of(repo), RepositoryPermission.MANAGE);
        order.verify(db).queryForList(contains("FOR UPDATE"), eq(card), eq(repo), eq(branch));
        order.verify(db).update(contains("publication_status='DRAFT'"), eq(card), eq(repo), eq(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "ARCHIVED"})
    void requiresWithdrawalBeforeDeleting(String status) {
        row(status, 2);
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 2))
                                .code())
                .isEqualTo("KNOWLEDGE_DELETE_REQUIRES_DRAFT");
        verify(db, never()).update(anyString(), eq(card), eq(repo), eq(2));
    }

    @Test
    void rejectsChangedRevisionBeforeDeleting() {
        row("DRAFT", 3);
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 2))
                                .code())
                .isEqualTo("KNOWLEDGE_REVISION_CONFLICT");
        verify(db, never()).update(anyString(), eq(card), eq(repo), eq(2));
    }

    @Test
    void rejectsMissingOrInapplicableCard() {
        when(db.queryForList(anyString(), eq(card), eq(repo), eq(branch))).thenReturn(List.of());
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 2))
                                .status())
                .isEqualTo(404);
    }

    @Test
    void rejectsPermissionBeforeReadingOrDeletingAnything() {
        doThrow(new ApiSecurityException(403, "FORBIDDEN", "无权限"))
                .when(access)
                .require(actor, CodeRepositoryId.of(repo), RepositoryPermission.MANAGE);
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 2))
                                .status())
                .isEqualTo(403);
        verifyNoInteractions(db);
    }

    @Test
    void requiresPositiveRevision() {
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 0))
                                .status())
                .isEqualTo(400);
        verifyNoInteractions(db);
    }
}
