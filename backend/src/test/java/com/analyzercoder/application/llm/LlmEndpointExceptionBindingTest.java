package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class LlmEndpointExceptionBindingTest {
    @Test
    void bindsIndependentExceptionFlagsFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "app.llm.endpoint-exceptions[0].base-url=https://10.0.0.1/v1",
                        "app.llm.endpoint-exceptions[0].allow-private-network=true",
                        "app.llm.endpoint-exceptions[0].skip-tls-verification=true")
                .run(
                        context -> {
                            LlmEndpointPolicy policy = context.getBean(LlmEndpointPolicy.class);
                            assertEquals(
                                    "https://10.0.0.1/v1",
                                    policy.validateAndResolve("https://10.0.0.1/v1").toString());
                            assertTrue(
                                    policy.skipTlsVerification(URI.create("https://10.0.0.1/v1")));
                        });
    }

    @Test
    void omittedExceptionFlagsRemainFalse() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("app.llm.endpoint-exceptions[0].base-url=https://10.0.0.1/v1")
                .run(
                        context -> {
                            LlmEndpointPolicy policy = context.getBean(LlmEndpointPolicy.class);
                            assertFalse(
                                    policy.skipTlsVerification(URI.create("https://10.0.0.1/v1")));
                            assertEquals(
                                    "LLM_NETWORK_BLOCKED",
                                    org.junit.jupiter.api.Assertions.assertThrows(
                                                    LlmConnectionException.class,
                                                    () ->
                                                            policy.validateAndResolve(
                                                                    "https://10.0.0.1/v1"))
                                            .code());
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmEndpointExceptionProperties.class)
    @Import(LlmEndpointPolicy.class)
    static class TestConfiguration {}
}
