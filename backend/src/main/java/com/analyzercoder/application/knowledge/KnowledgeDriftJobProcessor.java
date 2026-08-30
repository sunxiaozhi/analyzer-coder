package com.analyzercoder.application.knowledge;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 在 CodeGraph 发布后检查知识来源是否受当前 Snapshot 影响。 */
@Service
public class KnowledgeDriftJobProcessor {
    private final IndexJobStore jobs;
    private final CodeRepositoryStore repositories;
    private final KnowledgeDriftService drift;
    private final long timeoutSeconds;

    public KnowledgeDriftJobProcessor(
            IndexJobStore jobs,
            CodeRepositoryStore repositories,
            KnowledgeDriftService drift,
            @Value("${app.knowledge.drift-task-timeout-minutes:5}") long timeoutMinutes) {
        this.jobs = jobs;
        this.repositories = repositories;
        this.drift = drift;
        this.timeoutSeconds = Math.max(1, timeoutMinutes) * 60;
    }

    public boolean processNextQueuedJob() {
        return jobs.claimNextQueued(
                        IndexJobType.KNOWLEDGE_DRIFT,
                        "check_knowledge_drift",
                        timeoutSeconds)
                .map(this::process)
                .orElse(false);
    }

    public int expireTimedOutJobs() {
        return jobs.expireTimedOut(IndexJobType.KNOWLEDGE_DRIFT);
    }

    private boolean process(IndexJob running) {
        RepositorySnapshotId inspectedSnapshot = null;
        try {
            if (running.isCancellationRequested()) {
                jobs.save(running.cancel());
                return true;
            }
            CodeRepository repository = repository(running);
            inspectedSnapshot =
                    Objects.requireNonNull(
                            repository.currentSnapshotId(), "仓库尚未发布可用的代码版本");
            jobs.heartbeat(running.id(), "check_knowledge_drift:" + inspectedSnapshot.value());
            KnowledgeDriftService.InspectionReport report = drift.inspect(repository);

            IndexJob latest = jobs.findById(running.id()).orElseThrow();
            if (latest.isCancellationRequested()) {
                jobs.save(latest.cancel());
                return true;
            }
            CodeRepository current = repository(running);
            if (!inspectedSnapshot.equals(current.currentSnapshotId())) {
                throw new IllegalStateException("知识失效检查期间项目 Snapshot 已切换");
            }
            String result = report.degraded() ? "degraded" : "ready";
            jobs.save(
                    latest.succeed(
                            "knowledge_drift_completed:"
                                    + inspectedSnapshot.value()
                                    + ":"
                                    + result));
            return true;
        } catch (RuntimeException exception) {
            IndexJob latest = jobs.findById(running.id()).orElse(running);
            if (latest.status() == IndexJobStatus.FAILED) {
                return false;
            }
            String snapshot = inspectedSnapshot == null ? "unknown" : inspectedSnapshot.value().toString();
            jobs.save(
                    latest.fail(
                            "knowledge_drift_failed:" + snapshot,
                            "KNOWLEDGE_DRIFT_FAILED",
                            safeMessage(exception)));
            return false;
        }
    }

    private CodeRepository repository(IndexJob job) {
        return repositories
                .findById(job.repositoryId())
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Repository not found: " + job.repositoryId().value()));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "知识失效检查失败";
        }
        return message.substring(0, Math.min(500, message.length()));
    }
}
