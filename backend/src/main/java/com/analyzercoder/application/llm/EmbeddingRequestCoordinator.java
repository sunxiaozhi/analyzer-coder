package com.analyzercoder.application.llm;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One local quota gate per service host and credential; counts every actual HTTP attempt. */
@Component
public final class EmbeddingRequestCoordinator {
    private static final long MAX_COOLDOWN_NANOS = Long.MAX_VALUE / 4;
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingRequestCoordinator.class);
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long intervalNanos;
    private final long maximumWaitNanos;
    private final int retries;
    private final long fallbackCooldownNanos;
    private final LongSupplier nanoTime;
    private final Supplier<Instant> instant;
    private final Sleeper sleeper;

    @Autowired
    public EmbeddingRequestCoordinator(
            @Value("${app.llm.embedding-requests-per-minute:30}") int rpm,
            @Value("${app.llm.embedding-max-wait-ms:120000}") long maxWaitMs,
            @Value("${app.llm.embedding-rate-limit-retries:3}") int retries,
            @Value("${app.llm.embedding-rate-limit-cooldown-ms:60000}") long cooldownMs) {
        this(
                rpm,
                Duration.ofMinutes(1).toNanos(),
                maxWaitMs,
                retries,
                cooldownMs,
                System::nanoTime,
                Instant::now,
                nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
        if (rpm < 1
                || rpm > 60000
                || maxWaitMs < 0
                || maxWaitMs > 3600000
                || retries < 0
                || retries > 10
                || cooldownMs < 0
                || cooldownMs > 86400000) throw new IllegalArgumentException("向量请求节流配置超出允许范围");
    }

    EmbeddingRequestCoordinator(
            int rpm,
            long windowNanos,
            long maxWaitMs,
            int retries,
            long cooldownMs,
            LongSupplier nanoTime,
            Supplier<Instant> instant,
            Sleeper sleeper) {
        this.intervalNanos = rpm == 0 ? 0 : Math.max(1, (windowNanos + rpm - 1) / rpm);
        this.maximumWaitNanos = Duration.ofMillis(maxWaitMs).toNanos();
        this.retries = retries;
        this.fallbackCooldownNanos = Duration.ofMillis(cooldownMs).toNanos();
        this.nanoTime = nanoTime;
        this.instant = instant;
        this.sleeper = sleeper;
    }

    static EmbeddingRequestCoordinator unmanaged() {
        return new EmbeddingRequestCoordinator(
                0,
                1,
                0,
                0,
                60000,
                System::nanoTime,
                Instant::now,
                nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
    }

    int retries() {
        return retries;
    }

    void acquire(URI baseUri, String apiKey, long responseDeadline, String callId)
            throws InterruptedException {
        if (intervalNanos == 0) return;
        Bucket bucket =
                buckets.computeIfAbsent(
                        key(baseUri, apiKey), ignored -> new Bucket(nanoTime.getAsLong()));
        long started = nanoTime.getAsLong();
        long waitDeadline = started + maximumWaitNanos;
        if (!EmbeddingRequestScope.canWaitAndRetry())
            waitDeadline = Math.min(waitDeadline, responseDeadline);
        boolean logged = false;
        while (true) {
            EmbeddingRequestScope.checkpoint();
            long now = nanoTime.getAsLong();
            long wait;
            synchronized (bucket) {
                wait = Math.max(bucket.nextRequest, bucket.cooldownUntil) - now;
                if (wait <= 0) {
                    bucket.nextRequest = now + intervalNanos;
                    if (logged)
                        LOG.info(
                                "向量请求等待结束: {}, callId={}, waitedMs={}",
                                ModelCallLogContext.fields(),
                                callId,
                                Duration.ofNanos(now - started).toMillis());
                    return;
                }
            }
            if (wait > waitDeadline - now)
                throw new LlmConnectionException(
                        "LLM_RATE_LIMITED",
                        "向量服务额度尚未恢复，至少需等待="
                                + (Duration.ofNanos(wait).toMillis() + 1)
                                + "ms，超过本次允许等待预算；已完成的后台片段会保留，请稍后重试");
            if (!logged) {
                LOG.info(
                        "向量请求等待额度: {}, callId={}, waitMs={}, background={}",
                        ModelCallLogContext.fields(),
                        callId,
                        Duration.ofNanos(wait).toMillis(),
                        EmbeddingRequestScope.canWaitAndRetry());
                logged = true;
            }
            long before = nanoTime.getAsLong();
            try {
                sleeper.sleep(Math.min(wait, Duration.ofSeconds(1).toNanos()));
            } finally {
                EmbeddingRequestScope.waited(nanoTime.getAsLong() - before);
            }
        }
    }

    long rateLimited(URI baseUri, String apiKey, String retryAfter) {
        long delay = retryAfterNanos(retryAfter);
        Bucket bucket =
                buckets.computeIfAbsent(
                        key(baseUri, apiKey), ignored -> new Bucket(nanoTime.getAsLong()));
        synchronized (bucket) {
            bucket.cooldownUntil = Math.max(bucket.cooldownUntil, nanoTime.getAsLong() + delay);
        }
        return Duration.ofNanos(delay).toMillis();
    }

    long retryAfterNanos(String value) {
        if (value == null || value.isBlank()) return fallbackCooldownNanos;
        try {
            if (value.trim().matches("[0-9]+")) {
                var nanos =
                        new java.math.BigInteger(value.trim())
                                .multiply(java.math.BigInteger.valueOf(1_000_000_000L));
                return nanos.min(java.math.BigInteger.valueOf(MAX_COOLDOWN_NANOS)).longValue();
            }
        } catch (RuntimeException ignored) {
            /* Try the HTTP date form. */
        }
        try {
            Instant target =
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
            Duration delay = Duration.between(instant.get(), target);
            if (delay.isNegative()) return 0;
            return delay.compareTo(Duration.ofNanos(MAX_COOLDOWN_NANOS)) > 0
                    ? MAX_COOLDOWN_NANOS
                    : delay.toNanos();
        } catch (RuntimeException ignored) {
            return fallbackCooldownNanos;
        }
    }

    private static String key(URI uri, String apiKey) {
        try {
            int port =
                    uri.getPort() >= 0
                            ? uri.getPort()
                            : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            String fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(apiKey.getBytes(StandardCharsets.UTF_8)));
            return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + ":" + fingerprint;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private static final class Bucket {
        long nextRequest;
        long cooldownUntil;

        Bucket(long now) {
            nextRequest = now;
            cooldownUntil = now;
        }
    }
}
