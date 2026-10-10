package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import com.analyzercoder.security.ApiSecurityException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ModelInvocationServiceTest {
    private final UUID configId = UUID.randomUUID();
    private LlmSettingsMapper mapper;
    private OpenAiCompatibleClient client;
    private LlmSettingsService settings;
    private Map<String, Object> config;

    @BeforeEach
    void setup() {
        mapper = mock(LlmSettingsMapper.class);
        client = mock(OpenAiCompatibleClient.class);
        settings =
                new LlmSettingsService(
                        mapper,
                        mock(LlmSecretCipher.class),
                        mock(LlmEndpointPolicy.class),
                        client,
                        new ObjectMapper(),
                        new LlmRuntimeStateService(mapper),
                        15,
                        3);
        config =
                new HashMap<>(
                        Map.of(
                                "id",
                                configId,
                                "name",
                                "问答",
                                "model",
                                "chat-model",
                                "base_url",
                                "https://chat.example/v1",
                                "availability",
                                "AVAILABLE",
                                "breaker_state",
                                "CLOSED"));
        when(mapper.config(configId)).thenReturn(config);
    }

    @ParameterizedTest
    @CsvSource({
        "LLM_TIMEOUT,504",
        "LLM_RATE_LIMITED,503",
        "LLM_CONNECTION_FAILED,503",
        "LLM_AUTH_FAILED,502",
        "LLM_PROTOCOL_INVALID,502",
        "LLM_OUTPUT_TRUNCATED,502"
    })
    void exposesFailureInsteadOfSilentlyReturningLocalEvidence(String code, int status) {
        when(client.generate(any(), anyString(), anyString()))
                .thenThrow(
                        new LlmConnectionException(
                                code,
                                "HTTP failure; request timeout=60000ms; token=private-token"));
        ApiSecurityException failure =
                assertThrows(
                        ApiSecurityException.class, () -> settings.generate(configId, "question"));
        assertThat(failure.status()).isEqualTo(status);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.getMessage()).contains(code, "60000ms").doesNotContain("private-token");
        verify(mapper).recordRuntimeFailure(configId, code, 3);
        verify(mapper, never()).recordRuntimeSuccess(any());
    }

    @Test
    void keepsNonStreamingQuestionsAvailableWhenOnlyStreamingProbeWasDegraded() {
        config.put("availability", "DEGRADED");
        when(mapper.configVersions()).thenReturn(List.of(config));
        when(client.generate(any(), anyString(), anyString())).thenReturn("answer [S1]");
        assertThat(settings.askModels().get(0).available()).isTrue();
        assertThat(settings.generate(configId, "question")).isPresent();
        verify(mapper).recordRuntimeSuccess(configId);
    }

    @Test
    void doesNotCallABrokenOrUntestedProvider() {
        config.put("availability", "UNTESTED");
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> settings.generate(configId, "question"))
                                .code())
                .isEqualTo("LLM_MODEL_UNAVAILABLE");
        verify(client, never()).generate(any(), anyString(), anyString());
    }

    @Test
    void vectorProbeExercisesSingleAndBatchUsingTheActualFallbackPath() {
        when(mapper.vectorModel(configId))
                .thenReturn(
                        Map.of(
                                "id",
                                configId,
                                "provider_type",
                                "OPENAI_COMPATIBLE",
                                "base_url",
                                "https://vector.example/v1",
                                "model",
                                "vector-model",
                                "dimension",
                                2,
                                "request_timeout_ms",
                                5000));
        when(client.embed(
                        eq("https://vector.example/v1"),
                        eq("vector-model"),
                        eq(""),
                        anyString(),
                        eq(2),
                        anyInt()))
                .thenReturn("[1,0]");
        when(client.embedBatch(
                        eq("https://vector.example/v1"),
                        eq("vector-model"),
                        eq(""),
                        anyList(),
                        eq(2),
                        anyInt()))
                .thenThrow(
                        new LlmConnectionException(
                                "LLM_BATCH_UNSUPPORTED", "HTTP 400: array unsupported"));
        assertThat(settings.checkVectorModel(configId).available()).isTrue();
        verify(client)
                .embedBatch(
                        eq("https://vector.example/v1"),
                        eq("vector-model"),
                        eq(""),
                        eq(List.of("connection probe", "连接检测")),
                        eq(2),
                        anyInt());
        verify(client)
                .embed(
                        eq("https://vector.example/v1"),
                        eq("vector-model"),
                        eq(""),
                        eq("连接检测"),
                        eq(2),
                        anyInt());
    }

    @Test
    void vectorProbeFailsWhenSingleSucceedsButBatchTimesOut() {
        when(mapper.vectorModel(configId))
                .thenReturn(
                        Map.of(
                                "id",
                                configId,
                                "provider_type",
                                "OPENAI_COMPATIBLE",
                                "base_url",
                                "https://vector.example/v1",
                                "model",
                                "vector-model",
                                "dimension",
                                2,
                                "request_timeout_ms",
                                5000));
        when(client.embed(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn("[1,0]");
        when(client.embedBatch(
                        anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenThrow(new LlmConnectionException("LLM_TIMEOUT", "batch stalled"));
        var result = settings.checkVectorModel(configId);
        assertThat(result.available()).isFalse();
        assertThat(result.errorCode()).isEqualTo("LLM_TIMEOUT");
        assertThat(result.errorSummary()).contains("batch stalled");
    }
}
