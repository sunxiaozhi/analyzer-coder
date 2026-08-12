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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 提供智能分析相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api")
public class IntelligenceController {
    private final IntelligenceService service;
    private final AccessControlService access;

    public IntelligenceController(IntelligenceService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/repositories/{repoId}/hybrid-search")
    public List<IntelligenceService.SearchHit> search(
            @PathVariable UUID repoId,
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        require(request, repoId, RepositoryPermission.READ);
        return service.hybridSearch(repoId, query, limit);
    }

    @PostMapping("/repositories/{repoId}/ask")
    public IntelligenceService.Answer ask(
            @PathVariable UUID repoId,
            @Valid @RequestBody Question body,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        return service.ask(repoId, account.id(), body.question(), body.clientRequestId());
    }

    @GetMapping("/repositories/{repoId}/qa/records")
    public List<IntelligenceService.HistoryRecord> history(
            @PathVariable UUID repoId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        return service.history(repoId, account.id(), limit, offset);
    }

    @GetMapping("/repositories/{repoId}/qa/records/{conversationId}")
    public IntelligenceService.Answer historyDetail(
            @PathVariable UUID repoId,
            @PathVariable UUID conversationId,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        return service.historyDetail(repoId, account.id(), conversationId);
    }

    @PatchMapping("/repositories/{repoId}/qa/records/{conversationId}")
    public IntelligenceService.HistoryRecord renameHistory(
            @PathVariable UUID repoId,
            @PathVariable UUID conversationId,
            @RequestBody HistoryTitle body,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        return service.renameHistory(repoId, account.id(), conversationId, body.title());
    }

    @DeleteMapping("/repositories/{repoId}/qa/records/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHistory(
            @PathVariable UUID repoId,
            @PathVariable UUID conversationId,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        service.deleteHistory(repoId, account.id(), conversationId);
    }

    @GetMapping("/repositories/{repoId}/chunks/{chunkId}/graph-target")
    public IntelligenceService.GraphTarget graphTarget(
            @PathVariable UUID repoId, @PathVariable UUID chunkId, HttpServletRequest request) {
        require(request, repoId, RepositoryPermission.READ);
        return service.graphTarget(repoId, chunkId);
    }

    @GetMapping("/repositories/{repoId}/graph")
    public IntelligenceService.GraphResult graph(
            @PathVariable UUID repoId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(defaultValue = "BOTH") String direction,
            HttpServletRequest request) {
        require(request, repoId, RepositoryPermission.READ);
        return service.graph(repoId, symbol, depth, direction);
    }

    @GetMapping("/repositories/{repoId}/knowledge")
    public List<IntelligenceService.KnowledgeCard> cards(
            @PathVariable UUID repoId, HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.READ);
        return service.cards(repoId, account.isSuperAdmin());
    }

    @PostMapping("/repositories/{repoId}/knowledge")
    public IntelligenceService.KnowledgeCard createCard(
            @PathVariable UUID repoId,
            @Valid @RequestBody IntelligenceService.CardInput body,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.MAINTAIN);
        return service.createCard(repoId, account.id(), body);
    }

    @PutMapping("/repositories/{repoId}/knowledge/{cardId}")
    public IntelligenceService.KnowledgeCard updateCard(
            @PathVariable UUID repoId,
            @PathVariable UUID cardId,
            @Valid @RequestBody IntelligenceService.CardInput body,
            HttpServletRequest request) {
        var account = require(request, repoId, RepositoryPermission.MAINTAIN);
        return service.updateCard(repoId, cardId, account.id(), body);
    }

    @GetMapping("/settings")
    public Map<String, String> settings(HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return service.settings();
    }

    @PutMapping("/settings")
    public Map<String, String> saveSettings(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        var account = SecurityContext.requireAdmin(request);
        return service.saveSettings(account.id(), body);
    }

    private com.analyzercoder.security.AuthenticatedAccount require(
            HttpServletRequest request, UUID repoId, RepositoryPermission permission) {
        var account = SecurityContext.account(request);
        access.require(account, CodeRepositoryId.of(repoId), permission);
        return account;
    }

    public record Question(@NotBlank String question, UUID clientRequestId) {}

    public record HistoryTitle(String title) {}
}
