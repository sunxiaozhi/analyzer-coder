package com.analyzercoder.application.branch;

import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.GitRepositorySnapshot;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.infrastructure.repository.GitBranchSnapshotFactory;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Branch publication is independent of repositories.current_snapshot_id and legacy index jobs. */
@Service
public class RepositoryBranchService {
    @org.springframework.beans.factory.annotation.Autowired private BranchRemoteService remote;
    private final JdbcTemplate db;
    private final CodeRepositoryStore repositories;
    private final AccessControlService access;
    private final GitBranchSnapshotFactory snapshots;
    private final RepositoryScannerPort scanner;
    private final CodeSymbolExtractor symbols;
    private final TransactionTemplate transaction;

    public RepositoryBranchService(
            JdbcTemplate db,
            CodeRepositoryStore repositories,
            AccessControlService access,
            GitBranchSnapshotFactory snapshots,
            RepositoryScannerPort scanner,
            CodeSymbolExtractor symbols,
            PlatformTransactionManager manager) {
        this.db = db;
        this.repositories = repositories;
        this.access = access;
        this.snapshots = snapshots;
        this.scanner = scanner;
        this.symbols = symbols;
        this.transaction = new TransactionTemplate(manager);
    }

    public record Branch(
            UUID id,
            String name,
            UUID snapshotId,
            String commitSha,
            String status,
            String error,
            long generation) {}

    public List<Branch> list(AuthenticatedAccount actor, UUID repoId) {
        require(actor, repoId, RepositoryPermission.READ);
        return db.query(
                """
            SELECT b.*,s.commit_sha FROM repository_branches b LEFT JOIN branch_snapshots s ON s.id=b.published_snapshot_id
            WHERE b.repo_id=? ORDER BY b.name
            """,
                (r, n) ->
                        new Branch(
                                r.getObject("id", UUID.class),
                                r.getString("name"),
                                r.getObject("published_snapshot_id", UUID.class),
                                r.getString("commit_sha"),
                                r.getString("preparation_status"),
                                r.getString("preparation_error"),
                                r.getLong("generation")),
                repoId);
    }

    public Branch track(AuthenticatedAccount actor, UUID repoId, String name) {
        require(actor, repoId, RepositoryPermission.MAINTAIN);
        GitBranchSnapshotFactory.validateBranch(name);
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

    public Branch prepare(AuthenticatedAccount actor, UUID repoId, UUID branchId) {
        require(actor, repoId, RepositoryPermission.MAINTAIN);
        Branch branch =
                list(actor, repoId).stream()
                        .filter(b -> b.id().equals(branchId))
                        .findFirst()
                        .orElseThrow(
                                () -> new ApiSecurityException(404, "BRANCH_NOT_FOUND", "分支不存在"));
        int claimed =
                db.update(
                        """
            UPDATE repository_branches SET generation=generation+1,preparation_status='BUILDING',preparation_error=NULL,updated_at=CURRENT_TIMESTAMP
            WHERE id=? AND repo_id=? AND generation=? AND (preparation_status<>'BUILDING' OR updated_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes')
            """,
                        branchId,
                        repoId,
                        branch.generation());
        if (claimed != 1) throw new ApiSecurityException(409, "BRANCH_BUSY", "该分支正在准备，请稍后刷新");
        long generation = branch.generation() + 1;
        ManagedRepositorySnapshot unpublished = null;
        try {
            CodeRepository repository = repository(repoId);
            String commit = repository.sourceType()==com.analyzercoder.domain.repository.RepositorySourceType.REMOTE_GIT
                    ||repository.sourceType()==com.analyzercoder.domain.repository.RepositorySourceType.GITLAB
                    ?remote.fetch(actor,repository,branch.name()):snapshots.resolve(repository.path(), branch.name());
            ManagedRepositorySnapshot snapshot =
                    snapshots.create(repository.id(), repository.path(), commit);
            unpublished = snapshot;
            CodeRepository source =
                    repository.withManagedSnapshot(
                            new GitRepositorySnapshot(
                                    branch.name(), commit, commit, false, Instant.now()),
                            snapshot);
            List<CodeChunk> chunks = new ArrayList<>();
            for (var file : scanner.scan(source)) {
                String[] lines = file.content().split("\\R", -1);
                for (int start = 0; start < lines.length; start += 100) {
                    int end = Math.min(lines.length, start + 120);
                    chunks.add(
                            CodeChunk.fileChunk(
                                    source.id(),
                                    snapshot.id(),
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
                                    snapshot.id(),
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
            transaction.executeWithoutResult(
                    status -> {
                        db.update(
                                "INSERT INTO branch_snapshots(id,repo_id,branch_id,commit_sha,content_path) VALUES(?,?,?,?,?)",
                                snapshot.id().value(),
                                repoId,
                                branchId,
                                commit,
                                snapshot.contentPath().toString());
                        db.batchUpdate(
                                """
                    INSERT INTO code_chunks(id,repo_id,snapshot_id,commit_sha,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,created_at,symbol_id,symbol_name,symbol_kind)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                                chunks,
                                250,
                                (statement, c) -> {
                                    statement.setObject(1, c.id().value());
                                    statement.setObject(2, repoId);
                                    statement.setObject(3, snapshot.id().value());
                                    statement.setString(4, commit);
                                    statement.setString(5, c.filePath());
                                    statement.setString(6, c.language());
                                    statement.setString(7, c.assetType().name());
                                    statement.setString(8, c.chunkType().name());
                                    statement.setInt(9, c.startLine());
                                    statement.setInt(10, c.endLine());
                                    statement.setString(11, c.content());
                                    statement.setString(12, c.contentHash());
                                    statement.setTimestamp(13, Timestamp.from(c.createdAt()));
                                    statement.setString(14, c.symbolId());
                                    statement.setString(15, c.symbolName());
                                    statement.setString(16, c.symbolKind());
                                });
                        if (db.update(
                                        """
                    UPDATE repository_branches SET published_snapshot_id=?,preparation_status='READY',updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND repo_id=? AND generation=? AND preparation_status='BUILDING'
                    """,
                                        snapshot.id().value(),
                                        branchId,
                                        repoId,
                                        generation)
                                != 1)
                            throw new ApiSecurityException(
                                    409, "BRANCH_BUILD_SUPERSEDED", "该次准备已被新的任务取代");
                    });
            unpublished = null;
        } catch (RuntimeException error) {
            if (unpublished != null) snapshots.discardUnpublished(unpublished);
            db.update(
                    "UPDATE repository_branches SET preparation_status='FAILED',preparation_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND generation=?",
                    "准备失败，请确认本地存在该分支及可读取的提交",
                    branchId,
                    generation);
            throw error;
        }
        return list(actor, repoId).stream()
                .filter(b -> b.id().equals(branchId))
                .findFirst()
                .orElseThrow();
    }

    public BranchReadContext resolve(
            AuthenticatedAccount actor, UUID repoId, UUID branchId, UUID contextId) {
        require(actor, repoId, RepositoryPermission.READ);
        if (contextId != null) {
            List<BranchReadContext> rows =
                    db.query(
                            """
                SELECT c.*,b.name,s.commit_sha,s.content_path FROM branch_read_contexts c
                JOIN repository_branches b ON b.id=c.branch_id JOIN branch_snapshots s ON s.id=c.snapshot_id
                WHERE c.id=? AND c.repo_id=? AND c.account_id=? AND c.expires_at>CURRENT_TIMESTAMP
                """,
                            (r, n) ->
                                    new BranchReadContext(
                                            r.getObject("id", UUID.class),
                                            repoId,
                                            r.getObject("branch_id", UUID.class),
                                            r.getString("name"),
                                            r.getObject("snapshot_id", UUID.class),
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
                SELECT b.id,b.name,s.id snapshot_id,s.commit_sha,s.content_path FROM repository_branches b
                JOIN branch_snapshots s ON s.id=b.published_snapshot_id WHERE b.repo_id=? AND b.id=?
                """,
                                    (r, n) ->
                                            new BranchReadContext(
                                                    UUID.randomUUID(),
                                                    repoId,
                                                    branchId,
                                                    r.getString("name"),
                                                    r.getObject("snapshot_id", UUID.class),
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
                            "INSERT INTO branch_read_contexts(id,account_id,repo_id,branch_id,snapshot_id,expires_at) VALUES(?,?,?,?,?,?)",
                            context.contextId(),
                            actor.id(),
                            repoId,
                            branchId,
                            context.snapshotId(),
                            Timestamp.from(context.expiresAt()));
                    db.update(
                            """
                INSERT INTO branch_context_knowledge(context_id,card_id,revision)
                SELECT ?,k.id,k.revision FROM knowledge_cards k JOIN knowledge_branch_scopes s ON s.card_id=k.id
                WHERE k.repo_id=? AND k.publication_status='PUBLISHED' AND k.review_status='APPROVED'
                AND (s.mode='ALL_BRANCHES' OR ?=ANY(s.branch_ids))
                """,
                            context.contextId(),
                            repoId,
                            branchId);
                    return context;
                });
    }

    public CodeRepository repositoryFor(BranchReadContext context) {
        CodeRepository original = repository(context.repositoryId());
        return original.withManagedSnapshot(
                new GitRepositorySnapshot(
                        context.branchName(),
                        context.commitSha(),
                        context.commitSha(),
                        false,
                        Instant.now()),
                new ManagedRepositorySnapshot(
                        RepositorySnapshotId.of(context.snapshotId()),
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
