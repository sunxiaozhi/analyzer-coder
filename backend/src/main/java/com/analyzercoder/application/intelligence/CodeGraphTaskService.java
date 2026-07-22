package com.analyzercoder.application.intelligence;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import org.springframework.stereotype.Service;

@Service
public class CodeGraphTaskService {
    private final IndexJobStore tasks;
    private final CodeGraphService codeGraph;

    public CodeGraphTaskService(IndexJobStore tasks, CodeGraphService codeGraph) {
        this.tasks = tasks;
        this.codeGraph = codeGraph;
    }

    public synchronized IndexJob start(CodeRepositoryId repositoryId) {
        IndexJob active = tasks.findByRepositoryId(repositoryId).stream()
            .filter(task -> task.status() == IndexJobStatus.QUEUED || task.status() == IndexJobStatus.RUNNING
                || task.status() == IndexJobStatus.CANCEL_REQUESTED)
            .findFirst().orElse(null);
        if (active != null) return active;

        IndexJob running = tasks.save(IndexJob.create(repositoryId, IndexJobType.CODEGRAPH).start("building_codegraph"));
        try {
            codeGraph.build(repositoryId.value());
            return tasks.save(running.succeed("codegraph_published"));
        } catch (RuntimeException exception) {
            return tasks.save(running.fail("codegraph_failed", safeMessage(exception)));
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "CodeGraph 构建失败" : message.substring(0, Math.min(500, message.length()));
    }
}
