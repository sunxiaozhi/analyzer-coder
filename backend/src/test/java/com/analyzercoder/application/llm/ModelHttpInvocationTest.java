package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelHttpInvocationTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService handlers;
    private OpenAiCompatibleClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext(
                "/v1/models",
                exchange -> respond(exchange, 200, "{\"data\":[{\"id\":\"chat-model\"}]}"));
        server.start();
        client =
                new OpenAiCompatibleClient(
                        json,
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private LlmProviderSpec spec(int timeout, int output, boolean stream) {
        return new LlmProviderSpec(
                null,
                1,
                "chat",
                "OPENAI_COMPATIBLE",
                url(),
                "chat-model",
                1000,
                timeout,
                output,
                0.2,
                stream,
                null,
                "test");
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static String answer(String content, String reason) {
        return "{\"choices\":[{\"finish_reason\":\""
                + reason
                + "\",\"message\":{\"content\":"
                + content
                + "}}]}";
    }

    @Test
    void exposesProviderErrorAndScaleWithoutSendingOrEchoingCredentialsInDiagnostics() {
        server.createContext(
                "/v1/chat/completions",
                exchange ->
                        respond(
                                exchange,
                                400,
                                "{\"error\":{\"message\":\"context length exceeded; received private-api-key; Bearer private-header\"}}"));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                client.generate(
                                        spec(5000, 8192, false),
                                        "private-api-key",
                                        "private source code"));
        assertThat(failure.getMessage())
                .contains(
                        "HTTP 400",
                        "context length exceeded",
                        "模型=chat-model",
                        "输出上限=8192",
                        "请求超时=5000ms",
                        "耗时=",
                        "调用ID=")
                .doesNotContain("private-api-key", "private-header", "private source code");
    }

    @Test
    void probeAndGenerationHonorTheSameConfiguredOutputLimitAndCredentials() {
        List<JsonNode> payloads = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    payloads.add(json.readTree(exchange.getRequestBody()));
                    authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, 200, answer("\"CONNECTED\"", "stop"));
                });
        client.probe(
                spec(5000, 8192, false),
                "chat-key",
                System.nanoTime() + Duration.ofSeconds(5).toNanos(),
                new AtomicBoolean(),
                stage -> {});
        assertThat(client.generate(spec(5000, 8192, false), "chat-key", "question"))
                .isEqualTo("CONNECTED");
        assertThat(payloads)
                .hasSize(2)
                .allSatisfy(
                        payload -> {
                            assertThat(payload.path("max_tokens").asInt()).isEqualTo(8192);
                            assertThat(payload.path("stream").asBoolean()).isFalse();
                        });
        assertThat(authorization).containsExactly("Bearer chat-key", "Bearer chat-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \"", "[]"})
    void rejectsEmptyContentEvenWhenTheHttpStatusIsSuccessful(String content) {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(exchange, 200, answer(content, "stop")));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () -> client.generate(spec(5000, 2048, false), "", "question"));
        assertThat(failure.code()).isEqualTo("LLM_PROTOCOL_INVALID");
        assertThat(failure.getMessage()).contains("非空回答正文");
    }

    @Test
    void supportsTextPartsButNeverSubstitutesReasoningForTheAnswer() {
        server.createContext(
                "/v1/chat/completions",
                exchange ->
                        respond(
                                exchange,
                                200,
                                answer(
                                        "[{\"type\":\"text\",\"text\":\"第一段\"},{\"type\":\"text\",\"text\":\"第二段\"}]",
                                        "stop")));
        assertThat(client.generate(spec(5000, 2048, false), "", "question")).isEqualTo("第一段第二段");
        server.removeContext("/v1/chat/completions");
        server.createContext(
                "/v1/chat/completions",
                exchange ->
                        respond(
                                exchange,
                                200,
                                "{\"choices\":[{\"message\":{\"reasoning_content\":\"private reasoning\",\"content\":null}}]}"));
        assertThat(
                        assertThrows(
                                        LlmConnectionException.class,
                                        () ->
                                                client.generate(
                                                        spec(5000, 2048, false), "", "question"))
                                .getMessage())
                .doesNotContain("private reasoning");
    }

    @Test
    void rejectsTruncatedAnswersInsteadOfPassingThemToCitationValidation() {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(exchange, 200, answer("\"unfinished [S1]\"", "length")));
        assertThat(
                        assertThrows(
                                        LlmConnectionException.class,
                                        () ->
                                                client.generate(
                                                        spec(5000, 2048, false), "", "question"))
                                .code())
                .isEqualTo("LLM_OUTPUT_TRUNCATED");
    }

    @Test
    void timesOutWhenChatHeadersSucceedButTheBodyStalls() {
        server.createContext("/v1/chat/completions", this::stallBody);
        long started = System.nanoTime();
        var failure =
                assertThrows(
                        LlmConnectionException.class,
                        () -> client.generate(spec(500, 2048, false), "", "question"));
        assertThat(failure.code()).isEqualTo("LLM_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2500);
    }

    @Test
    void timesOutWhenEmbeddingHeadersSucceedButTheBodyStalls() {
        server.createContext("/v1/embeddings", this::stallBody);
        long started = System.nanoTime();
        var failure =
                assertThrows(
                        LlmConnectionException.class,
                        () -> client.embed(url(), "vector-model", "", "source", 64, 500));
        assertThat(failure.code()).isEqualTo("LLM_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2500);
    }

    @Test
    void endsAStalledStreamProbeWithinItsTotalDeadline() {
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    JsonNode request = json.readTree(exchange.getRequestBody());
                    if (request.path("stream").asBoolean()) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        stallBody(exchange);
                    } else respond(exchange, 200, answer("\"CONNECTED\"", "stop"));
                });
        long started = System.nanoTime();
        var result =
                client.probe(
                        spec(5000, 2048, true),
                        "",
                        started + Duration.ofMillis(800).toNanos(),
                        new AtomicBoolean(),
                        stage -> {});
        assertThat(result.availability()).isEqualTo("DEGRADED");
        assertThat(result.errorCode()).isEqualTo("LLM_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2500);
    }

    @Test
    void questionsAndVectorsUseTheirOwnSavedEndpointModelAndSecret() {
        var mapper =
                org.mockito.Mockito.mock(
                        com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper
                                .class);
        var cipher = org.mockito.Mockito.mock(LlmSecretCipher.class);
        java.util.UUID chatId = java.util.UUID.randomUUID(),
                chatSecret = java.util.UUID.randomUUID(),
                vectorSecret = java.util.UUID.randomUUID();
        String base = url().substring(0, url().length() - 3);
        org.mockito.Mockito.when(mapper.config(chatId))
                .thenReturn(
                        java.util.Map.of(
                                "id",
                                chatId,
                                "name",
                                "chat",
                                "model",
                                "chat-model",
                                "base_url",
                                base + "/chat/v1",
                                "secret_version_id",
                                chatSecret,
                                "availability",
                                "AVAILABLE",
                                "breaker_state",
                                "CLOSED"));
        org.mockito.Mockito.when(mapper.activeVectorModel())
                .thenReturn(
                        java.util.Map.of(
                                "model",
                                "vector-model",
                                "provider_type",
                                "OPENAI_COMPATIBLE",
                                "base_url",
                                base + "/vector/v1",
                                "secret_version_id",
                                vectorSecret,
                                "dimension",
                                2));
        org.mockito.Mockito.when(mapper.secret(chatSecret))
                .thenReturn(java.util.Map.of("cipher_text", "chat-cipher", "iv", "chat-iv"));
        org.mockito.Mockito.when(mapper.secret(vectorSecret))
                .thenReturn(java.util.Map.of("cipher_text", "vector-cipher", "iv", "vector-iv"));
        org.mockito.Mockito.when(cipher.decrypt("chat-cipher", "chat-iv")).thenReturn("chat-key");
        org.mockito.Mockito.when(cipher.decrypt("vector-cipher", "vector-iv"))
                .thenReturn("vector-key");
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext(
                "/chat/v1/chat/completions",
                exchange -> {
                    JsonNode payload = json.readTree(exchange.getRequestBody());
                    requests.add(
                            exchange.getRequestURI()
                                    + "|"
                                    + exchange.getRequestHeaders().getFirst("Authorization")
                                    + "|"
                                    + payload.path("model").asText());
                    respond(exchange, 200, answer("\"answer [S1]\"", "stop"));
                });
        server.createContext(
                "/vector/v1/embeddings",
                exchange -> {
                    JsonNode payload = json.readTree(exchange.getRequestBody());
                    requests.add(
                            exchange.getRequestURI()
                                    + "|"
                                    + exchange.getRequestHeaders().getFirst("Authorization")
                                    + "|"
                                    + payload.path("model").asText());
                    respond(exchange, 200, "{\"data\":[{\"embedding\":[1,0]}]}");
                });
        var settings =
                new LlmSettingsService(
                        mapper,
                        cipher,
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())),
                        client,
                        json,
                        new LlmRuntimeStateService(mapper),
                        15,
                        3);
        assertThat(settings.vectorize("question").model()).isEqualTo("vector-model");
        assertThat(settings.generate(chatId, "question")).isPresent();
        assertThat(requests)
                .containsExactly(
                        "/vector/v1/embeddings|Bearer vector-key|vector-model",
                        "/chat/v1/chat/completions|Bearer chat-key|chat-model");
    }

    @Test
    void logsCorrelatedStartAndFailureWithoutBodiesCredentialsOrRawParserMessages() {
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleClient.class);
        var events =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        java.util.UUID task = java.util.UUID.randomUUID(), repo = java.util.UUID.randomUUID();
        try (var ignored = ModelCallLogContext.open(task.toString(), repo, null, task, null)) {
            ModelCallLogContext.stage("GENERATE");
            server.createContext(
                    "/v1/chat/completions",
                    exchange ->
                            respond(
                                    exchange,
                                    200,
                                    "private-response-text; Bearer private-api-key; invalid json"));
            assertThrows(
                    LlmConnectionException.class,
                    () ->
                            client.generate(
                                    spec(5000, 2048, false),
                                    "private-api-key",
                                    "private-question-body"));
            var messages =
                    events.list.stream()
                            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                            .toList();
            assertThat(messages)
                    .hasSize(2)
                    .allSatisfy(
                            message ->
                                    assertThat(message)
                                            .contains(
                                                    "traceId=" + task,
                                                    "repoId=" + repo,
                                                    "taskId=" + task,
                                                    "stage=GENERATE",
                                                    "operation=CHAT")
                                            .doesNotContain(
                                                    "private-api-key",
                                                    "private-response-text",
                                                    "private-question-body"));
            assertThat(events.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(events.list.get(1).getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(messages.get(0))
                    .contains("endpointHost=127.0.0.1", "maxOutputTokens=2048", "timeoutMs=5000");
            var id =
                    java.util.regex.Pattern.compile("callId=([a-f0-9-]+)").matcher(messages.get(0));
            assertThat(id.find()).isTrue();
            assertThat(messages.get(1))
                    .contains(
                            "callId=" + id.group(1),
                            "code=LLM_PROTOCOL_INVALID",
                            "JsonParseException");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private void stallBody(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(' ');
        exchange.getResponseBody().flush();
        try {
            Thread.sleep(4000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
