package com.analyzercoder.worker;

import com.analyzercoder.application.intelligence.CodeGraphJobProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立领取 CodeGraph 任务，避免 HTTP 请求线程执行文件复制和外部 CLI。 */
@Component
public class CodeGraphJobWorker {
    private final CodeGraphJobProcessor processor;

    public CodeGraphJobWorker(CodeGraphJobProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.codegraph.poll-interval-ms:2000}")
    public synchronized void pollCodeGraphJobs() {
        processor.expireTimedOutJobs();
        processor.processNextQueuedJob();
    }
}
