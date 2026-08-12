package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositoryCredentialService;
import com.analyzercoder.application.repository.RepositoryCredentialService.CredentialInput;
import com.analyzercoder.application.repository.RepositoryCredentialService.CredentialView;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供仓库凭据相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api/repository-credentials")
public class RepositoryCredentialController {
    private final RepositoryCredentialService service;

    public RepositoryCredentialController(RepositoryCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public List<CredentialView> list(HttpServletRequest request) {
        return service.list(SecurityContext.account(request));
    }

    @PostMapping
    public CredentialView create(@RequestBody CredentialInput input, HttpServletRequest request) {
        return service.create(SecurityContext.account(request), input, request.getRemoteAddr());
    }

    @PutMapping("/{id}")
    public CredentialView update(
            @PathVariable UUID id, @RequestBody CredentialInput input, HttpServletRequest request) {
        return service.update(SecurityContext.account(request), id, input, request.getRemoteAddr());
    }

    @PostMapping("/{id}/validate")
    public CredentialView validate(
            @PathVariable UUID id, @RequestBody ValidationInput input, HttpServletRequest request) {
        return service.validate(
                SecurityContext.account(request),
                id,
                input.repositoryUrl(),
                request.getRemoteAddr());
    }

    @PostMapping("/{id}/enable")
    public CredentialView enable(@PathVariable UUID id, HttpServletRequest request) {
        return service.setEnabled(
                SecurityContext.account(request), id, true, request.getRemoteAddr());
    }

    @PostMapping("/{id}/disable")
    public CredentialView disable(@PathVariable UUID id, HttpServletRequest request) {
        return service.setEnabled(
                SecurityContext.account(request), id, false, request.getRemoteAddr());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        service.delete(SecurityContext.account(request), id, request.getRemoteAddr());
    }

    @GetMapping("/{id}/bindings")
    public List<RepositoryCredentialService.BindingView> bindings(
            @PathVariable UUID id, HttpServletRequest request) {
        return service.bindings(SecurityContext.account(request), id);
    }

    public record ValidationInput(String repositoryUrl) {}
}
