package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.KnowledgeDriftService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

/** 暴露知识来源漂移证据和受版本保护的人工复核动作。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/knowledge/{cardId}")
public class KnowledgeDriftController {
    private final KnowledgeDriftService drift;
    private final IntelligenceService intelligence;
    private final AccessControlService access;

    public KnowledgeDriftController(
            KnowledgeDriftService drift,
            IntelligenceService intelligence,
            AccessControlService access) {
        this.drift = drift;
        this.intelligence = intelligence;
        this.access = access;
    }

    @GetMapping("/source-drift")
    public ResponseEntity<KnowledgeDriftService.DriftEvent> latest(
            @PathVariable UUID repositoryId,
            @PathVariable UUID cardId,
            HttpServletRequest request) {
        access.require(
                SecurityContext.account(request),
                CodeRepositoryId.of(repositoryId),
                RepositoryPermission.READ);
        KnowledgeDriftService.DriftEvent event =
                drift.latestEvent(CodeRepositoryId.of(repositoryId), cardId);
        return event == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(event);
    }

    @PostMapping("/source-review")
    public SourceReviewResponse review(
            @PathVariable UUID repositoryId,
            @PathVariable UUID cardId,
            @Valid @RequestBody SourceReviewRequest body,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(account, id, RepositoryPermission.MAINTAIN);
        KnowledgeDriftService.DriftEvent event =
                drift.reviewSource(
                        id,
                        cardId,
                        account.id(),
                        new KnowledgeDriftService.SourceReviewRequest(
                                body.action(), body.expectedRevision(), body.note()));
        return new SourceReviewResponse(intelligence.card(repositoryId, cardId), event);
    }

    public record SourceReviewRequest(
            @NotBlank String action, @Min(1) int expectedRevision, @NotBlank String note) {}

    public record SourceReviewResponse(
            IntelligenceService.KnowledgeCard card, KnowledgeDriftService.DriftEvent event) {}
}
