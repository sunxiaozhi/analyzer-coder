package com.analyzercoder.security;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,32}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID_LOGIN = "用户名、密码或账号状态不正确";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordHasher passwordHasher;
    private final String initialAdminUsername;
    private final String initialAdminPassword;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final Duration lockDuration;

    public AuthService(
        JdbcTemplate jdbcTemplate,
        PasswordHasher passwordHasher,
        @Value("${app.security.initial-admin-username:}") String initialAdminUsername,
        @Value("${app.security.initial-admin-password:}") String initialAdminPassword,
        @Value("${app.security.session-idle-minutes:30}") long idleMinutes,
        @Value("${app.security.session-max-hours:12}") long maxHours,
        @Value("${app.security.lock-minutes:15}") long lockMinutes
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordHasher = passwordHasher;
        this.initialAdminUsername = initialAdminUsername;
        this.initialAdminPassword = initialAdminPassword;
        this.idleTimeout = Duration.ofMinutes(idleMinutes);
        this.absoluteTimeout = Duration.ofHours(maxHours);
        this.lockDuration = Duration.ofMinutes(lockMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeFirstAdmin() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        if (count != null && count > 0) return;
        if (initialAdminUsername.isBlank() || initialAdminPassword.isBlank()) {
            LOGGER.warn("No accounts exist. Set APP_INITIAL_ADMIN_USERNAME and APP_INITIAL_ADMIN_PASSWORD, then restart.");
            return;
        }
        validateUsername(initialAdminUsername);
        passwordHasher.validate(initialAdminUsername, initialAdminPassword);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO accounts (
                id, username, display_name, password_hash, account_role, enabled,
                must_change_password, failed_attempts, temporary_password_expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'SUPER_ADMIN', TRUE, TRUE, 0, ?, ?, ?)
            """,
            id, initialAdminUsername.trim(), "系统管理员", passwordHasher.hash(initialAdminPassword),
            Timestamp.from(now.plus(Duration.ofHours(24))), Timestamp.from(now), Timestamp.from(now)
        );
        audit(null, id, null, "INITIAL_ADMIN_CREATED", "SUCCESS", null);
        LOGGER.info("Initial super administrator created; password value was not logged");
    }

    @Transactional
    public LoginResult login(String username, String password, String sourceIp) {
        Optional<AccountRow> optional = findByUsername(username);
        if (optional.isEmpty()) {
            audit(null, null, null, "LOGIN_FAILED", "DENIED", sourceIp);
            throw new ApiSecurityException(401, "INVALID_CREDENTIALS", INVALID_LOGIN);
        }
        AccountRow account = optional.get();
        Instant now = Instant.now();
        if (!account.enabled() || (account.lockedUntil() != null && account.lockedUntil().isAfter(now))) {
            audit(account.id(), account.id(), null, "LOGIN_FAILED", "DENIED", sourceIp);
            throw new ApiSecurityException(401, "INVALID_CREDENTIALS", INVALID_LOGIN);
        }
        if (account.temporaryPasswordExpiresAt() != null && account.mustChangePassword()
            && account.temporaryPasswordExpiresAt().isBefore(now)) {
            throw new ApiSecurityException(401, "TEMPORARY_PASSWORD_EXPIRED", "临时密码已过期，请联系管理员重置");
        }
        if (!passwordHasher.matches(password == null ? "" : password, account.passwordHash())) {
            int failures = account.failedAttempts() + 1;
            Instant lockedUntil = failures >= 5 ? now.plus(lockDuration) : null;
            jdbcTemplate.update(
                "UPDATE accounts SET failed_attempts = ?, locked_until = ?, updated_at = ? WHERE id = ?",
                failures, timestamp(lockedUntil), Timestamp.from(now), account.id()
            );
            audit(account.id(), account.id(), null, failures >= 5 ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", "DENIED", sourceIp);
            String code = failures >= 3 && failures < 5 ? "CAPTCHA_REQUIRED" : "INVALID_CREDENTIALS";
            throw new ApiSecurityException(401, code, INVALID_LOGIN);
        }

        jdbcTemplate.update(
            """
            UPDATE accounts SET failed_attempts = 0, locked_until = NULL, last_login_at = ?,
                last_login_ip = ?, updated_at = ? WHERE id = ?
            """,
            Timestamp.from(now), sourceIp, Timestamp.from(now), account.id()
        );
        AccountRow updated = findById(account.id()).orElseThrow();
        LoginResult result = issueSession(updated);
        audit(account.id(), account.id(), null, "LOGIN_SUCCEEDED", "SUCCESS", sourceIp);
        return result;
    }

    public Optional<AuthenticatedSession> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String tokenHash = PasswordHasher.sha256(rawToken);
        List<SessionRow> rows = jdbcTemplate.query(
            """
            SELECT s.token_hash, s.csrf_token, s.created_at, s.last_seen_at, s.expires_at,
                   a.id, a.username, a.display_name, a.account_role, a.enabled,
                   a.must_change_password, a.last_login_at
            FROM login_sessions s JOIN accounts a ON a.id = s.account_id
            WHERE s.token_hash = ?
            """,
            AuthService::mapSession, tokenHash
        );
        if (rows.isEmpty()) return Optional.empty();
        SessionRow row = rows.get(0);
        Instant now = Instant.now();
        if (!row.enabled() || row.expiresAt().isBefore(now) || row.lastSeenAt().plus(idleTimeout).isBefore(now)) {
            jdbcTemplate.update("DELETE FROM login_sessions WHERE token_hash = ?", tokenHash);
            return Optional.empty();
        }
        jdbcTemplate.update("UPDATE login_sessions SET last_seen_at = ? WHERE token_hash = ?", Timestamp.from(now), tokenHash);
        return Optional.of(new AuthenticatedSession(tokenHash, row.csrfToken(), row.account()));
    }

    @Transactional
    public LoginResult changePassword(
        AuthenticatedSession session,
        String currentPassword,
        String newPassword,
        String sourceIp
    ) {
        AccountRow account = findById(session.account().id()).orElseThrow();
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw new ApiSecurityException(400, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        passwordHasher.validate(account.username(), newPassword);
        jdbcTemplate.update(
            """
            UPDATE accounts SET password_hash = ?, must_change_password = FALSE,
                temporary_password_expires_at = NULL, updated_at = ? WHERE id = ?
            """,
            passwordHasher.hash(newPassword), Timestamp.from(Instant.now()), account.id()
        );
        jdbcTemplate.update("DELETE FROM login_sessions WHERE account_id = ?", account.id());
        audit(account.id(), account.id(), null, "PASSWORD_CHANGED", "SUCCESS", sourceIp);
        return issueSession(findById(account.id()).orElseThrow());
    }

    public void logout(AuthenticatedSession session, String sourceIp) {
        jdbcTemplate.update("DELETE FROM login_sessions WHERE token_hash = ?", session.tokenHash());
        audit(session.account().id(), session.account().id(), null, "LOGOUT", "SUCCESS", sourceIp);
    }

    public List<AccountSummary> listAccounts() {
        return jdbcTemplate.query(
            """
            SELECT a.*, (SELECT COUNT(*) FROM repository_permissions p WHERE p.account_id = a.id) permission_count
            FROM accounts a ORDER BY a.created_at, a.id
            """,
            AuthService::mapSummary
        );
    }

    @Transactional
    public CreatedAccount createAccount(
        UUID actorId,
        String username,
        String displayName,
        AccountRole role,
        String suppliedTemporaryPassword,
        String sourceIp
    ) {
        validateUsername(username);
        validateDisplayName(displayName);
        String temporaryPassword = suppliedTemporaryPassword == null || suppliedTemporaryPassword.isBlank()
            ? generateTemporaryPassword() : suppliedTemporaryPassword;
        passwordHasher.validate(username, temporaryPassword);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO accounts (
                id, username, display_name, password_hash, account_role, enabled,
                must_change_password, failed_attempts, temporary_password_expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, TRUE, TRUE, 0, ?, ?, ?)
            """,
            id, username.trim(), displayName.trim(), passwordHasher.hash(temporaryPassword), role.name(),
            Timestamp.from(now.plus(Duration.ofHours(24))), Timestamp.from(now), Timestamp.from(now)
        );
        audit(actorId, id, null, "ACCOUNT_CREATED", "SUCCESS", sourceIp);
        return new CreatedAccount(summary(id), temporaryPassword);
    }

    @Transactional
    public AccountSummary updateAccount(
        UUID actorId,
        UUID targetId,
        String displayName,
        AccountRole role,
        Boolean enabled,
        String sourceIp
    ) {
        AccountRow current = findById(targetId).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        String nextName = displayName == null ? current.displayName() : displayName.trim();
        AccountRole nextRole = role == null ? current.role() : role;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        validateDisplayName(nextName);
        if (actorId.equals(targetId) && !nextEnabled) throw new IllegalStateException("Cannot disable the current account");
        if (current.role() == AccountRole.SUPER_ADMIN && current.enabled()
            && (nextRole != AccountRole.SUPER_ADMIN || !nextEnabled) && enabledAdminCount() <= 1) {
            throw new IllegalStateException("At least one enabled super administrator is required");
        }
        jdbcTemplate.update(
            "UPDATE accounts SET display_name = ?, account_role = ?, enabled = ?, updated_at = ? WHERE id = ?",
            nextName, nextRole.name(), nextEnabled, Timestamp.from(Instant.now()), targetId
        );
        if (!nextEnabled) jdbcTemplate.update("DELETE FROM login_sessions WHERE account_id = ?", targetId);
        audit(actorId, targetId, null, nextEnabled ? "ACCOUNT_UPDATED" : "ACCOUNT_DISABLED", "SUCCESS", sourceIp);
        return summary(targetId);
    }

    @Transactional
    public String resetPassword(UUID actorId, UUID targetId, String suppliedPassword, String sourceIp) {
        AccountRow target = findById(targetId).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        String temporaryPassword = suppliedPassword == null || suppliedPassword.isBlank()
            ? generateTemporaryPassword() : suppliedPassword;
        passwordHasher.validate(target.username(), temporaryPassword);
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            UPDATE accounts SET password_hash = ?, must_change_password = TRUE,
                temporary_password_expires_at = ?, failed_attempts = 0, locked_until = NULL, updated_at = ?
            WHERE id = ?
            """,
            passwordHasher.hash(temporaryPassword), Timestamp.from(now.plus(Duration.ofHours(24))),
            Timestamp.from(now), targetId
        );
        jdbcTemplate.update("DELETE FROM login_sessions WHERE account_id = ?", targetId);
        audit(actorId, targetId, null, "PASSWORD_RESET", "SUCCESS", sourceIp);
        return temporaryPassword;
    }

    public void unlock(UUID actorId, UUID targetId, String sourceIp) {
        jdbcTemplate.update(
            "UPDATE accounts SET failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE id = ?",
            Timestamp.from(Instant.now()), targetId
        );
        audit(actorId, targetId, null, "ACCOUNT_UNLOCKED", "SUCCESS", sourceIp);
    }

    public List<PermissionView> permissions(UUID accountId) {
        return jdbcTemplate.query(
            """
            SELECT p.repo_id, r.name, p.permission_level
            FROM repository_permissions p JOIN repositories r ON r.id = p.repo_id
            WHERE p.account_id = ? ORDER BY r.name, r.id
            """,
            (rs, rowNum) -> new PermissionView(
                rs.getObject("repo_id", UUID.class), rs.getString("name"),
                RepositoryPermission.valueOf(rs.getString("permission_level"))
            ), accountId
        );
    }

    @Transactional
    public void setPermission(
        UUID actorId,
        UUID accountId,
        UUID repositoryId,
        RepositoryPermission permission,
        String sourceIp
    ) {
        findById(accountId).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (permission == null) {
            jdbcTemplate.update("DELETE FROM repository_permissions WHERE account_id = ? AND repo_id = ?", accountId, repositoryId);
            audit(actorId, accountId, repositoryId, "REPOSITORY_PERMISSION_REVOKED", "SUCCESS", sourceIp);
            return;
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO repository_permissions (account_id, repo_id, permission_level, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (account_id, repo_id) DO UPDATE SET
                permission_level = EXCLUDED.permission_level, updated_at = EXCLUDED.updated_at
            """,
            accountId, repositoryId, permission.name(), Timestamp.from(now), Timestamp.from(now)
        );
        audit(actorId, accountId, repositoryId, "REPOSITORY_PERMISSION_CHANGED", "SUCCESS", sourceIp);
    }

    public List<AuditView> auditEvents(int limit, int offset) {
        return jdbcTemplate.query(
            """
            SELECT e.id, e.event_type, e.result, e.request_id, e.source_ip, e.created_at,
                   actor.username actor_username, target.username target_username, r.name repository_name
            FROM audit_events e
            LEFT JOIN accounts actor ON actor.id = e.actor_account_id
            LEFT JOIN accounts target ON target.id = e.target_account_id
            LEFT JOIN repositories r ON r.id = e.target_repo_id
            ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?
            """,
            (rs, rowNum) -> new AuditView(
                rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("result"),
                rs.getObject("request_id", UUID.class), rs.getString("source_ip"),
                rs.getString("actor_username"), rs.getString("target_username"),
                rs.getString("repository_name"), rs.getTimestamp("created_at").toInstant()
            ), Math.min(Math.max(limit, 1), 200), Math.max(offset, 0)
        );
    }

    public void audit(UUID actorId, UUID targetId, UUID repositoryId, String eventType, String result, String sourceIp) {
        jdbcTemplate.update(
            """
            INSERT INTO audit_events (
                id, actor_account_id, target_account_id, target_repo_id, event_type,
                result, request_id, source_ip, details, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
            """,
            UUID.randomUUID(), actorId, targetId, repositoryId, eventType, result,
            UUID.randomUUID(), sourceIp, Timestamp.from(Instant.now())
        );
    }

    private LoginResult issueSession(AccountRow account) {
        String rawToken = randomToken(32);
        String csrfToken = randomToken(24);
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO login_sessions (token_hash, account_id, csrf_token, created_at, last_seen_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            PasswordHasher.sha256(rawToken), account.id(), csrfToken, Timestamp.from(now),
            Timestamp.from(now), Timestamp.from(now.plus(absoluteTimeout))
        );
        return new LoginResult(rawToken, csrfToken, account.authenticated());
    }

    private Optional<AccountRow> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return jdbcTemplate.query(
            "SELECT * FROM accounts WHERE LOWER(BTRIM(username)) = LOWER(BTRIM(?))",
            AuthService::mapAccount, username
        ).stream().findFirst();
    }

    private Optional<AccountRow> findById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM accounts WHERE id = ?", AuthService::mapAccount, id).stream().findFirst();
    }

    private AccountSummary summary(UUID id) {
        return jdbcTemplate.query(
            """
            SELECT a.*, (SELECT COUNT(*) FROM repository_permissions p WHERE p.account_id = a.id) permission_count
            FROM accounts a WHERE a.id = ?
            """,
            AuthService::mapSummary, id
        ).stream().findFirst().orElseThrow();
    }

    private int enabledAdminCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM accounts WHERE account_role = 'SUPER_ADMIN' AND enabled = TRUE", Integer.class
        );
        return count == null ? 0 : count;
    }

    private static AccountRow mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRow(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
            rs.getString("password_hash"), AccountRole.valueOf(rs.getString("account_role")),
            rs.getBoolean("enabled"), rs.getBoolean("must_change_password"), rs.getInt("failed_attempts"),
            instant(rs.getTimestamp("locked_until")), instant(rs.getTimestamp("temporary_password_expires_at")),
            instant(rs.getTimestamp("last_login_at")), rs.getString("last_login_ip"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        AuthenticatedAccount account = new AuthenticatedAccount(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
            AccountRole.valueOf(rs.getString("account_role")), rs.getBoolean("must_change_password"),
            instant(rs.getTimestamp("last_login_at"))
        );
        return new SessionRow(
            rs.getString("token_hash"), rs.getString("csrf_token"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            rs.getBoolean("enabled"), account
        );
    }

    private static AccountSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        Instant locked = instant(rs.getTimestamp("locked_until"));
        String status = !rs.getBoolean("enabled") ? "DISABLED"
            : locked != null && locked.isAfter(Instant.now()) ? "LOCKED"
            : rs.getBoolean("must_change_password") ? "PASSWORD_CHANGE_REQUIRED" : "ENABLED";
        return new AccountSummary(
            rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
            AccountRole.valueOf(rs.getString("account_role")), status, rs.getInt("permission_count"),
            instant(rs.getTimestamp("last_login_at")), rs.getString("last_login_ip"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must be 3-32 letters, digits, dots, underscores or hyphens");
        }
    }

    private static void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 50) {
            throw new IllegalArgumentException("Display name length must be between 1 and 50 characters");
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

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record LoginResult(String rawToken, String csrfToken, AuthenticatedAccount account) {}
    public record CreatedAccount(AccountSummary account, String temporaryPassword) {}
    public record PermissionView(UUID repositoryId, String repositoryName, RepositoryPermission permission) {}
    public record AuditView(
        UUID id, String eventType, String result, UUID requestId, String sourceIp,
        String actorUsername, String targetUsername, String repositoryName, Instant createdAt
    ) {}
    public record AccountSummary(
        UUID id, String username, String displayName, AccountRole role, String status, int repositoryPermissionCount,
        Instant lastLoginAt, String lastLoginIp, Instant createdAt, Instant updatedAt
    ) {}

    private record AccountRow(
        UUID id, String username, String displayName, String passwordHash, AccountRole role, boolean enabled,
        boolean mustChangePassword, int failedAttempts, Instant lockedUntil, Instant temporaryPasswordExpiresAt,
        Instant lastLoginAt, String lastLoginIp, Instant createdAt, Instant updatedAt
    ) {
        AuthenticatedAccount authenticated() {
            return new AuthenticatedAccount(id, username, displayName, role, mustChangePassword, lastLoginAt);
        }
    }

    private record SessionRow(
        String tokenHash, String csrfToken, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
        boolean enabled, AuthenticatedAccount account
    ) {}
}
