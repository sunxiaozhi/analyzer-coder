package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.architecture.ProjectArchitectureSymbolService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供架构模块到当前快照真实代码符号的只读下钻接口。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/architecture-map/modules")
public class ProjectArchitectureSymbolController {
    private final ProjectArchitectureSymbolService service;
    private final AccessControlService access;

    public ProjectArchitectureSymbolController(
            ProjectArchitectureSymbolService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/symbols")
    public ProjectArchitectureSymbolService.ModuleSymbols symbols(
            @PathVariable UUID repositoryId,
            @RequestParam String module,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.symbols(id, module, limit);
    }
}
