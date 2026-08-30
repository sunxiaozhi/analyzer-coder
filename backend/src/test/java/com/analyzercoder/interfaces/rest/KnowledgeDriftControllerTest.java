package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.KnowledgeDriftService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeDriftControllerTest {
    private KnowledgeDriftService drift;
    private IntelligenceService intelligence;
    private AccessControlService access;
    private KnowledgeDriftController controller;
    private HttpServletRequest request;
    private AuthenticatedAccount account;

    @BeforeEach
    void setUp() {
        drift = mock(KnowledgeDriftService.class);
        intelligence = mock(IntelligenceService.class);
        access = mock(AccessControlService.class);
        controller = new KnowledgeDriftController(drift, intelligence, access);
        request = mock(HttpServletRequest.class);
        account =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "maintainer",
                        "维护者",
                        AccountRole.NORMAL,
                        false,
                        Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
    }

    @Test
    void readingEvidenceRequiresReadAndReviewingRequiresMaintain() {
        UUID repositoryId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        var body =
                new KnowledgeDriftController.SourceReviewRequest(
                        "CONFIRM_CURRENT", 4, "已核对当前实现");

        controller.latest(repositoryId, cardId, request);
        controller.review(repositoryId, cardId, body, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.MAINTAIN);
        verify(drift)
                .reviewSource(
                        eq(CodeRepositoryId.of(repositoryId)),
                        eq(cardId),
                        eq(account.id()),
                        any(KnowledgeDriftService.SourceReviewRequest.class));
    }
}
