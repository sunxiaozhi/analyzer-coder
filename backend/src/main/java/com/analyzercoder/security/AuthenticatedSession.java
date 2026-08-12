package com.analyzercoder.security;

/** 描述或执行已认证会话相关安全规则，供接口层与应用服务统一复用。 */
public record AuthenticatedSession(
        String tokenHash, String csrfToken, AuthenticatedAccount account) {}
