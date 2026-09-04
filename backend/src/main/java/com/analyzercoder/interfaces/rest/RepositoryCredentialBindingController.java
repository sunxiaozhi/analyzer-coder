package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RepositoryCredentialBindingService;
import com.analyzercoder.application.repository.RepositoryCredentialBindingService.BindingStatus;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为仓库所有者提供已有远程仓库的凭据维护入口。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/credential")
public class RepositoryCredentialBindingController {
    private final RepositoryCredentialBindingService service;

    public RepositoryCredentialBindingController(RepositoryCredentialBindingService service) {
        this.service = service;
    }

    @GetMapping
    public BindingStatus current(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        return service.current(
                SecurityContext.account(request), CodeRepositoryId.of(repositoryId));
    }

    @PutMapping
    public BindingStatus bind(
            @PathVariable UUID repositoryId,
            @RequestBody BindInput input,
            HttpServletRequest request) {
        return service.bind(
                SecurityContext.account(request),
                CodeRepositoryId.of(repositoryId),
                input.credentialId(),
                request.getRemoteAddr());
    }

    @DeleteMapping
    public void unbind(@PathVariable UUID repositoryId, HttpServletRequest request) {
        service.unbind(
                SecurityContext.account(request),
                CodeRepositoryId.of(repositoryId),
                request.getRemoteAddr());
    }

    public record BindInput(UUID credentialId) {}
}
