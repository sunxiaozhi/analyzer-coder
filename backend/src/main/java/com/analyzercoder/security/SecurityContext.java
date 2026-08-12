package com.analyzercoder.security;

import jakarta.servlet.http.HttpServletRequest;

/** 在单次请求范围保存已认证账户与会话，避免业务方法重复解析认证信息。 */
public final class SecurityContext {
    public static final String SESSION_ATTRIBUTE = SecurityContext.class.getName() + ".session";

    private SecurityContext() {}

    public static AuthenticatedSession session(HttpServletRequest request) {
        Object value = request.getAttribute(SESSION_ATTRIBUTE);
        if (value instanceof AuthenticatedSession session) {
            return session;
        }
        throw new ApiSecurityException(401, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
    }

    public static AuthenticatedAccount account(HttpServletRequest request) {
        return session(request).account();
    }

    public static AuthenticatedAccount requireAdmin(HttpServletRequest request) {
        AuthenticatedAccount account = account(request);
        if (!account.isSuperAdmin()) {
            throw new ApiSecurityException(403, "FORBIDDEN", "无权限执行该操作");
        }
        return account;
    }
}
