package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.memory.TaskContextService;
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
import org.junit.jupiter.api.Test;

class TaskContextControllerTest {

    @Test
    void requiresReadPermissionAndForwardsBudgets() {
        TaskContextService service = mock(TaskContextService.class);
        AccessControlService access = mock(AccessControlService.class);
        TaskContextController controller = new TaskContextController(service, access);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "reader",
                        "只读用户",
                        AccountRole.NORMAL,
                        false,
                        Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
        UUID repositoryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        TaskContextController.TaskContextRequest body =
                new TaskContextController.TaskContextRequest(
                        "修改订单", reviewId, 12, 12_000, 2_500);

        controller.generate(repositoryId, body, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service)
                .generate(
                        CodeRepositoryId.of(repositoryId),
                        "修改订单",
                        reviewId,
                        12,
                        12_000,
                        2_500);
    }
}
