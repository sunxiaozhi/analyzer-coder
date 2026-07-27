package com.analyzercoder.interfaces.rest;

import com.analyzercoder.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final CaptchaService captcha;

    public AuthController(AuthService auth, CaptchaService captcha) {
        this.auth = auth;
        this.captcha = captcha;
    }

    private static void cookie(HttpServletResponse r, String t) {
        r.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SessionInterceptor.COOKIE_NAME, t).httpOnly(true).sameSite("Lax").path("/").maxAge(43200).build().toString());
    }

    private static String ip(HttpServletRequest r) {
        return r.getRemoteAddr();
    }

    @GetMapping("/captcha")
    public CaptchaService.Challenge captcha(@RequestParam String username) {
        return captcha.create(username);
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest b, HttpServletRequest req, HttpServletResponse res) {
        captcha.verifyIfRequired(b.username(), b.captchaId(), b.captchaAnswer());
        try {
            AuthService.LoginResult r = auth.login(b.username(), b.password(), ip(req));
            captcha.clear(b.username());
            cookie(res, r.rawToken());
            return SessionResponse.from(r.account(), r.csrfToken());
        } catch (ApiSecurityException e) {
            if ("INVALID_CREDENTIALS".equals(e.code())) {
                int failures = captcha.recordFailure(b.username());
                if (failures >= 3) throw new ApiSecurityException(429, "CAPTCHA_REQUIRED", "连续登录失败，请完成验证码");
            }
            throw e;
        }
    }

    @GetMapping("/me")
    public SessionResponse me(HttpServletRequest r) {
        AuthenticatedSession s = SecurityContext.session(r);
        return SessionResponse.from(s.account(), s.csrfToken());
    }

    @PostMapping("/change-password")
    public SessionResponse change(@Valid @RequestBody ChangePasswordRequest b, HttpServletRequest req, HttpServletResponse res) {
        AuthService.LoginResult r = auth.changePassword(SecurityContext.session(req), b.currentPassword(), b.newPassword(), ip(req));
        cookie(res, r.rawToken());
        return SessionResponse.from(r.account(), r.csrfToken());
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest req, HttpServletResponse res) {
        auth.logout(SecurityContext.session(req), ip(req));
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SessionInterceptor.COOKIE_NAME, "").httpOnly(true).sameSite("Lax").path("/").maxAge(0).build().toString());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, UUID captchaId,
                               String captchaAnswer) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record SessionResponse(UUID id, String username, String displayName, String role, boolean mustChangePassword,
                                  java.time.Instant lastLoginAt, String csrfToken) {
        static SessionResponse from(AuthenticatedAccount a, String c) {
            return new SessionResponse(a.id(), a.username(), a.displayName(), a.role().name(), a.mustChangePassword(), a.lastLoginAt(), c);
        }
    }
}
