package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
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

/** 提供当前快照的项目级架构地图，只返回可追溯的静态分析结果。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/architecture-map")
public class ProjectArchitectureMapController {
    private final ProjectArchitectureMapService service;
    private final AccessControlService access;

    public ProjectArchitectureMapController(
            ProjectArchitectureMapService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ProjectArchitectureMapService.ArchitectureMap get(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.map(id);
    }
}
