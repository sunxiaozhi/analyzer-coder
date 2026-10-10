package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbeddingInputHandlingTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService handlers;
    private OpenAiCompatibleClient client;
    private LlmSettingsService settings;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        client =
                new OpenAiCompatibleClient(
                        json,
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
        var mapper = mock(LlmSettingsMapper.class);
        when(mapper.activeVectorModel())
                .thenReturn(
                        Map.of(
                                "provider_type",
                                "OPENAI_COMPATIBLE",
                                "base_url",
                                url,
                                "model",
                                "bge-m3",
                                "dimension",
                                2,
                                "request_timeout_ms",
                                5000));
        settings =
                new LlmSettingsService(
                        mapper,
                        mock(LlmSecretCipher.class),
                        mock(LlmEndpointPolicy.class),
                        client,
                        json,
                        new LlmRuntimeStateService(mapper),
                        15,
                        3);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void tooLong(HttpExchange exchange) throws IOException {
        respond(
                exchange,
                400,
                "{\"error\":{\"message\":\"You passed 11193 input tokens and requested 0 output tokens. However, the model's context length is only 8192 tokens, resulting in a maximum input length of 8192 tokens. Please reduce the length of the input prompt.\"}}");
    }

    @Test
    void classifiesThePhotographedProviderErrorForSingleAndBatchCalls() {
        server.createContext("/v1/embeddings", this::tooLong);
        var single =
                assertThrows(
                        LlmConnectionException.class,
                        () -> client.embed(url, "bge-m3", "", "input", 2, 5000));
        var batch =
                assertThrows(
                        LlmConnectionException.class,
                        () -> client.embedBatch(url, "bge-m3", "", List.of("a", "b"), 2, 5000));
        assertThat(single.code()).isEqualTo("LLM_INPUT_TOO_LONG");
        assertThat(batch.code()).isEqualTo("LLM_INPUT_TOO_LONG");
        assertThat(single.getMessage()).contains("11193", "8192", "增大超时无法解决");
    }

    @Test
    void preservesEveryUnicodeCharacterWhenSplittingDenseOrSingleLineContent() {
        String input = ("中文😀function x() { return 1; }\n").repeat(700) + "尾部必须保留";
        var pieces = EmbeddingInputHandler.split(input, 6000);
        assertThat(String.join("", pieces)).isEqualTo(input);
        assertThat(pieces)
                .allSatisfy(
                        piece -> {
                            assertThat(piece.getBytes(StandardCharsets.UTF_8).length)
                                    .isLessThanOrEqualTo(6000);
                            assertThat(Character.isLowSurrogate(piece.charAt(0))).isFalse();
                            assertThat(Character.isHighSurrogate(piece.charAt(piece.length() - 1)))
                                    .isFalse();
                        });
    }

    @Test
    void poolsAllPartsByByteWeightWithoutDroppingTheTailAndUsesSamePathForQueries()
            throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    String input = json.readTree(exchange.getRequestBody()).path("input").asText();
                    seen.add(input);
                    if (input.getBytes(StandardCharsets.UTF_8).length > 6000) {
                        tooLong(exchange);
                        return;
                    }
                    respond(
                            exchange,
                            200,
                            input.startsWith("a")
                                    ? "{\"data\":[{\"embedding\":[1,0]}]}"
                                    : "{\"data\":[{\"embedding\":[0,1]}]}");
                });
        String original = "a".repeat(6000) + "b".repeat(3000);
        var result = settings.openExternalVectorizer().vectorize(original);
        assertThat(String.join("", seen)).isEqualTo(original);
        var vector = json.readTree(result.vector());
        assertThat(vector.get(0).asDouble())
                .isCloseTo(2 / Math.sqrt(5), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(vector.get(1).asDouble())
                .isCloseTo(1 / Math.sqrt(5), org.assertj.core.data.Offset.offset(1e-12));
        seen.clear();
        assertThat(settings.vectorize(original).vector()).isEqualTo(result.vector());
        assertThat(String.join("", seen)).isEqualTo(original);
    }

    @Test
    void adaptsToASmallerContextAfterAnExplicitLengthError() {
        List<String> accepted = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    String input = json.readTree(exchange.getRequestBody()).path("input").asText();
                    if (input.getBytes(StandardCharsets.UTF_8).length > 300) {
                        tooLong(exchange);
                        return;
                    }
                    accepted.add(input);
                    respond(exchange, 200, "{\"data\":[{\"embedding\":[1,0]}]}");
                });
        String original = "中文😀".repeat(150);
        var result = settings.openExternalVectorizer().vectorize(original);
        assertThat(String.join("", accepted)).isEqualTo(original);
        assertThat(accepted).hasSizeGreaterThan(1);
        assertThat(result.vector()).isEqualTo("[1.0,0.0]");
    }

    @Test
    void shrinksOnlyOverLimitBatchesAndPreservesInputOrderAndBatchSupport() {
        List<Integer> batches = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    JsonNode input = json.readTree(exchange.getRequestBody()).path("input");
                    batches.add(input.size());
                    if (input.size() > 2) {
                        tooLong(exchange);
                        return;
                    }
                    List<Map<String, Object>> data = new ArrayList<>();
                    for (int index = 0; index < input.size(); index++)
                        data.add(
                                Map.of(
                                        "index",
                                        index,
                                        "embedding",
                                        List.of(Integer.parseInt(input.get(index).asText()), 1)));
                    respond(exchange, 200, json.writeValueAsString(Map.of("data", data)));
                });
        var vectorizer = settings.openExternalVectorizer();
        var results = vectorizer.vectorizeBatch(List.of("1", "2", "3", "4"));
        assertThat(results)
                .extracting(LlmSettingsService.VectorEmbedding::vector)
                .containsExactly("[1.0,1.0]", "[2.0,1.0]", "[3.0,1.0]", "[4.0,1.0]");
        assertThat(vectorizer.vectorizeBatch(List.of("5", "6", "7", "8"))).hasSize(4);
        assertThat(batches).containsExactly(4, 2, 2, 2, 2);
    }

    @Test
    void doesNotRetryOrPoolPartialContentWhenAuthenticationFails() {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    seen.add(json.readTree(exchange.getRequestBody()).path("input").asText());
                    if (seen.size() == 1)
                        respond(exchange, 200, "{\"data\":[{\"embedding\":[1,0]}]}");
                    else respond(exchange, 401, "{\"error\":{\"message\":\"invalid credential\"}}");
                });
        var failure =
                assertThrows(
                        LlmConnectionException.class,
                        () -> settings.openExternalVectorizer().vectorize("x".repeat(12000)));
        assertThat(failure.code()).isEqualTo("LLM_AUTH_FAILED");
        assertThat(seen).hasSize(2);
    }

    @Test
    void appliesOneTotalDeadlineAcrossRejectedInputAndItsSubparts() {
        var provider = mock(OpenAiCompatibleClient.class);
        when(provider.embed(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            throw new LlmConnectionException(
                                    "LLM_INPUT_TOO_LONG", "input too long");
                        });
        var handler = new EmbeddingInputHandler(provider, json, url, "bge-m3", "", 2);
        var failure =
                assertThrows(LlmConnectionException.class, () -> handler.embed("abcd", () -> 20));
        assertThat(failure.code()).isEqualTo("LLM_TIMEOUT");
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(1))
                .embed(
                        org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void preservesOriginalInputPositionAfterRecursiveBatchFallback() {
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    JsonNode input = json.readTree(exchange.getRequestBody()).path("input");
                    if (input.isArray()) {
                        if (input.size() > 2) {
                            tooLong(exchange);
                            return;
                        }
                        if (input.get(0).asText().equals("3")) {
                            respond(
                                    exchange,
                                    400,
                                    "{\"error\":{\"message\":\"array not supported\"}}");
                            return;
                        }
                        respond(
                                exchange,
                                200,
                                "{\"data\":[{\"embedding\":[1,0]},{\"embedding\":[0,1]}]}");
                    } else if (input.asText().equals("4"))
                        respond(exchange, 401, "{\"error\":{\"message\":\"invalid credential\"}}");
                    else respond(exchange, 200, "{\"data\":[{\"embedding\":[1,0]}]}");
                });
        var failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                settings.openExternalVectorizer()
                                        .vectorizeBatch(List.of("1", "2", "3", "4")));
        assertThat(failure.code()).isEqualTo("LLM_AUTH_FAILED");
        assertThat(failure.getMessage()).contains("第 4/4 条输入失败", "array not supported");
    }

    @Test
    void stopsAtMinimumInputRatherThanRetryingForever() {
        server.createContext("/v1/embeddings", this::tooLong);
        var failure =
                assertThrows(
                        LlmConnectionException.class,
                        () -> settings.openExternalVectorizer().vectorize("😀"));
        assertThat(failure.code()).isEqualTo("LLM_INPUT_TOO_LONG");
        assertThat(failure.getMessage()).contains("最小文本输入仍超过模型上限");
    }
}
