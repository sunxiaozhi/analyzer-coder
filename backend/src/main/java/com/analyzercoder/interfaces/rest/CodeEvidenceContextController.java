package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.code.CodeEvidenceContextService;
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

/** 为代码与证据工作台返回当前文件的可追溯上下文。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/code-evidence-context")
public class CodeEvidenceContextController {
    private final CodeEvidenceContextService service;
    private final AccessControlService access;

    public CodeEvidenceContextController(
            CodeEvidenceContextService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public CodeEvidenceContextService.CodeEvidenceContext get(
            @PathVariable UUID repositoryId,
            @RequestParam String filePath,
            @RequestParam(required = false) String symbol,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        var account = SecurityContext.account(request);
        access.require(account, id, RepositoryPermission.READ);
        boolean includeDraft = access.canAccess(account, id, RepositoryPermission.MAINTAIN);
        return service.context(id, filePath, symbol, includeDraft);
    }
}
