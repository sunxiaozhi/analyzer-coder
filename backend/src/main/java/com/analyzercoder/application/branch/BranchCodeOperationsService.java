package com.analyzercoder.application.branch;

import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.ManagedRepositoryContentVersion;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.repository.GitBranchContentVersionFactory;
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
 * Branch-scoped code workflow. A branch has one live workspace and one current publication token.
 */
@Service
public class BranchCodeOperationsService {
    private final JdbcTemplate db;
    private final RepositoryBranchService branches;
    private final CodeRepositoryStore repositories;
    private final AccessControlService access;
    private final BranchRemoteService remote;
    private final GitBranchContentVersionFactory contentVersions;
    private final BranchContentIndexService contentIndexes;
    private final CodeGraphService graph;
    private final TransactionTemplate transaction;

    public BranchCodeOperationsService(
            JdbcTemplate db,
            RepositoryBranchService branches,
            CodeRepositoryStore repositories,
            AccessControlService access,
            BranchRemoteService remote,
            GitBranchContentVersionFactory contentVersions,
            BranchContentIndexService contentIndexes,
            CodeGraphService graph,
            PlatformTransactionManager manager) {
        this.db = db;
        this.branches = branches;
        this.repositories = repositories;
        this.access = access;
        this.remote = remote;
        this.contentVersions = contentVersions;
        this.contentIndexes = contentIndexes;
        this.graph = graph;
        this.transaction = new TransactionTemplate(manager);
    }

    /** Fetch only this branch, then atomically replace its current code publication. */
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
        ManagedRepositoryContentVersion unpublished = null;
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
                                    : repository.sourceType() == RepositorySourceType.ZIP
                                            ? contentVersions.resolveWorkspace(repository.path())
                                            : contentVersions.resolve(repository.path(), branch.name());
            progress.accept("SYNC", commit);
            if (branch.contentVersion() != null && commit.equals(branch.commitSha())) {
                BranchReadContext current =
                        contentVersionContext(actor, repoId, branchId, branch.contentVersion());
                if (contentVersions.isLatestWorkspace(repository.id(), branchId, current.contentPath())) {
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
            var contentVersion =
                    contentVersions.createLatest(repository.id(), branchId, repository.path(), commit);
            unpublished = contentVersion;
            progress.accept("PUBLISHING", commit);
            transaction.executeWithoutResult(
                    status -> {
                        checkpoint.run();
                        if (db.update(
                                        """
                        UPDATE repository_branches SET previous_content_version=content_version,
                            content_version=?,commit_sha=?,content_path=?,
                            published_at=CURRENT_TIMESTAMP,content_indexed_at=NULL,
                            preparation_status='READY',last_synced_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                        WHERE repo_id=? AND id=? AND generation=? AND preparation_status='BUILDING' AND tracking_status='ACTIVE'
                        """,
                                        contentVersion.id().value(),
                                        commit,
                                        contentVersion.contentPath().toString(),
                                        repoId,
                                        branchId,
                                        generation)
                                != 1)
                            throw new ApiSecurityException(
                                    409, "BRANCH_BUILD_SUPERSEDED", "同步任务已被取代");
                    });
            contentVersions.confirmPublished(contentVersion);
            unpublished = null;
            return contentVersionContext(actor, repoId, branchId, contentVersion.id().value());
        } catch (RuntimeException failure) {
            if (unpublished != null) contentVersions.discardUnpublished(unpublished);
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
        contentVersionContext(actor, context.repositoryId(), context.branchId(), context.contentVersion());
        contentIndexes.index(context, branches.repositoryFor(context), checkpoint);
    }

    public BranchReadContext contentVersionContext(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, UUID contentVersion) {
        require(actor, repoId, RepositoryPermission.READ);
        var rows =
                db.query(
                        """
                SELECT b.name,b.commit_sha,b.content_path FROM repository_branches b
                JOIN repositories r ON r.id=b.repo_id
                WHERE b.repo_id=? AND b.id=? AND b.content_version=?
                  AND b.tracking_status='ACTIVE' AND r.deleted_at IS NULL
                """,
                        (r, n) ->
                                new BranchReadContext(
                                        UUID.randomUUID(),
                                        repoId,
                                        branchId,
                                        r.getString("name"),
                                        contentVersion,
                                        r.getString("commit_sha"),
                                        Path.of(r.getString("content_path")),
                                        Instant.now().plusSeconds(3600)),
                        repoId,
                        branchId,
                        contentVersion);
        if (rows.isEmpty())
            throw new ApiSecurityException(404, "BRANCH_VERSION_MISMATCH", "分支代码已经更新，请重新选择分支");
        return rows.get(0);
    }

    public void buildGraph(
            AuthenticatedAccount actor, BranchReadContext context, Runnable checkpoint) {
        require(actor, context.repositoryId(), RepositoryPermission.MAINTAIN);
        contentVersionContext(actor, context.repositoryId(), context.branchId(), context.contentVersion());
        checkpoint.run();
        graph.buildContentVersion(
                context.repositoryId(),
                context.contentVersion(),
                context.contentPath(),
                stage -> checkpoint.run());
    }

    public record IndexStatus(
            UUID branchId,
            UUID contentVersion,
            Instant syncedAt,
            boolean contentReady,
            boolean graphReady,
            boolean vectorsReady) {}

    public List<IndexStatus> statuses(AuthenticatedAccount actor, UUID repoId) {
        require(actor, repoId, RepositoryPermission.READ);
        return queryStatuses(repoId, null, null);
    }

    public IndexStatus status(AuthenticatedAccount actor, BranchReadContext context) {
        contentVersionContext(actor, context.repositoryId(), context.branchId(), context.contentVersion());
        return queryStatuses(context.repositoryId(), context.branchId(), context.contentVersion())
                .stream()
                .filter(item -> item.branchId().equals(context.branchId()))
                .findFirst()
                .orElseThrow();
    }

    private List<IndexStatus> queryStatuses(UUID repoId, UUID pinnedBranch, UUID pinnedContentVersion) {
        return db.query(
                """
                SELECT b.id,b.content_version,
                    COALESCE(b.last_synced_at,b.published_at) created_at,
                    b.content_indexed_at,
                    EXISTS(SELECT 1 FROM codegraph_artifacts g WHERE g.repo_id=b.repo_id AND g.content_version=b.content_version AND g.status='PUBLISHED') graph_ready,
                    (EXISTS(SELECT 1 FROM code_chunks c WHERE c.repo_id=b.repo_id AND c.content_version=b.content_version)
                     AND NOT EXISTS(SELECT 1 FROM code_chunks c
                        WHERE c.repo_id=b.repo_id AND c.content_version=b.content_version AND NOT EXISTS(
                            SELECT 1 FROM chunk_embeddings e WHERE e.chunk_id=c.id AND e.content_hash=c.content_hash
                              AND e.model=COALESCE((SELECT vm.model FROM vector_model_activation va JOIN vector_model_configs vm ON vm.id=va.active_config_id WHERE va.singleton_id=1),'local-hash-64')
                              AND e.dimension=COALESCE((SELECT vm.dimension FROM vector_model_activation va JOIN vector_model_configs vm ON vm.id=va.active_config_id WHERE va.singleton_id=1),64)
                              AND e.retrieval_capability=COALESCE((SELECT CASE WHEN vm.provider_type='LOCAL_HASH' THEN 'CHARACTER_HASH' ELSE 'SEMANTIC_EMBEDDING' END FROM vector_model_activation va JOIN vector_model_configs vm ON vm.id=va.active_config_id WHERE va.singleton_id=1),'CHARACTER_HASH')))) vectors_ready
                FROM repository_branches b
                WHERE b.repo_id=? ORDER BY b.name
                """,
                (r, n) ->
                        new IndexStatus(
                                r.getObject("id", UUID.class),
                                r.getObject("content_version", UUID.class),
                                r.getTimestamp("created_at") == null
                                        ? null
                                        : r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("content_indexed_at") != null,
                                r.getBoolean("graph_ready"),
                                r.getBoolean("vectors_ready")),
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
