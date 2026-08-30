package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.ci.CiCheckService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对不可变任务审查执行无模型、可重复的 CI 策略评估。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/task-reviews/{reviewId}/ci-check")
public class CiCheckController {
    private final CiCheckService service;
    private final AccessControlService access;

    public CiCheckController(CiCheckService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public CiCheckService.CiCheckResult check(
            @PathVariable UUID repositoryId,
            @PathVariable UUID reviewId,
            @RequestBody CiCheckService.CiCheckRequest body,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        access.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return service.check(id, reviewId, body);
    }
}
