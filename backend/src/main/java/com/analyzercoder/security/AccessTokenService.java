package com.analyzercoder.security;

import com.analyzercoder.infrastructure.persistence.mapper.AccessTokenMapper;
import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.persistence.model.AccessTokenRow;
import com.analyzercoder.infrastructure.persistence.model.AuthAccountRow;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 令牌仅代表账户身份，仓库权限在每次业务请求中由 AccessControlService 实时校验。 */
@Service
public class AccessTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccessTokenMapper tokens;
    private final AuthMapper accounts;
    private final AuthService auth;

    public AccessTokenService(AccessTokenMapper tokens, AuthMapper accounts, AuthService auth) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.auth = auth;
    }

    public List<TokenView> list(AuthenticatedAccount actor, UUID accountId) {
        requireManager(actor, accountId);
        return tokens.list(accountId).stream().map(AccessTokenService::view).toList();
    }

    @Transactional
    public IssuedToken create(
            AuthenticatedAccount actor, UUID accountId, String name, int days, String ip) {
        requireManager(actor, accountId);
        if (name == null || name.isBlank() || name.trim().length() > 80 || days < 1 || days > 365)
            throw new ApiSecurityException(
                    400, "TOKEN_INPUT_INVALID", "令牌名称为 1–80 个字符，有效期为 1–365 天");
        activeAccount(accountId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = "acp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        AccessTokenRow row =
                new AccessTokenRow(
                        UUID.randomUUID(),
                        accountId,
                        name.trim(),
                        PasswordHasher.sha256(raw),
                        raw.substring(0, 12),
                        now,
                        now.plus(Duration.ofDays(days)),
                        null,
                        null);
        tokens.insert(row);
        auth.audit(actor.id(), accountId, null, "ACCESS_TOKEN_CREATED", "SUCCESS", ip);
        return new IssuedToken(view(row), raw);
    }

    @Transactional
    public void revoke(AuthenticatedAccount actor, UUID accountId, UUID tokenId, String ip) {
        requireManager(actor, accountId);
        if (tokens.revoke(accountId, tokenId, Instant.now()) == 0)
            throw new ApiSecurityException(404, "TOKEN_NOT_FOUND", "访问令牌不存在");
        auth.audit(actor.id(), accountId, null, "ACCESS_TOKEN_REVOKED", "SUCCESS", ip);
    }

    public AuthenticatedAccount authenticate(String raw) {
        if (raw == null || !raw.matches("acp_[A-Za-z0-9_-]{43}")) throw invalid();
        AccessTokenRow row = tokens.findByHash(PasswordHasher.sha256(raw));
        Instant now = Instant.now();
        if (row == null || row.revokedAt() != null || !row.expiresAt().isAfter(now))
            throw invalid();
        AuthAccountRow account = activeAccount(row.accountId());
        if (tokens.touch(row.id(), now) == 0) throw invalid();
        return new AuthenticatedAccount(
                account.id(),
                account.username(),
                account.displayName(),
                AccountRole.valueOf(account.accountRole()),
                account.mustChangePassword(),
                account.lastLoginAt(),
                account.lastRepositoryId());
    }

    private AuthAccountRow activeAccount(UUID id) {
        AuthAccountRow account = accounts.findById(id);
        if (account == null || !account.enabled()) throw invalid();
        if (account.mustChangePassword())
            throw new ApiSecurityException(403, "PASSWORD_CHANGE_REQUIRED", "账户需先登录平台完成改密");
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now()))
            throw new ApiSecurityException(403, "ACCOUNT_LOCKED", "账户已锁定");
        return account;
    }

    private static void requireManager(AuthenticatedAccount actor, UUID accountId) {
        if (!actor.isSuperAdmin() && !actor.id().equals(accountId))
            throw new ApiSecurityException(403, "FORBIDDEN", "只能管理自己的访问令牌");
    }

    private static ApiSecurityException invalid() {
        return new ApiSecurityException(401, "ACCESS_TOKEN_INVALID", "访问令牌无效、已过期、已撤销或账户已停用");
    }

    private static TokenView view(AccessTokenRow row) {
        return new TokenView(
                row.id(),
                row.name(),
                row.tokenPrefix(),
                row.createdAt(),
                row.expiresAt(),
                row.lastUsedAt(),
                row.revokedAt());
    }

    public record TokenView(
            UUID id,
            String name,
            String prefix,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt) {}

    public record IssuedToken(TokenView token, String rawToken) {}
}
