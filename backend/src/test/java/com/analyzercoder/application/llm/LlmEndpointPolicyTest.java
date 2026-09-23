package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmEndpointPolicyTest {
    @Test
    void rejectsPrivateNetworkByDefault() {
        LlmEndpointPolicy policy = policy(false);

        LlmConnectionException exception =
                assertThrows(
                        LlmConnectionException.class,
                        () -> policy.validateAndResolve("https://10.0.0.1/v1"));

        assertEquals("LLM_NETWORK_BLOCKED", exception.code());
    }

    @Test
    void permitsExplicitDevelopmentLoopbackAndNormalizesTrailingSlash() {
        LlmEndpointPolicy policy = policy(true);

        assertEquals(
                "http://localhost:11434/v1",
                policy.normalize("http://localhost:11434/v1/").toString());
    }

    @Test
    void permitsOnlyExactPrivateEndpoint() {
        LlmEndpointPolicy policy =
                policy(false, exception("http://10.0.0.1:11434/v1", true, false));

        assertEquals(
                "http://10.0.0.1:11434/v1",
                policy.validateAndResolve("http://10.0.0.1:11434/v1/").toString());
        assertEquals(
                "LLM_NETWORK_BLOCKED",
                assertThrows(
                                LlmConnectionException.class,
                                () -> policy.validateAndResolve("http://10.0.0.1:11435/v1"))
                        .code());
        assertEquals(
                "LLM_NETWORK_BLOCKED",
                assertThrows(
                                LlmConnectionException.class,
                                () -> policy.validateAndResolve("https://10.0.0.1:11434/other"))
                        .code());
    }

    @Test
    void tlsExceptionIsIndependentOfPrivateNetworkAccess() {
        URI endpoint = URI.create("https://10.0.0.1/v1");
        LlmEndpointPolicy policy = policy(false, exception(endpoint.toString(), false, true));

        assertTrue(policy.skipTlsVerification(endpoint));
        assertFalse(policy.skipTlsVerification(URI.create("https://10.0.0.1/other")));
        assertEquals(
                "LLM_NETWORK_BLOCKED",
                assertThrows(
                                LlmConnectionException.class,
                                () -> policy.validateAndResolve(endpoint.toString()))
                        .code());
    }

    @Test
    void rejectsPublicHttpEvenWhenPrivateEndpointIsListed() {
        LlmEndpointPolicy policy = policy(false, exception("http://8.8.8.8/v1", true, false));

        assertEquals(
                "LLM_NETWORK_BLOCKED",
                assertThrows(
                                LlmConnectionException.class,
                                () -> policy.validateAndResolve("http://8.8.8.8/v1"))
                        .code());
    }

    @Test
    void rejectsTlsExceptionForHttpEndpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(false, exception("http://10.0.0.1/v1", true, true)));
    }

    private static LlmEndpointPolicy policy(
            boolean allowInsecureLocal,
            LlmEndpointExceptionProperties.EndpointException... exceptions) {
        return new LlmEndpointPolicy(
                allowInsecureLocal, new LlmEndpointExceptionProperties(List.of(exceptions)));
    }

    private static LlmEndpointExceptionProperties.EndpointException exception(
            String baseUrl, boolean allowPrivateNetwork, boolean skipTlsVerification) {
        return new LlmEndpointExceptionProperties.EndpointException(
                baseUrl, allowPrivateNetwork, skipTlsVerification);
    }
}
