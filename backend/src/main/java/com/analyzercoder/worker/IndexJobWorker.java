package com.analyzercoder.worker;

import com.analyzercoder.application.indexing.IndexJobProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IndexJobWorker {

    private final IndexJobProcessor indexJobProcessor;

    public IndexJobWorker(IndexJobProcessor indexJobProcessor) {
        this.indexJobProcessor = indexJobProcessor;
    }

    @Scheduled(fixedDelayString = "${app.indexing.poll-interval-ms:5000}")
    public synchronized void pollIndexJobs() {
        indexJobProcessor.processNextQueuedJob();
    }
}

