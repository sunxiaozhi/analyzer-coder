package com.analyzercoder.application.intelligence;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import org.springframework.stereotype.Service;

/** 维护代码图谱异步任务的生命周期，防止同一仓库重复提交冲突任务。 */
@Service
public class CodeGraphTaskService {
    private final IndexJobStore tasks;

    public CodeGraphTaskService(IndexJobStore tasks) {
        this.tasks = tasks;
    }

    public synchronized IndexJob start(CodeRepositoryId repositoryId) {
        IndexJob active =
                tasks.findByRepositoryId(repositoryId).stream()
                        .filter(
                                task ->
                                        task.status() == IndexJobStatus.QUEUED
                                                || task.status() == IndexJobStatus.RUNNING
                                                || task.status() == IndexJobStatus.CANCEL_REQUESTED)
                        .findFirst()
                        .orElse(null);
        if (active != null && active.type() == IndexJobType.CODEGRAPH) {
            return active;
        }
        if (active != null) {
            throw new IllegalStateException("仓库已有活动任务，请等待完成后再构建 CodeGraph");
        }

        return tasks.save(IndexJob.create(repositoryId, IndexJobType.CODEGRAPH));
    }
}
