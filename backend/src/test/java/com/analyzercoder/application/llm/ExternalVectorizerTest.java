package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalVectorizerTest {
    @Test
    void fallsBackToSinglesOnceWhenCompatibleServerRejectsArrays() {
        LlmSettingsMapper mapper = mock(LlmSettingsMapper.class);
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        when(mapper.activeVectorModel())
                .thenReturn(
                        Map.of(
                                "provider_type", "OPENAI_COMPATIBLE",
                                "base_url", "https://example.com/v1",
                                "model", "bge-m3",
                                "dimension", 2,
                                "request_timeout_ms", 5000));
        when(client.embedBatch(
                        eq("https://example.com/v1"),
                        eq("bge-m3"),
                        eq(""),
                        anyList(),
                        eq(2),
                        eq(5000)))
                .thenThrow(new LlmConnectionException("LLM_BATCH_UNSUPPORTED", "unsupported"));
        when(client.embed(
                        eq("https://example.com/v1"),
                        eq("bge-m3"),
                        eq(""),
                        org.mockito.ArgumentMatchers.anyString(),
                        eq(2),
                        eq(5000)))
                .thenReturn("[1.0,0.0]");
        LlmSettingsService settings =
                new LlmSettingsService(
                        mapper,
                        mock(LlmSecretCipher.class),
                        mock(LlmEndpointPolicy.class),
                        client,
                        new ObjectMapper(),
                        15,
                        3);
        LlmSettingsService.ExternalVectorizer vectorizer = settings.openExternalVectorizer();

        assertEquals(2, vectorizer.vectorizeBatch(List.of("a", "b")).size());
        assertEquals(2, vectorizer.vectorizeBatch(List.of("c", "d")).size());

        verify(mapper, times(1)).activeVectorModel();
        verify(client, times(1))
                .embedBatch(
                        eq("https://example.com/v1"),
                        eq("bge-m3"),
                        eq(""),
                        anyList(),
                        eq(2),
                        eq(5000));
        verify(client, times(4))
                .embed(
                        eq("https://example.com/v1"),
                        eq("bge-m3"),
                        eq(""),
                        org.mockito.ArgumentMatchers.anyString(),
                        eq(2),
                        eq(5000));
    }
}
