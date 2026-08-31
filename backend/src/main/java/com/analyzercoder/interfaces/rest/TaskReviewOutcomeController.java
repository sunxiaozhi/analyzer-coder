package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.outcome.TaskReviewOutcomeService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 对不可变审查追加具名开发结果和人工反馈。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/task-reviews/{reviewId}/outcomes")
public class TaskReviewOutcomeController {
    private final TaskReviewOutcomeService service;
    private final AccessControlService access;

    public TaskReviewOutcomeController(
            TaskReviewOutcomeService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public TaskReviewOutcomeService.OutcomeView report(
            @PathVariable UUID repositoryId,
            @PathVariable UUID reviewId,
            @RequestBody TaskReviewOutcomeService.OutcomeRequest body,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(actor, id, RepositoryPermission.READ);
        return service.report(id, reviewId, actor, body, request.getRemoteAddr());
    }

    @GetMapping
    public List<TaskReviewOutcomeService.OutcomeView> list(
            @PathVariable UUID repositoryId,
            @PathVariable UUID reviewId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.list(id, reviewId, limit, offset);
    }

    @GetMapping("/{outcomeId}")
    public TaskReviewOutcomeService.OutcomeView get(
            @PathVariable UUID repositoryId,
            @PathVariable UUID reviewId,
            @PathVariable UUID outcomeId,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.get(id, reviewId, outcomeId);
    }
}
