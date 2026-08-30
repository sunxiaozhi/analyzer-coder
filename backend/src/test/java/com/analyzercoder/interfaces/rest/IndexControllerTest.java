package com.analyzercoder.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.indexing.IndexJobPageService;
import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IndexControllerTest {
    @Test
    void taskCenterPageRequiresSystemAdministrator() {
        IndexJobUseCase jobs = mock(IndexJobUseCase.class);
        AccessControlService access = mock(AccessControlService.class);
        IndexJobPageService page = mock(IndexJobPageService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "developer",
                        "普通开发者",
                        AccountRole.NORMAL,
                        false,
                        Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));

        assertThatThrownBy(
                        () ->
                                new IndexController(jobs, access, page)
                                        .page(1, 20, request))
                .isInstanceOf(ApiSecurityException.class)
                .hasMessageContaining("无权限");
        verifyNoInteractions(page);
    }
}
