package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.branch.BranchKnowledgeService;
import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}")
public class RepositoryBranchController {
    @org.springframework.beans.factory.annotation.Autowired private com.analyzercoder.application.branch.BranchRemoteService remote;
    @GetMapping("/branches/discover")
    public List<com.analyzercoder.application.repository.GitCredentialExecutor.RemoteBranch> discover(@PathVariable UUID repositoryId,HttpServletRequest request) {
        return remote.discover(SecurityContext.account(request),repositoryId);
    }
    private final RepositoryBranchService branches;

    @org.springframework.beans.factory.annotation.Autowired
    private BranchKnowledgeService knowledge;

    public RepositoryBranchController(RepositoryBranchService branches) {
        this.branches = branches;
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

    @PostMapping("/branches/{branchId}/prepare")
    public RepositoryBranchService.Branch prepare(
            @PathVariable UUID repositoryId,
            @PathVariable UUID branchId,
            HttpServletRequest request) {
        return branches.prepare(SecurityContext.account(request), repositoryId, branchId);
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

    @GetMapping("/knowledge/branch-scopes")
    public List<BranchKnowledgeService.Scope> scopes(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        return knowledge.scopes(SecurityContext.account(request), repositoryId);
    }

    @PutMapping("/knowledge/{cardId}/branch-scope")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void scope(
            @PathVariable UUID repositoryId,
            @PathVariable UUID cardId,
            @RequestBody Scope body,
            HttpServletRequest request) {
        knowledge.apply(
                SecurityContext.account(request),
                repositoryId,
                cardId,
                body.revision(),
                body.mode(),
                body.branchIds());
    }

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

    public record Scope(int revision, String mode, List<UUID> branchIds) {}

    public record Validation(int revision, UUID contextId, String state, String note) {}
}
