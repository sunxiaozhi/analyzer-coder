package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.intelligence.KnowledgeCardHistoryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repoId}/knowledge/{cardId}/history")
public class KnowledgeCardHistoryController {
    private final KnowledgeCardHistoryService service;
    private final AccessControlService access;

    public KnowledgeCardHistoryController(KnowledgeCardHistoryService service, AccessControlService access) {
        this.service = service; this.access = access;
    }

    @GetMapping
    public List<KnowledgeCardHistoryService.Revision> history(@PathVariable UUID repoId,@PathVariable UUID cardId,
        HttpServletRequest request) {
        access.require(SecurityContext.account(request),CodeRepositoryId.of(repoId),RepositoryPermission.READ);
        return service.history(repoId,cardId);
    }

    @PostMapping("/{revision}/restore")
    public IntelligenceService.KnowledgeCard restore(@PathVariable UUID repoId,@PathVariable UUID cardId,
        @PathVariable int revision,HttpServletRequest request) {
        var account=SecurityContext.account(request);
        access.require(account,CodeRepositoryId.of(repoId),RepositoryPermission.MAINTAIN);
        return service.restore(repoId,cardId,revision,account.id());
    }
}
