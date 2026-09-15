package com.analyzercoder.application.branch;

import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Content indexing is pinned to an immutable branch snapshot; it never fetches Git. */
@Service
public class BranchContentIndexService {
    private final JdbcTemplate db;
    private final RepositoryScannerPort scanner;
    private final CodeSymbolExtractor symbols;
    private final MarkdownKnowledgeSourceService markdown;
    private final TransactionTemplate transaction;

    public BranchContentIndexService(
            JdbcTemplate db,
            RepositoryScannerPort scanner,
            CodeSymbolExtractor symbols,
            MarkdownKnowledgeSourceService markdown,
            PlatformTransactionManager manager) {
        this.db = db;
        this.scanner = scanner;
        this.symbols = symbols;
        this.markdown = markdown;
        this.transaction = new TransactionTemplate(manager);
    }

    public void index(BranchReadContext context, CodeRepository source, Runnable checkpoint) {
        if (!source.id().value().equals(context.repositoryId())
                || source.currentSnapshotId() == null
                || !source.currentSnapshotId().value().equals(context.snapshotId())
                || !context.contentPath().equals(source.currentSnapshotPath()))
            throw new IllegalArgumentException("内容索引必须使用目标分支快照的代码");
        checkpoint.run();
        if (indexed(context)) return;
        String commit = context.commitSha();
        var repoId = context.repositoryId();
        java.util.List<CodeChunk> chunks = new ArrayList<>();
        var scannedFiles = scanner.scan(source);
        checkpoint.run();
        for (var file : scannedFiles) {
            String[] lines = file.content().split("\\R", -1);
            for (int start = 0; start < lines.length; start += 100) {
                int end = Math.min(lines.length, start + 120);
                chunks.add(
                        CodeChunk.fileChunk(
                                source.id(),
                                RepositorySnapshotId.of(context.snapshotId()),
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
                                RepositorySnapshotId.of(context.snapshotId()),
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
            if (chunks.size() > 100_000) throw new IllegalArgumentException("分支片段数量超过限制，请缩小索引范围");
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("分支没有可索引的文本文件");

        checkpoint.run();
        transaction.executeWithoutResult(
                status -> {
                    // Lock and re-check, so duplicate or recovered tasks cannot duplicate immutable
                    // chunks.
                    db.queryForObject(
                            "SELECT id FROM branch_snapshots WHERE repo_id=? AND branch_id=? AND id=? FOR UPDATE",
                            java.util.UUID.class,
                            repoId,
                            context.branchId(),
                            context.snapshotId());
                    checkpoint.run();
                    if (indexed(context)) return;
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
                                statement.setObject(3, context.snapshotId());
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

                    // Old pinned index tasks must not replace the new branch's Markdown source
                    // inventory.
                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT published_snapshot_id=? FROM repository_branches WHERE repo_id=? AND id=?",
                                    Boolean.class,
                                    context.snapshotId(),
                                    repoId,
                                    context.branchId()))) {
                        markdown.synchronizeBranch(
                                repoId, context.branchId(), context.snapshotId(), scannedFiles);
                    }
                    db.update(
                            "UPDATE branch_snapshots SET content_indexed_at=CURRENT_TIMESTAMP WHERE repo_id=? AND branch_id=? AND id=?",
                            repoId,
                            context.branchId(),
                            context.snapshotId());
                });
    }

    public boolean indexed(BranchReadContext context) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT content_indexed_at IS NOT NULL FROM branch_snapshots WHERE repo_id=? AND branch_id=? AND id=?",
                        Boolean.class,
                        context.repositoryId(),
                        context.branchId(),
                        context.snapshotId()));
    }
}
