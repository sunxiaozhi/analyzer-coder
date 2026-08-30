package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.memory.TaskContextService;
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

/** 为开发 Agent 返回经权限校验、绑定当前版本的结构化任务上下文。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/task-context")
public class TaskContextController {
    private final TaskContextService service;
    private final AccessControlService access;

    public TaskContextController(TaskContextService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public TaskContextService.TaskContext generate(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody TaskContextRequest body,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.generate(
                id,
                body.task(),
                body.taskReviewId(),
                body.maxItems(),
                body.maxChars(),
                body.maxTokens());
    }

    public record TaskContextRequest(
            @NotBlank @Size(max = 1000) String task,
            UUID taskReviewId,
            @Min(5) @Max(40) Integer maxItems,
            @Min(4000) @Max(60000) Integer maxChars,
            @Min(500) @Max(15000) Integer maxTokens) {}
}
