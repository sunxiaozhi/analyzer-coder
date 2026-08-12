package com.analyzercoder.application.llm;

import java.util.UUID;

/** 承载大模型供应商在应用层边界内使用的数据与行为。 */
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
        String fingerprint) {}
