package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 为已有远程仓库提供可审计的凭据查询、验证绑定和解绑流程。 */
@Service
public class RepositoryCredentialBindingService {
    private final CodeRepositoryStore repositories;
    private final RepositoryMapper repositoryMapper;
    private final RepositoryCredentialService credentials;
    private final AccessControlService access;
    private final AuthService auth;

    public RepositoryCredentialBindingService(
            CodeRepositoryStore repositories,
            RepositoryMapper repositoryMapper,
            RepositoryCredentialService credentials,
            AccessControlService access,
            AuthService auth) {
        this.repositories = repositories;
        this.repositoryMapper = repositoryMapper;
        this.credentials = credentials;
        this.access = access;
        this.auth = auth;
    }

    public BindingStatus current(AuthenticatedAccount actor, CodeRepositoryId repositoryId) {
        String remoteUrl = remoteUrl(actor, repositoryId);
        return new BindingStatus(remoteUrl, credentials.boundView(repositoryId.value()));
    }

    public BindingStatus bind(
            AuthenticatedAccount actor,
            CodeRepositoryId repositoryId,
            UUID credentialId,
            String sourceIp) {
        if (credentialId == null) {
            throw new IllegalArgumentException("请选择 Git 凭据");
        }
        String remoteUrl = remoteUrl(actor, repositoryId);
        RepositoryCredentialService.CredentialView validated =
                credentials.validate(actor, credentialId, remoteUrl, sourceIp);
        credentials.bind(repositoryId.value(), credentialId, actor.id());
        auth.audit(
                actor.id(),
                credentialId,
                repositoryId.value(),
                "REPOSITORY_CREDENTIAL_BOUND",
                "SUCCESS",
                sourceIp);
        return new BindingStatus(remoteUrl, validated);
    }

    @Transactional
    public void unbind(AuthenticatedAccount actor, CodeRepositoryId repositoryId, String sourceIp) {
        remoteUrl(actor, repositoryId);
        RepositoryCredentialService.CredentialView bound =
                credentials.boundView(repositoryId.value());
        credentials.unbindForRepository(repositoryId.value());
        auth.audit(
                actor.id(),
                bound == null ? null : bound.id(),
                repositoryId.value(),
                "REPOSITORY_CREDENTIAL_UNBOUND",
                "SUCCESS",
                sourceIp);
    }

    private String remoteUrl(AuthenticatedAccount actor, CodeRepositoryId repositoryId) {
        if (actor == null || repositoryId == null) {
            throw new IllegalArgumentException("仓库凭据请求不完整");
        }
        access.requireOwner(actor, repositoryId);
        repositories
                .findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("代码仓库不存在"));
        String remoteUrl = repositoryMapper.findRemoteUrl(repositoryId.value());
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalStateException("只有远程 Git 仓库可以绑定访问凭据");
        }
        return remoteUrl;
    }

    public record BindingStatus(
            String remoteUrl, RepositoryCredentialService.CredentialView credential) {}
}
