package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供仓库代码浏览相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/files")
public class RepositoryCodeBrowserController {
    private final RepositoryCodeBrowserService browser;
    private final AccessControlService accessControl;

    @org.springframework.beans.factory.annotation.Autowired
    private BranchRequestContext branchContexts;

    public RepositoryCodeBrowserController(
            RepositoryCodeBrowserService browser, AccessControlService accessControl) {
        this.browser = browser;
        this.accessControl = accessControl;
    }

    @GetMapping
    public RepositoryCodeBrowserService.ContentVersionFiles list(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        var context = branchContexts == null ? null : branchContexts.resolve(request, repositoryId);
        return context == null
                ? browser.list(id)
                : browser.list(branchContexts.repository(context));
    }

    @GetMapping("/content")
    public RepositoryCodeBrowserService.FileContent content(
            @PathVariable UUID repositoryId,
            @RequestParam String path,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        var context = branchContexts == null ? null : branchContexts.resolve(request, repositoryId);
        return context == null
                ? browser.read(id, path)
                : browser.read(branchContexts.repository(context), path);
    }

    @GetMapping("/raw")
    public ResponseEntity<byte[]> rawImage(
            @PathVariable UUID repositoryId,
            @RequestParam String path,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        var context = branchContexts == null ? null : branchContexts.resolve(request, repositoryId);
        RepositoryCodeBrowserService.BinaryContent content =
                context == null
                        ? browser.readImage(id, path)
                        : browser.readImage(branchContexts.repository(context), path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(content.mediaType()));
        headers.setCacheControl(CacheControl.noCache());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        return ResponseEntity.ok().headers(headers).body(content.bytes());
    }
}
