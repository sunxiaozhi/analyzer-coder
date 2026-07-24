package com.analyzercoder.interfaces.rest;

import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/preferences")
public class AccountPreferenceController {
    private final AuthService authService;

    public AccountPreferenceController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/current-repository")
    public CurrentRepositoryResponse currentRepository(HttpServletRequest request) {
        return new CurrentRepositoryResponse(SecurityContext.account(request).lastRepositoryId());
    }

    @PutMapping("/current-repository")
    public CurrentRepositoryResponse updateCurrentRepository(
        @RequestBody CurrentRepositoryRequest body,
        HttpServletRequest request
    ) {
        var account = SecurityContext.account(request);
        authService.updateLastRepository(account, body.repositoryId());
        return new CurrentRepositoryResponse(body.repositoryId());
    }

    public record CurrentRepositoryRequest(UUID repositoryId) {}
    public record CurrentRepositoryResponse(UUID repositoryId) {}
}
