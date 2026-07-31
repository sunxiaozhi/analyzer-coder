package com.analyzercoder.interfaces.rest;

import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.AuthenticatedSession;
import com.analyzercoder.security.CaptchaService;
import com.analyzercoder.security.SecurityContext;
import com.analyzercoder.security.SessionInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final CaptchaService captcha;
    private final boolean secureCookie;
    private final Duration cookieMaxAge;

    public AuthController(
        AuthService auth,
        CaptchaService captcha,
        @Value("${app.security.cookie-secure:false}") boolean secureCookie,
        @Value("${app.security.session-max-hours:12}") long sessionMaxHours
    ) {
        this.auth = auth;
        this.captcha = captcha;
        this.secureCookie = secureCookie;
        this.cookieMaxAge = Duration.ofHours(Math.max(1, sessionMaxHours));
    }

    ResponseCookie sessionCookie(String token, Duration maxAge) {
        return ResponseCookie.from(SessionInterceptor.COOKIE_NAME, token)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    private void setSessionCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, cookieMaxAge).toString());
    }

    private static String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    @GetMapping("/captcha")
    public CaptchaService.Challenge captcha(@RequestParam String username) {
        return captcha.create(username);
    }

    @PostMapping("/login")
    public SessionResponse login(
        @Valid @RequestBody LoginRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        captcha.verifyIfRequired(body.username(), body.captchaId(), body.captchaAnswer());
        try {
            AuthService.LoginResult result = auth.login(body.username(), body.password(), ip(request));
            captcha.clear(body.username());
            setSessionCookie(response, result.rawToken());
            return SessionResponse.from(result.account(), result.csrfToken());
        } catch (ApiSecurityException exception) {
            if ("INVALID_CREDENTIALS".equals(exception.code())) {
                int failures = captcha.recordFailure(body.username());
                if (failures >= 3) {
                    throw new ApiSecurityException(429, "CAPTCHA_REQUIRED", "连续登录失败，请完成验证码");
                }
            }
            throw exception;
        }
    }

    @GetMapping("/me")
    public SessionResponse me(HttpServletRequest request) {
        AuthenticatedSession session = SecurityContext.session(request);
        return SessionResponse.from(session.account(), session.csrfToken());
    }

    @PostMapping("/change-password")
    public SessionResponse change(
        @Valid @RequestBody ChangePasswordRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AuthService.LoginResult result = auth.changePassword(
            SecurityContext.session(request), body.currentPassword(), body.newPassword(), ip(request)
        );
        setSessionCookie(response, result.rawToken());
        return SessionResponse.from(result.account(), result.csrfToken());
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(SecurityContext.session(request), ip(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString());
    }

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        UUID captchaId,
        String captchaAnswer
    ) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record SessionResponse(
        UUID id,
        String username,
        String displayName,
        String role,
        boolean mustChangePassword,
        java.time.Instant lastLoginAt,
        String csrfToken
    ) {
        static SessionResponse from(AuthenticatedAccount account, String csrfToken) {
            return new SessionResponse(
                account.id(), account.username(), account.displayName(), account.role().name(),
                account.mustChangePassword(), account.lastLoginAt(), csrfToken
            );
        }
    }
}