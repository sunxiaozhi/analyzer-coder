package com.analyzercoder.application.branch;

import com.analyzercoder.application.repository.GitCredentialExecutor;
import com.analyzercoder.application.repository.RepositoryCredentialService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BranchRemoteService {
    private final RepositoryMapper repositories;
    private final RepositoryCredentialService credentials;
    private final GitCredentialExecutor git;
    private final AccessControlService access;

    public BranchRemoteService(
            RepositoryMapper repositories,
            RepositoryCredentialService credentials,
            GitCredentialExecutor git,
            AccessControlService access) {
        this.repositories = repositories;
        this.credentials = credentials;
        this.git = git;
        this.access = access;
    }

    public List<GitCredentialExecutor.RemoteBranch> discover(
            AuthenticatedAccount actor, UUID repoId) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.MAINTAIN);
        String url = url(repoId);
        var credential = credentials.resolveBound(actor, repoId, url);
        return git.discoverBranches(url, credential == null ? null : credential.value());
    }

    public String fetch(AuthenticatedAccount actor, CodeRepository repository, String branch) {
        access.require(actor, repository.id(), RepositoryPermission.MAINTAIN);
        String url = url(repository.id().value());
        var credential = credentials.resolveBound(actor, repository.id().value(), url);
        return git.fetchBranch(
                repository.path(), url, branch, credential == null ? null : credential.value());
    }

    private String url(UUID repoId) {
        String url = repositories.findRemoteUrl(repoId);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("当前仓库未配置远程地址");
        }
        RemoteRepositoryTargetPolicy.requireAllowed(url);
        return url;
    }
}
