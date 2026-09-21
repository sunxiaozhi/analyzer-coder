package com.analyzercoder.application.branch;

import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.repository.GitBranchSnapshotFactory;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Additive branch-scoped code workflow. Legacy repository/default-version services stay compatible.
 */
@Service
public class BranchCodeOperationsService {
    private final JdbcTemplate db;
    private final RepositoryBranchService branches;
    private final CodeRepositoryStore repositories;
    private final AccessControlService access;
    private final BranchRemoteService remote;
    private final GitBranchSnapshotFactory snapshots;
    private final BranchContentIndexService contentIndexes;
    private final CodeGraphService graph;
    private final TransactionTemplate transaction;

    public BranchCodeOperationsService(
            JdbcTemplate db,
            RepositoryBranchService branches,
            CodeRepositoryStore repositories,
            AccessControlService access,
            BranchRemoteService remote,
            GitBranchSnapshotFactory snapshots,
            BranchContentIndexService contentIndexes,
            CodeGraphService graph,
            PlatformTransactionManager manager) {
        this.db = db;
        this.branches = branches;
        this.repositories = repositories;
        this.access = access;
        this.remote = remote;
        this.snapshots = snapshots;
        this.contentIndexes = contentIndexes;
        this.graph = graph;
        this.transaction = new TransactionTemplate(manager);
    }

    /** Fetch only this branch, then publish a new immutable snapshot (or reuse the same commit). */
    public BranchReadContext executeSync(
            AuthenticatedAccount actor,
            UUID repoId,
            UUID branchId,
            String pinnedCommit,
            java.util.function.BiConsumer<String, String> progress,
            Runnable checkpoint) {
        require(actor, repoId, RepositoryPermission.MAINTAIN);
        RepositoryBranchService.Branch branch =
                branches.list(actor, repoId).stream()
                        .filter(b -> b.id().equals(branchId) && "ACTIVE".equals(b.trackingStatus()))
                        .findFirst()
                        .orElseThrow(
                                () -> new ApiSecurityException(404, "BRANCH_NOT_FOUND", "分支不存在"));
        if (db.update(
                        """
                UPDATE repository_branches SET generation=generation+1,preparation_status='BUILDING',
                    preparation_error=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND repo_id=? AND generation=?
                """,
                        branchId,
                        repoId,
                        branch.generation())
                != 1) throw new ApiSecurityException(409, "BRANCH_BUSY", "该分支正在同步");
        long generation = branch.generation() + 1;
        ManagedRepositorySnapshot unpublished = null;
        try {
            checkpoint.run();
            CodeRepository repository = repository(repoId);
            boolean remoteRepository =
                    repository.sourceType() == RepositorySourceType.REMOTE_GIT
                            || repository.sourceType() == RepositorySourceType.GITLAB;
            String commit =
                    pinnedCommit != null
                            ? pinnedCommit
                            : remoteRepository
                                    ? remote.fetch(actor, repository, branch.name())
                                    : snapshots.resolve(repository.path(), branch.name());
            progress.accept("SNAPSHOT", commit);
            if (branch.snapshotId() != null && commit.equals(branch.commitSha())) {
                BranchReadContext current =
                        snapshotContext(actor, repoId, branchId, branch.snapshotId());
                if (snapshots.isLatestWorkspace(repository.id(), branchId, current.contentPath())) {
                    transaction.executeWithoutResult(
                            status -> {
                                checkpoint.run();
                                if (db.update(
                                                """
                                UPDATE repository_branches SET preparation_status='READY',last_synced_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                                WHERE repo_id=? AND id=? AND generation=? AND tracking_status='ACTIVE'
                                """,
                                                repoId,
                                                branchId,
                                                generation)
                                        != 1)
                                    throw new ApiSecurityException(
                                            409, "BRANCH_BUILD_SUPERSEDED", "同步任务已被取代");
                            });
                    return current;
                }
            }
            var snapshot =
                    snapshots.createLatest(repository.id(), branchId, repository.path(), commit);
            unpublished = snapshot;
            progress.accept("PUBLISHING", commit);
            transaction.executeWithoutResult(
                    status -> {
                        checkpoint.run();
                        db.update(
                                "INSERT INTO branch_snapshots(id,repo_id,branch_id,commit_sha,content_path) VALUES(?,?,?,?,?)",
                                snapshot.id().value(),
                                repoId,
                                branchId,
                                commit,
                                snapshot.contentPath().toString());
                        if (db.update(
                                        """
                        UPDATE repository_branches SET published_snapshot_id=?,preparation_status='READY',last_synced_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                        WHERE repo_id=? AND id=? AND generation=? AND preparation_status='BUILDING' AND tracking_status='ACTIVE'
                        """,
                                        snapshot.id().value(),
                                        repoId,
                                        branchId,
                                        generation)
                                != 1)
                            throw new ApiSecurityException(
                                    409, "BRANCH_BUILD_SUPERSEDED", "同步任务已被取代");
                    });
            unpublished = null;
            return snapshotContext(actor, repoId, branchId, snapshot.id().value());
        } catch (RuntimeException failure) {
            if (unpublished != null) snapshots.discardUnpublished(unpublished);
            db.update(
                    "UPDATE repository_branches SET preparation_status='FAILED',preparation_error=? WHERE repo_id=? AND id=? AND generation=?",
                    "同步失败，请检查分支、代码源、权限或凭据",
                    repoId,
                    branchId,
                    generation);
            throw failure;
        }
    }

    public void indexContent(
            AuthenticatedAccount actor, BranchReadContext context, Runnable checkpoint) {
        require(actor, context.repositoryId(), RepositoryPermission.MAINTAIN);
        snapshotContext(actor, context.repositoryId(), context.branchId(), context.snapshotId());
        contentIndexes.index(context, branches.repositoryFor(context), checkpoint);
    }

    public BranchReadContext snapshotContext(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, UUID snapshotId) {
        require(actor, repoId, RepositoryPermission.READ);
        var rows =
                db.query(
                        """
                SELECT b.name,s.commit_sha,s.content_path FROM branch_snapshots s
                JOIN repository_branches b ON b.repo_id=s.repo_id AND b.id=s.branch_id
                JOIN repositories r ON r.id=s.repo_id
                WHERE s.repo_id=? AND s.branch_id=? AND s.id=? AND b.tracking_status='ACTIVE' AND r.deleted_at IS NULL
                  AND s.id=b.published_snapshot_id
                """,
                        (r, n) ->
                                new BranchReadContext(
                                        UUID.randomUUID(),
                                        repoId,
                                        branchId,
                                        r.getString("name"),
                                        snapshotId,
                                        r.getString("commit_sha"),
                                        Path.of(r.getString("content_path")),
                                        Instant.now().plusSeconds(3600)),
                        repoId,
                        branchId,
                        snapshotId);
        if (rows.isEmpty())
            throw new ApiSecurityException(404, "BRANCH_SNAPSHOT_NOT_FOUND", "该分支快照不存在或已归档");
        return rows.get(0);
    }

    public void buildGraph(
            AuthenticatedAccount actor, BranchReadContext context, Runnable checkpoint) {
        require(actor, context.repositoryId(), RepositoryPermission.MAINTAIN);
        snapshotContext(actor, context.repositoryId(), context.branchId(), context.snapshotId());
        checkpoint.run();
        graph.buildSnapshot(
                context.repositoryId(),
                context.snapshotId(),
                context.contentPath(),
                stage -> checkpoint.run());
    }

    public record IndexStatus(
            UUID branchId,
            UUID snapshotId,
            Instant syncedAt,
            boolean contentReady,
            boolean graphReady,
            boolean vectorsReady) {}

    public List<IndexStatus> statuses(AuthenticatedAccount actor, UUID repoId) {
        require(actor, repoId, RepositoryPermission.READ);
        return queryStatuses(repoId, null, null);
    }

    public IndexStatus status(AuthenticatedAccount actor, BranchReadContext context) {
        snapshotContext(actor, context.repositoryId(), context.branchId(), context.snapshotId());
        return queryStatuses(context.repositoryId(), context.branchId(), context.snapshotId())
                .stream()
                .filter(item -> item.branchId().equals(context.branchId()))
                .findFirst()
                .orElseThrow();
    }

    private List<IndexStatus> queryStatuses(UUID repoId, UUID pinnedBranch, UUID pinnedSnapshot) {
        return db.query(
                """
                SELECT b.id,s.id snapshot_id,
                    CASE WHEN s.id=b.published_snapshot_id THEN COALESCE(b.last_synced_at,s.created_at) ELSE s.created_at END created_at,
                    s.content_indexed_at,
                    EXISTS(SELECT 1 FROM codegraph_artifacts g WHERE g.repo_id=b.repo_id AND g.snapshot_id=s.id AND g.status='PUBLISHED') graph_ready,
                    (EXISTS(SELECT 1 FROM code_chunks c WHERE c.repo_id=b.repo_id AND c.snapshot_id=s.id)
                     AND NOT EXISTS(SELECT 1 FROM code_chunks c
                        WHERE c.repo_id=b.repo_id AND c.snapshot_id=s.id AND NOT EXISTS(
                            SELECT 1 FROM chunk_embeddings e WHERE e.chunk_id=c.id AND e.content_hash=c.content_hash
                              AND e.model=COALESCE((SELECT vm.model FROM vector_model_activation va JOIN vector_model_configs vm ON vm.id=va.active_config_id WHERE va.singleton_id=1),'local-hash-64')
                              AND e.dimension=COALESCE((SELECT vm.dimension FROM vector_model_activation va JOIN vector_model_configs vm ON vm.id=va.active_config_id WHERE va.singleton_id=1),64)
                              AND e.retrieval_capability=COALESCE((SELECT CASE WHEN vm.provider_type='LOCAL_HASH' THEN 'CHARACTER_HASH' ELSE 'SEMANTIC_EMBEDDING' END FROM vector_model_activation va JOIN vector_model_configs vm ON vm.id=va.active_config_id WHERE va.singleton_id=1),'CHARACTER_HASH')))) vectors_ready
                FROM repository_branches b LEFT JOIN branch_snapshots s ON s.repo_id=b.repo_id AND s.branch_id=b.id
                    AND s.id=CASE WHEN b.id=? THEN ? ELSE b.published_snapshot_id END
                WHERE b.repo_id=? ORDER BY b.name
                """,
                (r, n) ->
                        new IndexStatus(
                                r.getObject("id", UUID.class),
                                r.getObject("snapshot_id", UUID.class),
                                r.getTimestamp("created_at") == null
                                        ? null
                                        : r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("content_indexed_at") != null,
                                r.getBoolean("graph_ready"),
                                r.getBoolean("vectors_ready")),
                pinnedBranch,
                pinnedSnapshot,
                repoId);
    }

    private CodeRepository repository(UUID id) {
        return repositories
                .findById(CodeRepositoryId.of(id))
                .orElseThrow(() -> new ApiSecurityException(404, "PROJECT_NOT_FOUND", "项目不存在"));
    }

    private void require(AuthenticatedAccount actor, UUID repoId, RepositoryPermission permission) {
        access.require(actor, CodeRepositoryId.of(repoId), permission);
    }
}
