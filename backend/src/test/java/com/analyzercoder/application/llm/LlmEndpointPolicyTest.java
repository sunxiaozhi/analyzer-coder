package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LlmEndpointPolicyTest {
    @Test
    void requiresHttpsByDefault() {
        LlmEndpointPolicy policy = new LlmEndpointPolicy(false);

        LlmConnectionException exception = assertThrows(
            LlmConnectionException.class,
            () -> policy.normalize("http://localhost:11434/v1")
        );

        assertEquals("LLM_NETWORK_BLOCKED", exception.code());
    }

    @Test
    void permitsExplicitDevelopmentLoopbackAndNormalizesTrailingSlash() {
        LlmEndpointPolicy policy = new LlmEndpointPolicy(true);

        assertEquals(
            "http://localhost:11434/v1",
            policy.normalize("http://localhost:11434/v1/").toString()
        );
    }
}
