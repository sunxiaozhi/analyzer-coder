package com.analyzercoder.interfaces.rest;

import com.analyzercoder.security.AccessTokenService;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/{accountId}/access-tokens")
public class AccountAccessTokenController {
    private final AccessTokenService service;

    public AccountAccessTokenController(AccessTokenService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AccessTokenService.TokenView>> list(
            @PathVariable UUID accountId, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.list(SecurityContext.account(request), accountId));
    }

    @PostMapping
    public ResponseEntity<AccessTokenService.IssuedToken> create(
            @PathVariable UUID accountId,
            @RequestBody CreateToken body,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.create(
                                SecurityContext.account(request),
                                accountId,
                                body.name(),
                                body.expiresInDays(),
                                request.getRemoteAddr()));
    }

    @DeleteMapping("/{tokenId}")
    public Map<String, Boolean> revoke(
            @PathVariable UUID accountId, @PathVariable UUID tokenId, HttpServletRequest request) {
        service.revoke(
                SecurityContext.account(request), accountId, tokenId, request.getRemoteAddr());
        return Map.of("revoked", true);
    }

    public record CreateToken(String name, int expiresInDays) {}
}
