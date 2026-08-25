package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes repository Markdown preparation sources and idempotent card generation. */
@RestController
@RequestMapping("/api/repositories/{repoId}/knowledge/markdown-sources")
public class MarkdownKnowledgeSourceController {
    private final MarkdownKnowledgeSourceService service;
    private final AccessControlService access;

    public MarkdownKnowledgeSourceController(
            MarkdownKnowledgeSourceService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public MarkdownKnowledgeSourceService.MarkdownSourceList list(
            @PathVariable UUID repoId, HttpServletRequest request) {
        require(request, repoId, RepositoryPermission.READ);
        return service.list(repoId);
    }

    @PostMapping("/generate")
    public IntelligenceService.KnowledgeCard generate(
            @PathVariable UUID repoId,
            @Valid @RequestBody GenerateRequest body,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.MAINTAIN);
        return service.generate(
                repoId,
                account.id(),
                new MarkdownKnowledgeSourceService.GenerateInput(
                        body.sourcePath(), body.expectedSnapshotId(), body.expectedContentHash()));
    }

    @PostMapping("/generate-pending")
    public MarkdownKnowledgeSourceService.BatchGenerationResult generatePending(
            @PathVariable UUID repoId,
            @Valid @RequestBody GeneratePendingRequest body,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.MAINTAIN);
        return service.generatePending(repoId, account.id(), body.expectedSnapshotId());
    }

    private com.analyzercoder.security.AuthenticatedAccount require(
            HttpServletRequest request, UUID repoId, RepositoryPermission permission) {
        var account = SecurityContext.account(request);
        access.require(account, CodeRepositoryId.of(repoId), permission);
        return account;
    }

    public record GenerateRequest(
            @NotBlank String sourcePath,
            @NotNull UUID expectedSnapshotId,
            @NotBlank
                    @Pattern(
                            regexp = "(?i)^[0-9a-f]{64}$",
                            message = "Markdown 内容摘要格式无效")
                    String expectedContentHash) {}

    public record GeneratePendingRequest(@NotNull UUID expectedSnapshotId) {}
}
