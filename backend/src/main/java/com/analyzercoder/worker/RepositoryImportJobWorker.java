package com.analyzercoder.worker;

import com.analyzercoder.application.repository.RepositoryImportJobService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时认领并执行远程仓库导入任务。 */
@Component
public class RepositoryImportJobWorker {
    private final RepositoryImportJobService service;

    public RepositoryImportJobWorker(RepositoryImportJobService service) {
        this.service = service;
    }

    /** 单实例内串行轮询，防止上一次导入尚未结束时再次触发。 */
    @Scheduled(fixedDelayString = "${app.repository.import-poll-interval-ms:2000}")
    public synchronized void poll() {
        service.processNext();
    }
}
