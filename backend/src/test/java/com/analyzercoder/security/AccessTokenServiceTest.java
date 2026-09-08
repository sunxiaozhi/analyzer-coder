package com.analyzercoder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.AccessTokenMapper;
import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.persistence.model.AccessTokenRow;
import com.analyzercoder.infrastructure.persistence.model.AuthAccountRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccessTokenServiceTest {
    private final AccessTokenMapper tokens = mock(AccessTokenMapper.class);
    private final AuthMapper accounts = mock(AuthMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final AccessTokenService service = new AccessTokenService(tokens, accounts, auth);
    private final UUID id = UUID.randomUUID();
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(id, "alice", "Alice", AccountRole.NORMAL, false, null);

    private AuthAccountRow account(boolean enabled, boolean mustChange) {
        return new AuthAccountRow(
                id,
                "alice",
                "Alice",
                "hash",
                "NORMAL",
                enabled,
                mustChange,
                0,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    @Test
    void generatesDistinctSecretsAndStoresOnlyHash() throws Exception {
        when(accounts.findById(id)).thenReturn(account(true, false));
        var issued = service.create(actor, id, " workstation ", 90, "127.0.0.1");
        var next = service.create(actor, id, "second", 90, "127.0.0.1");
        assertThat(issued.rawToken())
                .matches("acp_[A-Za-z0-9_-]{43}")
                .isNotEqualTo(next.rawToken());
        var capture = ArgumentCaptor.forClass(AccessTokenRow.class);
        verify(tokens, org.mockito.Mockito.times(2)).insert(capture.capture());
        AccessTokenRow stored = capture.getAllValues().get(0);
        assertThat(stored.tokenHash())
                .isEqualTo(PasswordHasher.sha256(issued.rawToken()))
                .doesNotContain(issued.rawToken());
        assertThat(stored.name()).isEqualTo("workstation");
        when(tokens.list(id)).thenReturn(List.of(stored));
        String listed =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .writeValueAsString(service.list(actor, id));
        assertThat(listed)
                .doesNotContain(issued.rawToken(), stored.tokenHash(), "tokenHash", "rawToken");
        verify(auth, org.mockito.Mockito.times(2))
                .audit(id, id, null, "ACCESS_TOKEN_CREATED", "SUCCESS", "127.0.0.1");
    }

    @Test
    void cannotManageAnotherAccountsTokens() {
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.list(actor, other))
                .isInstanceOf(ApiSecurityException.class);
        assertThatThrownBy(() -> service.create(actor, other, "test", 1, "ip"))
                .isInstanceOf(ApiSecurityException.class);
        assertThatThrownBy(() -> service.revoke(actor, other, UUID.randomUUID(), "ip"))
                .isInstanceOf(ApiSecurityException.class);
    }

    @Test
    void administratorCanIssueForAnotherAccount() {
        when(accounts.findById(id)).thenReturn(account(true, false));
        var admin =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "admin", "Admin", AccountRole.SUPER_ADMIN, false, null);
        assertThat(service.create(admin, id, "test", 1, "ip").rawToken()).startsWith("acp_");
    }

    @Test
    void checksLiveAccountStatusEveryTime() {
        String raw = "acp_" + "a".repeat(43);
        var row =
                new AccessTokenRow(
                        UUID.randomUUID(),
                        id,
                        "test",
                        PasswordHasher.sha256(raw),
                        "acp_aaaa",
                        Instant.now(),
                        Instant.now().plusSeconds(100),
                        null,
                        null);
        when(tokens.findByHash(anyString())).thenReturn(row);
        when(tokens.touch(eq(row.id()), any())).thenReturn(1);
        when(accounts.findById(id)).thenReturn(account(true, false), account(false, false));
        assertThat(service.authenticate(raw).id()).isEqualTo(id);
        assertThatThrownBy(() -> service.authenticate(raw))
                .isInstanceOf(ApiSecurityException.class);
    }

    @Test
    void rejectsExpiredRevokedMalformedAndPasswordChangeRequiredTokens() {
        String raw = "acp_" + "a".repeat(43);
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.authenticate("wrong"))
                .isInstanceOf(ApiSecurityException.class);
        for (var row :
                List.of(
                        new AccessTokenRow(
                                UUID.randomUUID(),
                                id,
                                "t",
                                "hash",
                                "prefix",
                                now.minusSeconds(20),
                                now.minusSeconds(1),
                                null,
                                null),
                        new AccessTokenRow(
                                UUID.randomUUID(),
                                id,
                                "t",
                                "hash",
                                "prefix",
                                now,
                                now.plusSeconds(100),
                                null,
                                now))) {
            when(tokens.findByHash(anyString())).thenReturn(row);
            assertThatThrownBy(() -> service.authenticate(raw))
                    .isInstanceOf(ApiSecurityException.class);
        }
        when(accounts.findById(id)).thenReturn(account(true, true));
        assertThatThrownBy(() -> service.create(actor, id, "test", 30, "ip"))
                .isInstanceOf(ApiSecurityException.class);
    }

    @Test
    void restrictsLifetimeAndRevokesWithinAccount() {
        assertThatThrownBy(() -> service.create(actor, id, "test", 366, "ip"))
                .isInstanceOf(ApiSecurityException.class);
        UUID tokenId = UUID.randomUUID();
        when(tokens.revoke(eq(id), eq(tokenId), any())).thenReturn(1);
        service.revoke(actor, id, tokenId, "ip");
        verify(auth).audit(id, id, null, "ACCESS_TOKEN_REVOKED", "SUCCESS", "ip");
    }
}
