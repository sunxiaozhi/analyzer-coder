package com.analyzercoder.worker;

import com.analyzercoder.application.knowledge.KnowledgeDriftJobProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立消费知识失效检查任务，使准备流程可以在 CodeGraph 发布后自动继续。 */
@Component
public class KnowledgeDriftJobWorker {
    private final KnowledgeDriftJobProcessor processor;

    public KnowledgeDriftJobWorker(KnowledgeDriftJobProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.knowledge.drift-poll-interval-ms:3000}")
    public synchronized void pollKnowledgeDriftJobs() {
        processor.expireTimedOutJobs();
        processor.processNextQueuedJob();
    }
}
