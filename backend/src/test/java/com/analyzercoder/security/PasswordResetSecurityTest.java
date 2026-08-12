package com.analyzercoder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryAccessMapper;
import com.analyzercoder.infrastructure.persistence.model.AuthAccountRow;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasswordResetSecurityTest {
    @Test
    void resetUsesRandomTemporaryPasswordAndInvalidatesExistingSessions() {
        AuthMapper mapper = mock(AuthMapper.class);
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();
        when(mapper.findById(accountId))
                .thenReturn(
                        new AuthAccountRow(
                                accountId,
                                "normal-user",
                                "普通账号",
                                "old-hash",
                                "NORMAL",
                                true,
                                false,
                                0,
                                null,
                                null,
                                null,
                                null,
                                now,
                                now));
        PasswordHasher hasher = new PasswordHasher();
        AuthService service =
                new AuthService(
                        mapper, hasher, mock(RepositoryAccessMapper.class), "", "", 30, 12, 15);

        String resetPassword = service.resetPassword(UUID.randomUUID(), accountId, "127.0.0.1");

        assertThat(resetPassword).hasSizeGreaterThanOrEqualTo(12).isNotEqualTo("12345678");
        ArgumentCaptor<String> passwordHash = ArgumentCaptor.forClass(String.class);
        verify(mapper)
                .resetPassword(
                        eq(accountId),
                        passwordHash.capture(),
                        any(Instant.class),
                        any(Instant.class));
        assertThat(hasher.matches(resetPassword, passwordHash.getValue())).isTrue();
        verify(mapper).deleteAccountSessions(accountId);
    }

    @Test
    void accountAwaitingPasswordChangeCannotAccessBusinessApi() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        UUID.randomUUID(), "normal-user", "普通账号", AccountRole.NORMAL, true, null);
        when(request.getRequestURI()).thenReturn("/api/repositories");
        when(request.getMethod()).thenReturn("GET");
        when(request.getCookies())
                .thenReturn(new Cookie[] {new Cookie(SessionInterceptor.COOKIE_NAME, "token")});
        when(auth.authenticate("token"))
                .thenReturn(Optional.of(new AuthenticatedSession("hash", "csrf", account)));

        assertThatThrownBy(
                        () ->
                                new SessionInterceptor(auth)
                                        .preHandle(request, response, new Object()))
                .isInstanceOfSatisfying(
                        ApiSecurityException.class,
                        exception -> {
                            assertThat(exception.status()).isEqualTo(403);
                            assertThat(exception.code()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
                        });
    }
}
