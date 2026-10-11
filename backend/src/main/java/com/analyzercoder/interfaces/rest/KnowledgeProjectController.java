package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.KnowledgeProjectService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/knowledge-projects")
public class KnowledgeProjectController {
    private final KnowledgeProjectService projects;
    private final RepositoryController repositories;

    public KnowledgeProjectController(
            KnowledgeProjectService projects, RepositoryController repositories) {
        this.projects = projects;
        this.repositories = repositories;
    }

    @PostMapping
    public RepositoryController.RepositoryResponse create(
            @Valid @RequestBody CreateRequest body, HttpServletRequest request) {
        var project =
                projects.create(SecurityContext.account(request), body.name(), body.description());
        return repositories.get(project.id().value(), request);
    }

    public record CreateRequest(
            @NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {}
}
