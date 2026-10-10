package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.knowledge.KnowledgeDeletionService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repoId}/knowledge")
public class KnowledgeDeletionController {
    private final KnowledgeDeletionService deletion;
    private final BranchRequestContext contexts;

    public KnowledgeDeletionController(
            KnowledgeDeletionService deletion, BranchRequestContext contexts) {
        this.deletion = deletion;
        this.contexts = contexts;
    }

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID repoId,
            @PathVariable UUID cardId,
            @RequestParam(defaultValue = "") String expectedRevision,
            HttpServletRequest request) {
        var actor = SecurityContext.account(request);
        int revision;
        try {
            revision = Integer.parseInt(expectedRevision);
            if (revision < 1) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new ApiSecurityException(400, "KNOWLEDGE_REVISION_REQUIRED", "删除时必须提供有效的知识修订号");
        }
        deletion.delete(actor, contexts.resolve(request, repoId), cardId, revision);
    }
}
