package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.review.TaskReviewRequest;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 为开发者、Agent、PR 和 CI 提供同一套真实变更审查 API。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/task-reviews")
public class TaskReviewController {
    private final TaskReviewService service;
    private final AccessControlService access;

    public TaskReviewController(TaskReviewService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public TaskReviewResult create(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody TaskReviewRequest body,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(account, id, RepositoryPermission.READ);
        return service.create(id, account.id(), body);
    }

    @GetMapping
    public List<TaskReviewResult.ReviewSummary> list(
            @PathVariable UUID repositoryId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.list(id, limit, offset);
    }

    @GetMapping("/{reviewId}")
    public TaskReviewResult get(
            @PathVariable UUID repositoryId,
            @PathVariable UUID reviewId,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.get(id, reviewId);
    }
}
