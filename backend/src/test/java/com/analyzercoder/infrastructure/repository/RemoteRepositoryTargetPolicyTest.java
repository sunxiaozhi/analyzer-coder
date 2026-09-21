package com.analyzercoder.infrastructure.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RemoteRepositoryTargetPolicyTest {
    private final RemoteRepositoryTargetPolicy policy = new RemoteRepositoryTargetPolicy("");

    @Test
    void rejectsLocalAndCredentialBearingTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.requireAllowed("https://localhost/repo.git"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.requireAllowed("https://user:secret@example.com/repo.git"));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.requireAllowed("http://example.com/repo.git"));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.requireAllowed("https://127.0.0.1/repo.git"));
    }

    @Test
    void allowsProtectedAddressOnlyWhenExactHostIsTrusted() {
        var trusted = new RemoteRepositoryTargetPolicy("127.0.0.2");

        trusted.requireAllowed("https://127.0.0.2/repo.git");
        assertThrows(
                IllegalArgumentException.class,
                () -> trusted.requireAllowed("https://127.0.0.3/repo.git"));
    }

    @Test
    void rejectsWildcardAndLocalhostTrustEntries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RemoteRepositoryTargetPolicy("*.internal.example"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RemoteRepositoryTargetPolicy("localhost"));
    }

    @Test
    void acceptsPublicAddressClassification() throws Exception {
        assertFalse(
                RemoteRepositoryTargetPolicy.isBlocked(java.net.InetAddress.getByName("8.8.8.8")));
    }
}
