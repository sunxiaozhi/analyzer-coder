package com.analyzercoder.application.llm;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleClient {
    private static final String PROBE_PROMPT = "Reply with exactly: CONNECTED";
    private final ObjectMapper json;
    private final LlmEndpointPolicy endpointPolicy;

    public OpenAiCompatibleClient(ObjectMapper json, LlmEndpointPolicy endpointPolicy) {
        this.json = json;
        this.endpointPolicy = endpointPolicy;
    }

    public ProbeResult probe(
        LlmProviderSpec spec,
        String apiKey,
        long deadlineNanos,
        AtomicBoolean canceled,
        StageSink sink
    ) {
        long stageStarted = System.nanoTime();
        sink.accept(StageResult.success("VALIDATE_CONFIG", elapsed(stageStarted)));

        stageStarted = System.nanoTime();
        URI baseUri;
        try {
            baseUri = endpointPolicy.validateAndResolve(spec.baseUrl());
            sink.accept(StageResult.success("RESOLVE_AND_AUTHORIZE_TARGET", elapsed(stageStarted)));
        } catch (LlmConnectionException exception) {
            sink.accept(StageResult.failed("RESOLVE_AND_AUTHORIZE_TARGET", elapsed(stageStarted), exception.code()));
            throw exception;
        }

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(spec.connectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        Long connectDuration;
        stageStarted = System.nanoTime();
        HttpResponse<String> modelsResponse;
        try {
            modelsResponse = client.send(
                request(baseUri, "/models", apiKey, deadlineNanos).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
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
                requireAllowedStatus(modelsResponse.statusCode(), modelsResponse.body());
            }
            sink.accept(StageResult.success("AUTHENTICATE", elapsed(stageStarted)));
        } catch (LlmConnectionException exception) {
            sink.accept(StageResult.failed("AUTHENTICATE", elapsed(stageStarted), exception.code()));
            throw exception;
        }

        if (modelsResponse.statusCode() >= 200 && modelsResponse.statusCode() < 300) {
            requireModelIfListProvided(modelsResponse.body(), spec.model());
        }

        stageStarted = System.nanoTime();
        try {
            String content = generate(client, baseUri, spec, apiKey, PROBE_PROMPT, deadlineNanos, canceled);
            if (content == null || content.isBlank()) {
                throw new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型返回了空响应");
            }
            sink.accept(StageResult.success("GENERATE_MINIMAL", elapsed(stageStarted)));
        } catch (LlmConnectionException exception) {
            sink.accept(StageResult.failed("GENERATE_MINIMAL", elapsed(stageStarted), exception.code()));
            throw exception;
        }

        Long firstTokenDuration = null;
        String degradedCode = null;
        if (spec.streamingEnabled()) {
            stageStarted = System.nanoTime();
            try {
                firstTokenDuration = streamFirstToken(
                    client, baseUri, spec, apiKey, PROBE_PROMPT, deadlineNanos, canceled
                );
                sink.accept(StageResult.success("STREAM_FIRST_TOKEN", elapsed(stageStarted)));
            } catch (LlmConnectionException exception) {
                degradedCode = exception.code();
                sink.accept(StageResult.failed("STREAM_FIRST_TOKEN", elapsed(stageStarted), exception.code()));
            }
        }
        return new ProbeResult(
            degradedCode == null ? "AVAILABLE" : "DEGRADED",
            degradedCode,
            degradedCode == null ? null : "基础生成可用，但流式输出检测失败",
            connectDuration,
            firstTokenDuration
        );
    }

    public String generate(LlmProviderSpec spec, String apiKey, String prompt) {
        URI baseUri = endpointPolicy.validateAndResolve(spec.baseUrl());
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(spec.connectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return generate(
            client,
            baseUri,
            spec,
            apiKey,
            prompt,
            System.nanoTime() + Duration.ofMillis(spec.requestTimeoutMs()).toNanos(),
            new AtomicBoolean(false)
        );
    }

    public String embed(
        String baseUrl,
        String model,
        String apiKey,
        String input,
        int dimension,
        int requestTimeoutMs
    ) {
        URI baseUri = endpointPolicy.validateAndResolve(baseUrl);
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(requestTimeoutMs, 10000)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        ObjectNode payload = json.createObjectNode();
        payload.put("model", model);
        payload.put("input", input);
        payload.put("dimensions", dimension);
        long deadline = System.nanoTime() + Duration.ofMillis(requestTimeoutMs).toNanos();
        try {
            HttpResponse<String> response = http.send(
                request(baseUri, "/embeddings", apiKey, deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            requireAllowedStatus(response.statusCode(), response.body());
            JsonNode values = json.readTree(response.body()).path("data").path(0).path("embedding");
            if (!values.isArray() || values.size() != dimension) {
                throw new LlmConnectionException(
                    "VECTOR_DIMENSION_INCOMPATIBLE",
                    "向量模型返回维度与当前索引不兼容，要求 " + dimension + " 维"
                );
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
        } catch (LlmConnectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw mapTransport(exception);
        }
    }

    private String generate(
        HttpClient client,
        URI baseUri,
        LlmProviderSpec spec,
        String apiKey,
        String prompt,
        long deadlineNanos,
        AtomicBoolean canceled
    ) {
        checkCanceled(canceled);
        ObjectNode payload = completionPayload(spec, prompt, false);
        try {
            HttpResponse<String> response = client.send(
                request(baseUri, "/chat/completions", apiKey, deadlineNanos)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            requireAllowedStatus(response.statusCode(), response.body());
            JsonNode root = json.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new LlmConnectionException("LLM_PROTOCOL_INVALID", "模型响应格式错误，缺少回答正文");
            }
            return content.asText();
        } catch (LlmConnectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw mapTransport(exception);
        }
    }

    private long streamFirstToken(
        HttpClient client,
        URI baseUri,
        LlmProviderSpec spec,
        String apiKey,
        String prompt,
        long deadlineNanos,
        AtomicBoolean canceled
    ) {
        long started = System.nanoTime();
        ObjectNode payload = completionPayload(spec, prompt, true);
        try {
            HttpResponse<Stream<String>> response = client.send(
                request(baseUri, "/chat/completions", apiKey, deadlineNanos)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build(),
                HttpResponse.BodyHandlers.ofLines()
            );
            requireAllowedStatus(response.statusCode(), "");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase().contains("text/event-stream")) {
                throw new LlmConnectionException("LLM_STREAM_UNSUPPORTED", "模型服务未返回流式响应");
            }
            try (Stream<String> lines = response.body()) {
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    checkCanceled(canceled);
                    String line = iterator.next();
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode root = json.readTree(data);
                    JsonNode content = root.path("choices").path(0).path("delta").path("content");
                    if (content.isTextual() && !content.asText().isEmpty()) return elapsed(started);
                }
            }
            throw new LlmConnectionException("LLM_STREAM_UNSUPPORTED", "事件流中没有模型增量内容");
        } catch (LlmConnectionException exception) {
            throw exception;
        } catch (Exception exception) {
            LlmConnectionException mapped = mapTransport(exception);
            if ("LLM_PROTOCOL_INVALID".equals(mapped.code())) {
                return throwStreamUnsupported(mapped);
            }
            throw mapped;
        }
    }

    private static long throwStreamUnsupported(Exception cause) {
        throw new LlmConnectionException("LLM_STREAM_UNSUPPORTED", "无法解析模型服务的流式响应", cause);
    }

    private ObjectNode completionPayload(LlmProviderSpec spec, String prompt, boolean stream) {
        ObjectNode payload = json.createObjectNode();
        payload.put("model", spec.model());
        payload.put("temperature", spec.temperature());
        payload.put("max_tokens", Math.min(spec.maxOutputTokens(), stream ? 16 : 64));
        payload.put("stream", stream);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        return payload;
    }

    private HttpRequest.Builder request(URI baseUri, String suffix, String apiKey, long deadlineNanos) {
        long remainingMillis = Math.max(1, Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri + suffix))
            .timeout(Duration.ofMillis(remainingMillis))
            .header("User-Agent", "analyzer-coder/0.1")
            .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        return builder;
    }

    private void requireModelIfListProvided(String body, String model) {
        try {
            JsonNode data = json.readTree(body).path("data");
            if (!data.isArray()) return;
            for (JsonNode item : data) {
                if (model.equals(item.path("id").asText())) return;
            }
            throw new LlmConnectionException("LLM_MODEL_NOT_FOUND", "模型服务未提供指定模型");
        } catch (LlmConnectionException exception) {
            throw exception;
        } catch (Exception ignored) {
            // Some compatible providers do not implement a models list. Generation remains authoritative.
        }
    }

    private static void requireAllowedStatus(int status, String body) {
        if (status >= 200 && status < 300) return;
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
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
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
        Long firstTokenDurationMs
    ) {}
}
