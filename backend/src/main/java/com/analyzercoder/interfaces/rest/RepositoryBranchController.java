package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.branch.BranchKnowledgeService;
import com.analyzercoder.application.branch.BranchPreparationJobs;
import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.branch.BranchRemoteService;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.application.repository.GitCredentialExecutor;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}")
public class RepositoryBranchController {
    private final RepositoryBranchService branches;
    private final BranchRemoteService remote;
    private final BranchKnowledgeService knowledge;
    private final BranchPreparationJobs preparation;

    public RepositoryBranchController(
            RepositoryBranchService branches,
            BranchRemoteService remote,
            BranchKnowledgeService knowledge,
            BranchPreparationJobs preparation) {
        this.branches = branches;
        this.remote = remote;
        this.knowledge = knowledge;
        this.preparation = preparation;
    }

    @GetMapping("/branches/discover")
    public List<GitCredentialExecutor.RemoteBranch> discover(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        return remote.discover(SecurityContext.account(request), repositoryId);
    }

    @GetMapping("/branches")
    public List<RepositoryBranchService.Branch> list(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        return branches.list(SecurityContext.account(request), repositoryId);
    }

    @PostMapping("/branches")
    public RepositoryBranchService.Branch track(
            @PathVariable UUID repositoryId, @RequestBody Track body, HttpServletRequest request) {
        return branches.track(SecurityContext.account(request), repositoryId, body.name());
    }

    @PostMapping("/branches/{branchId}/archive")
    public RepositoryBranchService.Branch archive(
            @PathVariable UUID repositoryId,
            @PathVariable UUID branchId,
            HttpServletRequest request) {
        return branches.archive(SecurityContext.account(request), repositoryId, branchId);
    }

    @PostMapping("/branches/{branchId}/restore")
    public RepositoryBranchService.Branch restore(
            @PathVariable UUID repositoryId,
            @PathVariable UUID branchId,
            HttpServletRequest request) {
        return branches.restore(SecurityContext.account(request), repositoryId, branchId);
    }

    @PostMapping("/branches/{branchId}/prepare")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public BranchPreparationJobs.Job prepare(
            @PathVariable UUID repositoryId,
            @PathVariable UUID branchId,
            HttpServletRequest request) {
        return preparation.submit(SecurityContext.account(request), repositoryId, branchId);
    }

    @GetMapping("/branch-preparation-jobs")
    public List<BranchPreparationJobs.Job> preparationJobs(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        return preparation.list(SecurityContext.account(request), repositoryId);
    }

    @PostMapping("/branch-vector-jobs")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public BranchPreparationJobs.Job vectors(
            @PathVariable UUID repositoryId,
            @RequestBody Context body,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        if (body.contextId() == null) throw new IllegalArgumentException("请选择已准备的分支阅读版本");
        return preparation.submitVectors(
                actor, branches.resolve(actor, repositoryId, body.branchId(), body.contextId()));
    }

    @PostMapping("/contexts")
    public BranchReadContext context(
            @PathVariable UUID repositoryId,
            @RequestBody Context body,
            HttpServletRequest request) {
        return branches.resolve(
                SecurityContext.account(request), repositoryId, body.branchId(), body.contextId());
    }

    public record Track(String name) {}

    public record Context(UUID branchId, UUID contextId) {}

    @PostMapping("/knowledge/{cardId}/branch-validation")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void verify(
            @PathVariable UUID repositoryId,
            @PathVariable UUID cardId,
            @RequestBody Validation body,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        knowledge.verify(
                actor,
                branches.resolve(actor, repositoryId, null, body.contextId()),
                cardId,
                body.revision(),
                body.state(),
                body.note());
    }

    public record Validation(int revision, UUID contextId, String state, String note) {}

    @GetMapping("/branch-preparation-jobs/history")
    public com.analyzercoder.application.common.PageResult<BranchPreparationJobs.Job> taskHistory(
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "15") int pageSize,
            HttpServletRequest request) {
        return preparation.history(SecurityContext.account(request), repositoryId, branchId, pageNum, pageSize);
    }

    @GetMapping("/knowledge/branch-validations")
    public List<BranchKnowledgeService.ValidationCard> validations(
            @PathVariable UUID repositoryId,
            @RequestParam UUID contextId,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        return knowledge.validations(actor, branches.resolve(actor, repositoryId, null, contextId));
    }
}
