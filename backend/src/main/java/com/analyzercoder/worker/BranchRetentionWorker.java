package com.analyzercoder.worker;

import com.analyzercoder.application.branch.BranchArtifactRetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BranchRetentionWorker {
    private final BranchArtifactRetentionService retention;

    public BranchRetentionWorker(BranchArtifactRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.repository.branch-context-cleanup-ms:3600000}")
    public void expireContexts() {
        retention.deleteExpiredContexts();
    }
}
