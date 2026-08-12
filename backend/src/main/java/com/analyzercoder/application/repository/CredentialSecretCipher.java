package com.analyzercoder.application.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 负责凭据密钥敏感信息的加密与解密，明文仅在必要的调用边界短暂存在。 */
@Component
public class CredentialSecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec digestKey;

    public CredentialSecretCipher(@Value("${app.credentials.master-key}") String masterKey) {
        if (masterKey == null || masterKey.length() < 24) {
            throw new IllegalStateException("APP_CREDENTIAL_MASTER_KEY 必须至少包含 24 个字符");
        }
        encryptionKey = new SecretKeySpec(sha256("git-credential-encrypt:" + masterKey), "AES");
        digestKey = new SecretKeySpec(sha256("git-credential-digest:" + masterKey), "HmacSHA256");
    }

    public EncryptedSecret encrypt(String value) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            return new EncryptedSecret(
                    Base64.getEncoder()
                            .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))),
                    Base64.getEncoder().encodeToString(iv),
                    digest(value),
                    "AES-256-GCM");
        } catch (Exception exception) {
            throw new IllegalStateException("Git 凭据加密功能不可用", exception);
        }
    }

    public String decrypt(String cipherText, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(iv)));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法解密 Git 凭据", exception);
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(digestKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Git 凭据摘要功能不可用", exception);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record EncryptedSecret(String cipherText, String iv, String digest, String algorithm) {}
}
