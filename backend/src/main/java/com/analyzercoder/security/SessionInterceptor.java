package com.analyzercoder.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class SessionInterceptor implements HandlerInterceptor {
    public static final String COOKIE_NAME = "AC_SESSION";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final AuthService auth;

    public SessionInterceptor(AuthService auth) {
        this.auth = auth;
    }

    private static boolean publicPath(String path) {
        return path.equals("/api/health") || path.equals("/api/auth/login") || path.equals("/api/auth/captcha") || path.startsWith("/actuator/health") || path.startsWith("/error");
    }

    private static String cookie(HttpServletRequest r) {
        if (r.getCookies() != null)
            for (Cookie c : r.getCookies()) if (COOKIE_NAME.equals(c.getName())) return c.getValue();
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (publicPath(path)) return true;
        AuthenticatedSession session = auth.authenticate(cookie(request)).orElseThrow(() -> new ApiSecurityException(401, "SESSION_EXPIRED", "登录状态已失效，请重新登录"));
        request.setAttribute(SecurityContext.SESSION_ATTRIBUTE, session);
        if (!SAFE.contains(request.getMethod())) {
            String csrf = request.getHeader("X-CSRF-Token");
            if (csrf == null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), session.csrfToken().getBytes(StandardCharsets.UTF_8)))
                throw new ApiSecurityException(403, "CSRF_INVALID", "请求安全校验失败，请刷新页面后重试");
        }
        if (session.account().mustChangePassword() && !Set.of("/api/auth/change-password", "/api/auth/logout", "/api/auth/me").contains(path))
            throw new ApiSecurityException(403, "PASSWORD_CHANGE_REQUIRED", "首次登录必须修改密码");
        return true;
    }
}
