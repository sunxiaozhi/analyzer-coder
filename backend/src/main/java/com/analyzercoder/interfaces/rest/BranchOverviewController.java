package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.branch.BranchOverviewService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BranchOverviewController {
    private final BranchRequestContext contexts;
    private final BranchOverviewService overview;

    public BranchOverviewController(BranchRequestContext contexts, BranchOverviewService overview) {
        this.contexts = contexts;
        this.overview = overview;
    }

    @GetMapping("/api/repositories/{repositoryId}/branch-overview")
    public BranchOverviewService.Overview view(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        var context = contexts.resolve(request, repositoryId);
        if (context == null) throw new IllegalArgumentException("分支总览需要明确的阅读上下文");
        return overview.view(SecurityContext.account(request), context);
    }
}
