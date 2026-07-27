package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositoryGovernanceService;
import com.analyzercoder.infrastructure.persistence.model.GovernanceAccountRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryMemberRow;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/governance")
public class RepositoryGovernanceController {
    private final RepositoryGovernanceService service;

    public RepositoryGovernanceController(RepositoryGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/members")
    public List<RepositoryMemberRow> members(@PathVariable UUID repositoryId, HttpServletRequest request) {
        return service.members(SecurityContext.account(request), repositoryId);
    }

    @GetMapping("/candidates")
    public List<GovernanceAccountRow> candidates(@PathVariable UUID repositoryId, HttpServletRequest request) {
        return service.candidates(SecurityContext.account(request), repositoryId);
    }

    @PutMapping("/members/{accountId}")
    public VersionResponse grant(@PathVariable UUID repositoryId, @PathVariable UUID accountId, @Valid @RequestBody GrantRequest body, HttpServletRequest request) {
        return new VersionResponse(service.setGrant(SecurityContext.account(request), repositoryId, accountId, body.permission(), body.expectedOwnershipVersion(), request.getRemoteAddr()));
    }

    @DeleteMapping("/members/{accountId}")
    public VersionResponse revoke(@PathVariable UUID repositoryId, @PathVariable UUID accountId, @RequestParam long expectedOwnershipVersion, HttpServletRequest request) {
        return new VersionResponse(service.revokeGrant(SecurityContext.account(request), repositoryId, accountId, expectedOwnershipVersion, request.getRemoteAddr()));
    }

    @PostMapping("/transfer")
    public VersionResponse transfer(@PathVariable UUID repositoryId, @Valid @RequestBody TransferRequest body, HttpServletRequest request) {
        return new VersionResponse(service.transfer(SecurityContext.account(request), repositoryId, body.newOwnerAccountId(), body.newName(), body.previousOwnerPermission(), body.expectedOwnershipVersion(), request.getRemoteAddr()));
    }

    public record GrantRequest(@NotNull RepositoryPermission permission, long expectedOwnershipVersion) {
    }

    public record TransferRequest(@NotNull UUID newOwnerAccountId, String newName,
                                  RepositoryPermission previousOwnerPermission, long expectedOwnershipVersion) {
    }

    public record VersionResponse(long ownershipVersion) {
    }
}