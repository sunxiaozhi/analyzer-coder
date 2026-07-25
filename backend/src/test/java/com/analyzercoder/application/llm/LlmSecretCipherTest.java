package com.analyzercoder.application.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class LlmSecretCipherTest {
    @Test
    void encryptsAndDecryptsWithoutPersistingPlaintext() {
        LlmSecretCipher cipher = new LlmSecretCipher("test-master-key-with-at-least-24-characters");

        var encrypted = cipher.encrypt("secret-token");

        assertNotEquals("secret-token", encrypted.cipherText());
        assertEquals("secret-token", cipher.decrypt(encrypted.cipherText(), encrypted.iv()));
        assertEquals(cipher.digest("secret-token"), encrypted.digest());
    }
}
