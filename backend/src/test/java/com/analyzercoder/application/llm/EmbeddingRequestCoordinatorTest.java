package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EmbeddingRequestCoordinatorTest {
    private final AtomicLong clock = new AtomicLong(-1_000_000_000L);
    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");
    private final URI host = URI.create("https://example.com/v1");

    private EmbeddingRequestCoordinator coordinator(long waitMs) {
        return new EmbeddingRequestCoordinator(
                30,
                Duration.ofMinutes(1).toNanos(),
                waitMs,
                3,
                60000,
                clock::get,
                () -> now,
                nanos -> clock.addAndGet(nanos));
    }

    private long deadline() {
        return clock.get() + Duration.ofSeconds(5).toNanos();
    }

    @Test
    void sharesQuotaAcrossPathsAndDefaultPortButSeparatesCredentialsAndHosts() throws Exception {
        var gate = coordinator(120000);
        long started = clock.get();
        gate.acquire(host, "key", deadline(), "1");
        gate.acquire(URI.create("https://EXAMPLE.com:443/other"), "key", deadline(), "2");
        assertThat(clock.get() - started).isEqualTo(Duration.ofSeconds(2).toNanos());
        gate.acquire(host, "other-key", deadline(), "3");
        gate.acquire(URI.create("https://other.example.com/v1"), "key", deadline(), "4");
        assertThat(clock.get() - started).isEqualTo(Duration.ofSeconds(2).toNanos());
    }

    @Test
    void honorsRetryAfterSecondsHttpDateAndFallbackWithoutShorteningCooldown() throws Exception {
        var gate = coordinator(120000);
        assertThat(gate.retryAfterNanos("5")).isEqualTo(Duration.ofSeconds(5).toNanos());
        String date =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        now.plusSeconds(9).atZone(ZoneOffset.UTC));
        assertThat(gate.retryAfterNanos(date)).isEqualTo(Duration.ofSeconds(9).toNanos());
        assertThat(gate.retryAfterNanos("bad-header")).isEqualTo(Duration.ofSeconds(60).toNanos());
        assertThat(gate.retryAfterNanos(null)).isEqualTo(Duration.ofSeconds(60).toNanos());
        gate.rateLimited(host, "key", "9");
        gate.rateLimited(host, "key", "1");
        long started = clock.get();
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            gate.acquire(host, "key", deadline(), "retry");
            assertThat(clock.get() - started).isEqualTo(Duration.ofSeconds(9).toNanos());
            assertThat(EmbeddingRequestScope.waitedNanos())
                    .isEqualTo(Duration.ofSeconds(9).toNanos());
        }
        assertThat(EmbeddingRequestScope.waitedNanos()).isZero();
    }

    @Test
    void rejectsOversizedRetryAfterWithoutRetryingBeforeQuotaRecovery() {
        var gate = coordinator(120000);
        gate.rateLimited(host, "key", "9999999999999999999999999999999999");
        long started = clock.get();
        try (var ignored = EmbeddingRequestScope.indexing(() -> {})) {
            assertThat(
                            assertThrows(
                                            LlmConnectionException.class,
                                            () -> gate.acquire(host, "key", deadline(), "retry"))
                                    .code())
                    .isEqualTo("LLM_RATE_LIMITED");
        }
        assertThat(clock.get()).isEqualTo(started);
    }

    @Test
    void foregroundFailsWithinResponseBudgetWithoutConsumingAnotherSlot() throws Exception {
        var gate = coordinator(120000);
        gate.acquire(host, "key", deadline(), "1");
        long started = clock.get();
        assertThat(
                        assertThrows(
                                        LlmConnectionException.class,
                                        () -> gate.acquire(host, "key", started + 100_000_000, "2"))
                                .code())
                .isEqualTo("LLM_RATE_LIMITED");
        gate.acquire(host, "key", deadline(), "3");
        assertThat(clock.get() - started).isEqualTo(Duration.ofSeconds(2).toNanos());
    }

    @Test
    void backgroundWaitChecksCancellationEverySecondAndRestoresThreadScope() throws Exception {
        var gate = coordinator(120000);
        gate.rateLimited(host, "key", "60");
        AtomicInteger checks = new AtomicInteger();
        var canceled =
                new com.analyzercoder.security.ApiSecurityException(
                        409, "BRANCH_BUILD_SUPERSEDED", "任务已被接管");
        long started = clock.get();
        try (var ignored =
                EmbeddingRequestScope.indexing(
                        () -> {
                            if (checks.incrementAndGet() == 3) throw canceled;
                        })) {
            assertThat(
                            assertThrows(
                                    com.analyzercoder.security.ApiSecurityException.class,
                                    () -> gate.acquire(host, "key", deadline(), "canceled")))
                    .isSameAs(canceled);
        }
        assertThat(clock.get() - started).isEqualTo(Duration.ofSeconds(2).toNanos());
        assertThat(EmbeddingRequestScope.canWaitAndRetry()).isFalse();
    }

    @Test
    void nestedScopesCarryWaitTimeAndRestoreCheckpoint() {
        AtomicInteger outer = new AtomicInteger(), inner = new AtomicInteger();
        try (var ignored = EmbeddingRequestScope.indexing(outer::incrementAndGet)) {
            EmbeddingRequestScope.waited(10);
            try (var nested = EmbeddingRequestScope.indexing(inner::incrementAndGet)) {
                assertThat(EmbeddingRequestScope.waitedNanos()).isEqualTo(10);
                EmbeddingRequestScope.waited(20);
                EmbeddingRequestScope.checkpoint();
            }
            assertThat(EmbeddingRequestScope.waitedNanos()).isEqualTo(30);
            EmbeddingRequestScope.checkpoint();
        }
        assertThat(outer.get()).isEqualTo(1);
        assertThat(inner.get()).isEqualTo(1);
        assertThat(EmbeddingRequestScope.canWaitAndRetry()).isFalse();
    }

    @Test
    void interruptionIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThat(
                            assertThrows(
                                            LlmConnectionException.class,
                                            EmbeddingRequestScope::checkpoint)
                                    .code())
                    .isEqualTo("LLM_CHECK_CANCELED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void refusesInvalidConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingRequestCoordinator(0, 120000, 3, 60000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingRequestCoordinator(30, 120000, 11, 60000));
    }
}
