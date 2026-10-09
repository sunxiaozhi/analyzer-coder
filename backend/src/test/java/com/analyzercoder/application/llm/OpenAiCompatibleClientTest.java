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
