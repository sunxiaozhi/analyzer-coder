package com.analyzercoder.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.servlet.HandlerInterceptor;

/** Explicit contexts must never be silently ignored by legacy endpoints. */
public class BranchContextInterceptor implements HandlerInterceptor {
    private static final Pattern REPOSITORY =
            Pattern.compile("^/api/repositories/[0-9a-fA-F-]{36}(/.*)$");
    private static final Set<String> GET =
            Set.of(
                    "/hybrid-search",
                    "/evidence-search",
                    "/files",
                    "/files/content",
                    "/files/raw",
                    "/codegraph/explore");
    private static final Set<String> POST = Set.of("/ask");

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getHeader("X-Branch-Context") == null) return true;
        var match = REPOSITORY.matcher(request.getRequestURI());
        boolean supported =
                match.matches()
                        && ("GET".equals(request.getMethod()) && GET.contains(match.group(1))
                                || "POST".equals(request.getMethod())
                                        && POST.contains(match.group(1)));
        if (!supported)
            throw new ApiSecurityException(
                    409, "BRANCH_CONTEXT_UNSUPPORTED", "该操作尚未接入分支上下文，不会回退到默认分支");
        return true;
    }
}
