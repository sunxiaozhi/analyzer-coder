package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.pullrequest.PullRequestProvider;
import com.analyzercoder.application.pullrequest.PullRequestReviewService;
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

class PullRequestReviewControllerTest {
    @Test
    void requiresMaintainerPermissionBeforeUsingTheBoundProviderCredential() {
        PullRequestReviewService service = mock(PullRequestReviewService.class);
        AccessControlService access = mock(AccessControlService.class);
        PullRequestReviewController controller = new PullRequestReviewController(service, access);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "maintainer", "维护者", AccountRole.NORMAL, false, Instant.now());
        when(servletRequest.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
        UUID repositoryId = UUID.randomUUID();
        PullRequestReviewService.ReviewRequest body =
                new PullRequestReviewService.ReviewRequest(
                        UUID.randomUUID(),
                        PullRequestProvider.ProviderKind.GITHUB,
                        7,
                        null,
                        null,
                        null);

        controller.review(repositoryId, body, servletRequest);

        verify(access)
                .require(account, CodeRepositoryId.of(repositoryId), RepositoryPermission.MAINTAIN);
        verify(service).review(account, CodeRepositoryId.of(repositoryId), body);
    }
}
