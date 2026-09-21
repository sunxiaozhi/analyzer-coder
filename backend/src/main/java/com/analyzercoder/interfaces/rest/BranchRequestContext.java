package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Explicit HTTP adapter; no ThreadLocal, SQL rewriting, or repository-pointer mutation. */
@Component
public class BranchRequestContext {
    private final RepositoryBranchService branches;

    public BranchRequestContext(RepositoryBranchService branches) {
        this.branches = branches;
    }

    public BranchReadContext resolve(HttpServletRequest request, UUID repositoryId) {
        String id = request.getHeader("X-Branch-Context");
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("请选择并固定当前阅读分支");
        try {
            return branches.resolve(
                    SecurityContext.account(request), repositoryId, null, UUID.fromString(id));
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalArgumentException("分支阅读上下文无效", invalidId);
        }
    }

    public com.analyzercoder.domain.repository.CodeRepository repository(
            BranchReadContext context) {
        return branches.repositoryFor(context);
    }
}
