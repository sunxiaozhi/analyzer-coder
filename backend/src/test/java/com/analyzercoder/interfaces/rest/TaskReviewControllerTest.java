package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.review.TaskReviewRequest;
import com.analyzercoder.application.review.TaskReviewService;
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

class TaskReviewControllerTest {
    private TaskReviewService service;
    private AccessControlService access;
    private TaskReviewController controller;
    private HttpServletRequest request;
    private AuthenticatedAccount account;

    @BeforeEach
    void setUp() {
        service = mock(TaskReviewService.class);
        access = mock(AccessControlService.class);
        controller = new TaskReviewController(service, access);
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
    void createRequiresReadPermissionAndUsesAuthenticatedCreator() {
        UUID repositoryId = UUID.randomUUID();
        TaskReviewRequest body =
                new TaskReviewRequest(
                        UUID.randomUUID(),
                        "review worktree",
                        GitChangeRequest.Source.WORKTREE,
                        "HEAD",
                        null,
                        null);

        controller.create(repositoryId, body, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service).create(CodeRepositoryId.of(repositoryId), account.id(), body);
    }

    @Test
    void historyAndDetailRecheckReadPermission() {
        UUID repositoryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();

        controller.list(repositoryId, 25, 0, request);
        controller.get(repositoryId, reviewId, request);

        verify(access, org.mockito.Mockito.times(2))
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service).list(CodeRepositoryId.of(repositoryId), 25, 0);
        verify(service).get(CodeRepositoryId.of(repositoryId), reviewId);
    }
}
