package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AuthService authService;
    public AccountController(AuthService authService) { this.authService = authService; }

    @GetMapping
    public List<AuthService.AccountSummary> list(HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return authService.listAccounts();
    }

    @GetMapping("/page")
    public PageResult<AuthService.AccountSummary> page(
        @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize,
        HttpServletRequest request
    ) {
        SecurityContext.requireAdmin(request);
        return authService.pageAccounts(query, pageNum, pageSize);
    }

    @PostMapping
    public AuthService.CreatedAccount create(@Valid @RequestBody CreateAccountRequest body, HttpServletRequest request) {
        var actor = SecurityContext.requireAdmin(request);
        return authService.createAccount(actor.id(), body.username(), body.displayName(), body.role(), body.temporaryPassword(), request.getRemoteAddr());
    }

    @PatchMapping("/{accountId}")
    public AuthService.AccountSummary update(@PathVariable UUID accountId, @RequestBody UpdateAccountRequest body, HttpServletRequest request) {
        var actor = SecurityContext.requireAdmin(request);
        return authService.updateAccount(actor.id(), accountId, body.displayName(), body.role(), body.enabled(), body.version(), request.getRemoteAddr());
    }

    @PostMapping("/{accountId}/reset-password")
    public TemporaryPasswordResponse resetPassword(@PathVariable UUID accountId, HttpServletRequest request) {
        var actor = SecurityContext.requireAdmin(request);
        return new TemporaryPasswordResponse(authService.resetPassword(actor.id(), accountId, request.getRemoteAddr()));
    }

    @PostMapping("/{accountId}/unlock")
    public void unlock(@PathVariable UUID accountId, HttpServletRequest request) {
        var actor = SecurityContext.requireAdmin(request);
        authService.unlock(actor.id(), accountId, request.getRemoteAddr());
    }

    @GetMapping("/{accountId}/permissions")
    public List<AuthService.PermissionView> permissions(@PathVariable UUID accountId, HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return authService.permissions(accountId);
    }

    @PutMapping("/{accountId}/permissions/{repositoryId}")
    public void setPermission(@PathVariable UUID accountId, @PathVariable UUID repositoryId, @RequestBody PermissionRequest body, HttpServletRequest request) {
        var actor = SecurityContext.requireAdmin(request);
        authService.setPermission(actor.id(), accountId, repositoryId, body.permission(), request.getRemoteAddr());
    }

    @GetMapping("/audit")
    public List<AuthService.AuditView> audit(@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        SecurityContext.requireAdmin(request);
        return authService.auditEvents(limit, offset);
    }

    public record CreateAccountRequest(@NotBlank String username, @NotBlank String displayName, AccountRole role, String temporaryPassword) {
        public CreateAccountRequest { if (role == null) role = AccountRole.NORMAL; }
    }
    public record UpdateAccountRequest(String displayName, AccountRole role, Boolean enabled, Long version) {}
    public record TemporaryPasswordResponse(String temporaryPassword) {}
    public record PermissionRequest(RepositoryPermission permission) {}
}