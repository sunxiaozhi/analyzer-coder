package com.analyzercoder.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryAccessMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryAccessRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceRepositoryPreferenceTest {
    private AuthMapper authMapper;
    private RepositoryAccessMapper accessMapper;
    private AuthService service;
    private AuthenticatedAccount account;

    @BeforeEach
    void setUp() {
        authMapper = mock(AuthMapper.class);
        accessMapper = mock(RepositoryAccessMapper.class);
        service =
                new AuthService(authMapper, new PasswordHasher(), accessMapper, "", "", 30, 12, 15);
        account =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "normal-user", "普通账号", AccountRole.NORMAL, false, null);
    }

    @Test
    void savesAccessibleRepository() {
        UUID repositoryId = UUID.randomUUID();
        when(accessMapper.findAccess(account.id(), repositoryId))
                .thenReturn(
                        new RepositoryAccessRow(
                                repositoryId, account.id(), "普通账号", null, 1, "READY"));

        service.updateLastRepository(account, repositoryId);

        verify(authMapper)
                .updateLastRepository(eq(account.id()), eq(repositoryId), any(Instant.class));
    }

    @Test
    void rejectsRepositoryOutsideAccountScope() {
        UUID repositoryId = UUID.randomUUID();

        assertThatThrownBy(() -> service.updateLastRepository(account, repositoryId))
                .isInstanceOfSatisfying(
                        ApiSecurityException.class,
                        exception -> {
                            org.assertj.core.api.Assertions.assertThat(exception.status())
                                    .isEqualTo(403);
                            org.assertj.core.api.Assertions.assertThat(exception.code())
                                    .isEqualTo("FORBIDDEN");
                        });

        verify(authMapper, never()).updateLastRepository(eq(account.id()), eq(repositoryId), any());
    }

    @Test
    void allowsClearingPreference() {
        service.updateLastRepository(account, null);

        verify(authMapper).updateLastRepository(eq(account.id()), eq(null), any(Instant.class));
    }
}
