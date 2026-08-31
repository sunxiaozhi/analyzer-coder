package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.project.EngineeringProjectService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EngineeringProjectControllerTest {
    @Test
    void forwardsTheAuthenticatedActorToTheServiceThatChecksEveryMemberRepository() {
        EngineeringProjectService service = mock(EngineeringProjectService.class);
        EngineeringProjectController controller = new EngineeringProjectController(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticatedAccount actor =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "manager", "Manager", AccountRole.NORMAL, false, Instant.now());
        when(request.getAttribute(SecurityContext.SESSION_ATTRIBUTE))
                .thenReturn(new AuthenticatedSession("token", "csrf", actor));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        EngineeringProjectService.ProjectInput body =
                new EngineeringProjectService.ProjectInput(
                        "Commerce", "", null, List.of(), List.of());

        controller.create(body, request);

        verify(service).create(actor, body, "127.0.0.1");
    }
}
