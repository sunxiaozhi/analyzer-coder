package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.repository.InMemoryCodeRepositoryStore;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCredentialBindingServiceTest {
    @TempDir Path workspace;

    private final InMemoryCodeRepositoryStore repositories = new InMemoryCodeRepositoryStore();
    private final RepositoryMapper repositoryMapper = mock(RepositoryMapper.class);
    private final RepositoryCredentialService credentials = mock(RepositoryCredentialService.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "owner", "仓库所有者", AccountRole.NORMAL, false, Instant.now());
    private RepositoryCredentialBindingService service;
    private CodeRepository repository;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        repository =
                new CodeRepository(
                        CodeRepositoryId.newId(),
                        "remote",
                        workspace,
                        RepositorySourceType.REMOTE_GIT,
                        "main",
                        "b".repeat(40),
                        null,
                        false,
                        null,
                        null,
                        workspace.resolve(".codegraph"),
                        null,
                        now,
                        now,
                        now);
        repositories.save(repository);
        when(repositoryMapper.findRemoteUrl(repository.id().value()))
                .thenReturn("https://github.com/acme/app.git");
        service =
                new RepositoryCredentialBindingService(
                        repositories, repositoryMapper, credentials, access, auth);
    }

    @Test
    void validatesBeforeBindingAnExistingRepositoryCredential() {
        UUID credentialId = UUID.randomUUID();
        Instant now = Instant.now();
        RepositoryCredentialService.CredentialView credential =
                new RepositoryCredentialService.CredentialView(
                        credentialId,
                        "GIT_HTTP_TOKEN",
                        "GitHub Token",
                        "https://github.com",
                        "git",
                        "••••1234",
                        "ACTIVE",
                        now,
                        actor.id(),
                        now,
                        now);
        when(credentials.validate(
                        actor, credentialId, "https://github.com/acme/app.git", "127.0.0.1"))
                .thenReturn(credential);

        RepositoryCredentialBindingService.BindingStatus result =
                service.bind(actor, repository.id(), credentialId, "127.0.0.1");

        assertThat(result.credential()).isEqualTo(credential);
        verify(access).requireOwner(actor, repository.id());
        verify(credentials).bind(repository.id().value(), credentialId, actor.id());
        verify(auth)
                .audit(
                        actor.id(),
                        credentialId,
                        repository.id().value(),
                        "REPOSITORY_CREDENTIAL_BOUND",
                        "SUCCESS",
                        "127.0.0.1");
    }
}
