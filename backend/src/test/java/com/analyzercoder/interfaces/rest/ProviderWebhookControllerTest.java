package com.analyzercoder.interfaces.rest;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.pullrequest.ProviderWebhookAuthenticator;
import com.analyzercoder.application.pullrequest.ProviderWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ProviderWebhookControllerTest {
    @Test
    void authenticatesRawGithubPayloadBeforeDispatchingIt() {
        ProviderWebhookAuthenticator authenticator = mock(ProviderWebhookAuthenticator.class);
        ProviderWebhookService service = mock(ProviderWebhookService.class);
        ProviderWebhookController controller =
                new ProviderWebhookController(authenticator, service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(request.getHeader("X-Hub-Signature-256")).thenReturn("sha256=signature");
        when(request.getHeader("X-GitHub-Event")).thenReturn("pull_request");
        when(request.getHeader("X-GitHub-Delivery")).thenReturn("delivery");

        controller.github(payload, request);

        InOrder order = inOrder(authenticator, service);
        order.verify(authenticator).verifyGithub(payload, "sha256=signature");
        order.verify(service).github("pull_request", "delivery", payload);
    }
}
