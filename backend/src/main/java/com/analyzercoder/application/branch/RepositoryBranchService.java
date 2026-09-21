package com.analyzercoder.application.branch;

import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.GitRepositoryContentVersion;
import com.analyzercoder.domain.repository.ManagedRepositoryContentVersion;
import com.analyzercoder.domain.repository.RepositoryContentVersion;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.repository.GitBranchContentVersionFactory;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Branch is the durable code boundary; contentVersion is only a stale-work guard. */
@Service
public class RepositoryBranchService {
    private final JdbcTemplate db;
    private final CodeRepositoryStore repositories;
    private final AccessControlService access;
    private final BranchRemoteService remote;
    private final GitBranchContentVersionFactory contentVersions;
    private final RepositoryScannerPort scanner;
    private final CodeSymbolExtractor symbols;
    private final MarkdownKnowledgeSourceService markdown;
    private final TransactionTemplate transaction;

    public RepositoryBranchService(
            JdbcTemplate db,
            CodeRepositoryStore repositories,
            AccessControlService access,
            BranchRemoteService remote,
            GitBranchContentVersionFactory contentVersions,
            RepositoryScannerPort scanner,
            CodeSymbolExtractor symbols,
            MarkdownKnowledgeSourceService markdown,
            PlatformTransactionManager manager) {
        this.db = db;
        this.repositories = repositories;
        this.access = access;
        this.remote = remote;
        this.contentVersions = contentVersions;
        this.scanner = scanner;
        this.symbols = symbols;
        this.markdown = markdown;
        this.transaction = new TransactionTemplate(manager);
    }

    public record Branch(
            UUID id,
            String name,
            UUID contentVersion,
            String commitSha,
            String status,
            String error,
            long generation,
            String trackingStatus,
            Instant archivedAt) {}

    public List<Branch> list(AuthenticatedAccount actor, UUID repoId) {
        require(actor, repoId, RepositoryPermission.READ);
        return db.query(
                """
            SELECT b.* FROM repository_branches b WHERE b.repo_id=? ORDER BY b.name
            """,
                (r, n) ->
                        new Branch(
                                r.getObject("id", UUID.class),
                                r.getString("name"),
                                r.getObject("content_version", UUID.class),
                                r.getString("commit_sha"),
                                r.getString("preparation_status"),
                                r.getString("preparation_error"),
                                r.getLong("generation"),
                                r.getString("tracking_status"),
                                r.getTimestamp("archived_at") == null
                                        ? null
                                        : r.getTimestamp("archived_at").toInstant()),
                repoId);
    }

    public Branch track(AuthenticatedAccount actor, UUID repoId, String name) {
        require(actor, repoId, RepositoryPermission.MAINTAIN);
        GitBranchContentVersionFactory.validateBranch(name);
        Integer archived =
                db.queryForObject(
                        "SELECT COUNT(*) FROM repository_branches WHERE repo_id=? AND name=? AND tracking_status='ARCHIVED'",
                        Integer.class,
                        repoId,
                        name);
        if (archived != null && archived > 0)
            throw new ApiSecurityException(409, "BRANCH_ARCHIVED", "同名受管分支已归档；请明确恢复，避免继承错误的历史身份");
        db.update(
                "INSERT INTO repository_branches(id,repo_id,name) VALUES(?,?,?) ON CONFLICT(repo_id,name) DO NOTHING",
                UUID.randomUUID(),
                repoId,
                name);
        return list(actor, repoId).stream()
                .filter(b -> b.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public Branch archive(AuthenticatedAccount actor, UUID repoId, UUID branchId) {
        require(actor, repoId, RepositoryPermission.MANAGE);
        int changed =
                db.update(
                        """
                        UPDATE repository_branches SET tracking_status='ARCHIVED',archived_at=CURRENT_TIMESTAMP,
                            archived_by=?,generation=generation+1,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND repo_id=? AND tracking_status='ACTIVE'
                          AND NOT EXISTS(SELECT 1 FROM branch_preparation_jobs j WHERE j.branch_id=repository_branches.id AND j.status IN ('QUEUED','RUNNING'))
                        """,
                        actor.id(),
                        branchId,
                        repoId);
        if (changed != 1) {
            Integer activeJobs =
                    db.queryForObject(
                            "SELECT COUNT(*) FROM branch_preparation_jobs WHERE branch_id=? AND status IN ('QUEUED','RUNNING')",
                            Integer.class,
                            branchId);
            if (activeJobs != null && activeJobs > 0)
                throw new ApiSecurityException(409, "BRANCH_HAS_ACTIVE_TASK", "分支仍有准备任务，完成后才能归档");
            throw new ApiSecurityException(404, "BRANCH_NOT_FOUND", "活动分支不存在");
        }
        return branch(repoId, branchId);
    }

    @Transactional
    public Branch restore(AuthenticatedAccount actor, UUID repoId, UUID branchId) {
        require(actor, repoId, RepositoryPermission.MANAGE);
        if (db.update(
                        """
                        UPDATE repository_branches SET tracking_status='ACTIVE',archived_at=NULL,archived_by=NULL,
                            generation=generation+1,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND repo_id=? AND tracking_status='ARCHIVED'
                        """,
                        branchId,
                        repoId)
                != 1) throw new ApiSecurityException(404, "BRANCH_NOT_FOUND", "归档分支不存在");
        return branch(repoId, branchId);
    }

    private Branch branch(UUID repoId, UUID branchId) {
        return db
                .query(
                        """
                        SELECT b.* FROM repository_branches b WHERE b.repo_id=? AND b.id=?
                        """,
                        (r, n) ->
                                new Branch(
                                        r.getObject("id", UUID.class),
                                        r.getString("name"),
                                        r.getObject("content_version", UUID.class),
                                        r.getString("commit_sha"),
                                        r.getString("preparation_status"),
                                        r.getString("preparation_error"),
                                        r.getLong("generation"),
                                        r.getString("tracking_status"),
                                        r.getTimestamp("archived_at") == null
                                                ? null
                                                : r.getTimestamp("archived_at").toInstant()),
                        repoId,
                        branchId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiSecurityException(404, "BRANCH_NOT_FOUND", "分支不存在"));
    }

    void executePreparation(
            AuthenticatedAccount actor,
            UUID repoId,
            UUID branchId,
            String pinnedCommit,
            java.util.function.BiConsumer<String, String> progress,
            Runnable complete) {
        require(actor, repoId, RepositoryPermission.MAINTAIN);
        Branch branch =
                list(actor, repoId).stream()
                        .filter(b -> b.id().equals(branchId) && "ACTIVE".equals(b.trackingStatus()))
                        .findFirst()
                        .orElseThrow(
                                () -> new ApiSecurityException(404, "BRANCH_NOT_FOUND", "分支不存在"));
        int claimed =
                db.update(
                        """
            UPDATE repository_branches SET generation=generation+1,preparation_status='BUILDING',preparation_error=NULL,updated_at=CURRENT_TIMESTAMP
            WHERE id=? AND repo_id=? AND generation=?
            """,
                        branchId,
                        repoId,
                        branch.generation());
        if (claimed != 1) throw new ApiSecurityException(409, "BRANCH_BUSY", "该分支正在准备，请稍后刷新");
        long generation = branch.generation() + 1;
        ManagedRepositoryContentVersion unpublished = null;
        try {
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
            ManagedRepositoryContentVersion contentVersion =
                    contentVersions.createLatest(repository.id(), branchId, repository.path(), commit);
            unpublished = contentVersion;
            CodeRepository source =
                    repository.withManagedContentVersion(
                            new GitRepositoryContentVersion(
                                    branch.name(), commit, commit, false, Instant.now()),
                            contentVersion);
            List<CodeChunk> chunks = new ArrayList<>();
            var scannedFiles = scanner.scan(source);
            progress.accept("INDEXING", commit);
            for (var file : scannedFiles) {
                String[] lines = file.content().split("\\R", -1);
                for (int start = 0; start < lines.length; start += 100) {
                    int end = Math.min(lines.length, start + 120);
                    chunks.add(
                            CodeChunk.fileChunk(
                                    source.id(),
                                    contentVersion.id(),
                                    commit,
                                    file.relativePath(),
                                    file.language(),
                                    file.assetType(),
                                    start + 1,
                                    end,
                                    String.join("\n", Arrays.copyOfRange(lines, start, end))));
                    if (end == lines.length) break;
                }
                for (var symbol :
                        symbols.extract(file.content(), file.relativePath(), file.language())
                                .symbols()) {
                    int start = Math.max(0, symbol.startLine() - 1),
                            end = Math.min(lines.length, symbol.endLine());
                    if (start >= end) continue;
                    // Bound declaration excerpts; full content remains available as file chunks.
                    end = Math.min(end, start + 120);
                    chunks.add(
                            CodeChunk.symbolChunk(
                                    source.id(),
                                    contentVersion.id(),
                                    commit,
                                    file.relativePath(),
                                    file.language(),
                                    file.assetType(),
                                    symbol.name(),
                                    symbol.kind(),
                                    start + 1,
                                    end,
                                    String.join("\n", Arrays.copyOfRange(lines, start, end))));
                }
                if (chunks.size() > 100_000)
                    throw new IllegalArgumentException("分支片段数量超过限制，请缩小索引范围");
            }
            if (chunks.isEmpty()) throw new IllegalArgumentException("分支没有可索引的文本文件");
            progress.accept("PUBLISHING", commit);
            transaction.executeWithoutResult(
                    status -> {
                        complete.run();
                        markdown.synchronizeBranch(
                                repoId, branchId, contentVersion.id().value(), scannedFiles);
                        db.batchUpdate(
                                """
                    INSERT INTO code_chunks(id,repo_id,branch_id,content_version,commit_sha,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,created_at,symbol_id,symbol_name,symbol_kind)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                                chunks,
                                250,
                                (statement, c) -> {
                                    statement.setObject(1, c.id().value());
                                    statement.setObject(2, repoId);
                                    statement.setObject(3, branchId);
                                    statement.setObject(4, contentVersion.id().value());
                                    statement.setString(5, commit);
                                    statement.setString(6, c.filePath());
                                    statement.setString(7, c.language());
                                    statement.setString(8, c.assetType().name());
                                    statement.setString(9, c.chunkType().name());
                                    statement.setInt(10, c.startLine());
                                    statement.setInt(11, c.endLine());
                                    statement.setString(12, c.content());
                                    statement.setString(13, c.contentHash());
                                    statement.setTimestamp(14, Timestamp.from(c.createdAt()));
                                    statement.setString(15, c.symbolId());
                                    statement.setString(16, c.symbolName());
                                    statement.setString(17, c.symbolKind());
                                });
                        if (branch.contentVersion() != null
                                && !branch.contentVersion().equals(contentVersion.id().value())) {
                            db.update(
                                    "DELETE FROM codegraph_artifacts WHERE repo_id=? AND content_version=?",
                                    repoId,
                                    branch.contentVersion());
                            db.update(
                                    "DELETE FROM code_chunks WHERE repo_id=? AND branch_id=? AND content_version=?",
                                    repoId,
                                    branchId,
                                    branch.contentVersion());
                        }
                        if (db.update(
                                        """
                    UPDATE repository_branches SET previous_content_version=NULL,
                        content_version=?,commit_sha=?,content_path=?,
                        published_at=CURRENT_TIMESTAMP,last_synced_at=CURRENT_TIMESTAMP,
                        content_indexed_at=CURRENT_TIMESTAMP,preparation_status='READY',updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND repo_id=? AND generation=? AND preparation_status='BUILDING'
                    """,
                                        contentVersion.id().value(),
                                        commit,
                                        contentVersion.contentPath().toString(),
                                        branchId,
                                        repoId,
                                        generation)
                                != 1)
                            throw new ApiSecurityException(
                                    409, "BRANCH_BUILD_SUPERSEDED", "该次准备已被新的任务取代");
                    });
            contentVersions.confirmPublished(contentVersion);
            unpublished = null;
        } catch (RuntimeException error) {
            if (unpublished != null) contentVersions.discardUnpublished(unpublished);
            db.update(
                    "UPDATE repository_branches SET preparation_status='FAILED',preparation_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND generation=?",
                    "准备失败，请确认分支存在且仓库来源或凭据可用",
                    branchId,
                    generation);
            throw error;
        }
    }

    public BranchReadContext resolve(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, UUID contextId) {
        require(actor, repoId, RepositoryPermission.READ);
        if (contextId != null) {
            List<BranchReadContext> rows =
                    db.query(
                            """
                SELECT c.*,b.name,b.commit_sha,b.content_path FROM branch_read_contexts c
                JOIN repository_branches b ON b.id=c.branch_id
                WHERE c.id=? AND c.repo_id=? AND c.account_id=? AND c.expires_at>CURRENT_TIMESTAMP
                  AND c.content_version=b.content_version AND b.tracking_status='ACTIVE'
                """,
                            (r, n) ->
                                    new BranchReadContext(
                                            r.getObject("id", UUID.class),
                                            repoId,
                                            r.getObject("branch_id", UUID.class),
                                            r.getString("name"),
                                            r.getObject("content_version", UUID.class),
                                            r.getString("commit_sha"),
                                            Path.of(r.getString("content_path")),
                                            r.getTimestamp("expires_at").toInstant()),
                            contextId,
                            repoId,
                            actor.id());
            if (rows.isEmpty())
                throw new ApiSecurityException(409, "CONTEXT_EXPIRED", "分支上下文不存在或已过期，请重新选择分支");
            BranchReadContext context = rows.get(0);
            if (branchId != null && !branchId.equals(context.branchId()))
                throw new ApiSecurityException(409, "CONTEXT_MISMATCH", "分支与上下文不匹配");
            return context;
        }
        if (branchId == null) throw new IllegalArgumentException("请选择分支");
        return transaction.execute(
                status -> {
                    List<BranchReadContext> rows =
                            db.query(
                                    """
                SELECT b.id,b.name,b.content_version,b.commit_sha,b.content_path FROM repository_branches b
                WHERE b.repo_id=? AND b.id=? AND b.tracking_status='ACTIVE'
                  AND b.content_version IS NOT NULL AND b.content_path IS NOT NULL
                """,
                                    (r, n) ->
                                            new BranchReadContext(
                                                    UUID.randomUUID(),
                                                    repoId,
                                                    branchId,
                                                    r.getString("name"),
                                                    r.getObject("content_version", UUID.class),
                                                    r.getString("commit_sha"),
                                                    Path.of(r.getString("content_path")),
                                                    Instant.now().plusSeconds(3600)),
                                    repoId,
                                    branchId);
                    if (rows.isEmpty())
                        throw new ApiSecurityException(
                                409, "BRANCH_NOT_READY", "分支尚未准备，不会使用其他分支的数据");
                    BranchReadContext context = rows.get(0);
                    db.update(
                            "INSERT INTO branch_read_contexts(id,account_id,repo_id,branch_id,content_version,expires_at) VALUES(?,?,?,?,?,?)",
                            context.contextId(),
                            actor.id(),
                            repoId,
                            branchId,
                            context.contentVersion(),
                            Timestamp.from(context.expiresAt()));
                    db.update(
                            """
                INSERT INTO branch_context_knowledge(context_id,card_id,revision)
                SELECT ?,k.id,k.revision FROM knowledge_cards k
                WHERE k.repo_id=? AND k.publication_status='PUBLISHED' AND k.review_status='APPROVED'
                AND k.branch_id=?
                """,
                            context.contextId(),
                            repoId,
                            branchId);
                    return context;
                });
    }

    public CodeRepository repositoryFor(BranchReadContext context) {
        CodeRepository original = repository(context.repositoryId());
        return original.withManagedContentVersion(
                new GitRepositoryContentVersion(
                        context.branchName(),
                        context.commitSha(),
                        context.commitSha(),
                        false,
                        Instant.now()),
                new ManagedRepositoryContentVersion(
                        RepositoryContentVersion.of(context.contentVersion()),
                        original.id(),
                        context.contentPath(),
                        context.commitSha(),
                        context.commitSha(),
                        Instant.now()));
    }

    private CodeRepository repository(UUID id) {
        return repositories
                .findById(CodeRepositoryId.of(id))
                .orElseThrow(() -> new IllegalArgumentException("仓库不存在"));
    }

    private void require(AuthenticatedAccount actor, UUID id, RepositoryPermission permission) {
        access.require(actor, CodeRepositoryId.of(id), permission);
    }
}
