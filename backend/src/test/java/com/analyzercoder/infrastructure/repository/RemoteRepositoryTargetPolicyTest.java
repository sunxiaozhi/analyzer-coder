package com.analyzercoder.infrastructure.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RemoteRepositoryTargetPolicyTest {
    @Test void rejectsLocalAndCredentialBearingTargets() {
        assertThrows(IllegalArgumentException.class,()->RemoteRepositoryTargetPolicy.requireAllowed("https://localhost/repo.git"));
        assertThrows(IllegalArgumentException.class,()->RemoteRepositoryTargetPolicy.requireAllowed("https://user:secret@example.com/repo.git"));
        assertThrows(IllegalArgumentException.class,()->RemoteRepositoryTargetPolicy.requireAllowed("http://example.com/repo.git"));
        assertThrows(IllegalArgumentException.class,()->RemoteRepositoryTargetPolicy.requireAllowed("https://127.0.0.1/repo.git"));
    }

    @Test void acceptsPublicAddressClassification() throws Exception {
        assertFalse(RemoteRepositoryTargetPolicy.isBlocked(java.net.InetAddress.getByName("8.8.8.8")));
    }
}
