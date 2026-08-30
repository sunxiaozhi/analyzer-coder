package com.analyzercoder.application.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 对原始请求体执行 GitHub HMAC 或 GitLab Token 验证，再允许进入无会话 Webhook 路径。 */
@Component
public class ProviderWebhookAuthenticator {
    static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    private final String githubSecret;
    private final String gitlabSecret;

    public ProviderWebhookAuthenticator(
            @Value("${app.provider-webhooks.github-secret:}") String githubSecret,
            @Value("${app.provider-webhooks.gitlab-secret:}") String gitlabSecret) {
        this.githubSecret = normalized(githubSecret);
        this.gitlabSecret = normalized(gitlabSecret);
    }

    public void verifyGithub(byte[] payload, String signature) {
        requirePayload(payload);
        requireConfigured(githubSecret, "GitHub");
        String supplied = signature == null ? "" : signature.trim().toLowerCase();
        String expected = "sha256=" + hmac(payload, githubSecret);
        if (!constantTime(expected, supplied)) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_SIGNATURE_INVALID", "GitHub Webhook 签名无效");
        }
    }

    public void verifyGitlab(byte[] payload, String token) {
        requirePayload(payload);
        requireConfigured(gitlabSecret, "GitLab");
        if (!constantTime(gitlabSecret, token == null ? "" : token)) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_SIGNATURE_INVALID", "GitLab Webhook Token 无效");
        }
    }

    private static void requirePayload(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_PAYLOAD_INVALID", "Webhook 请求体为空或超过 2 MiB 上限");
        }
    }

    private static void requireConfigured(String secret, String provider) {
        if (secret.isBlank()) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_NOT_CONFIGURED", provider + " Webhook 尚未配置签名密钥");
        }
    }

    private static String hmac(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }

    private static boolean constantTime(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
