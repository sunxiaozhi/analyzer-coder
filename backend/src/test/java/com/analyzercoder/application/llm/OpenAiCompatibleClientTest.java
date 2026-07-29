package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange ->
            respond(exchange, 200, "application/json", "{\"data\":[{\"id\":\"test-model\"}]}")
        );
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"stream\":true")) {
                respond(
                    exchange,
                    200,
                    "text/event-stream",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"CONNECTED\"}}]}\n\ndata: [DONE]\n\n"
                );
            } else {
                respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"choices\":[{\"message\":{\"content\":\"CONNECTED\"}}]}"
                );
            }
        });
        server.createContext("/v1/embeddings", exchange -> {
            StringBuilder values = new StringBuilder();
            for (int index = 0; index < 64; index++) {
                if (index > 0) values.append(',');
                values.append(index / 64.0);
            }
            respond(exchange, 200, "application/json",
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
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
            new ObjectMapper(),
            new LlmEndpointPolicy(true)
        );
        LlmProviderSpec spec = new LlmProviderSpec(
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
            "fingerprint"
        );
        List<OpenAiCompatibleClient.StageResult> stages = new ArrayList<>();

        var result = client.probe(
            spec,
            "test-key",
            System.nanoTime() + Duration.ofSeconds(5).toNanos(),
            new AtomicBoolean(false),
            stages::add
        );

        assertEquals("AVAILABLE", result.availability());
        assertTrue(stages.stream().anyMatch(stage -> stage.stage().equals("AUTHENTICATE")));
        assertTrue(stages.stream().anyMatch(stage -> stage.stage().equals("STREAM_FIRST_TOKEN")));
    }

    @Test
    void readsOpenAiCompatibleEmbeddingWithRequiredDimension() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
            new ObjectMapper(),
            new LlmEndpointPolicy(true)
        );

        String vector = client.embed(
            "http://localhost:" + server.getAddress().getPort() + "/v1",
            "embedding-model",
            "test-key",
            "sample code",
            64,
            5000
        );

        assertTrue(vector.startsWith("[0.0,0.015625"));
        assertEquals(64, vector.substring(1, vector.length() - 1).split(",").length);
    }

    private static void respond(
        HttpExchange exchange,
        int status,
        String contentType,
        String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
