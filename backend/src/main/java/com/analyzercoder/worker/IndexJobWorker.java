package com.analyzercoder.worker;

import com.analyzercoder.application.indexing.IndexJobProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期性领取待执行索引任务并交由处理器运行，单次失败不会阻断后续调度。 */
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
