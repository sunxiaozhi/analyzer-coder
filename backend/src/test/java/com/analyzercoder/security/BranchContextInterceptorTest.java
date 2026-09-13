package com.analyzercoder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BranchContextInterceptorTest {
    final String base = "/api/repositories/11111111-1111-4111-8111-111111111111";

    @Test
    void rejectsIgnoredContextsButAllowsExplicitReaders() {
        var interceptor = new BranchContextInterceptor();
        var request = new MockHttpServletRequest("GET", base + "/codegraph/latest");
        request.addHeader("X-Branch-Context", "pinned");
        assertThatThrownBy(
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ApiSecurityException.class);
        request.setRequestURI(base + "/evidence-search");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    void doesNotBroadenBearerTokenPermissionsToBranchWrites() {
        assertThat(AccessTokenInterceptor.allowed("POST", base + "/contexts")).isTrue();
        assertThat(AccessTokenInterceptor.allowed("POST", base + "/branches")).isFalse();
        assertThat(
                        AccessTokenInterceptor.allowed(
                                "POST",
                                base + "/branches/11111111-1111-4111-8111-111111111111/prepare"))
                .isFalse();
        assertThat(AccessTokenInterceptor.allowed("PUT", base + "/knowledge/card/branch-scope"))
                .isFalse();
    }
}
