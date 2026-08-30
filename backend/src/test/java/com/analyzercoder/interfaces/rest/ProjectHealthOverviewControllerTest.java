package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.overview.ProjectHealthOverviewService;
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

class ProjectHealthOverviewControllerTest {
    @Test
    void requiresRepositoryReadPermissionBeforeLoadingHealthFacts() {
        ProjectHealthOverviewService service = mock(ProjectHealthOverviewService.class);
        AccessControlService access = mock(AccessControlService.class);
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

        new ProjectHealthOverviewController(service, access).get(repositoryId, request);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        verify(service).view(CodeRepositoryId.of(repositoryId));
    }
}
