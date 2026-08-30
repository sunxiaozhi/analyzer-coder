package com.analyzercoder.application.knowledge;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import org.springframework.stereotype.Service;

/** 为知识失效检查创建独立后台任务，避免在索引或 HTTP 请求线程内执行治理扫描。 */
@Service
public class KnowledgeDriftTaskService {
    private final IndexJobStore tasks;

    public KnowledgeDriftTaskService(IndexJobStore tasks) {
        this.tasks = tasks;
    }

    public synchronized IndexJob start(CodeRepositoryId repositoryId) {
        IndexJob active =
                tasks.findByRepositoryId(repositoryId).stream()
                        .filter(task -> isActive(task.status()))
                        .findFirst()
                        .orElse(null);
        if (active != null && active.type() == IndexJobType.KNOWLEDGE_DRIFT) {
            return active;
        }
        if (active != null) {
            throw new IllegalStateException("仓库已有活动任务，请等待完成后再检查知识失效");
        }
        return tasks.save(IndexJob.create(repositoryId, IndexJobType.KNOWLEDGE_DRIFT));
    }

    private static boolean isActive(IndexJobStatus status) {
        return status == IndexJobStatus.QUEUED
                || status == IndexJobStatus.RUNNING
                || status == IndexJobStatus.CANCEL_REQUESTED;
    }
}
