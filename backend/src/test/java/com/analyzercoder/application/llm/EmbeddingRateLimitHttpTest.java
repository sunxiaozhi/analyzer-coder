package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbeddingRateLimitHttpTest {
    private HttpServer server;
    private ExecutorService handlers;
    private OpenAiCompatibleClient client;
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
                        new ObjectMapper(),
                        new LlmEndpointPolicy(true, new LlmEndpointExceptionProperties(List.of())),
                        new EmbeddingRequestCoordinator(
                                1,
                                Duration.ofMillis(40).toNanos(),
                                2000,
                                3,
                                80,
                                System::nanoTime,
                                Instant::now,
                                nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos)));
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

    private void success(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}");
    }

    private void limited(HttpExchange exchange, String retryAfter) throws IOException {
        if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
        respond(exchange, 429, "{\"error\":{\"message\":\"maximum 40 requests per minute\"}}");
    }

    @Test
    void backgroundHonorsRetryAfterWithoutChargingQueueWaitToResponseBudget() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    if (calls.incrementAndGet() == 1) limited(exchange, "1");
                    else success(exchange);
                });
        long start = System.nanoTime();
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            assertThat(client.embed(url, "bge-m3", "secret", "private input", 2, 700))
                    .isEqualTo("[1.0,0.0]");
            assertThat(EmbeddingRequestScope.waitedNanos())
                    .isGreaterThanOrEqualTo(Duration.ofMillis(900).toNanos());
        }
        assertThat(calls.get()).isEqualTo(2);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                .isGreaterThanOrEqualTo(1000);
    }

    @Test
    void limitsBackgroundRetriesAndCountsFailedRequestsInPacing() {
        List<Long> calls = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    calls.add(System.nanoTime());
                    limited(exchange, "0");
                });
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            var failure =
                    assertThrows(
                            LlmConnectionException.class,
                            () -> client.embed(url, "bge-m3", "secret", "input", 2, 3000));
            assertThat(failure.code()).isEqualTo("LLM_RATE_LIMITED");
            assertThat(failure.getMessage())
                    .contains("HTTP 429", "maximum 40")
                    .doesNotContain("secret");
        }
        assertThat(calls).hasSize(4);
        for (int i = 1; i < calls.size(); i++)
            assertThat(calls.get(i) - calls.get(i - 1))
                    .isGreaterThan(Duration.ofMillis(25).toNanos());
    }

    @Test
    void foregroundDoesNotRetryAndSharesCooldownWithOtherModels() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    calls.incrementAndGet();
                    limited(exchange, "1");
                });
        assertThat(
                        assertThrows(
                                        LlmConnectionException.class,
                                        () -> client.embed(url, "first", "key", "input", 2, 500))
                                .code())
                .isEqualTo("LLM_RATE_LIMITED");
        assertThat(
                        assertThrows(
                                        LlmConnectionException.class,
                                        () -> client.embed(url, "second", "key", "input", 2, 100))
                                .code())
                .isEqualTo("LLM_RATE_LIMITED");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void dimensionsCompatibilityRetryAlsoUsesQuotaGate() {
        List<Long> calls = new CopyOnWriteArrayList<>();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    calls.add(System.nanoTime());
                    if (calls.size() == 1)
                        respond(
                                exchange,
                                400,
                                "{\"error\":{\"message\":\"unsupported dimensions parameter\"}}");
                    else success(exchange);
                });
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            assertThat(client.embed(url, "bge-m3", "key", "input", 2, 2000)).isEqualTo("[1.0,0.0]");
        }
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1) - calls.get(0)).isGreaterThan(Duration.ofMillis(25).toNanos());
    }

    @Test
    void authenticationErrorsAreNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    calls.incrementAndGet();
                    respond(exchange, 401, "{}");
                });
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            assertThat(
                            assertThrows(
                                            LlmConnectionException.class,
                                            () ->
                                                    client.embed(
                                                            url, "bge-m3", "key", "input", 2, 2000))
                                    .code())
                    .isEqualTo("LLM_AUTH_FAILED");
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void taskSupersessionAndDatabaseCheckpointErrorsKeepTheirOriginalTypes() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    calls.incrementAndGet();
                    success(exchange);
                });
        var superseded =
                new com.analyzercoder.security.ApiSecurityException(
                        409, "BRANCH_BUILD_SUPERSEDED", "任务已接管");
        try (var ignored =
                EmbeddingRequestScope.indexing(
                        () -> {
                            throw superseded;
                        })) {
            assertThat(
                            assertThrows(
                                    com.analyzercoder.security.ApiSecurityException.class,
                                    () -> client.embed(url, "bge-m3", "key", "input", 2, 2000)))
                    .isSameAs(superseded);
        }
        var unavailable =
                new org.springframework.dao.DataAccessResourceFailureException(
                        "database unavailable");
        try (var ignored =
                EmbeddingRequestScope.indexing(
                        () -> {
                            throw unavailable;
                        })) {
            assertThat(
                            assertThrows(
                                    org.springframework.dao.DataAccessException.class,
                                    () ->
                                            client.embedBatch(
                                                    url,
                                                    "bge-m3",
                                                    "key",
                                                    List.of("a", "b"),
                                                    2,
                                                    2000)))
                    .isSameAs(unavailable);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void quotaWaitDoesNotDisableResponseBodyTimeout() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    if (calls.incrementAndGet() == 1) {
                        limited(exchange, "1");
                        return;
                    }
                    byte[] body =
                            "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            assertThat(
                            assertThrows(
                                            LlmConnectionException.class,
                                            () ->
                                                    client.embed(
                                                            url, "bge-m3", "key", "input", 2, 700))
                                    .code())
                    .isEqualTo("LLM_TIMEOUT");
        }
        assertThat(calls.get()).isEqualTo(2);
    }
}
