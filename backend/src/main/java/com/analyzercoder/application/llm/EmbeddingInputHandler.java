package com.analyzercoder.application.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embed every part of long texts, adapt on explicit context errors, then pool their vectors. */
final class EmbeddingInputHandler {
    // Byte budget is conservative and independent of the provider tokenizer. It is not a token
    // count.
    static final int MAX_INPUT_BYTES = 6000;
    private static final int MAX_PARTS = 512;
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingInputHandler.class);
    private final OpenAiCompatibleClient client;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int dimension;

    EmbeddingInputHandler(
            OpenAiCompatibleClient client,
            ObjectMapper json,
            String baseUrl,
            String model,
            String apiKey,
            int dimension) {
        this.client = client;
        this.json = json;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.dimension = dimension;
    }

    static boolean needsSplitting(String input) {
        return bytes(input) > MAX_INPUT_BYTES;
    }

    String embed(String input, IntSupplier timeout) {
        int initialTimeout = timeout.getAsInt();
        long deadline =
                System.nanoTime()
                        + Duration.ofMillis(initialTimeout).toNanos()
                        - EmbeddingRequestScope.waitedNanos();
        if (!needsSplitting(input)) {
            try {
                // Preserve the provider's vector exactly for normal inputs.
                return client.embed(baseUrl, model, apiKey, input, dimension, initialTimeout);
            } catch (LlmConnectionException failure) {
                if (!"LLM_INPUT_TOO_LONG".equals(failure.code())) throw failure;
                LOG.warn(
                        "向量输入超限，尝试分段: {}, model={}, characters={}, utf8Bytes={}",
                        ModelCallLogContext.fields(),
                        LlmFailureMessages.safe(model, apiKey),
                        input.length(),
                        bytes(input));
                List<Part> parts = new ArrayList<>();
                splitRejected(input, parts, deadline, timeout, failure);
                return combine(input, parts);
            }
        }
        List<String> segments = split(input, MAX_INPUT_BYTES);
        if (segments.size() > MAX_PARTS) throw tooManyParts();
        LOG.info(
                "向量长文本分段: {}, model={}, characters={}, utf8Bytes={}, parts={}, byteBudget={}",
                ModelCallLogContext.fields(),
                LlmFailureMessages.safe(model, apiKey),
                input.length(),
                bytes(input),
                segments.size(),
                MAX_INPUT_BYTES);
        List<Part> parts = new ArrayList<>();
        for (String segment : segments) embedPart(segment, parts, deadline, timeout);
        return combine(input, parts);
    }

    private void embedPart(String input, List<Part> parts, long deadline, IntSupplier timeout) {
        if (parts.size() >= MAX_PARTS) throw tooManyParts();
        int remaining =
                (int)
                        Math.min(
                                timeout.getAsInt(),
                                Duration.ofNanos(
                                                deadline
                                                        + EmbeddingRequestScope.waitedNanos()
                                                        - System.nanoTime())
                                        .toMillis());
        if (remaining <= 0)
            throw new LlmConnectionException("LLM_TIMEOUT", "长文本分段向量化超过总请求超时，尚未完成的片段不会写入");
        try {
            parts.add(
                    new Part(
                            client.embed(baseUrl, model, apiKey, input, dimension, remaining),
                            bytes(input)));
        } catch (LlmConnectionException failure) {
            if (!"LLM_INPUT_TOO_LONG".equals(failure.code())) throw failure;
            splitRejected(input, parts, deadline, timeout, failure);
        }
    }

    private void splitRejected(
            String input,
            List<Part> parts,
            long deadline,
            IntSupplier timeout,
            LlmConnectionException failure) {
        if (input.codePointCount(0, input.length()) <= 1) {
            throw new LlmConnectionException(
                    "LLM_INPUT_TOO_LONG",
                    "最小文本输入仍超过模型上限，请核对服务的上下文限制；" + failure.getMessage(),
                    failure);
        }
        // Halve on Unicode code-point boundaries; even a tokenizer with a smaller context can
        // adapt.
        int middle = input.offsetByCodePoints(0, input.codePointCount(0, input.length()) / 2);
        embedPart(input.substring(0, middle), parts, deadline, timeout);
        embedPart(input.substring(middle), parts, deadline, timeout);
    }

    private String combine(String original, List<Part> parts) {
        if (parts.size() == 1) return parts.get(0).vector();
        double[] pooled = new double[dimension];
        long totalWeight = parts.stream().mapToLong(Part::weight).sum();
        try {
            for (Part part : parts) {
                JsonNode values = json.readTree(part.vector());
                if (!values.isArray() || values.size() != dimension)
                    throw new IllegalArgumentException();
                double weight = (double) part.weight() / totalWeight;
                for (int index = 0; index < dimension; index++) {
                    if (!values.get(index).isNumber()
                            || !Double.isFinite(values.get(index).asDouble()))
                        throw new IllegalArgumentException();
                    pooled[index] += values.get(index).asDouble() * weight;
                }
            }
            double norm = 0;
            for (double value : pooled) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException();
                norm = Math.hypot(norm, value);
            }
            if (norm > 0) for (int index = 0; index < dimension; index++) pooled[index] /= norm;
            String result = json.writeValueAsString(pooled);
            LOG.info(
                    "向量长文本合并完成: {}, model={}, characters={}, parts={}, dimension={}, strategy=BYTE_WEIGHTED_MEAN_L2",
                    ModelCallLogContext.fields(),
                    LlmFailureMessages.safe(model, apiKey),
                    original.length(),
                    parts.size(),
                    dimension);
            return result;
        } catch (Exception failure) {
            throw new LlmConnectionException(
                    "LLM_PROTOCOL_INVALID", "长文本分段向量无法合并，请按流程 ID 查看模型日志", failure);
        }
    }

    static List<String> split(String input, int maximumBytes) {
        List<String> result = new ArrayList<>();
        int start = 0, length = 0;
        for (int index = 0; index < input.length(); ) {
            int point = input.codePointAt(index);
            int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (length > 0 && length + size > maximumBytes) {
                result.add(input.substring(start, index));
                start = index;
                length = 0;
            }
            length += size;
            index += Character.charCount(point);
        }
        if (start < input.length() || result.isEmpty()) result.add(input.substring(start));
        return List.copyOf(result);
    }

    private static int bytes(String input) {
        return input.getBytes(StandardCharsets.UTF_8).length;
    }

    private static LlmConnectionException tooManyParts() {
        return new LlmConnectionException(
                "LLM_INPUT_TOO_LONG", "文本超过自动分段处理上限（512 段），请拆分源文件或知识内容后重试");
    }

    private record Part(String vector, int weight) {}
}
