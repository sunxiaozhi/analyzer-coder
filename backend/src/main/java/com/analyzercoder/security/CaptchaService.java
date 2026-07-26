package com.analyzercoder.security;

import com.analyzercoder.infrastructure.persistence.mapper.CaptchaMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CaptchaService {
    private final CaptchaMapper mapper;

    public CaptchaService(CaptchaMapper mapper) {
        this.mapper = mapper;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public boolean required(String username) {
        return mapper.failureCount(norm(username)) >= 3;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(String username) {
        String value = norm(username);
        mapper.recordFailure(value);
        return mapper.failureCount(value);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(String username) {
        mapper.clearFailures(norm(username));
    }

    @Transactional
    public Challenge create(String username) {
        if (!required(username)) throw new ApiSecurityException(400, "CAPTCHA_NOT_REQUIRED", "当前账号不需要验证码");
        int a = ThreadLocalRandom.current().nextInt(1, 10), b = ThreadLocalRandom.current().nextInt(1, 10);
        UUID id = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);
        mapper.insertChallenge(id, norm(username), hash(id + ":" + (a + b)), expiry);
        return new Challenge(id, a + " + " + b + " = ?", expiry);
    }

    @Transactional
    public void verifyIfRequired(String username, UUID id, String answer) {
        if (!required(username)) return;
        if (id == null || answer == null)
            throw new ApiSecurityException(429, "CAPTCHA_REQUIRED", "连续登录失败，请完成验证码");
        String expected = mapper.findValidAnswerHash(id, norm(username));
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hash(id + ":" + answer.trim()).getBytes(StandardCharsets.UTF_8)))
            throw new ApiSecurityException(400, "CAPTCHA_INVALID", "验证码错误或已过期");
        mapper.markUsed(id);
    }

    public record Challenge(UUID id, String prompt, Instant expiresAt) {
    }
}