package com.analyzercoder.security;

import jakarta.servlet.http.HttpServletRequest;

public final class SecurityContext {
    public static final String SESSION_ATTRIBUTE = SecurityContext.class.getName() + ".session";

    private SecurityContext() {
    }

    public static AuthenticatedSession session(HttpServletRequest request) {
        Object value = request.getAttribute(SESSION_ATTRIBUTE);
        if (value instanceof AuthenticatedSession session) return session;
        throw new ApiSecurityException(401, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
    }

    public static AuthenticatedAccount account(HttpServletRequest request) {
        return session(request).account();
    }

    public static AuthenticatedAccount requireAdmin(HttpServletRequest request) {
        AuthenticatedAccount account = account(request);
        if (!account.isSuperAdmin()) throw new ApiSecurityException(403, "FORBIDDEN", "无权限执行该操作");
        return account;
    }
}
