package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.overview.ProjectHealthOverviewService;
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

/** 暴露不依赖 README 或模型推断的工程健康总览。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/health-overview")
public class ProjectHealthOverviewController {
    private final ProjectHealthOverviewService service;
    private final AccessControlService access;

    public ProjectHealthOverviewController(
            ProjectHealthOverviewService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ProjectHealthOverviewService.ProjectHealthOverview get(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.view(id);
    }
}
