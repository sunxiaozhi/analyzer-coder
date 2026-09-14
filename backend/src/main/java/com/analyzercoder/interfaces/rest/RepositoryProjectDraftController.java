package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositoryProjectDraftService;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repository-project-drafts")
public class RepositoryProjectDraftController {
    private final RepositoryProjectDraftService drafts;

    public RepositoryProjectDraftController(RepositoryProjectDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public List<RepositoryProjectDraftService.Draft> list(HttpServletRequest request) {
        return drafts.list(SecurityContext.account(request));
    }

    @PostMapping
    public RepositoryProjectDraftService.Draft create(
            @RequestBody Create body, HttpServletRequest request) {
        return drafts.create(SecurityContext.account(request), body.name(), body.description());
    }

    @PatchMapping("/{id}/source")
    public RepositoryProjectDraftService.Draft configure(
            @PathVariable UUID id, @RequestBody Source body, HttpServletRequest request) {
        return drafts.configure(
                SecurityContext.account(request),
                id,
                body.version(),
                body.sourceType(),
                body.sourceLocation(),
                body.credentialId());
    }

    @PostMapping("/{id}/complete")
    public RepositoryProjectDraftService.Draft complete(
            @PathVariable UUID id, @RequestBody Complete body, HttpServletRequest request) {
        return drafts.complete(SecurityContext.account(request), id, body.repositoryId());
    }

    public record Create(String name, String description) {}

    public record Source(
            long version,
            RepositorySourceType sourceType,
            String sourceLocation,
            UUID credentialId) {}

    public record Complete(UUID repositoryId) {}
}
