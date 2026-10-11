package com.analyzercoder.security;

import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Reject code-only HTTP operations before a task or filesystem action can be started. */
@Component
public class KnowledgeProjectOperationInterceptor implements HandlerInterceptor {
    private static final Pattern PROJECT =
            Pattern.compile("^/api/repositories/([0-9a-fA-F-]{36})(/.*)$");
    private static final Pattern CODE =
            Pattern.compile(
                    "^/(?:credential|rescan|files|chunks|code-evidence-context|codegraph|graph|atlas|vector-index|index|index-jobs|branch-overview|branch-index-statuses|branch-preparation-jobs|branch-vector-jobs)(?:/|$).*"
                            + "|^/knowledge/(?:markdown-sources|branch-validations)(?:/|$).*"
                            + "|^/knowledge/[0-9a-fA-F-]{36}/(?:source-drift|source-review|branch-validation)$");
    private final RepositoryBranchService branches;
    private final AccessControlService access;

    public KnowledgeProjectOperationInterceptor(
            RepositoryBranchService branches, AccessControlService access) {
        this.branches = branches;
        this.access = access;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        var match = PROJECT.matcher(request.getRequestURI());
        if (!match.matches()) return true;
        String path = match.group(2);
        boolean branchOperation =
                path.startsWith("/branches/")
                        || (path.equals("/branches") && !"GET".equals(request.getMethod()));
        if (branchOperation || CODE.matcher(path).matches()) {
            UUID id = UUID.fromString(match.group(1));
            access.require(
                    SecurityContext.account(request),
                    CodeRepositoryId.of(id),
                    RepositoryPermission.READ);
            branches.requireCodeProject(id);
        }
        return true;
    }
}
