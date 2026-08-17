package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntelligenceControllerTest {
    private IntelligenceService service;
    private AccessControlService access;
    private IntelligenceController controller;
    private HttpServletRequest request;
    private AuthenticatedAccount account;

    @BeforeEach
    void setUp() {
        service = mock(IntelligenceService.class);
        access = mock(AccessControlService.class);
        controller = new IntelligenceController(service, access);
        request = mock(HttpServletRequest.class);
        account =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "reader",
                        "只读用户",
                        AccountRole.NORMAL,
                        false,
                        Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
    }

    @Test
    void asksOnlyRepositoryFromPath() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        var question =
                new IntelligenceController.Question(
                        "如何完成库存回滚？", requestId, threadId, modelConfigId);

        controller.ask(repositoryId, question, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service)
                .ask(
                        repositoryId,
                        account.id(),
                        question.question(),
                        requestId,
                        threadId,
                        modelConfigId);
    }

    @Test
    void rechecksRepositoryPermissionForHistory() {
        UUID repositoryId = UUID.randomUUID();
        when(service.history(repositoryId, account.id(), 25, 0)).thenReturn(List.of());

        controller.history(repositoryId, 25, 0, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service).history(repositoryId, account.id(), 25, 0);
    }
}
