package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/files")
public class RepositoryCodeBrowserController {
    private final RepositoryCodeBrowserService browser;
    private final AccessControlService accessControl;

    public RepositoryCodeBrowserController(
            RepositoryCodeBrowserService browser,
            AccessControlService accessControl
    ) {
        this.browser = browser;
        this.accessControl = accessControl;
    }

    @GetMapping
    public RepositoryCodeBrowserService.SnapshotFiles list(
            @PathVariable UUID repositoryId,
            HttpServletRequest request
    ) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return browser.list(id);
    }

    @GetMapping("/content")
    public RepositoryCodeBrowserService.FileContent content(
            @PathVariable UUID repositoryId,
            @RequestParam String path,
            HttpServletRequest request
    ) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return browser.read(id, path);
    }
}
