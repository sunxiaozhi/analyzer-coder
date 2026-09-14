package com.analyzercoder.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.servlet.HandlerInterceptor;

/** Explicit contexts must never be silently ignored by legacy endpoints. */
public class BranchContextInterceptor implements HandlerInterceptor {
    private static final Pattern REPOSITORY =
            Pattern.compile("^/api/repositories/[0-9a-fA-F-]{36}(/.*)$");
    private static final List<Pattern> GET =
            patterns(
                    "/hybrid-search",
                    "/evidence-search",
                    "/code-evidence-context",
                    "/files(?:/content|/raw)?",
                    "/codegraph/(?:explore|latest|impact)",
                    "/qa/records(?:/[0-9a-fA-F-]{36})?",
                    "/knowledge",
                    "/knowledge/markdown-sources",
                    "/chunks/[0-9a-fA-F-]{36}/graph-target");
    private static final List<Pattern> POST =
            patterns(
                    "/ask",
                    "/codegraph/build",
                    "/knowledge",
                    "/knowledge/markdown-sources/(?:generate|generate-pending)");
    private static final List<Pattern> PATCH = patterns("/qa/records/[0-9a-fA-F-]{36}");
    private static final List<Pattern> DELETE = PATCH;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getHeader("X-Branch-Context") == null) return true;
        var match = REPOSITORY.matcher(request.getRequestURI());
        boolean supported = match.matches() && supports(request.getMethod(), match.group(1));
        if (!supported)
            throw new ApiSecurityException(
                    409, "BRANCH_CONTEXT_UNSUPPORTED", "该操作尚未接入分支上下文，不会回退到默认分支");
        return true;
    }

    private static boolean supports(String method, String path) {
        var candidates =
                switch (method) {
                    case "GET" -> GET;
                    case "POST" -> POST;
                    case "PATCH" -> PATCH;
                    case "DELETE" -> DELETE;
                    default -> List.<Pattern>of();
                };
        return candidates.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    private static List<Pattern> patterns(String... values) {
        return java.util.Arrays.stream(values).map(Pattern::compile).toList();
    }
}
