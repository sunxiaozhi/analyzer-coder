package com.analyzercoder.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

class ForwardedClientIpTest {
    @Test
    void trustedForwardedFilterRestoresClientAddressAndScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Forwarded-Proto", "https");
        AtomicReference<String> remoteAddress = new AtomicReference<>();
        AtomicReference<String> scheme = new AtomicReference<>();

        new ForwardedHeaderFilter().doFilter(
            request,
            new MockHttpServletResponse(),
            (filteredRequest, ignored) -> {
                HttpServletRequest http = (HttpServletRequest) filteredRequest;
                remoteAddress.set(http.getRemoteAddr());
                scheme.set(http.getScheme());
            }
        );

        assertThat(remoteAddress.get()).isEqualTo("203.0.113.7");
        assertThat(scheme.get()).isEqualTo("https");
    }
}