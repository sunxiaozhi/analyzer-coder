package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.ci.CiCheckService;
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

class CiCheckControllerTest {
    @Test
    void requiresReadPermissionBeforeEvaluatingTheImmutableReview() {
        CiCheckService service = mock(CiCheckService.class);
        AccessControlService access = mock(AccessControlService.class);
        CiCheckController controller = new CiCheckController(service, access);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "reader", "读者", AccountRole.NORMAL, false, Instant.now());
        when(servletRequest.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
        UUID repositoryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        CiCheckService.CiCheckRequest body =
                new CiCheckService.CiCheckRequest("a".repeat(40), List.of(), List.of());

        controller.check(repositoryId, reviewId, body, servletRequest);

        verify(access).require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service).check(CodeRepositoryId.of(repositoryId), reviewId, body);
    }
}
