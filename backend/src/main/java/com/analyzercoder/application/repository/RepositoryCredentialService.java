package com.analyzercoder.application.repository;

import com.analyzercoder.infrastructure.persistence.mapper.RepositoryCredentialMapper;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryCredentialService {
    private static final List<String> TYPES = List.of("GIT_HTTP_TOKEN", "GITLAB_PAT");
    private final RepositoryCredentialMapper mapper;
    private final CredentialSecretCipher cipher;
    private final GitCredentialExecutor git;
    private final AuthService auth;

    public RepositoryCredentialService(RepositoryCredentialMapper mapper, CredentialSecretCipher cipher,
        GitCredentialExecutor git, AuthService auth) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.git = git;
        this.auth = auth;
    }

    public List<CredentialView> list(AuthenticatedAccount actor) {
        return mapper.list(actor.id(), actor.isSuperAdmin()).stream().map(this::view).toList();
    }

    @Transactional
    public CredentialView create(AuthenticatedAccount actor, CredentialInput input, String sourceIp) {
        ValidatedInput value = validateInput(input, true);
        UUID id = UUID.randomUUID();
        var encrypted = cipher.encrypt(value.secret());
        mapper.insert(id, value.type(), value.displayName(), value.serverUrl(), value.username(),
            encrypted.cipherText(), encrypted.iv(), encrypted.digest(), encrypted.algorithm(),
            mask(value.secret()), actor.id());
        auth.audit(actor.id(), null, null, "REPOSITORY_CREDENTIAL_CREATED", "SUCCESS", sourceIp);
        return view(requireOwned(actor, id));
    }

    @Transactional
    public CredentialView update(AuthenticatedAccount actor, UUID id, CredentialInput input, String sourceIp) {
        Map<String, Object> existing = requireOwned(actor, id);
        boolean replaceSecret = input.secret() != null && !input.secret().isBlank();
        ValidatedInput value = validateInput(input, replaceSecret);
        CredentialSecretCipher.EncryptedSecret encrypted = replaceSecret ? cipher.encrypt(value.secret()) : null;
        mapper.update(id, value.type(), value.displayName(), value.serverUrl(), value.username(),
            encrypted == null ? null : encrypted.cipherText(), encrypted == null ? null : encrypted.iv(),
            encrypted == null ? null : encrypted.digest(), encrypted == null ? null : encrypted.algorithm(),
            replaceSecret ? mask(value.secret()) : string(existing, "masked_value"), actor.id(), replaceSecret);
        auth.audit(actor.id(), null, null, "REPOSITORY_CREDENTIAL_UPDATED", "SUCCESS", sourceIp);
        return view(requireOwned(actor, id));
    }

    public CredentialView validate(AuthenticatedAccount actor, UUID id, String repositoryUrl, String sourceIp) {
        RemoteRepositoryTargetPolicy.requireAllowed(repositoryUrl);
        Resolved resolved = resolve(actor, id, repositoryUrl);
        try {
            git.validate(repositoryUrl, resolved.value());
            mapper.updateValidation(id, "ACTIVE", Instant.now(), null);
            auth.audit(actor.id(), null, null, "REPOSITORY_CREDENTIAL_VALIDATED", "SUCCESS", sourceIp);
        } catch (RuntimeException exception) {
            mapper.updateValidation(id, "INVALID", Instant.now(), safeError(exception));
            auth.audit(actor.id(), null, null, "REPOSITORY_CREDENTIAL_VALIDATED", "DENIED", sourceIp);
            throw exception;
        }
        return view(requireOwned(actor, id));
    }

    public Resolved resolve(AuthenticatedAccount actor, UUID id, String repositoryUrl) {
        Map<String, Object> row = requireOwned(actor, id);
        return resolveRow(row,repositoryUrl);
    }

    public Resolved resolveInternal(UUID id,String repositoryUrl){
        Map<String,Object> row=mapper.find(id);if(row==null)throw new IllegalArgumentException("Git 凭据不存在");
        return resolveRow(row,repositoryUrl);
    }

    private Resolved resolveRow(Map<String,Object> row,String repositoryUrl){
        if (!"ACTIVE".equals(string(row, "status"))) throw new IllegalStateException("所选 Git 凭据当前不可用");
        requireMatchingServer(string(row, "server_url"), repositoryUrl);
        String username = string(row, "username");
        if (username == null || username.isBlank()) username = "GITLAB_PAT".equals(string(row, "credential_type")) ? "oauth2" : "git";
        return new Resolved(uuid(row,"id"), new GitCredentialExecutor.ResolvedCredential(username,
            cipher.decrypt(string(row, "encrypted_secret"), string(row, "secret_iv"))));
    }

    public Resolved resolveBound(AuthenticatedAccount actor, UUID repositoryId, String repositoryUrl) {
        Map<String, Object> row = mapper.findBound(repositoryId);
        if (row == null) return null;
        return resolve(actor, uuid(row, "id"), repositoryUrl);
    }

    @Transactional
    public CredentialView setEnabled(AuthenticatedAccount actor, UUID id, boolean enabled, String sourceIp) {
        requireOwned(actor, id);
        mapper.updateStatus(id, enabled ? "ACTIVE" : "DISABLED", actor.id());
        auth.audit(actor.id(), null, null, enabled ? "REPOSITORY_CREDENTIAL_ENABLED" : "REPOSITORY_CREDENTIAL_DISABLED", "SUCCESS", sourceIp);
        return view(requireOwned(actor, id));
    }

    @Transactional
    public void delete(AuthenticatedAccount actor, UUID id, String sourceIp) {
        requireOwned(actor, id);
        int bindings = mapper.countBindings(id);
        if (bindings > 0) throw new IllegalStateException("凭据仍被 " + bindings + " 个仓库使用，请先更换或解绑");
        mapper.delete(id);
        auth.audit(actor.id(), null, null, "REPOSITORY_CREDENTIAL_DELETED", "SUCCESS", sourceIp);
    }

    public List<BindingView> bindings(AuthenticatedAccount actor, UUID id) {
        requireOwned(actor, id);
        return mapper.bindings(id).stream().map(row -> new BindingView(uuid(row,"repository_id"),
            string(row,"repository_name"), string(row,"usage_type"), instant(row,"created_at"))).toList();
    }

    public void unbind(AuthenticatedAccount actor, UUID repositoryId) {
        Map<String,Object> bound = mapper.findBound(repositoryId);
        if (bound != null) requireOwned(actor, uuid(bound,"id"));
        mapper.unbind(repositoryId);
    }

    public void bind(UUID repositoryId, UUID credentialId, UUID actorId) {
        mapper.bind(repositoryId, credentialId, actorId);
    }

    private Map<String, Object> requireOwned(AuthenticatedAccount actor, UUID id) {
        Map<String, Object> row = mapper.find(id);
        if (row == null) throw new IllegalArgumentException("Git 凭据不存在");
        UUID owner = uuid(row, "created_by");
        if (!actor.isSuperAdmin() && !actor.id().equals(owner)) {
            throw new ApiSecurityException(403, "FORBIDDEN", "无权使用该 Git 凭据");
        }
        return row;
    }

    private static ValidatedInput validateInput(CredentialInput input, boolean requireSecret) {
        String type = normalized(input.type()).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的 Git 凭据类型");
        String name = normalized(input.displayName());
        if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("凭据名称长度必须为 1-100 个字符");
        String serverUrl = normalized(input.serverUrl());
        URI uri;
        try { uri = URI.create(serverUrl); } catch (RuntimeException exception) { throw new IllegalArgumentException("Git 服务地址格式无效"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("凭据服务地址必须是无内嵌用户信息的 HTTPS 地址");
        }
        String username = normalized(input.username());
        String secret = input.secret() == null ? "" : input.secret().trim();
        if (requireSecret && secret.length() < 8) throw new IllegalArgumentException("访问令牌至少包含 8 个字符");
        return new ValidatedInput(type, name, stripPath(serverUrl), username, secret);
    }

    private static void requireMatchingServer(String configured, String target) {
        URI allowed = URI.create(configured);
        URI actual = URI.create(target);
        int allowedPort = allowed.getPort() < 0 ? 443 : allowed.getPort();
        int actualPort = actual.getPort() < 0 ? 443 : actual.getPort();
        if (!"https".equalsIgnoreCase(actual.getScheme()) || !allowed.getHost().equalsIgnoreCase(actual.getHost())
            || allowedPort != actualPort) throw new IllegalArgumentException("所选凭据与远程仓库主机不匹配");
    }

    private CredentialView view(Map<String, Object> row) {
        return new CredentialView(uuid(row, "id"), string(row, "credential_type"), string(row, "display_name"),
            string(row, "server_url"), string(row, "username"), string(row, "masked_value"),
            string(row, "status"), instant(row, "last_validated_at"), uuid(row, "created_by"),
            instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static String stripPath(String value) {
        URI uri = URI.create(value);
        String authority = uri.getRawAuthority();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority;
    }
    private static String mask(String secret) { return "••••" + secret.substring(Math.max(0, secret.length() - 4)); }
    private static String safeError(RuntimeException exception) { String value=exception.getMessage();return value==null?"检测失败":value.substring(0,Math.min(240,value.length())); }
    private static String normalized(String value) { return value == null ? "" : value.trim(); }
    private static Object value(Map<String, Object> row, String key) { Object v=row.get(key);return v==null?row.get(key.toUpperCase(Locale.ROOT)):v; }
    private static String string(Map<String, Object> row, String key) { Object v=value(row,key);return v==null?null:v.toString(); }
    private static UUID uuid(Map<String, Object> row, String key) { Object v=value(row,key);return v==null?null:v instanceof UUID id?id:UUID.fromString(v.toString()); }
    private static Instant instant(Map<String, Object> row, String key) { Object v=value(row,key);if(v==null)return null;if(v instanceof Instant i)return i;if(v instanceof java.sql.Timestamp t)return t.toInstant();return Instant.parse(v.toString()); }

    public record CredentialInput(String type, String displayName, String serverUrl, String username, String secret) {}
    public record CredentialView(UUID id, String type, String displayName, String serverUrl, String username,
        String maskedValue, String status, Instant lastValidatedAt, UUID createdBy, Instant createdAt, Instant updatedAt) {}
    public record Resolved(UUID id, GitCredentialExecutor.ResolvedCredential value) {}
    public record BindingView(UUID repositoryId, String repositoryName, String usageType, Instant createdAt) {}
    private record ValidatedInput(String type, String displayName, String serverUrl, String username, String secret) {}
}
