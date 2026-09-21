package com.analyzercoder.application.branch;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import com.analyzercoder.application.common.PageResult;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BranchPreparationJobs {
    private static final Logger log = LoggerFactory.getLogger(BranchPreparationJobs.class);
    private static final String GENERIC_FAILURE =
            "任务失败，请检查分支、仓库权限、凭据或向量模型配置后重试";

    private final JdbcTemplate db;
    private final DataSource dataSource;
    private final RepositoryBranchService branches;
    private final AccessControlService access;
    private final TransactionTemplate transaction;
    private final IntelligenceService intelligence;

    public BranchPreparationJobs(
            JdbcTemplate db,
            DataSource dataSource,
            RepositoryBranchService branches,
            AccessControlService access,
            PlatformTransactionManager manager,
            IntelligenceService intelligence) {
        this.db = db;
        this.dataSource = dataSource;
        this.branches = branches;
        this.access = access;
        this.transaction = new TransactionTemplate(manager);
        this.intelligence = intelligence;
    }

    public record Job(
            UUID id,
            UUID branchId,
            String status,
            String stage,
            String error,
            String kind,
            UUID contentVersion) {}

    /** Full task history; list() continues to return only the latest task per branch/kind. */
    public PageResult<Job> history(AuthenticatedAccount actor, UUID repoId, UUID branchId, int pageNum, int pageSize) {
        PageResult.validate(pageNum, pageSize);
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.READ);
        String filter = " WHERE repo_id=?" + (branchId == null ? "" : " AND branch_id=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(repoId);
        if (branchId != null) arguments.add(branchId);
        Long count = db.queryForObject("SELECT COUNT(*) FROM branch_preparation_jobs" + filter, Long.class, arguments.toArray());
        long total = count == null ? 0 : count;
        arguments.add(pageSize);
        arguments.add(((long) pageNum - 1) * pageSize);
        List<Job> rows = db.query(
                "SELECT id,branch_id,status,stage,error,kind,target_content_version FROM branch_preparation_jobs" + filter
                        + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (r, n) -> new Job(r.getObject("id", UUID.class), r.getObject("branch_id", UUID.class),
                        r.getString("status"), r.getString("stage"), r.getString("error"),
                        r.getString("kind"), r.getObject("target_content_version", UUID.class)), arguments.toArray());
        return new PageResult<>(rows, pageNum, pageSize, total, (int) Math.min(Integer.MAX_VALUE, (total + pageSize - 1) / pageSize));
    }

    public List<Job> list(AuthenticatedAccount actor, UUID repoId) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.READ);
        return db.query(
                """
                SELECT DISTINCT ON (branch_id,kind) id,branch_id,status,stage,error,kind,target_content_version
                FROM branch_preparation_jobs WHERE repo_id=?
                ORDER BY branch_id,kind,created_at DESC,id DESC
                """,
                (r, n) ->
                        new Job(
                                r.getObject("id", UUID.class),
                                r.getObject("branch_id", UUID.class),
                                r.getString("status"),
                                r.getString("stage"),
                                r.getString("error"),
                                r.getString("kind"),
                                r.getObject("target_content_version", UUID.class)),
                repoId);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private BranchCodeOperationsService codeOperations;

    /** Explicit operations are either branch sync or pinned-contentVersion index tasks. */
    public Job submitOperation(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, String kind, UUID contentVersion) {
        if (!java.util.Set.of("SYNC", "CONTENT", "GRAPH", "PREPARE").contains(kind))
            throw new IllegalArgumentException("不支持的分支操作");
        if (("CONTENT".equals(kind) || "GRAPH".equals(kind)) && contentVersion == null)
            throw new IllegalArgumentException("索引任务必须指定分支内容版本");
        if (contentVersion != null) codeOperations.contentVersionContext(actor, repoId, branchId, contentVersion);
        return enqueue(actor, repoId, branchId, kind, contentVersion);
    }

    public Job submit(AuthenticatedAccount actor, UUID repoId, UUID branchId) {
        return enqueue(actor, repoId, branchId, "PREPARE", null);
    }

    public Job submitVectors(AuthenticatedAccount actor, BranchReadContext context) {
        access.require(
                actor, CodeRepositoryId.of(context.repositoryId()), RepositoryPermission.MAINTAIN);
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        """
                SELECT EXISTS(SELECT 1 FROM repository_branches b WHERE b.repo_id=? AND b.id=? AND b.content_version=?
                    AND b.content_indexed_at IS NOT NULL
                    AND EXISTS(SELECT 1 FROM code_chunks c WHERE c.repo_id=b.repo_id AND c.content_version=b.content_version))
                """,
                        Boolean.class,
                        context.repositoryId(),
                        context.branchId(),
                        context.contentVersion())))
            throw new IllegalArgumentException("请先构建此分支内容版本的内容索引");
        return enqueue(
                actor, context.repositoryId(), context.branchId(), "VECTORS", context.contentVersion());
    }

    private Job enqueue(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, String kind, UUID contentVersion) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.MAINTAIN);
        return transaction.execute(
                status -> {
                    var ids =
                            db.queryForList(
                                    """
                    SELECT b.id FROM repository_branches b JOIN repositories r ON r.id=b.repo_id
                    WHERE b.repo_id=? AND b.id=? AND b.tracking_status='ACTIVE'
                      AND r.deleted_at IS NULL FOR UPDATE OF b
                    """,
                                    UUID.class,
                                    repoId,
                                    branchId);
                    if (ids.isEmpty())
                        throw new ApiSecurityException(404, "BRANCH_NOT_FOUND", "分支不存在");
                    var active =
                            db.queryForList(
                                    """
                    SELECT id FROM branch_preparation_jobs
                    WHERE branch_id=? AND kind=? AND status IN ('QUEUED','RUNNING')
                    """,
                                    UUID.class,
                                    branchId,
                                    kind);
                    if (active.isEmpty()) {
                        db.update(
                                """
                        INSERT INTO branch_preparation_jobs(id,repo_id,branch_id,account_id,status,kind,target_content_version)
                        VALUES(?,?,?,?,'QUEUED',?,?)
                        """,
                                UUID.randomUUID(),
                                repoId,
                                branchId,
                                actor.id(),
                                kind,
                                contentVersion);
                    }
                    Job job =
                            list(actor, repoId).stream()
                                    .filter(
                                            j ->
                                                    j.branchId().equals(branchId)
                                                            && j.kind().equals(kind))
                                    .findFirst()
                                    .orElseThrow();
                    if (!java.util.Objects.equals(contentVersion, job.contentVersion()))
                        throw new ApiSecurityException(
                                409, "BRANCH_VECTOR_BUSY", "该分支已有其他内容版本的向量任务，请等待其完成");
                    return job;
                });
    }

    // A branch-scoped session lock spans Git/filesystem work without holding a database
    // transaction open. Separate branches may run concurrently; one branch remains serialized.
    public boolean processNext() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var pending =
                    db.queryForList(
                            """
                            SELECT id,repo_id,branch_id,account_id,target_commit,kind,target_content_version
                            FROM branch_preparation_jobs
                            WHERE status='QUEUED'
                               OR (status='RUNNING' AND updated_at<CURRENT_TIMESTAMP-INTERVAL '1 hour')
                            ORDER BY created_at,id LIMIT 32
                            """);
            if (pending.isEmpty()) return false;
            Map<String, Object> row = null;
            long lockKey = 0;
            for (var candidate : pending) {
                UUID candidateBranch = (UUID) candidate.get("branch_id");
                long candidateKey =
                        candidateBranch.getMostSignificantBits()
                                ^ candidateBranch.getLeastSignificantBits();
                if (tryLock(connection, candidateKey)) {
                    row = candidate;
                    lockKey = candidateKey;
                    break;
                }
            }
            if (row == null) return false;
            UUID branchId = (UUID) row.get("branch_id");
            try {
                UUID id = (UUID) row.get("id"),
                        repoId = (UUID) row.get("repo_id"),
                        token = UUID.randomUUID();
                if (db.update(
                                """
                        UPDATE branch_preparation_jobs SET status='RUNNING',stage='RESOLVING',
                        attempt_token=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                          AND (status='QUEUED' OR (status='RUNNING' AND updated_at<CURRENT_TIMESTAMP-INTERVAL '1 hour'))
                        """,
                                token,
                                id)
                        != 1) return false;
                try {
                    AuthenticatedAccount actor = actor((UUID) row.get("account_id"));
                    if (java.util.Set.of("SYNC", "CONTENT", "GRAPH", "PREPARE")
                            .contains(row.get("kind"))) {
                        performCodeOperation(
                                actor,
                                repoId,
                                branchId,
                                id,
                                token,
                                (String) row.get("kind"),
                                (String) row.get("target_commit"),
                                (UUID) row.get("target_content_version"));
                        return true;
                    }
                    if ("VECTORS".equals(row.get("kind"))) {
                        access.require(
                                actor, CodeRepositoryId.of(repoId), RepositoryPermission.MAINTAIN);
                        intelligence.prepareBranchEmbeddings(
                                repoId,
                                (UUID) row.get("target_content_version"),
                                () -> {
                                    access.require(
                                            actor(actor.id()),
                                            CodeRepositoryId.of(repoId),
                                            RepositoryPermission.MAINTAIN);
                                    if (db.update(
                                                    "UPDATE branch_preparation_jobs SET stage='EMBEDDING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND attempt_token=? AND status='RUNNING'",
                                                    id,
                                                    token)
                                            != 1) throw superseded();
                                });
                        access.require(
                                actor(actor.id()),
                                CodeRepositoryId.of(repoId),
                                RepositoryPermission.MAINTAIN);
                        if (db.update(
                                        "UPDATE branch_preparation_jobs SET status='SUCCEEDED',stage='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND attempt_token=? AND status='RUNNING'",
                                        id,
                                        token)
                                != 1) throw superseded();
                        return true;
                    }
                    branches.executePreparation(
                            actor,
                            repoId,
                            branchId,
                            (String) row.get("target_commit"),
                            (stage, commit) -> {
                                if (db.update(
                                                """
                                        UPDATE branch_preparation_jobs SET stage=?,
                                        target_commit=COALESCE(target_commit,?),updated_at=CURRENT_TIMESTAMP
                                        WHERE id=? AND attempt_token=? AND status='RUNNING'
                                        """,
                                                stage,
                                                commit,
                                                id,
                                                token)
                                        != 1) throw superseded();
                            },
                            () -> {
                                // Runs in the contentVersion publication transaction; token fences a lost
                                // worker.
                                access.require(
                                        actor(actor.id()),
                                        CodeRepositoryId.of(repoId),
                                        RepositoryPermission.MAINTAIN);
                                if (db.update(
                                                """
                                        UPDATE branch_preparation_jobs SET status='SUCCEEDED',stage='COMPLETED',
                                        updated_at=CURRENT_TIMESTAMP WHERE id=? AND attempt_token=? AND status='RUNNING'
                                        """,
                                                id,
                                                token)
                                        != 1) throw superseded();
                            });
                } catch (RuntimeException failure) {
                    log.error(
                            "分支任务失败: taskId={}, repoId={}, branchId={}, kind={}, contentVersion={}",
                            id,
                            repoId,
                            branchId,
                            row.get("kind"),
                            row.get("target_content_version"),
                            failure);
                    db.update(
                            """
                            UPDATE branch_preparation_jobs SET status='FAILED',
                            error=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND attempt_token=? AND status='RUNNING'
                            """,
                            failureMessage(failure),
                            id,
                            token);
                }
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1, lockKey);
                    statement.execute();
                }
            }
            return true;
        }
    }

    private static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return GENERIC_FAILURE;
        if (message.startsWith("CodeGraph")
                || message.startsWith("未找到 CodeGraph")
                || message.startsWith("无法创建 CodeGraph")
                || message.startsWith("无法准备 CodeGraph")
                || message.startsWith("分支文件数超过")
                || message.startsWith("分支内容超过")
                || message.startsWith("无法创建分支内容版本")) {
            return message.length() <= 500 ? message : message.substring(0, 500);
        }
        return GENERIC_FAILURE;
    }

    private void performCodeOperation(
            AuthenticatedAccount actor,
            UUID repoId,
            UUID branchId,
            UUID id,
            UUID token,
            String kind,
            String commit,
            UUID contentVersion) {
        Runnable resolving = checkpoint(actor, repoId, id, token, "RESOLVING");
        resolving.run();
        BranchReadContext context =
                contentVersion != null
                        ? codeOperations.contentVersionContext(actor, repoId, branchId, contentVersion)
                        : codeOperations.executeSync(
                                actor,
                                repoId,
                                branchId,
                                commit,
                                (stage, pinned) -> {
                                    checkpoint(actor, repoId, id, token, stage).run();
                                    if (db.update(
                                                    "UPDATE branch_preparation_jobs SET target_commit=COALESCE(target_commit,?) WHERE id=? AND attempt_token=? AND status='RUNNING'",
                                                    pinned,
                                                    id,
                                                    token)
                                            != 1) throw superseded();
                                },
                                resolving);
        if (db.update(
                        "UPDATE branch_preparation_jobs SET target_content_version=?,target_commit=? WHERE id=? AND attempt_token=? AND status='RUNNING'",
                        context.contentVersion(),
                        context.commitSha(),
                        id,
                        token)
                != 1) throw superseded();
        if ("CONTENT".equals(kind) || "PREPARE".equals(kind))
            codeOperations.indexContent(
                    actor, context, checkpoint(actor, repoId, id, token, "INDEXING"));
        if ("GRAPH".equals(kind) || "PREPARE".equals(kind))
            codeOperations.buildGraph(
                    actor, context, checkpoint(actor, repoId, id, token, "GRAPH"));
        checkpoint(actor, repoId, id, token, "COMPLETED").run();
        if (db.update(
                        "UPDATE branch_preparation_jobs SET status='SUCCEEDED',error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND attempt_token=? AND status='RUNNING'",
                        id,
                        token)
                != 1) throw superseded();
    }

    private Runnable checkpoint(
            AuthenticatedAccount actor, UUID repoId, UUID id, UUID token, String stage) {
        return () -> {
            access.require(
                    actor(actor.id()), CodeRepositoryId.of(repoId), RepositoryPermission.MAINTAIN);
            if (db.update(
                            "UPDATE branch_preparation_jobs SET stage=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND attempt_token=? AND status='RUNNING'",
                            stage,
                            id,
                            token)
                    != 1) throw superseded();
        };
    }

    private static boolean tryLock(Connection connection, long lockKey) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, lockKey);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private AuthenticatedAccount actor(UUID id) {
        return db
                .query(
                        """
                SELECT id,username,display_name,account_role FROM accounts
                WHERE id=? AND enabled=TRUE AND must_change_password=FALSE
                """,
                        (r, n) ->
                                new AuthenticatedAccount(
                                        r.getObject("id", UUID.class),
                                        r.getString("username"),
                                        r.getString("display_name"),
                                        AccountRole.valueOf(r.getString("account_role")),
                                        false,
                                        null),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ApiSecurityException(403, "ACCOUNT_UNAVAILABLE", "任务提交账号不可用"));
    }

    private static ApiSecurityException superseded() {
        return new ApiSecurityException(409, "BRANCH_BUILD_SUPERSEDED", "准备任务已被接管");
    }
}
