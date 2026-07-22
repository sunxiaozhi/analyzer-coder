package com.analyzercoder.worker;

import com.analyzercoder.application.repository.RepositoryDeletionService;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RepositoryDeletionWorker {
    private final RepositoryDeletionService deletionService;

    public RepositoryDeletionWorker(RepositoryDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @Scheduled(fixedDelayString = "${app.repository.deletion-poll-interval-ms:5000}")
    public void cleanNext() {
        UUID repositoryId = deletionService.claimNext();
        if (repositoryId == null) return;
        try {
            deletionService.deleteManagedFiles(repositoryId);
            deletionService.complete(repositoryId);
        } catch (RuntimeException exception) {
            deletionService.fail(repositoryId, exception);
        }
    }
}
