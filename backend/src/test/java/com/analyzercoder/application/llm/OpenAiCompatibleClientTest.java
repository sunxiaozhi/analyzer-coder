package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {
    private HttpServer server;
    private final AtomicBoolean batchAccepted = new AtomicBoolean();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange ->
                        respond(
                                exchange,
                                200,
                                "application/json",
                                "{\"data\":[{\"id\":\"test-model\"}]}"));
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    String request =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (request.contains("\"stream\":true")) {
                        respond(
                                exchange,
                                200,
                                "text/event-stream",
                                "data: {\"choices\":[{\"delta\":{\"content\":\"CONNECTED\"}}]}\n\n"
                                        + "data: [DONE]\n\n");
                    } else {
                        respond(
                                exchange,
                                200,
                                "application/json",
                                "{\"choices\":[{\"message\":{\"content\":\"CONNECTED\"}}]}");
                    }
                });
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    String request =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (request.contains("\"input\":[")) {
                        if (!batchAccepted.get()) {
                            respond(
                                    exchange,
                                    400,
                                    "application/json",
                                    "{\"error\":\"array unsupported\"}");
                            return;
                        }
                        respond(
                                exchange,
                                200,
                                "application/json",
                                "{\"data\":[{\"index\":1,\"embedding\":[0.0,1.0]},"
                                        + "{\"index\":0,\"embedding\":[1.0,0.0]}]}");
                        return;
                    }
                    StringBuilder values = new StringBuilder();
                    for (int index = 0; index < 64; index++) {
                        if (index > 0) {
                            values.append(',');
                        }
                        values.append(index / 64.0);
                    }
                    respond(
                            exchange,
                            200,
                            "application/json",
                            "{\"data\":[{\"embedding\":[" + values + "]}]}");
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void verifiesModelGenerationAndStreaming() {
        OpenAiCompatibleClient client =
                new OpenAiCompatibleClient(
                        new ObjectMapper(),
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
        LlmProviderSpec spec =
                new LlmProviderSpec(
                        null,
                        1,
                        "test",
                        "OPENAI_COMPATIBLE",
                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                        "test-model",
                        2000,
                        5000,
                        128,
                        0.2,
                        true,
                        null,
                        "fingerprint");
        List<OpenAiCompatibleClient.StageResult> stages = new ArrayList<>();

        var result =
                client.probe(
                        spec,
                        "test-key",
                        System.nanoTime() + Duration.ofSeconds(5).toNanos(),
                        new AtomicBoolean(false),
                        stages::add);

        assertEquals("AVAILABLE", result.availability());
        assertTrue(stages.stream().anyMatch(stage -> stage.stage().equals("AUTHENTICATE")));
        assertTrue(stages.stream().anyMatch(stage -> stage.stage().equals("STREAM_FIRST_TOKEN")));
    }

    @Test
    void readsOpenAiCompatibleEmbeddingWithRequiredDimension() {
        OpenAiCompatibleClient client =
                new OpenAiCompatibleClient(
                        new ObjectMapper(),
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));

        String vector =
                client.embed(
                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                        "embedding-model",
                        "test-key",
                        "sample code",
                        64,
                        5000);

        assertTrue(vector.startsWith("[0.0,0.015625"));
        assertEquals(64, vector.substring(1, vector.length() - 1).split(",").length);
    }

    @Test
    void readsBatchEmbeddingsInInputOrder() {
        batchAccepted.set(true);
        OpenAiCompatibleClient client =
                new OpenAiCompatibleClient(
                        new ObjectMapper(),
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
        List<String> vectors =
                client.embedBatch(
                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                        "embedding-model",
                        "test-key",
                        List.of("first", "second"),
                        2,
                        5000);
        assertEquals(List.of("[1.0,0.0]", "[0.0,1.0]"), vectors);
    }

    @Test
    void identifiesUnsupportedBatchInputForFallback() {
        OpenAiCompatibleClient client =
                new OpenAiCompatibleClient(
                        new ObjectMapper(),
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
        LlmConnectionException exception =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                client.embedBatch(
                                        "http://localhost:" + server.getAddress().getPort() + "/v1",
                                        "embedding-model",
                                        "test-key",
                                        List.of("first", "second"),
                                        2,
                                        5000));
        assertEquals("LLM_BATCH_UNSUPPORTED", exception.code());
    }

    @Test
    void retriesFixedDimensionModelWithoutDimensions() {
        List<JsonNode> requests = rejectDimensionsThenRespond(embeddingResponse(1024));
        String vector =
                embeddingClient()
                        .embed(
                                embeddingUrl(),
                                "bge-m3",
                                "test-key",
                                "connection probe",
                                1024,
                                5000);
        assertEquals(1024, vector.substring(1, vector.length() - 1).split(",").length);
        assertEquals(2, requests.size());
        assertEquals(1024, requests.get(0).path("dimensions").asInt());
        assertTrue(!requests.get(1).has("dimensions"));
        for (JsonNode request : requests) {
            assertEquals("float", request.path("encoding_format").asText());
            assertEquals("bge-m3", request.path("model").asText());
            assertEquals("connection probe", request.path("input").asText());
        }
    }

    @Test
    void retriesBatchWithoutDimensionsAndPreservesOrder() {
        List<JsonNode> requests =
                rejectDimensionsThenRespond(
                        "{\"data\":[{\"index\":1,\"embedding\":[0.0,1.0]},{\"index\":0,\"embedding\":[1.0,0.0]}]}");
        List<String> vectors =
                embeddingClient()
                        .embedBatch(
                                embeddingUrl(),
                                "fixed-model",
                                "test-key",
                                List.of("first", "second"),
                                2,
                                5000);
        assertEquals(List.of("[1.0,0.0]", "[0.0,1.0]"), vectors);
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).has("dimensions"));
        assertTrue(!requests.get(1).has("dimensions"));
        assertEquals(requests.get(0).path("input"), requests.get(1).path("input"));
        assertEquals("float", requests.get(1).path("encoding_format").asText());
    }

    @Test
    void stillRejectsNativeDimensionMismatchAfterRetry() {
        List<JsonNode> requests = rejectDimensionsThenRespond(embeddingResponse(1024));
        LlmConnectionException exception =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "bge-m3",
                                                "test-key",
                                                "probe",
                                                64,
                                                5000));
        assertEquals("VECTOR_DIMENSION_INCOMPATIBLE", exception.code());
        assertEquals(2, requests.size());
    }

    @Test
    void doesNotRetryUnrelatedBadRequest() {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    requests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
                    respond(
                            exchange,
                            400,
                            "application/json",
                            "{\"error\":{\"message\":\"Model does not support this input\"}}");
                });
        LlmConnectionException exception =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "bge-m3",
                                                "test-key",
                                                "probe",
                                                1024,
                                                5000));
        assertEquals("LLM_PROTOCOL_INVALID", exception.code());
        assertEquals(1, requests.size());
    }

    @Test
    void retainsDimensionsForServicesThatAcceptThem() {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    requests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
                    respond(exchange, 200, "application/json", embeddingResponse(64));
                });
        embeddingClient().embed(embeddingUrl(), "adjustable-model", "test-key", "probe", 64, 5000);
        assertEquals(1, requests.size());
        assertEquals(64, requests.get(0).path("dimensions").asInt());
        assertEquals("float", requests.get(0).path("encoding_format").asText());
    }

    @Test
    void onlyRetriesDimensionsRejectionOnce() {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    requests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
                    respond(exchange, 400, "application/json", dimensionsRejection());
                });
        assertThrows(
                LlmConnectionException.class,
                () ->
                        embeddingClient()
                                .embed(embeddingUrl(), "bge-m3", "test-key", "probe", 1024, 5000));
        assertEquals(2, requests.size());
    }

    @Test
    void exposesHttpReasonAndRequestScaleWithoutTheApiKey() {
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange ->
                        respond(
                                exchange,
                                400,
                                "application/json",
                                "{\"error\":{\"message\":\"maximum input length is 8192 tokens; received test-key; Bearer private-header\"}}"));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "bge-m3",
                                                "test-key",
                                                "private source code",
                                                1024,
                                                5000));
        assertEquals("LLM_INPUT_TOO_LONG", failure.code());
        assertTrue(failure.getMessage().contains("HTTP 400"));
        assertTrue(failure.getMessage().contains("maximum input length is 8192 tokens"));
        assertTrue(failure.getMessage().contains("模型=bge-m3"));
        assertTrue(failure.getMessage().contains("输入条数=1"));
        assertTrue(failure.getMessage().contains("请求超时=5000ms"));
        assertTrue(!failure.getMessage().contains("test-key"));
        assertTrue(!failure.getMessage().contains("private-header"));
        assertTrue(!failure.getMessage().contains("private source code"));
    }

    @Test
    void preservesBatchRejectionReasonForSingleInputFallback() {
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange ->
                        respond(
                                exchange,
                                413,
                                "application/json",
                                "{\"error\":\"batch arrays are not supported\"}"));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embedBatch(
                                                embeddingUrl(),
                                                "model",
                                                "test-key",
                                                List.of("first", "second"),
                                                64,
                                                5000));
        assertEquals("LLM_BATCH_UNSUPPORTED", failure.code());
        assertTrue(failure.getMessage().contains("HTTP 413"));
        assertTrue(failure.getMessage().contains("batch arrays are not supported"));
        assertTrue(failure.getMessage().contains("输入条数=2，总字符数=11，最长输入字符数=6"));
    }

    @Test
    void reportsTimeoutWithConfiguredBudgetAndInputScale() {
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        respond(exchange, 200, "application/json", embeddingResponse(64));
                    } catch (IOException ignored) {
                        exchange.close();
                    }
                });
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embedBatch(
                                                embeddingUrl(),
                                                "slow-model",
                                                "test-key",
                                                List.of("first", "second"),
                                                64,
                                                100));
        assertEquals("LLM_TIMEOUT", failure.code());
        assertTrue(failure.getMessage().contains("请求超时=100ms"));
        assertTrue(failure.getMessage().contains("输入条数=2"));
        assertTrue(failure.getMessage().contains("适当增大请求超时"));
    }

    @Test
    void includesExpectedAndActualDimensions() {
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "model",
                                                "test-key",
                                                "probe",
                                                1024,
                                                5000));
        assertEquals("VECTOR_DIMENSION_INCOMPATIBLE", failure.code());
        assertTrue(failure.getMessage().contains("配置 1024 维，实际 64 维"));
    }

    @Test
    void distinguishesMissingEmbeddingFromDimensionMismatch() {
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange -> respond(exchange, 200, "application/json", "{\"data\":[{}]}"));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "model",
                                                "test-key",
                                                "probe",
                                                64,
                                                5000));
        assertEquals("LLM_PROTOCOL_INVALID", failure.code());
        assertTrue(failure.getMessage().contains("缺少 data[].embedding"));
    }

    @Test
    void ignoresUnstructuredErrorPagesAndRetainsHttpStatus() {
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange ->
                        respond(
                                exchange,
                                502,
                                "text/html",
                                "<html>private proxy diagnostics</html>"));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "model",
                                                "test-key",
                                                "probe",
                                                64,
                                                5000));
        assertTrue(failure.getMessage().contains("HTTP 502"));
        assertTrue(!failure.getMessage().contains("private proxy diagnostics"));
    }

    @Test
    void extractsValidationMessagesWithoutEchoingInputs() {
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange ->
                        respond(
                                exchange,
                                422,
                                "application/json",
                                "{\"detail\":[{\"msg\":\"input exceeds maximum length\",\"input\":\"private source code\"}]}"));
        LlmConnectionException failure =
                assertThrows(
                        LlmConnectionException.class,
                        () ->
                                embeddingClient()
                                        .embed(
                                                embeddingUrl(),
                                                "model",
                                                "test-key",
                                                "probe",
                                                64,
                                                5000));
        assertTrue(failure.getMessage().contains("input exceeds maximum length"));
        assertTrue(!failure.getMessage().contains("private source code"));
    }

    private List<JsonNode> rejectDimensionsThenRespond(String response) {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        server.removeContext("/v1/embeddings");
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
                    requests.add(request);
                    respond(
                            exchange,
                            request.has("dimensions") ? 400 : 200,
                            "application/json",
                            request.has("dimensions") ? dimensionsRejection() : response);
                });
        return requests;
    }

    private static String dimensionsRejection() {
        return "{\"error\":{\"message\":\"Model bge-m3 does not support matryoshka representation, changing output dimensions will lead to poor results.\",\"type\":\"BadRequestError\",\"code\":400}}";
    }

    private static String embeddingResponse(int dimension) {
        return "{\"data\":[{\"embedding\":[" + "0.1,".repeat(dimension - 1) + "0.1]}]}";
    }

    private OpenAiCompatibleClient embeddingClient() {
        return new OpenAiCompatibleClient(
                new ObjectMapper(),
                new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
    }

    private String embeddingUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void reusesHttpClientForTheSameEndpointAndTimeout() {
        OpenAiCompatibleClient client =
                new OpenAiCompatibleClient(
                        new ObjectMapper(),
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())));
        URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/v1");

        assertSame(client.httpClient(endpoint, 5000), client.httpClient(endpoint, 5000));
        assertNotSame(client.httpClient(endpoint, 5000), client.httpClient(endpoint, 6000));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
