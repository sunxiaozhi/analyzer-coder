package com.analyzercoder.worker;

import com.analyzercoder.application.branch.BranchPreparationJobs;
import java.sql.SQLException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BranchPreparationWorker {
    private final BranchPreparationJobs jobs;

    public BranchPreparationWorker(BranchPreparationJobs jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${app.repository.branch-poll-interval-ms:2000}")
    public void poll() throws SQLException {
        jobs.processNext();
    }
}
