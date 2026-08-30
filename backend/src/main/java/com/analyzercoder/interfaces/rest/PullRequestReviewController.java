package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.pullrequest.PullRequestReviewService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 由仓库维护者显式触发 PR/MR 提示性审查与幂等评论同步。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/pull-request-reviews")
public class PullRequestReviewController {
    private final PullRequestReviewService service;
    private final AccessControlService access;

    public PullRequestReviewController(
            PullRequestReviewService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public PullRequestReviewService.ReviewResult review(
            @PathVariable UUID repositoryId,
            @RequestBody PullRequestReviewService.ReviewRequest body,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(account, id, RepositoryPermission.MAINTAIN);
        return service.review(account, id, body);
    }
}
