package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.outcome.TaskReviewOutcomeService;
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
import org.junit.jupiter.api.Test;

class TaskReviewOutcomeControllerTest {
    @Test
    void requiresReadAndPassesTheAuthenticatedReporterToTheOutcomeService() {
        TaskReviewOutcomeService service = mock(TaskReviewOutcomeService.class);
        AccessControlService access = mock(AccessControlService.class);
        TaskReviewOutcomeController controller =
                new TaskReviewOutcomeController(service, access);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedAccount actor =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "developer", "开发者", AccountRole.NORMAL, false, Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", actor));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        UUID repositoryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        TaskReviewOutcomeService.OutcomeRequest body =
                new TaskReviewOutcomeService.OutcomeRequest(
                        UUID.randomUUID(), "a".repeat(40), "完成", List.of(), List.of(), List.of());

        controller.report(repositoryId, reviewId, body, request);

        verify(access)
                .require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service)
                .report(
                        CodeRepositoryId.of(repositoryId),
                        reviewId,
                        actor,
                        body,
                        "127.0.0.1");
    }
}
