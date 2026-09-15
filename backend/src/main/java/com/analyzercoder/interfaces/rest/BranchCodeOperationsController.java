package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.branch.BranchCodeOperationsService;
import com.analyzercoder.application.branch.BranchPreparationJobs;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}")
public class BranchCodeOperationsController {
    private final BranchPreparationJobs jobs;
    private final RepositoryBranchService branches;
    private final BranchCodeOperationsService operations;

    public BranchCodeOperationsController(
            BranchPreparationJobs jobs,
            RepositoryBranchService branches,
            BranchCodeOperationsService operations) {
        this.jobs = jobs;
        this.branches = branches;
        this.operations = operations;
    }

    @GetMapping("/branch-index-statuses")
    public List<BranchCodeOperationsService.IndexStatus> statuses(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        return operations.statuses(SecurityContext.account(request), repositoryId);
    }

    @GetMapping("/branches/{branchId}/index-status")
    public BranchCodeOperationsService.IndexStatus status(
            @PathVariable UUID repositoryId,
            @PathVariable UUID branchId,
            @RequestParam UUID contextId,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        return operations.status(actor, branches.resolve(actor, repositoryId, branchId, contextId));
    }

    @PostMapping("/branches/{branchId}/code-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BranchPreparationJobs.Job start(
            @PathVariable UUID repositoryId,
            @PathVariable UUID branchId,
            @RequestBody Operation body,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        if ("SYNC".equals(body.kind()) || "PREPARE".equals(body.kind())) {
            if (body.contextId() != null) throw new IllegalArgumentException("同步操作不能指定历史阅读上下文");
            return jobs.submitOperation(actor, repositoryId, branchId, body.kind(), null);
        }
        if (body.contextId() == null) throw new IllegalArgumentException("索引操作需要已同步的分支快照");
        var context = branches.resolve(actor, repositoryId, branchId, body.contextId());
        return jobs.submitOperation(
                actor, repositoryId, branchId, body.kind(), context.snapshotId());
    }

    public record Operation(String kind, UUID contextId) {}
}
