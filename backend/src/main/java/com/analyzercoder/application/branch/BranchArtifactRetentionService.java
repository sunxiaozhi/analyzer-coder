package com.analyzercoder.application.branch;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.infrastructure.repository.GitBranchSnapshotFactory;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit garbage collection; every durable reference is checked before removing a snapshot. */
@Service
public class BranchArtifactRetentionService {
    private final JdbcTemplate db;
    private final AccessControlService access;
    private final GitBranchSnapshotFactory snapshots;
    private final TransactionTemplate transaction;

    public BranchArtifactRetentionService(
            JdbcTemplate db,
            AccessControlService access,
            GitBranchSnapshotFactory snapshots,
            PlatformTransactionManager manager) {
        this.db = db;
        this.access = access;
        this.snapshots = snapshots;
        this.transaction = new TransactionTemplate(manager);
    }

    public int deleteExpiredContexts() {
        return db.update("DELETE FROM branch_read_contexts WHERE expires_at<=CURRENT_TIMESTAMP");
    }

    public Retention inspect(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, UUID snapshotId) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.MANAGE);
        return inspect(repoId, branchId, snapshotId, false);
    }

    public Retention remove(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, UUID snapshotId) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.MANAGE);
        Retention retained = inspect(repoId, branchId, snapshotId, true);
        if (!retained.removable())
            throw new ApiSecurityException(
                    409, "SNAPSHOT_REFERENCED", "快照仍被分支、活动上下文、任务、问答或知识引用，不能清理");
        Path contentPath = Path.of(retained.contentPath());
        transaction.executeWithoutResult(
                status -> {
                    // Remove the immutable identity first. The V3 chunk trigger then permits this
                    // explicitly checked cleanup while continuing to reject in-place rewrites.
                    if (db.update(
                                    "DELETE FROM branch_snapshots WHERE id=? AND repo_id=? AND branch_id=?",
                                    snapshotId,
                                    repoId,
                                    branchId)
                            != 1)
                        throw new ApiSecurityException(409, "SNAPSHOT_CHANGED", "快照状态已变化，请刷新后重试");
                    db.update(
                            "DELETE FROM codegraph_artifacts WHERE repo_id=? AND snapshot_id=?",
                            repoId,
                            snapshotId);
                    db.update(
                            "DELETE FROM code_chunks WHERE repo_id=? AND snapshot_id=?",
                            repoId,
                            snapshotId);
                });
        snapshots.discardUnpublished(
                new ManagedRepositorySnapshot(
                        RepositorySnapshotId.of(snapshotId),
                        CodeRepositoryId.of(repoId),
                        contentPath,
                        "retired",
                        "retired",
                        Instant.now()));
        return new Retention(snapshotId, contentPath.toString(), true, 0, 0, 0, 0, 0, 0);
    }

    private Retention inspect(UUID repoId, UUID branchId, UUID snapshotId, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF s" : "";
        var rows =
                db.queryForList(
                        "SELECT s.content_path FROM branch_snapshots s WHERE s.id=? AND s.repo_id=? AND s.branch_id=?"
                                + suffix,
                        snapshotId,
                        repoId,
                        branchId);
        if (rows.isEmpty()) throw new ApiSecurityException(404, "SNAPSHOT_NOT_FOUND", "分支快照不存在");
        int published =
                count(
                        "SELECT COUNT(*) FROM repository_branches WHERE published_snapshot_id=?",
                        snapshotId);
        int contexts =
                count(
                        "SELECT COUNT(*) FROM branch_read_contexts WHERE snapshot_id=? AND expires_at>CURRENT_TIMESTAMP",
                        snapshotId);
        int jobs =
                count(
                                "SELECT COUNT(*) FROM branch_preparation_jobs WHERE target_snapshot=? AND status IN ('QUEUED','RUNNING')",
                                snapshotId)
                        + count(
                                "SELECT COUNT(*) FROM index_job_branch_targets WHERE snapshot_id=?",
                                snapshotId);
        int questions =
                count(
                        "SELECT COUNT(*) FROM qa_conversations WHERE repo_id=? AND snapshot_id=?",
                        repoId,
                        snapshotId);
        int knowledge =
                count(
                                "SELECT COUNT(*) FROM knowledge_code_refs WHERE repo_id=? AND snapshot_id=?",
                                repoId,
                                snapshotId)
                        + count(
                                "SELECT COUNT(*) FROM knowledge_branch_validations WHERE snapshot_id=?",
                                snapshotId)
                        + count(
                                "SELECT COUNT(*) FROM knowledge_card_markdown_source_links WHERE repo_id=? AND source_snapshot_id=?",
                                repoId,
                                snapshotId);
        int sources =
                count(
                        "SELECT COUNT(*) FROM repository_markdown_sources WHERE repo_id=? AND snapshot_id=?",
                        repoId,
                        snapshotId);
        boolean removable = published + contexts + jobs + questions + knowledge + sources == 0;
        return new Retention(
                snapshotId,
                String.valueOf(rows.get(0).get("content_path")),
                removable,
                published,
                contexts,
                jobs,
                questions,
                knowledge,
                sources);
    }

    private int count(String sql, Object... arguments) {
        Integer value = db.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    public record Retention(
            UUID snapshotId,
            String contentPath,
            boolean removable,
            int publishedReferences,
            int activeContexts,
            int taskReferences,
            int questionReferences,
            int knowledgeReferences,
            int markdownSources) {}
}
