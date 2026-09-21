package com.analyzercoder.application.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.RepositoryCredentialMapper;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepositoryCredentialServiceTest {
    private final UUID actorId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();
    private final CredentialSecretCipher cipher =
            new CredentialSecretCipher("credential-master-key-for-tests-123456");
    private final RepositoryCredentialMapper mapper = mock(RepositoryCredentialMapper.class);
    private final GitCredentialExecutor git = mock(GitCredentialExecutor.class);
    private final AuthService auth = mock(AuthService.class);
    private final RemoteRepositoryTargetPolicy remoteTargets =
            mock(RemoteRepositoryTargetPolicy.class);
    private final RepositoryCredentialService service =
            new RepositoryCredentialService(mapper, cipher, git, auth, remoteTargets);
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    actorId, "developer", "Developer", AccountRole.NORMAL, false, Instant.now());

    @Test
    void resolvesCredentialOnlyForMatchingHttpsHost() {
        when(mapper.find(credentialId)).thenReturn(row());

        var resolved =
                service.resolve(actor, credentialId, "https://gitlab.example.com/team/project.git");

        assertEquals("oauth2", resolved.value().username());
        assertEquals("glpat-secret-value", resolved.value().secret());
    }

    @Test
    void rejectsCredentialForDifferentHost() {
        when(mapper.find(credentialId)).thenReturn(row());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.resolve(
                                actor, credentialId, "https://evil.example.com/team/project.git"));
    }

    @Test
    void allowsAnInvalidCredentialToBeValidatedAgain() {
        Map<String, Object> row = row();
        row.put("status", "INVALID");
        when(mapper.find(credentialId)).thenReturn(row);
        String repositoryUrl = "https://gitlab.example.com/team/project.git";

        service.validate(actor, credentialId, repositoryUrl, "127.0.0.1");

        verify(git)
                .validate(
                        repositoryUrl,
                        new GitCredentialExecutor.ResolvedCredential(
                                "oauth2", "glpat-secret-value"));
        verify(mapper)
                .updateValidation(
                        org.mockito.ArgumentMatchers.eq(credentialId),
                        org.mockito.ArgumentMatchers.eq("ACTIVE"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.isNull());
    }

    private Map<String, Object> row() {
        var encrypted = cipher.encrypt("glpat-secret-value");
        Map<String, Object> row = new HashMap<>();
        row.put("id", credentialId);
        row.put("created_by", actorId);
        row.put("credential_type", "GITLAB_PAT");
        row.put("status", "ACTIVE");
        row.put("server_url", "https://gitlab.example.com");
        row.put("username", "oauth2");
        row.put("encrypted_secret", encrypted.cipherText());
        row.put("secret_iv", encrypted.iv());
        return row;
    }
}
