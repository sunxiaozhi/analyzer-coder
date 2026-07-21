package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IntelligenceController {
    private final IntelligenceService service;
    private final AccessControlService access;
    public IntelligenceController(IntelligenceService service, AccessControlService access) { this.service = service; this.access = access; }

    @GetMapping("/repositories/{repoId}/hybrid-search")
    public List<IntelligenceService.SearchHit> search(@PathVariable UUID repoId, @RequestParam String query,
        @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        require(request, repoId, RepositoryPermission.READ); return service.hybridSearch(repoId, query, limit);
    }

    @PostMapping("/repositories/{repoId}/ask")
    public IntelligenceService.Answer ask(@PathVariable UUID repoId, @Valid @RequestBody Question body, HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        return service.ask(repoId, account.id(), body.question());
    }

    @GetMapping("/repositories/{repoId}/graph")
    public IntelligenceService.GraphResult graph(@PathVariable UUID repoId, @RequestParam String symbol,
        @RequestParam(defaultValue = "3") int depth, @RequestParam(defaultValue = "BOTH") String direction,
        HttpServletRequest request) {
        require(request, repoId, RepositoryPermission.READ); return service.graph(repoId, symbol, depth, direction);
    }

    @GetMapping("/repositories/{repoId}/knowledge")
    public List<IntelligenceService.KnowledgeCard> cards(@PathVariable UUID repoId, HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ); return service.cards(repoId, account.isSuperAdmin());
    }

    @PostMapping("/repositories/{repoId}/knowledge")
    public IntelligenceService.KnowledgeCard createCard(@PathVariable UUID repoId,
        @Valid @RequestBody IntelligenceService.CardInput body, HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.MAINTAIN); return service.createCard(repoId, account.id(), body);
    }

    @PutMapping("/repositories/{repoId}/knowledge/{cardId}")
    public IntelligenceService.KnowledgeCard updateCard(@PathVariable UUID repoId, @PathVariable UUID cardId,
        @Valid @RequestBody IntelligenceService.CardInput body, HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.MAINTAIN); return service.updateCard(repoId, cardId, account.id(), body);
    }

    @GetMapping("/settings")
    public Map<String, String> settings(HttpServletRequest request) { SecurityContext.requireAdmin(request); return service.settings(); }

    @PutMapping("/settings")
    public Map<String, String> saveSettings(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request); return service.saveSettings(account.id(), body);
    }

    @GetMapping("/backups")
    public List<IntelligenceService.BackupView> backups(HttpServletRequest request) { SecurityContext.requireAdmin(request); return service.backups(); }

    @PostMapping("/backups")
    public IntelligenceService.BackupView backup(HttpServletRequest request) { var account = SecurityContext.requireAdmin(request); return service.createBackup(account.id()); }

    @PostMapping("/backups/{id}/restore")
    public void restore(@PathVariable UUID id, HttpServletRequest request) { SecurityContext.requireAdmin(request); service.restoreBackup(id); }

    private com.analyzercoder.security.AuthenticatedAccount require(HttpServletRequest request, UUID repoId, RepositoryPermission permission) {
        var account = SecurityContext.account(request); access.require(account, CodeRepositoryId.of(repoId), permission); return account;
    }
    public record Question(@NotBlank String question) {}
}
