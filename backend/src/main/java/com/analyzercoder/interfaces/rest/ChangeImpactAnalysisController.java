package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.change.ChangeImpactAnalysisService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供绑定当前仓库快照的只读变更影响分析。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/change-analyses")
public class ChangeImpactAnalysisController {
    private final ChangeImpactAnalysisService service;
    private final AccessControlService access;

    public ChangeImpactAnalysisController(
            ChangeImpactAnalysisService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public ChangeImpactAnalysisService.ChangeImpactAnalysis analyze(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody ChangeAnalysisRequest body,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.analyze(id, body.task(), body.modelConfigId());
    }

    public record ChangeAnalysisRequest(
            @NotBlank @Size(max = 1000) String task,
            UUID modelConfigId) {}
}
