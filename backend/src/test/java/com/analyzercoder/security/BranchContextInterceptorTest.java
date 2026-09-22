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
        var request = new MockHttpServletRequest("GET", base + "/profile");
        request.addHeader("X-Branch-Context", "pinned");
        assertThatThrownBy(
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ApiSecurityException.class);
        request.setRequestURI(base + "/codegraph/latest");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        request.setRequestURI(base + "/knowledge/markdown-sources");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    void allowsPinnedBranchOverviewWithoutOpeningLegacyOverviewEndpoints() {
        var interceptor = new BranchContextInterceptor();
        var request = new MockHttpServletRequest("GET", base + "/branch-overview");
        request.addHeader("X-Branch-Context", "pinned");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();

        for (var path : new String[] {"/profile", "/code-facts", "/health-overview"}) {
            request.setRequestURI(base + path);
            assertThatThrownBy(
                            () ->
                                    interceptor.preHandle(
                                            request, new MockHttpServletResponse(), null))
                    .isInstanceOf(ApiSecurityException.class);
        }
        request.setRequestURI(base + "/branch-overview");
        request.setMethod("POST");
        assertThatThrownBy(
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ApiSecurityException.class);
    }

    @Test
    void allowsPinnedVectorIndexReadsWithoutOpeningVectorWrites() {
        var interceptor = new BranchContextInterceptor();
        var request = new MockHttpServletRequest("GET", base + "/vector-index/summary");
        request.addHeader("X-Branch-Context", "pinned");

        for (var path :
                new String[] {
                    "/vector-index/summary",
                    "/vector-index/chunks",
                    "/vector-index/knowledge"
                }) {
            request.setRequestURI(base + path);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        }

        request.setMethod("POST");
        assertThatThrownBy(
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ApiSecurityException.class);
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
