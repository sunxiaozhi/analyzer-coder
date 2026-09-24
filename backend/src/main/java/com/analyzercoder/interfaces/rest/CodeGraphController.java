package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.intelligence.CodeGraphExplorer;
import com.analyzercoder.application.intelligence.CodeGraphPropagation;
import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.intelligence.CodeGraphTaskService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 提供代码图谱相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api/repositories/{repoId}/codegraph")
public class CodeGraphController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.analyzercoder.application.branch.BranchGraphTasks branchTasks;

    private final CodeGraphService service;
    private final CodeGraphTaskService tasks;
    private final AccessControlService access;

    @org.springframework.beans.factory.annotation.Autowired
    private BranchRequestContext branchContexts;

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
        var context = branchContexts == null ? null : branchContexts.resolve(request, repoId);
        if (context != null)
            return IndexController.IndexJobResponse.from(branchTasks.start(context));
        return IndexController.IndexJobResponse.from(tasks.start(id));
    }

    @GetMapping("/latest")
    public CodeGraphService.Artifact latest(@PathVariable UUID repoId, HttpServletRequest request) {
        access.require(
                SecurityContext.account(request),
                CodeRepositoryId.of(repoId),
                RepositoryPermission.READ);
        var context = branchContexts == null ? null : branchContexts.resolve(request, repoId);
        return context == null
                ? service.latest(repoId)
                : service.latestContentVersion(repoId, context.contentVersion());
    }

    @GetMapping("/impact")
    public CodeGraphPropagation impact(
            @PathVariable UUID repoId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "3") int depth,
            HttpServletRequest request) {
        access.require(
                SecurityContext.account(request),
                CodeRepositoryId.of(repoId),
                RepositoryPermission.READ);
        var context = branchContexts == null ? null : branchContexts.resolve(request, repoId);
        return context == null
                ? service.impact(repoId, symbol, depth)
                : service.impactContentVersion(repoId, context.contentVersion(), symbol, depth);
    }

    @GetMapping("/explore")
    public com.analyzercoder.application.intelligence.CodeGraphExplorer.View explore(
            @PathVariable UUID repoId,
            @RequestParam(defaultValue = "") String module,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String focusId,
            @RequestParam(defaultValue = "both") String direction,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "240") int limit,
            HttpServletRequest request) {
        access.require(
                SecurityContext.account(request),
                CodeRepositoryId.of(repoId),
                RepositoryPermission.READ);
        if (query.length() > 500 || module.length() > 500)
            throw new IllegalArgumentException("查询范围过长");
        var context = branchContexts == null ? null : branchContexts.resolve(request, repoId);
        var options = new CodeGraphExplorer.Options(focusId, direction, depth, limit);
        if (context != null)
            return service.exploreContentVersion(
                    repoId, context.contentVersion(), module, query, options);
        return service.explore(repoId, module, query, options);
    }
}
