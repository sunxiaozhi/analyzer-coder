package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.memory.ProjectContextPackService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供只读的 Agent 项目上下文包生成接口。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/context-pack")
public class ProjectContextPackController {
    private final ProjectContextPackService service;
    private final AccessControlService access;

    public ProjectContextPackController(
            ProjectContextPackService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public ProjectContextPackService.ContextPack generate(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody ContextPackRequest body,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.generate(id, body.task(), body.maxItems(), body.maxChars());
    }

    public record ContextPackRequest(
            @NotBlank @Size(max = 1000) String task,
            @Min(5) @Max(24) Integer maxItems,
            @Min(4000) @Max(30000) Integer maxChars) {}
}
