package com.analyzercoder.application.intelligence;

import com.analyzercoder.application.knowledge.KnowledgeDriftTaskService;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 在 Worker 线程执行 CodeGraph 复制、构建和原子发布，并持续报告心跳。 */
@Service
public class CodeGraphJobProcessor {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphJobProcessor.class);
    private final IndexJobStore jobs;
    private final CodeGraphService codeGraph;
    private final KnowledgeDriftTaskService driftTasks;
    private final long timeoutSeconds;

    @Autowired
    public CodeGraphJobProcessor(
            IndexJobStore jobs,
            CodeGraphService codeGraph,
            KnowledgeDriftTaskService driftTasks,
            @Value("${app.codegraph.task-timeout-minutes:12}") long timeoutMinutes) {
        this.jobs = jobs;
        this.codeGraph = codeGraph;
        this.driftTasks = driftTasks;
        this.timeoutSeconds = Math.max(1, timeoutMinutes) * 60;
    }

    public CodeGraphJobProcessor(
            IndexJobStore jobs, CodeGraphService codeGraph, long timeoutMinutes) {
        this(jobs, codeGraph, null, timeoutMinutes);
    }

    public boolean processNextQueuedJob() {
        return jobs.claimNextQueued(IndexJobType.CODEGRAPH, "copy_snapshot", timeoutSeconds)
                .map(this::process)
                .orElse(false);
    }

    public int expireTimedOutJobs() {
        return jobs.expireTimedOut(IndexJobType.CODEGRAPH);
    }

    private boolean process(IndexJob running) {
        try {
            CodeGraphService.Artifact artifact =
                    codeGraph.build(
                            running.repositoryId().value(), step -> checkpoint(running, step));
            IndexJob latest = jobs.findById(running.id()).orElseThrow();
            if (latest.status() == IndexJobStatus.FAILED) return false;
            if (latest.isCancellationRequested()) {
                jobs.save(latest.cancel());
                return true;
            }
            jobs.save(latest.succeed("codegraph_published:" + artifact.snapshotId()));
            enqueueKnowledgeDrift(running);
            return true;
        } catch (CodeGraphService.BuildCanceledException exception) {
            IndexJob latest = jobs.findById(running.id()).orElse(running);
            if (latest.isCancellationRequested()) jobs.save(latest.cancel());
            return true;
        } catch (RuntimeException exception) {
            IndexJob latest = jobs.findById(running.id()).orElse(running);
            if (latest.status() == IndexJobStatus.FAILED) return false;
            String message = safeMessage(exception);
            String code = message.contains("超时") ? "CODEGRAPH_TIMEOUT" : "CODEGRAPH_BUILD_FAILED";
            jobs.save(latest.fail("codegraph_failed", code, message));
            return false;
        }
    }

    private void enqueueKnowledgeDrift(IndexJob completedGraphJob) {
        if (driftTasks == null) {
            return;
        }
        try {
            driftTasks.start(completedGraphJob.repositoryId());
        } catch (RuntimeException continuationFailure) {
            log.warn(
                    "CodeGraph 已发布，但无法自动排队知识失效检查，repositoryId={}",
                    completedGraphJob.repositoryId().value(),
                    continuationFailure);
        }
    }

    private void checkpoint(IndexJob running, String step) {
        IndexJob current = jobs.heartbeat(running.id(), step).orElseThrow();
        if (current.isCancellationRequested()) throw new CodeGraphService.BuildCanceledException();
        if (current.status() == IndexJobStatus.FAILED) {
            throw new IllegalStateException(current.errorMessage());
        }
        if (current.timeoutAt() != null && !Instant.now().isBefore(current.timeoutAt())) {
            throw new IllegalStateException("CodeGraph 后台任务执行超时");
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = "CodeGraph 构建失败";
        return message.substring(0, Math.min(500, message.length()));
    }
}
