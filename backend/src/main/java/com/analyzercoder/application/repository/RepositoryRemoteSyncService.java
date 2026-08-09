package com.analyzercoder.application.repository;

import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.application.indexing.StartIndexCommand;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import org.springframework.stereotype.Service;

@Service
public class RepositoryRemoteSyncService {
    private final RegisterRepositoryUseCase repositories;
    private final RepositoryMapper mapper;
    private final RepositoryCredentialService credentials;
    private final GitCredentialExecutor git;
    private final AccessControlService access;
    private final IndexJobUseCase indexJobs;

    public RepositoryRemoteSyncService(RegisterRepositoryUseCase repositories, RepositoryMapper mapper,
        RepositoryCredentialService credentials, GitCredentialExecutor git, AccessControlService access,
        IndexJobUseCase indexJobs) {
        this.repositories=repositories;this.mapper=mapper;this.credentials=credentials;this.git=git;
        this.access=access;this.indexJobs=indexJobs;
    }

    public SyncResult sync(AuthenticatedAccount actor, CodeRepositoryId repositoryId) {
        access.require(actor, repositoryId, RepositoryPermission.MAINTAIN);
        var repository = repositories.get(repositoryId);
        if (repository.sourceType()!=RepositorySourceType.REMOTE_GIT && repository.sourceType()!=RepositorySourceType.GITLAB)
            throw new IllegalArgumentException("只有远程 Git 或 GitLab 仓库可以拉取远端更新");
        String remoteUrl=mapper.findRemoteUrl(repositoryId.value());
        if(remoteUrl==null||remoteUrl.isBlank())throw new IllegalStateException("该仓库缺少远程地址，请重新接入");
        var resolved=credentials.resolveBound(actor,repositoryId.value(),remoteUrl);
        git.syncRepository(repository.path(),repository.defaultBranch(),resolved==null?null:resolved.value());
        RepositoryScanResult scan=repositories.rescan(repositoryId);
        java.util.UUID jobId=null;
        if(scan.changed())jobId=indexJobs.start(new StartIndexCommand(repositoryId,IndexJobType.INCREMENTAL)).id().value();
        return new SyncResult(scan.changed(),scan.repository(),jobId);
    }

    public record SyncResult(boolean changed, com.analyzercoder.domain.repository.CodeRepository repository,
        java.util.UUID indexJobId) {}
}
