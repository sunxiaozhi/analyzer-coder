package com.analyzercoder.security;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.infrastructure.persistence.mapper.AuthMapper;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryAccessMapper;
import com.analyzercoder.infrastructure.persistence.model.AccountSummaryRow;
import com.analyzercoder.infrastructure.persistence.model.AuthAccountRow;
import com.analyzercoder.infrastructure.persistence.model.AuthSessionRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryAccessRow;
import com.github.pagehelper.PageHelper;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 处理登录、会话、密码变更和账户锁定流程，并统一记录鉴权失败状态。 */
@Service
public class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,32}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID_LOGIN = "用户名、密码或账号状态不正确";
    private final AuthMapper mapper;
    private final RepositoryAccessMapper repositoryAccessMapper;
    private final PasswordHasher passwordHasher;
    private final String initialAdminUsername, initialAdminPassword;
    private final Duration idleTimeout, absoluteTimeout, lockDuration;

    public AuthService(
            AuthMapper mapper,
            PasswordHasher passwordHasher,
            RepositoryAccessMapper repositoryAccessMapper,
            @Value("${app.security.initial-admin-username:}") String initialAdminUsername,
            @Value("${app.security.initial-admin-password:}") String initialAdminPassword,
            @Value("${app.security.session-idle-minutes:30}") long idleMinutes,
            @Value("${app.security.session-max-hours:12}") long maxHours,
            @Value("${app.security.lock-minutes:15}") long lockMinutes) {
        this.mapper = mapper;
        this.passwordHasher = passwordHasher;
        this.repositoryAccessMapper = repositoryAccessMapper;
        this.initialAdminUsername = initialAdminUsername;
        this.initialAdminPassword = initialAdminPassword;
        this.idleTimeout = Duration.ofMinutes(idleMinutes);
        this.absoluteTimeout = Duration.ofHours(maxHours);
        this.lockDuration = Duration.ofMinutes(lockMinutes);
    }

    private static AccountRow account(AuthAccountRow row) {
        return row == null
                ? null
                : new AccountRow(
                        row.id(),
                        row.username(),
                        row.displayName(),
                        row.passwordHash(),
                        AccountRole.valueOf(row.accountRole()),
                        row.enabled(),
                        row.mustChangePassword(),
                        row.failedAttempts(),
                        row.lockedUntil(),
                        row.temporaryPasswordExpiresAt(),
                        row.lastLoginAt(),
                        row.lastLoginIp(),
                        row.lastRepositoryId(),
                        row.createdAt(),
                        row.updatedAt(),
                        row.accountVersion());
    }

    private static AccountSummary summary(AccountSummaryRow row) {
        String status =
                !row.enabled()
                        ? "DISABLED"
                        : row.lockedUntil() != null && row.lockedUntil().isAfter(Instant.now())
                                ? "LOCKED"
                                : row.mustChangePassword() ? "PASSWORD_CHANGE_REQUIRED" : "ENABLED";
        return new AccountSummary(
                row.id(),
                row.username(),
                row.displayName(),
                AccountRole.valueOf(row.accountRole()),
                status,
                row.permissionCount(),
                row.lastLoginAt(),
                row.lastLoginIp(),
                row.createdAt(),
                row.updatedAt(),
                row.accountVersion());
    }

    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? row.get(key.toUpperCase(Locale.ROOT)) : value;
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null
                ? null
                : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null
                ? null
                : value instanceof Instant i ? i : ((java.sql.Timestamp) value).toInstant();
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("用户名必须为 3–32 位字母、数字、点、下划线或连字符");
        }
    }

    private static void validateDisplayName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 50) {
            throw new IllegalArgumentException("显示名称长度必须为 1–50 个字符");
        }
    }

    private static String generateTemporaryPassword() {
        return "A!" + randomToken(10).replace('-', 'x').replace('_', 'y').substring(0, 10) + "9a";
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeFirstAdmin() {
        if (mapper.accountCount() > 0) {
            return;
        }
        if (initialAdminUsername.isBlank() || initialAdminPassword.isBlank()) {
            LOGGER.warn(
                    "No accounts exist. Set APP_INITIAL_ADMIN_USERNAME and"
                            + " APP_INITIAL_ADMIN_PASSWORD, then restart.");
            return;
        }
        validateUsername(initialAdminUsername);
        passwordHasher.validate(initialAdminUsername, initialAdminPassword);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        mapper.insertAccount(
                id,
                initialAdminUsername.trim(),
                "系统管理员",
                passwordHasher.hash(initialAdminPassword),
                AccountRole.SUPER_ADMIN.name(),
                true,
                now.plus(Duration.ofHours(24)),
                now);
        audit(null, id, null, "INITIAL_ADMIN_CREATED", "SUCCESS", null);
        LOGGER.info("Initial super administrator created; password value was not logged");
    }

    @Transactional
    public LoginResult login(String username, String password, String sourceIp) {
        AccountRow account = findByUsername(username).orElse(null);
        if (account == null) {
            audit(null, null, null, "LOGIN_FAILED", "DENIED", sourceIp);
            throw new ApiSecurityException(401, "INVALID_CREDENTIALS", INVALID_LOGIN);
        }
        Instant now = Instant.now();
        if (!account.enabled()
                || (account.lockedUntil() != null && account.lockedUntil().isAfter(now))) {
            audit(account.id(), account.id(), null, "LOGIN_FAILED", "DENIED", sourceIp);
            throw new ApiSecurityException(401, "INVALID_CREDENTIALS", INVALID_LOGIN);
        }
        if (account.temporaryPasswordExpiresAt() != null
                && account.mustChangePassword()
                && account.temporaryPasswordExpiresAt().isBefore(now)) {
            throw new ApiSecurityException(401, "TEMPORARY_PASSWORD_EXPIRED", "临时密码已过期，请联系管理员重置");
        }
        if (!passwordHasher.matches(password == null ? "" : password, account.passwordHash())) {
            int failures = account.failedAttempts() + 1;
            Instant locked = failures >= 5 ? now.plus(lockDuration) : null;
            mapper.recordLoginFailure(account.id(), failures, locked, now);
            audit(
                    account.id(),
                    account.id(),
                    null,
                    failures >= 5 ? "ACCOUNT_LOCKED" : "LOGIN_FAILED",
                    "DENIED",
                    sourceIp);
            throw new ApiSecurityException(
                    401,
                    failures >= 3 && failures < 5 ? "CAPTCHA_REQUIRED" : "INVALID_CREDENTIALS",
                    INVALID_LOGIN);
        }
        mapper.recordLoginSuccess(account.id(), sourceIp, now);
        LoginResult result = issueSession(findById(account.id()).orElseThrow());
        audit(account.id(), account.id(), null, "LOGIN_SUCCEEDED", "SUCCESS", sourceIp);
        return result;
    }

    public Optional<AuthenticatedSession> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = PasswordHasher.sha256(rawToken);
        AuthSessionRow row = mapper.findSession(hash);
        if (row == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (!row.enabled()
                || row.expiresAt().isBefore(now)
                || row.lastSeenAt().plus(idleTimeout).isBefore(now)) {
            mapper.deleteSession(hash);
            return Optional.empty();
        }
        mapper.touchSession(hash, now);
        AuthenticatedAccount account =
                new AuthenticatedAccount(
                        row.accountId(),
                        row.username(),
                        row.displayName(),
                        AccountRole.valueOf(row.accountRole()),
                        row.mustChangePassword(),
                        row.lastLoginAt(),
                        row.lastRepositoryId());
        return Optional.of(new AuthenticatedSession(hash, row.csrfToken(), account));
    }

    @Transactional
    public LoginResult changePassword(
            AuthenticatedSession session,
            String currentPassword,
            String newPassword,
            String sourceIp) {
        AccountRow account = findById(session.account().id()).orElseThrow();
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw new ApiSecurityException(400, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        passwordHasher.validate(account.username(), newPassword);
        mapper.changePassword(account.id(), passwordHasher.hash(newPassword), Instant.now());
        mapper.deleteAccountSessions(account.id());
        audit(account.id(), account.id(), null, "PASSWORD_CHANGED", "SUCCESS", sourceIp);
        return issueSession(findById(account.id()).orElseThrow());
    }

    public void logout(AuthenticatedSession session, String sourceIp) {
        mapper.deleteSession(session.tokenHash());
        audit(session.account().id(), session.account().id(), null, "LOGOUT", "SUCCESS", sourceIp);
    }

    @Transactional
    public void updateLastRepository(AuthenticatedAccount account, UUID repositoryId) {
        if (repositoryId != null) {
            RepositoryAccessRow access =
                    account.isSuperAdmin()
                            ? repositoryAccessMapper.findMetadata(repositoryId)
                            : repositoryAccessMapper.findAccess(account.id(), repositoryId);
            if (access == null) {
                throw new ApiSecurityException(403, "FORBIDDEN", "无权限访问该仓库");
            }
        }
        mapper.updateLastRepository(account.id(), repositoryId, Instant.now());
    }

    public List<AccountSummary> listAccounts() {
        return mapper.listAccounts().stream().map(AuthService::summary).toList();
    }

    public PageResult<AccountSummary> pageAccounts(String query, int pageNum, int pageSize) {
        PageResult.validate(pageNum, pageSize);
        String normalized = query == null || query.isBlank() ? null : query.trim();
        PageHelper.startPage(pageNum, pageSize);
        return PageResult.fromPage(mapper.listAccountsFiltered(normalized))
                .map(AuthService::summary);
    }

    @Transactional
    public CreatedAccount createAccount(
            UUID actorId,
            String username,
            String displayName,
            AccountRole role,
            String supplied,
            String sourceIp) {
        validateUsername(username);
        validateDisplayName(displayName);
        String password =
                supplied == null || supplied.isBlank() ? generateTemporaryPassword() : supplied;
        passwordHasher.validate(username, password);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        mapper.insertAccount(
                id,
                username.trim(),
                displayName.trim(),
                passwordHasher.hash(password),
                role.name(),
                true,
                now.plus(Duration.ofHours(24)),
                now);
        audit(actorId, id, null, "ACCOUNT_CREATED", "SUCCESS", sourceIp);
        return new CreatedAccount(summary(id), password);
    }

    @Transactional
    public AccountSummary updateAccount(
            UUID actorId,
            UUID targetId,
            String displayName,
            AccountRole role,
            Boolean enabled,
            Long expectedVersion,
            String sourceIp) {
        AccountRow current =
                findById(targetId)
                        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        String nextName = displayName == null ? current.displayName() : displayName.trim();
        AccountRole nextRole = role == null ? current.role() : role;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        validateDisplayName(nextName);
        if (actorId.equals(targetId) && !nextEnabled) {
            throw new IllegalStateException("不能停用当前登录账号");
        }
        if (!nextEnabled && repositoryAccessMapper.countOwnedRepositories(targetId) > 0) {
            throw new IllegalStateException("该账号仍是仓库 OWNER，请先完成所有权转移");
        }
        if (current.role() == AccountRole.SUPER_ADMIN
                && current.enabled()
                && (nextRole != AccountRole.SUPER_ADMIN || !nextEnabled)
                && mapper.enabledAdminCount() <= 1) {
            throw new IllegalStateException("系统必须至少保留一个已启用的超级管理员");
        }
        long version = expectedVersion == null ? current.accountVersion() : expectedVersion;
        if (mapper.updateAccount(
                        targetId, nextName, nextRole.name(), nextEnabled, version, Instant.now())
                == 0) {
            throw new ApiSecurityException(409, "ACCOUNT_VERSION_CONFLICT", "账号资料已被其他操作修改，请刷新后重试");
        }
        if (!nextEnabled || nextRole != current.role()) {
            mapper.deleteAccountSessions(targetId);
        }
        String event =
                !nextEnabled
                        ? "ACCOUNT_DISABLED"
                        : !current.enabled()
                                ? "ACCOUNT_ENABLED"
                                : nextRole != current.role()
                                        ? "ACCOUNT_ROLE_CHANGED"
                                        : "ACCOUNT_UPDATED";
        audit(actorId, targetId, null, event, "SUCCESS", sourceIp);
        return summary(targetId);
    }

    @Transactional
    public String resetPassword(UUID actorId, UUID targetId, String sourceIp) {
        AccountRow account =
                findById(targetId)
                        .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        String temporaryPassword = generateTemporaryPassword();
        passwordHasher.validate(account.username(), temporaryPassword);
        Instant now = Instant.now();
        mapper.resetPassword(
                targetId,
                passwordHasher.hash(temporaryPassword),
                now.plus(Duration.ofHours(24)),
                now);
        mapper.deleteAccountSessions(targetId);
        audit(actorId, targetId, null, "PASSWORD_RESET", "SUCCESS", sourceIp);
        return temporaryPassword;
    }

    public void unlock(UUID actorId, UUID targetId, String sourceIp) {
        mapper.unlock(targetId, Instant.now());
        audit(actorId, targetId, null, "ACCOUNT_UNLOCKED", "SUCCESS", sourceIp);
    }

    public List<PermissionView> permissions(UUID accountId) {
        return mapper.permissions(accountId).stream()
                .map(
                        row ->
                                new PermissionView(
                                        uuid(row, "repo_id"),
                                        string(row, "name"),
                                        RepositoryPermission.valueOf(
                                                string(row, "permission_level"))))
                .toList();
    }

    @Transactional
    public void setPermission(
            UUID actorId,
            UUID accountId,
            UUID repositoryId,
            RepositoryPermission permission,
            String sourceIp) {
        throw new ApiSecurityException(409, "USE_REPOSITORY_GOVERNANCE", "请在仓库治理页面分配权限");
    }

    public List<AuditView> auditEvents(int limit, int offset) {
        return mapper.audits(Math.min(Math.max(limit, 1), 200), Math.max(offset, 0)).stream()
                .map(
                        row ->
                                new AuditView(
                                        uuid(row, "id"),
                                        string(row, "event_type"),
                                        string(row, "result"),
                                        uuid(row, "request_id"),
                                        string(row, "source_ip"),
                                        string(row, "actor_username"),
                                        string(row, "target_username"),
                                        string(row, "repository_name"),
                                        instant(row, "created_at")))
                .toList();
    }

    public void audit(
            UUID actorId,
            UUID targetId,
            UUID repositoryId,
            String eventType,
            String result,
            String sourceIp) {
        mapper.insertAudit(
                UUID.randomUUID(),
                actorId,
                targetId,
                repositoryId,
                eventType,
                result,
                UUID.randomUUID(),
                sourceIp,
                Instant.now());
    }

    private LoginResult issueSession(AccountRow account) {
        String raw = randomToken(32), csrf = randomToken(24);
        Instant now = Instant.now();
        mapper.insertSession(
                PasswordHasher.sha256(raw), account.id(), csrf, now, now.plus(absoluteTimeout));
        return new LoginResult(raw, csrf, account.authenticated());
    }

    private Optional<AccountRow> findByUsername(String username) {
        return username == null
                ? Optional.empty()
                : Optional.ofNullable(account(mapper.findByUsername(username)));
    }

    private Optional<AccountRow> findById(UUID id) {
        return Optional.ofNullable(account(mapper.findById(id)));
    }

    private AccountSummary summary(UUID id) {
        AccountSummaryRow row = mapper.summary(id);
        if (row == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        return summary(row);
    }

    public record LoginResult(String rawToken, String csrfToken, AuthenticatedAccount account) {}

    public record CreatedAccount(AccountSummary account, String temporaryPassword) {}

    public record PermissionView(
            UUID repositoryId, String repositoryName, RepositoryPermission permission) {}

    public record AuditView(
            UUID id,
            String eventType,
            String result,
            UUID requestId,
            String sourceIp,
            String actorUsername,
            String targetUsername,
            String repositoryName,
            Instant createdAt) {}

    public record AccountSummary(
            UUID id,
            String username,
            String displayName,
            AccountRole role,
            String status,
            int repositoryPermissionCount,
            Instant lastLoginAt,
            String lastLoginIp,
            Instant createdAt,
            Instant updatedAt,
            long version) {}

    private record AccountRow(
            UUID id,
            String username,
            String displayName,
            String passwordHash,
            AccountRole role,
            boolean enabled,
            boolean mustChangePassword,
            int failedAttempts,
            Instant lockedUntil,
            Instant temporaryPasswordExpiresAt,
            Instant lastLoginAt,
            String lastLoginIp,
            UUID lastRepositoryId,
            Instant createdAt,
            Instant updatedAt,
            long accountVersion) {
        AuthenticatedAccount authenticated() {
            return new AuthenticatedAccount(
                    id,
                    username,
                    displayName,
                    role,
                    mustChangePassword,
                    lastLoginAt,
                    lastRepositoryId);
        }
    }
}
