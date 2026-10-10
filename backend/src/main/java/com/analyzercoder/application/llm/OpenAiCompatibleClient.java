package com.analyzercoder.application.llm;

import com.analyzercoder.security.ApiSecurityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 调用 OpenAI 兼容的模型接口，统一构造请求并映射超时、协议及响应错误。 */
@Component
public class OpenAiCompatibleClient {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final ExecutorService STREAM_READERS =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "llm-stream-probe-reader");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final String PROBE_PROMPT = "Reply with exactly: CONNECTED";
    private final ObjectMapper json;
    private final LlmEndpointPolicy endpointPolicy;
    private final EmbeddingRequestCoordinator embeddingRequests;
    private final ConcurrentMap<ClientKey, HttpClient> httpClients = new ConcurrentHashMap<>();

    public OpenAiCompatibleClient(ObjectMapper json, LlmEndpointPolicy endpointPolicy) {
        this(json, endpointPolicy, EmbeddingRequestCoordinator.unmanaged());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiCompatibleClient(
            ObjectMapper json,
            LlmEndpointPolicy endpointPolicy,
            EmbeddingRequestCoordinator embeddingRequests) {
        this.json = json;
        this.endpointPolicy = endpointPolicy;
        this.embeddingRequests = embeddingRequests;
    }

    public ProbeResult probe(
            LlmProviderSpec spec,
            String apiKey,
            long deadlineNanos,
            AtomicBoolean canceled,
            StageSink sink) {
        long stageStarted = System.nanoTime();
        sink.accept(StageResult.success("VALIDATE_CONFIG", elapsed(stageStarted)));

        stageStarted = System.nanoTime();
        URI baseUri;
        try {
            baseUri = endpointPolicy.validateAndResolve(spec.baseUrl());
            sink.accept(StageResult.success("RESOLVE_AND_AUTHORIZE_TARGET", elapsed(stageStarted)));
        } catch (LlmConnectionException exception) {
            sink.accept(
                    StageResult.failed(
                            "RESOLVE_AND_AUTHORIZE_TARGET",
                            elapsed(stageStarted),
                            exception.code()));
            throw exception;
        }

        HttpClient client = httpClient(baseUri, spec.connectTimeoutMs());

        Long connectDuration;
        stageStarted = System.nanoTime();
        HttpResponse<String> modelsResponse;
        try {
            modelsResponse =
                    send(
                            client,
                            request(baseUri, "/models", apiKey, deadlineNanos).GET().build(),
                            deadlineNanos);
            connectDuration = elapsed(stageStarted);
            sink.accept(StageResult.success("CONNECT_TLS", connectDuration));
        } catch (Exception exception) {
            LlmConnectionException mapped = mapTransport(exception);
            sink.accept(StageResult.failed("CONNECT_TLS", elapsed(stageStarted), mapped.code()));
            throw mapped;
        }
        checkCanceled(canceled);

        stageStarted = System.nanoTime();
        try {
            if (modelsResponse.statusCode() != 404 && modelsResponse.statusCode() != 405) {
                requireResponseStatus(modelsResponse, apiKey, false, "模型列表服务");
            }
            sink.accept(StageResult.success("AUTHENTICATE", elapsed(stageStarted)));
        } catch (LlmConnectionException exception) {
            sink.accept(
                    StageResult.failed("AUTHENTICATE", elapsed(stageStarted), exception.code()));
            throw exception;
        }

        if (modelsResponse.statusCode() >= 200 && modelsResponse.statusCode() < 300) {
            requireModelIfListProvided(modelsResponse.body(), spec.model());
        }

        stageStarted = System.nanoTime();
        try {
            String content =
                    generate(
                            client,
                            baseUri,
                            spec,
                            apiKey,
                            PROBE_PROMPT,
                            deadlineNanos,
                            canceled,
                            true);
            if (content == null || content.isBlank()) {
                throw new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型返回了空响应");
            }
            sink.accept(StageResult.success("GENERATE_MINIMAL", elapsed(stageStarted)));
        } catch (LlmConnectionException exception) {
            sink.accept(
                    StageResult.failed(
                            "GENERATE_MINIMAL", elapsed(stageStarted), exception.code()));
            throw exception;
        }

        Long firstTokenDuration = null;
        String degradedCode = null;
        String degradedSummary = null;
        if (spec.streamingEnabled()) {
            stageStarted = System.nanoTime();
            try {
                firstTokenDuration =
                        streamFirstToken(
                                client,
                                baseUri,
                                spec,
                                apiKey,
                                PROBE_PROMPT,
                                deadlineNanos,
                                canceled);
                sink.accept(StageResult.success("STREAM_FIRST_TOKEN", elapsed(stageStarted)));
            } catch (LlmConnectionException exception) {
                degradedCode = exception.code();
                degradedSummary =
                        LlmFailureMessages.safe(
                                "基础生成可用，流式检测失败 ["
                                        + exception.code()
                                        + "]："
                                        + exception.getMessage(),
                                apiKey);
                sink.accept(
                        StageResult.failed(
                                "STREAM_FIRST_TOKEN", elapsed(stageStarted), exception.code()));
            }
        }
        return new ProbeResult(
                degradedCode == null ? "AVAILABLE" : "DEGRADED",
                degradedCode,
                degradedSummary,
                connectDuration,
                firstTokenDuration);
    }

    public String generate(LlmProviderSpec spec, String apiKey, String prompt) {
        URI baseUri = endpointPolicy.validateAndResolve(spec.baseUrl());
        HttpClient client = httpClient(baseUri, spec.connectTimeoutMs());
        return generate(
                client,
                baseUri,
                spec,
                apiKey,
                prompt,
                System.nanoTime() + Duration.ofMillis(spec.requestTimeoutMs()).toNanos(),
                new AtomicBoolean(false),
                false);
    }

    public String embed(
            String baseUrl,
            String model,
            String apiKey,
            String input,
            int dimension,
            int requestTimeoutMs) {
        URI baseUri = endpointPolicy.validateAndResolve(baseUrl);
        HttpClient http = httpClient(baseUri, Math.min(requestTimeoutMs, 10000));
        ObjectNode payload = json.createObjectNode();
        payload.put("model", model);
        payload.put("input", input);
        payload.put("dimensions", dimension);
        payload.put("encoding_format", "float");
        long deadline = System.nanoTime() + Duration.ofMillis(requestTimeoutMs).toNanos();
        long started = System.nanoTime();
        String callId = UUID.randomUUID().toString();
        logStart(
                callId,
                "EMBEDDING",
                baseUri,
                model,
                "inputs=1, characters="
                        + input.length()
                        + ", dimension="
                        + dimension
                        + ", timeoutMs="
                        + requestTimeoutMs,
                apiKey);
        try {
            HttpResponse<String> response =
                    sendEmbeddings(http, baseUri, apiKey, payload, deadline, callId);
            requireEmbeddingStatus(response, apiKey, false);
            String vector =
                    embeddingVector(
                            json.readTree(response.body()).path("data").path(0).path("embedding"),
                            dimension);
            logSuccess(
                    callId, "EMBEDDING", model, requestTimeoutMs, started, response.statusCode());
            return vector;
        } catch (LlmConnectionException exception) {
            throw embeddingFailure(
                    exception,
                    model,
                    List.of(input),
                    dimension,
                    requestTimeoutMs,
                    started,
                    apiKey,
                    callId,
                    "EMBEDDING");
        } catch (ApiSecurityException | DataAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw embeddingFailure(
                    mapTransport(exception),
                    model,
                    List.of(input),
                    dimension,
                    requestTimeoutMs,
                    started,
                    apiKey,
                    callId,
                    "EMBEDDING");
        }
    }

    public List<String> embedBatch(
            String baseUrl,
            String model,
            String apiKey,
            List<String> inputs,
            int dimension,
            int requestTimeoutMs) {
        if (inputs.isEmpty()) return List.of();
        URI baseUri = endpointPolicy.validateAndResolve(baseUrl);
        HttpClient http = httpClient(baseUri, Math.min(requestTimeoutMs, 10000));
        ObjectNode payload = json.createObjectNode();
        payload.put("model", model);
        payload.set("input", json.valueToTree(inputs));
        payload.put("dimensions", dimension);
        payload.put("encoding_format", "float");
        long deadline = System.nanoTime() + Duration.ofMillis(requestTimeoutMs).toNanos();
        long started = System.nanoTime();
        String callId = UUID.randomUUID().toString();
        logStart(
                callId,
                "EMBEDDING_BATCH",
                baseUri,
                model,
                "inputs="
                        + inputs.size()
                        + ", characters="
                        + inputs.stream().mapToLong(String::length).sum()
                        + ", longestInput="
                        + inputs.stream().mapToInt(String::length).max().orElse(0)
                        + ", dimension="
                        + dimension
                        + ", timeoutMs="
                        + requestTimeoutMs,
                apiKey);
        try {
            HttpResponse<String> response =
                    sendEmbeddings(http, baseUri, apiKey, payload, deadline, callId);
            requireEmbeddingStatus(response, apiKey, true);
            JsonNode data = json.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != inputs.size()) {
                throw new LlmConnectionException("LLM_BATCH_UNSUPPORTED", "向量服务返回的批量结果数量不匹配");
            }
            String[] vectors = new String[inputs.size()];
            for (int position = 0; position < data.size(); position++) {
                JsonNode item = data.get(position);
                JsonNode indexNode = item.path("index");
                if (!indexNode.isMissingNode()
                        && (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt())) {
                    throw new LlmConnectionException(
                            "LLM_BATCH_UNSUPPORTED", "向量服务返回的批量结果 index 必须为整数");
                }
                int index = indexNode.isMissingNode() ? position : indexNode.asInt();
                if (index < 0 || index >= vectors.length || vectors[index] != null) {
                    throw new LlmConnectionException("LLM_BATCH_UNSUPPORTED", "向量服务返回的批量结果顺序无效");
                }
                vectors[index] = embeddingVector(item.path("embedding"), dimension);
            }
            for (String vector : vectors) {
                if (vector == null) {
                    throw new LlmConnectionException("LLM_BATCH_UNSUPPORTED", "向量服务返回的批量结果顺序无效");
                }
            }
            logSuccess(
                    callId,
                    "EMBEDDING_BATCH",
                    model,
                    requestTimeoutMs,
                    started,
                    response.statusCode());
            return List.of(vectors);
        } catch (LlmConnectionException exception) {
            throw embeddingFailure(
                    exception,
                    model,
                    inputs,
                    dimension,
                    requestTimeoutMs,
                    started,
                    apiKey,
                    callId,
                    "EMBEDDING_BATCH");
        } catch (ApiSecurityException | DataAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw embeddingFailure(
                    mapTransport(exception),
                    model,
                    inputs,
                    dimension,
                    requestTimeoutMs,
                    started,
                    apiKey,
                    callId,
                    "EMBEDDING_BATCH");
        }
    }

    private void requireEmbeddingStatus(
            HttpResponse<String> response, String apiKey, boolean batch) {
        requireResponseStatus(response, apiKey, batch, "向量服务");
    }

    private void requireResponseStatus(
            HttpResponse<String> response, String apiKey, boolean batch, String service) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        String code;
        if (batch && (status == 400 || status == 413 || status == 422)) {
            code = "LLM_BATCH_UNSUPPORTED";
        } else {
            try {
                requireAllowedStatus(status, response.body());
                return;
            } catch (LlmConnectionException failure) {
                code = failure.code();
            }
        }
        String reason = "服务未提供可识别的错误说明";
        String providerCode = "";
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode error = root.path("error");
            JsonNode detail = root.path("detail");
            providerCode = error.path("code").asText("") + " " + root.path("code").asText("");
            if (error.path("message").isTextual()) reason = error.path("message").asText();
            else if (error.isTextual()) reason = error.asText();
            else if (root.path("message").isTextual()) reason = root.path("message").asText();
            else if (detail.isTextual()) reason = detail.asText();
            else if (detail.isArray()) {
                StringBuilder messages = new StringBuilder();
                for (JsonNode item : detail) {
                    if (item.path("msg").isTextual()) {
                        if (!messages.isEmpty()) messages.append("；");
                        messages.append(item.path("msg").asText());
                    }
                }
                if (!messages.isEmpty()) reason = messages.toString();
            }
        } catch (IOException | RuntimeException ignored) {
            // Do not expose an HTML error page or arbitrary response body.
        }
        if ((status == 400 || status == 413 || status == 422)
                && isInputTooLong(providerCode + " " + reason)) code = "LLM_INPUT_TOO_LONG";
        reason = LlmFailureMessages.safe(reason, apiKey);
        if (reason.length() > 500) reason = reason.substring(0, 499) + "…";
        throw new LlmConnectionException(code, service + " HTTP " + status + "：" + reason);
    }

    private static boolean isInputTooLong(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context_length_exceeded")
                || lower.contains("maximum context length")
                || lower.contains("context length exceeded")
                || lower.contains("context length is only")
                || lower.contains("maximum input length")
                || lower.contains("max input tokens")
                || lower.contains("maximum batch tokens exceeded")
                || lower.contains("input is too long")
                || lower.contains("input too long")
                || lower.contains("too many tokens")
                || lower.contains("token limit exceeded")
                || lower.contains("exceeds the token limit")
                || lower.contains("exceed the token limit")
                || lower.contains("超过模型最大输入")
                || lower.contains("输入长度超过")
                || lower.contains("上下文长度超过");
    }

    private static LlmConnectionException embeddingFailure(
            LlmConnectionException failure,
            String model,
            List<String> inputs,
            int dimension,
            int timeoutMs,
            long started,
            String apiKey,
            String callId,
            String operation) {
        long characters = inputs.stream().mapToLong(String::length).sum();
        int longest = inputs.stream().mapToInt(String::length).max().orElse(0);
        String advice =
                switch (failure.code()) {
                    case "LLM_TIMEOUT" -> "；建议检查服务负载和网络，适当增大请求超时后重试";
                    case "LLM_INPUT_TOO_LONG" -> "；输入超过模型上限，请缩小输入片段或使用更长上下文的模型；增大超时无法解决此错误";
                    case "LLM_RATE_LIMITED" -> "；建议等待服务限流恢复后重试";
                    case "VECTOR_DIMENSION_INCOMPATIBLE" -> "；请核对当前模型实际输出维度";
                    default -> "";
                };
        String message =
                failure.getMessage()
                        + "；模型="
                        + model
                        + "，配置维度="
                        + dimension
                        + "，输入条数="
                        + inputs.size()
                        + "，总字符数="
                        + characters
                        + "，最长输入字符数="
                        + longest
                        + "，请求超时="
                        + timeoutMs
                        + "ms，耗时="
                        + elapsed(started)
                        + "ms"
                        + advice;
        return logFailure(
                callId,
                operation,
                new LlmConnectionException(
                        failure.code(), LlmFailureMessages.safe(message, apiKey), failure),
                apiKey);
    }

    private HttpResponse<String> sendEmbeddings(
            HttpClient http,
            URI baseUri,
            String apiKey,
            ObjectNode payload,
            long deadline,
            String callId)
            throws IOException, InterruptedException {
        long waitedBefore = EmbeddingRequestScope.waitedNanos();
        int rateLimitRetries = 0;
        while (true) {
            long effectiveDeadline = deadline + EmbeddingRequestScope.waitedNanos() - waitedBefore;
            embeddingRequests.acquire(baseUri, apiKey, effectiveDeadline, callId);
            effectiveDeadline = deadline + EmbeddingRequestScope.waitedNanos() - waitedBefore;
            if (effectiveDeadline <= System.nanoTime())
                throw new HttpTimeoutException("Embedding response budget expired");
            HttpResponse<String> response =
                    send(
                            http,
                            request(baseUri, "/embeddings", apiKey, effectiveDeadline)
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    json.writeValueAsString(payload)))
                                    .build(),
                            effectiveDeadline);
            if (response.statusCode() == 429) {
                long cooldownMs =
                        embeddingRequests.rateLimited(
                                baseUri,
                                apiKey,
                                response.headers().firstValue("Retry-After").orElse(null));
                boolean retry =
                        EmbeddingRequestScope.canWaitAndRetry()
                                && rateLimitRetries < embeddingRequests.retries();
                LOG.warn(
                        "向量服务限流: {}, callId={}, retry={}, retryAttempt={}, maxRetries={}, cooldownMs={}",
                        ModelCallLogContext.fields(),
                        callId,
                        retry,
                        rateLimitRetries,
                        embeddingRequests.retries(),
                        cooldownMs);
                if (!retry) return response;
                rateLimitRetries++;
                continue;
            }
            if (payload.has("dimensions") && rejectsDimensions(response)) {
                LOG.warn(
                        "模型参数兼容重试: {}, callId={}, parameter=dimensions, httpStatus={}, remainingMs={}",
                        ModelCallLogContext.fields(),
                        callId,
                        response.statusCode(),
                        Math.max(
                                0,
                                Duration.ofNanos(effectiveDeadline - System.nanoTime())
                                        .toMillis()));
                payload.remove("dimensions");
                continue;
            }
            return response;
        }
    }

    private boolean rejectsDimensions(HttpResponse<String> response) {
        if (response.statusCode() != 400 && response.statusCode() != 422) return false;
        try {
            JsonNode error = json.readTree(response.body()).path("error");
            String message =
                    (error.isTextual() ? error.asText() : error.path("message").asText())
                            .toLowerCase(Locale.ROOT);
            boolean mentionsDimensions =
                    message.contains("dimension") || message.contains("matryoshka");
            boolean unsupported =
                    message.contains("not support")
                            || message.contains("unsupported")
                            || message.contains("not allowed")
                            || message.contains("not permitted")
                            || message.contains("unrecognized")
                            || message.contains("unknown parameter");
            return mentionsDimensions && unsupported;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static String embeddingVector(JsonNode values, int dimension) {
        if (!values.isArray()) {
            throw new LlmConnectionException(
                    "LLM_PROTOCOL_INVALID", "向量响应缺少 data[].embedding 数值数组");
        }
        if (values.size() != dimension) {
            throw new LlmConnectionException(
                    "VECTOR_DIMENSION_INCOMPATIBLE",
                    "向量模型返回维度不匹配，配置 " + dimension + " 维，实际 " + values.size() + " 维");
        }
        StringBuilder vector = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                throw new LlmConnectionException("LLM_PROTOCOL_INVALID", "向量模型返回了无效数值");
            }
            if (index > 0) vector.append(',');
            vector.append(value.asDouble());
        }
        return vector.append(']').toString();
    }

    HttpClient httpClient(URI baseUri, long connectTimeoutMs) {
        ClientKey key =
                new ClientKey(
                        baseUri, connectTimeoutMs, endpointPolicy.skipTlsVerification(baseUri));
        return httpClients.computeIfAbsent(key, this::createHttpClient);
    }

    private HttpClient createHttpClient(ClientKey key) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(key.connectTimeoutMs()))
                        .followRedirects(HttpClient.Redirect.NEVER);
        if (key.skipTlsVerification()) {
            builder.sslContext(EndpointTlsContext.insecureForExplicitException());
        }
        return builder.build();
    }

    private record ClientKey(URI baseUri, long connectTimeoutMs, boolean skipTlsVerification) {}

    private String generate(
            HttpClient client,
            URI baseUri,
            LlmProviderSpec spec,
            String apiKey,
            String prompt,
            long deadlineNanos,
            AtomicBoolean canceled,
            boolean probe) {
        checkCanceled(canceled);
        int outputLimit = spec.maxOutputTokens();
        ObjectNode payload = completionPayload(spec, prompt, false, outputLimit);
        long remainingBudgetMs =
                Math.max(1, Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis());
        long started = System.nanoTime();
        String callId = UUID.randomUUID().toString();
        logStart(
                callId,
                probe ? "CHAT_PROBE" : "CHAT",
                baseUri,
                spec.model(),
                "characters="
                        + prompt.length()
                        + ", maxOutputTokens="
                        + outputLimit
                        + ", connectTimeoutMs="
                        + spec.connectTimeoutMs()
                        + ", timeoutMs="
                        + spec.requestTimeoutMs()
                        + ", remainingMs="
                        + remainingBudgetMs,
                apiKey);
        try {
            HttpResponse<String> response =
                    send(
                            client,
                            request(baseUri, "/chat/completions", apiKey, deadlineNanos)
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    json.writeValueAsString(payload)))
                                    .build(),
                            deadlineNanos);
            requireResponseStatus(response, apiKey, false, "问答服务");
            JsonNode root = json.readTree(response.body());
            if (root == null) throw new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型返回了空响应体");
            JsonNode choice = root.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText();
            if ("length".equals(finishReason))
                throw new LlmConnectionException(
                        "LLM_OUTPUT_TRUNCATED", "模型达到输出上限，回答未完成；请提高输出 Token 上限或缩小问题范围");
            if ("content_filter".equals(finishReason))
                throw new LlmConnectionException("LLM_OUTPUT_FILTERED", "模型服务拦截了回答，请调整问题后重试");
            JsonNode content = choice.path("message").path("content");
            String answer = completionText(content);
            if (answer.isBlank())
                throw new LlmConnectionException(
                        "LLM_PROTOCOL_INVALID", "模型响应缺少非空回答正文，不能将推理过程作为回答");
            logSuccess(
                    callId,
                    probe ? "CHAT_PROBE" : "CHAT",
                    spec.model(),
                    spec.requestTimeoutMs(),
                    started,
                    response.statusCode());
            return answer;
        } catch (Exception exception) {
            LlmConnectionException failure =
                    exception instanceof LlmConnectionException known
                            ? known
                            : mapTransport(exception);
            String context =
                    failure.getMessage()
                            + "；模型="
                            + spec.model()
                            + "，输入字符数="
                            + prompt.length()
                            + "，输出上限="
                            + outputLimit
                            + "，请求超时="
                            + spec.requestTimeoutMs()
                            + "ms，本次剩余预算="
                            + remainingBudgetMs
                            + "ms，耗时="
                            + elapsed(started)
                            + "ms";
            throw logFailure(
                    callId,
                    probe ? "CHAT_PROBE" : "CHAT",
                    new LlmConnectionException(failure.code(), context, failure),
                    apiKey);
        }
    }

    private static String completionText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText()) && part.path("text").isTextual())
                    text.append(part.path("text").asText());
            }
            return text.toString();
        }
        return "";
    }

    private long streamFirstToken(
            HttpClient client,
            URI baseUri,
            LlmProviderSpec spec,
            String apiKey,
            String prompt,
            long deadlineNanos,
            AtomicBoolean canceled) {
        long started = System.nanoTime();
        ObjectNode payload =
                completionPayload(spec, prompt, true, Math.min(spec.maxOutputTokens(), 16));
        String callId = UUID.randomUUID().toString();
        logStart(
                callId,
                "STREAM_PROBE",
                baseUri,
                spec.model(),
                "characters="
                        + prompt.length()
                        + ", remainingMs="
                        + Math.max(
                                0, Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis()),
                apiKey);
        try {
            HttpRequest outbound =
                    request(baseUri, "/chat/completions", apiKey, deadlineNanos)
                            .header("Accept", "text/event-stream")
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(payload)))
                            .build();
            HttpResponse<Stream<String>> response =
                    await(
                            client.sendAsync(outbound, HttpResponse.BodyHandlers.ofLines()),
                            deadlineNanos);
            try (Stream<String> lines = response.body()) {
                requireAllowedStatus(response.statusCode(), "");
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).contains("text/event-stream"))
                    throw new LlmConnectionException("LLM_STREAM_UNSUPPORTED", "模型服务未返回流式响应");
                long firstTokenMs =
                        await(
                                STREAM_READERS.submit(
                                        () -> readFirstToken(lines, started, canceled)),
                                deadlineNanos);
                logSuccess(
                        callId,
                        "STREAM_PROBE",
                        spec.model(),
                        spec.requestTimeoutMs(),
                        started,
                        response.statusCode());
                return firstTokenMs;
            }
        } catch (Exception exception) {
            LlmConnectionException mapped =
                    exception instanceof LlmConnectionException known
                            ? known
                            : mapTransport(exception);
            if ("LLM_PROTOCOL_INVALID".equals(mapped.code()))
                mapped =
                        new LlmConnectionException(
                                "LLM_STREAM_UNSUPPORTED", "无法解析模型服务的流式响应", mapped);
            throw logFailure(
                    callId,
                    "STREAM_PROBE",
                    new LlmConnectionException(
                            mapped.code(),
                            mapped.getMessage()
                                    + "；模型="
                                    + spec.model()
                                    + "，耗时="
                                    + elapsed(started)
                                    + "ms",
                            mapped),
                    apiKey);
        }
    }

    private long readFirstToken(Stream<String> lines, long started, AtomicBoolean canceled)
            throws IOException {
        var iterator = lines.iterator();
        while (iterator.hasNext()) {
            checkCanceled(canceled);
            String line = iterator.next();
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            if ("[DONE]".equals(data)) break;
            JsonNode root = json.readTree(data);
            String text =
                    completionText(root.path("choices").path(0).path("delta").path("content"));
            if (!text.isEmpty()) return elapsed(started);
        }
        throw new LlmConnectionException("LLM_STREAM_UNSUPPORTED", "事件流中没有模型增量内容");
    }

    // Bound the complete response, including a stalled body after HTTP 200 headers.
    private static HttpResponse<String> send(HttpClient client, HttpRequest request, long deadline)
            throws IOException, InterruptedException {
        return await(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()), deadline);
    }

    private static <T> T await(Future<T> pending, long deadline)
            throws IOException, InterruptedException {
        try {
            return pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new HttpTimeoutException("模型服务完整响应超时");
        } catch (InterruptedException exception) {
            pending.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("模型服务通信失败", cause);
        }
    }

    private static void logStart(
            String callId,
            String operation,
            URI target,
            String model,
            String metadata,
            String apiKey) {
        LOG.info(
                "模型调用开始: {}, callId={}, operation={}, endpointHost={}, model={}, {}",
                ModelCallLogContext.fields(),
                callId,
                operation,
                target.getHost(),
                LlmFailureMessages.safe(model, apiKey),
                metadata);
    }

    private static void logSuccess(
            String callId, String operation, String model, int timeout, long started, int status) {
        LOG.info(
                "模型调用成功: {}, callId={}, operation={}, model={}, httpStatus={}, timeoutMs={}, elapsedMs={}",
                ModelCallLogContext.fields(),
                callId,
                operation,
                LlmFailureMessages.safe(model),
                status,
                timeout,
                elapsed(started));
    }

    private static LlmConnectionException logFailure(
            String callId, String operation, LlmConnectionException failure, String apiKey) {
        String message =
                "调用ID=" + callId + "；" + LlmFailureMessages.safe(failure.getMessage(), apiKey);
        String causes = causeTypes(failure);
        if ("LLM_BATCH_UNSUPPORTED".equals(failure.code())
                || "LLM_CHECK_CANCELED".equals(failure.code())) {
            LOG.warn(
                    "模型调用失败: {}, callId={}, operation={}, code={}, causeTypes={}, detail={}",
                    ModelCallLogContext.fields(),
                    callId,
                    operation,
                    failure.code(),
                    causes,
                    message);
        } else {
            LOG.error(
                    "模型调用失败: {}, callId={}, operation={}, code={}, causeTypes={}, detail={}",
                    ModelCallLogContext.fields(),
                    callId,
                    operation,
                    failure.code(),
                    causes,
                    message);
        }
        return new LlmConnectionException(failure.code(), message, failure);
    }

    private static String causeTypes(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; failure != null && depth < 12; depth++) {
            if (!result.isEmpty()) result.append(" -> ");
            result.append(failure.getClass().getSimpleName());
            Throwable next = failure.getCause();
            if (next == failure) break;
            failure = next;
        }
        return result.toString();
    }

    private ObjectNode completionPayload(
            LlmProviderSpec spec, String prompt, boolean stream, int outputLimit) {
        ObjectNode payload = json.createObjectNode();
        payload.put("model", spec.model());
        payload.put("temperature", spec.temperature());
        payload.put("max_tokens", outputLimit);
        payload.put("stream", stream);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        return payload;
    }

    private HttpRequest.Builder request(
            URI baseUri, String suffix, String apiKey, long deadlineNanos) {
        long remainingMillis =
                Math.max(1, Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis());
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUri + suffix))
                        .timeout(Duration.ofMillis(remainingMillis))
                        .header("User-Agent", "analyzer-coder/0.1")
                        .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    private void requireModelIfListProvided(String body, String model) {
        try {
            JsonNode data = json.readTree(body).path("data");
            if (!data.isArray()) {
                return;
            }
            for (JsonNode item : data) {
                if (model.equals(item.path("id").asText())) {
                    return;
                }
            }
            throw new LlmConnectionException("LLM_MODEL_NOT_FOUND", "模型服务未提供指定模型");
        } catch (LlmConnectionException exception) {
            throw exception;
        } catch (Exception ignored) {
            // Some compatible providers do not implement a models list. Generation remains
            // authoritative.
        }
    }

    private static void requireAllowedStatus(int status, String body) {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new LlmConnectionException("LLM_AUTH_FAILED", "模型服务拒绝了当前凭据");
        }
        if (status == 429) {
            throw new LlmConnectionException("LLM_RATE_LIMITED", "模型服务当前请求过多，请稍后重试");
        }
        if (status == 404 && body != null && body.toLowerCase().contains("model")) {
            throw new LlmConnectionException("LLM_MODEL_NOT_FOUND", "模型服务未找到指定模型");
        }
        throw new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型服务返回了不支持的 HTTP 状态码：" + status);
    }

    private static LlmConnectionException mapTransport(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        if (exception instanceof InterruptedException || cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new LlmConnectionException("LLM_CHECK_CANCELED", "连接检测已取消", exception);
        }
        if (exception instanceof HttpTimeoutException || cause instanceof HttpTimeoutException) {
            return new LlmConnectionException("LLM_TIMEOUT", "模型服务连接或响应超时", exception);
        }
        if (exception instanceof SSLException || cause instanceof SSLException) {
            return new LlmConnectionException("LLM_TLS_FAILED", "模型服务 TLS 校验失败", exception);
        }
        if (exception instanceof ConnectException || cause instanceof ConnectException) {
            return new LlmConnectionException("LLM_CONNECTION_FAILED", "无法连接模型服务", exception);
        }
        if (exception instanceof IOException || cause instanceof IOException) {
            return new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型服务通信失败", exception);
        }
        return new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型服务响应无法解析", exception);
    }

    private static void checkCanceled(AtomicBoolean canceled) {
        if (canceled.get() || Thread.currentThread().isInterrupted()) {
            throw new LlmConnectionException("LLM_CHECK_CANCELED", "连接检测已取消");
        }
    }

    private static long elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    public interface StageSink {
        /**
         * 接受当前候选结果并推进后续处理。
         *
         * @param result 外部进程执行得到的退出状态与输出
         */
        void accept(StageResult result);
    }

    public record StageResult(String stage, String status, long durationMs, String errorCode) {
        public static StageResult success(String stage, long durationMs) {
            return new StageResult(stage, "SUCCEEDED", durationMs, null);
        }

        public static StageResult failed(String stage, long durationMs, String errorCode) {
            return new StageResult(stage, "FAILED", durationMs, errorCode);
        }
    }

    public record ProbeResult(
            String availability,
            String errorCode,
            String errorSummary,
            Long connectDurationMs,
            Long firstTokenDurationMs) {}
}
