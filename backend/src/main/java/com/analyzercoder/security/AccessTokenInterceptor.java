package com.analyzercoder.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AccessTokenInterceptor implements HandlerInterceptor {
    private static final String ID = "[0-9a-fA-F-]{36}";
    private static final Pattern SEARCH =
            Pattern.compile("/api/repositories/" + ID + "/evidence-search");
    private final AccessTokenService tokens;

    public AccessTokenInterceptor(AccessTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        boolean mcp = path.equals("/api/mcp");
        String authorization = request.getHeader("Authorization");
        if (!mcp && authorization == null) return true;
        if (!mcp && !allowed(request.getMethod(), path))
            throw new ApiSecurityException(403, "TOKEN_ENDPOINT_FORBIDDEN", "访问令牌仅可调用 MCP 工具接口");
        if (mcp) validateOrigin(request);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"analyzer-mcp\"");
            throw new ApiSecurityException(401, "ACCESS_TOKEN_REQUIRED", "请配置账户访问令牌");
        }
        try {
            request.setAttribute(
                    SecurityContext.TOKEN_ACCOUNT_ATTRIBUTE,
                    tokens.authenticate(authorization.substring(7)));
        } catch (ApiSecurityException error) {
            if (error.status() == 401)
                response.setHeader(
                        "WWW-Authenticate",
                        "Bearer realm=\"analyzer-mcp\", error=\"invalid_token\"");
            throw error;
        }
        return true;
    }

    static boolean allowed(String method, String path) {
        return "GET".equals(method) && SEARCH.matcher(path).matches();
    }

    private static void validateOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        try {
            URI uri = URI.create(origin);
            int port =
                    uri.getPort() == -1
                            ? ("https".equals(uri.getScheme()) ? 443 : 80)
                            : uri.getPort();
            if (uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && uri.getScheme().equalsIgnoreCase(request.getScheme())
                    && port == request.getServerPort()
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null) return;
        } catch (IllegalArgumentException ignored) {
        }
        throw new ApiSecurityException(403, "MCP_ORIGIN_FORBIDDEN", "MCP 请求来源不受信任");
    }
}
