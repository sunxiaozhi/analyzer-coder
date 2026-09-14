package com.analyzercoder.application.branch;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.ApiSecurityException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BranchGraphTasks {
    private final JdbcTemplate db;
    private final IndexJobStore jobs;

    public BranchGraphTasks(JdbcTemplate db, IndexJobStore jobs) {
        this.db = db;
        this.jobs = jobs;
    }

    public record Target(UUID repoId, UUID branchId, UUID snapshotId, Path path) {}

    public Optional<Target> target(UUID jobId) {
        return db
                .query(
                        "SELECT t.*,s.content_path FROM index_job_branch_targets t JOIN branch_snapshots s ON s.id=t.snapshot_id WHERE t.job_id=?",
                        (r, n) ->
                                new Target(
                                        r.getObject("repo_id", UUID.class),
                                        r.getObject("branch_id", UUID.class),
                                        r.getObject("snapshot_id", UUID.class),
                                        Path.of(r.getString("content_path"))),
                        jobId)
                .stream()
                .findFirst();
    }

    @Transactional
    public IndexJob start(BranchReadContext context) {
        return start(
                new Target(
                        context.repositoryId(),
                        context.branchId(),
                        context.snapshotId(),
                        context.contentPath()));
    }

    @Transactional
    public IndexJob retry(IndexJob failed) {
        if (failed.status() != com.analyzercoder.domain.indexing.IndexJobStatus.FAILED)
            throw new IllegalArgumentException("只有失败任务可以重试");
        return start(target(failed.id().value()).orElseThrow());
    }

    private IndexJob start(Target target) {
        db.queryForObject(
                "SELECT id FROM repositories WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                UUID.class,
                target.repoId());
        var active =
                db.queryForList(
                        "SELECT id FROM index_jobs WHERE repo_id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",
                        UUID.class,
                        target.repoId());
        if (!active.isEmpty()) {
            var current = target(active.get(0));
            if (current.isPresent() && current.get().snapshotId().equals(target.snapshotId()))
                return jobs.findById(IndexJobId.of(active.get(0))).orElseThrow();
            throw new ApiSecurityException(409, "BRANCH_GRAPH_BUSY", "仓库已有其他版本的活动任务，请等待其完成");
        }
        IndexJob job =
                jobs.save(
                        IndexJob.create(
                                CodeRepositoryId.of(target.repoId()), IndexJobType.CODEGRAPH));
        db.update(
                "INSERT INTO index_job_branch_targets(job_id,repo_id,branch_id,snapshot_id) VALUES(?,?,?,?)",
                job.id().value(),
                target.repoId(),
                target.branchId(),
                target.snapshotId());
        return job;
    }
}
