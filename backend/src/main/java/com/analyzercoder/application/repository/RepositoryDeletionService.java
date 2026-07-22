package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryGovernanceMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryDeletionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryDeletionService.class);
    private final RepositoryGovernanceMapper mapper;
    private final RepositorySnapshotPort managedFiles;

    public RepositoryDeletionService(RepositoryGovernanceMapper mapper, RepositorySnapshotPort managedFiles) {
        this.mapper = mapper;
        this.managedFiles = managedFiles;
    }

    @Transactional
    public UUID claimNext() {
        return mapper.claimNextCleanup();
    }

    public void deleteManagedFiles(UUID repositoryId) {
        managedFiles.deleteRepository(CodeRepositoryId.of(repositoryId));
    }

    @Transactional
    public void complete(UUID repositoryId) {
        mapper.deleteQaConversations(repositoryId);
        mapper.deleteQaMessages(repositoryId);
        mapper.deleteQaSessions(repositoryId);
        mapper.deleteKnowledgeCards(repositoryId);
        mapper.deleteCodeGraphEdges(repositoryId);
        mapper.deleteCodeGraphArtifacts(repositoryId);
        mapper.deleteChunkEmbeddings(repositoryId);
        mapper.deleteCodeChunks(repositoryId);
        mapper.deleteRepositorySnapshots(repositoryId);
        mapper.deleteIndexJobs(repositoryId);
        mapper.deleteRepositoryGrants(repositoryId);
        mapper.deleteRepositoryCredentials(repositoryId);
        mapper.deleteGovernanceLock(repositoryId);
        mapper.finalizeDeletion(repositoryId);
        mapper.completeCleanup(repositoryId);
    }

    @Transactional
    public void fail(UUID repositoryId, RuntimeException exception) {
        String errorCode = exception.getClass().getSimpleName();
        mapper.failCleanup(repositoryId, errorCode.length() > 80 ? errorCode.substring(0, 80) : errorCode);
        LOGGER.warn("Managed repository cleanup failed for {}; it will be retried", repositoryId, exception);
    }
}
