package com.analyzercoder.application.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CredentialSecretCipherTest {
    @Test
    void encryptsWithRandomIvAndDecrypts() {
        CredentialSecretCipher cipher = new CredentialSecretCipher("credential-master-key-for-tests-123456");
        var first = cipher.encrypt("glpat-secret-value");
        var second = cipher.encrypt("glpat-secret-value");

        assertNotEquals(first.cipherText(), second.cipherText());
        assertEquals("glpat-secret-value", cipher.decrypt(first.cipherText(), first.iv()));
        assertEquals(first.digest(), second.digest());
    }

    @Test
    void rejectsShortMasterKey() {
        assertThrows(IllegalStateException.class, () -> new CredentialSecretCipher("too-short"));
    }
}
