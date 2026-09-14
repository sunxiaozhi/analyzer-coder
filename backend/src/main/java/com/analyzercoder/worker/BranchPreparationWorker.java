package com.analyzercoder.worker;

import com.analyzercoder.application.branch.BranchPreparationJobs;
import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BranchPreparationWorker {
    private final BranchPreparationJobs jobs;
    private final ExecutorService workers;
    private final int concurrency;
    private final AtomicInteger active = new AtomicInteger();

    public BranchPreparationWorker(
            BranchPreparationJobs jobs,
            @Value("${app.repository.branch-concurrency:2}") int concurrency) {
        this.jobs = jobs;
        this.concurrency = Math.max(1, Math.min(concurrency, 8));
        this.workers = Executors.newFixedThreadPool(this.concurrency);
    }

    @Scheduled(fixedDelayString = "${app.repository.branch-poll-interval-ms:2000}")
    public void poll() {
        while (active.get() < concurrency) {
            active.incrementAndGet();
            workers.execute(
                    () -> {
                        try {
                            jobs.processNext();
                        } catch (SQLException ignored) {
                            // The next scheduled poll retries durable queued or stale-running jobs.
                        } finally {
                            active.decrementAndGet();
                        }
                    });
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdown();
    }
}
