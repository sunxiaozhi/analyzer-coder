package com.analyzercoder.application.llm;

import java.util.UUID;

public record LlmProviderSpec(
    UUID id,
    long version,
    String name,
    String providerType,
    String baseUrl,
    String model,
    int connectTimeoutMs,
    int requestTimeoutMs,
    int maxOutputTokens,
    double temperature,
    boolean streamingEnabled,
    UUID secretVersionId,
    String fingerprint
) {}
