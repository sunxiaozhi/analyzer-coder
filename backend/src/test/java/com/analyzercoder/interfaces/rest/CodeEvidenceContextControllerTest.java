package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.code.CodeEvidenceContextService;
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

class CodeEvidenceContextControllerTest {
    @Test
    void requiresReadAndOnlyIncludesDraftKnowledgeForMaintainers() {
        CodeEvidenceContextService service = mock(CodeEvidenceContextService.class);
        AccessControlService access = mock(AccessControlService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        UUID.randomUUID(),
                        "maintainer",
                        "维护者",
                        AccountRole.NORMAL,
                        false,
                        Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", account));
        UUID repositoryId = UUID.randomUUID();
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        when(access.canAccess(account, id, RepositoryPermission.MAINTAIN)).thenReturn(true);

        new CodeEvidenceContextController(service, access)
                .get(repositoryId, "src/App.java", "run", request);

        verify(access).require(account, id, RepositoryPermission.READ);
        verify(access).canAccess(account, id, RepositoryPermission.MAINTAIN);
        verify(service).context(id, "src/App.java", "run", true);
    }
}
