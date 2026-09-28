package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.branch.BranchKnowledgeService;
import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgePublicationServiceTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final BranchKnowledgeService branches = mock(BranchKnowledgeService.class);
    final IntelligenceService intelligence = mock(IntelligenceService.class);
    final KnowledgePublicationService service =
            new KnowledgePublicationService(
                    db, mock(AccessControlService.class), branches, intelligence);
    final UUID repository = UUID.randomUUID(), card = UUID.randomUUID();
    final BranchReadContext context =
            new BranchReadContext(
                    UUID.randomUUID(),
                    repository,
                    UUID.randomUUID(),
                    "legacy",
                    UUID.randomUUID(),
                    "legacy-commit",
                    Path.of("."),
                    Instant.now());
    final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "owner", "Owner", AccountRole.SUPER_ADMIN, false, null);

    @Test
    void rejectsChangedRevisionBeforePublishing() {
        when(db.queryForList(
                        anyString(),
                        eq(Integer.class),
                        eq(card),
                        eq(repository),
                        eq(context.branchId())))
                .thenReturn(List.of(3));
        assertThatThrownBy(() -> service.publish(actor, context, card, 2))
                .hasMessageContaining("知识已修改");
        verify(intelligence, never()).setCardPublication(any(), any(), any(), any());
    }

    @Test
    void rejectsCodeReferencesMissingFromSelectedVersion() {
        when(db.queryForList(
                        anyString(),
                        eq(Integer.class),
                        eq(card),
                        eq(repository),
                        eq(context.branchId())))
                .thenReturn(List.of(2));
        when(db.queryForObject(
                        anyString(),
                        eq(Integer.class),
                        eq(card),
                        eq(2),
                        eq(context.contentVersion())))
                .thenReturn(1);
        assertThatThrownBy(() -> service.publish(actor, context, card, 2))
                .hasMessageContaining("关联代码已变化");
        verify(intelligence, never()).setCardPublication(any(), any(), any(), any());
    }

    @Test
    void confirmsSelectedBranchAndPublishesInOneOperation() {
        when(db.queryForList(
                        anyString(),
                        eq(Integer.class),
                        eq(card),
                        eq(repository),
                        eq(context.branchId())))
                .thenReturn(List.of(2));
        when(db.queryForObject(
                        anyString(),
                        eq(Integer.class),
                        eq(card),
                        eq(2),
                        eq(context.contentVersion())))
                .thenReturn(0);
        service.publish(actor, context, card, 2);
        verify(branches).verify(actor, context, card, 2, "CURRENT", "发布时已人工确认适用于当前分支");
        verify(intelligence).setCardPublication(repository, card, actor.id(), "PUBLISHED");
    }
}
