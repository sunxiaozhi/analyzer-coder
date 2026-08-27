package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.architecture.ProjectCodeFactsService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供不依赖项目文档的代码事实画像。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/code-facts")
public class ProjectCodeFactsController {
    private final ProjectCodeFactsService service;
    private final AccessControlService access;

    public ProjectCodeFactsController(
            ProjectCodeFactsService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ProjectCodeFactsService.CodeFacts get(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.analyze(id);
    }
}
