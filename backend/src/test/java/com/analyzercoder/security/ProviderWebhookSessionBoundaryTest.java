package com.analyzercoder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class ProviderWebhookSessionBoundaryTest {
    @Test
    void webhookPathDoesNotRequireBrowserSessionBecauseControllerRequiresProviderSignature() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/provider-webhooks/github");
        when(request.getMethod()).thenReturn("POST");

        boolean allowed =
                new SessionInterceptor(auth)
                        .preHandle(request, mock(HttpServletResponse.class), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(auth);
    }
}
