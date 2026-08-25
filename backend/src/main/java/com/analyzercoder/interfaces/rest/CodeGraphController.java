package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.intelligence.CodeGraphTaskService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

/** 提供代码图谱相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api/repositories/{repoId}/codegraph")
public class CodeGraphController {
    private final CodeGraphService service;
    private final CodeGraphTaskService tasks;
    private final AccessControlService access;

    public CodeGraphController(
            CodeGraphService service, CodeGraphTaskService tasks, AccessControlService access) {
        this.service = service;
        this.tasks = tasks;
        this.access = access;
    }

    @PostMapping("/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IndexController.IndexJobResponse build(
            @PathVariable UUID repoId, HttpServletRequest request) {
        var id = CodeRepositoryId.of(repoId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.MAINTAIN);
        return IndexController.IndexJobResponse.from(tasks.start(id));
    }

    @GetMapping("/latest")
    public CodeGraphService.Artifact latest(@PathVariable UUID repoId, HttpServletRequest request) {
        access.require(
                SecurityContext.account(request),
                CodeRepositoryId.of(repoId),
                RepositoryPermission.READ);
        return service.latest(repoId);
    }

    @GetMapping("/impact")
    public IntelligenceService.GraphResult impact(
            @PathVariable UUID repoId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "3") int depth,
            HttpServletRequest request) {
        access.require(
                SecurityContext.account(request),
                CodeRepositoryId.of(repoId),
                RepositoryPermission.READ);
        return service.impact(repoId, symbol, depth);
    }
}
