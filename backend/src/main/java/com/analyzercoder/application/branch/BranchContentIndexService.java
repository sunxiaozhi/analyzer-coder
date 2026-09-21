package com.analyzercoder.application.branch;

import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Content indexing reads the latest branch workspace and reuses unchanged chunks. */
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
        ChangePlan plan = changePlan(context);
        UUID previousSnapshot = plan.full() ? null : previousIndexedSnapshot(context);
        boolean incremental = previousSnapshot != null;
        java.util.List<CodeChunk> chunks = new ArrayList<>();
        var scannedFiles =
                incremental ? scanner.scan(source, plan.paths()) : scanner.scan(source);
        var markdownFiles = incremental ? scanner.scanMarkdown(source) : scannedFiles;
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
        if (chunks.isEmpty() && !incremental)
            throw new IllegalArgumentException("分支没有可索引的文本文件");

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
                    if (incremental) {
                        copyUnchangedChunks(
                                repoId,
                                previousSnapshot,
                                context.snapshotId(),
                                commit,
                                plan.paths());
                    }
                    if (!chunks.isEmpty()) {
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
                    }

                    Long total =
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM code_chunks WHERE repo_id=? AND snapshot_id=?",
                                    Long.class,
                                    repoId,
                                    context.snapshotId());
                    if (total == null || total == 0)
                        throw new IllegalArgumentException("分支没有可索引的文本文件");

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
                                repoId, context.branchId(), context.snapshotId(), markdownFiles);
                    }
                    db.update(
                            "UPDATE branch_snapshots SET content_indexed_at=CURRENT_TIMESTAMP WHERE repo_id=? AND branch_id=? AND id=?",
                            repoId,
                            context.branchId(),
                            context.snapshotId());
                });
    }

    private UUID previousIndexedSnapshot(BranchReadContext context) {
        return db.query(
                        """
                        SELECT previous.id
                        FROM branch_snapshots current
                        JOIN branch_snapshots previous
                          ON previous.repo_id=current.repo_id AND previous.branch_id=current.branch_id
                         AND previous.id<>current.id AND previous.content_indexed_at IS NOT NULL
                        WHERE current.repo_id=? AND current.branch_id=? AND current.id=?
                        ORDER BY previous.created_at DESC LIMIT 1
                        """,
                        (row, number) -> row.getObject("id", UUID.class),
                        context.repositoryId(),
                        context.branchId(),
                        context.snapshotId())
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void copyUnchangedChunks(
            UUID repoId,
            UUID previousSnapshot,
            UUID snapshotId,
            String commit,
            Set<String> changedPaths) {
        StringBuilder sql =
                new StringBuilder(
                        """
                        INSERT INTO code_chunks(id,repo_id,snapshot_id,commit_sha,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,created_at,symbol_id,symbol_name,symbol_kind)
                        SELECT gen_random_uuid(),repo_id,?,?,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,CURRENT_TIMESTAMP,symbol_id,symbol_name,symbol_kind
                        FROM code_chunks WHERE repo_id=? AND snapshot_id=?
                        """);
        ArrayList<Object> arguments =
                new ArrayList<>(List.of(snapshotId, commit, repoId, previousSnapshot));
        if (!changedPaths.isEmpty()) {
            sql.append(" AND file_path NOT IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(changedPaths.size(), "?")));
            sql.append(')');
            arguments.addAll(changedPaths);
        }
        db.update(sql.toString(), arguments.toArray());
    }

    private ChangePlan changePlan(BranchReadContext context) {
        java.nio.file.Path manifest = context.contentPath().getParent().resolve("current-changes");
        if (!Files.isRegularFile(manifest)) return ChangePlan.fullScan();
        try {
            java.util.List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !context.commitSha().equals(lines.get(0).trim()))
                return ChangePlan.fullScan();
            if (lines.size() > 1 && "FULL".equals(lines.get(1))) return ChangePlan.fullScan();
            Set<String> paths = new LinkedHashSet<>();
            Base64.Decoder decoder = Base64.getUrlDecoder();
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                int separator = line.indexOf('\t');
                if (separator != 1) return ChangePlan.fullScan();
                String path =
                        new String(
                                decoder.decode(line.substring(separator + 1)),
                                StandardCharsets.UTF_8);
                if (path.isBlank() || path.startsWith("/") || path.contains("\\"))
                    return ChangePlan.fullScan();
                paths.add(path);
            }
            return new ChangePlan(false, Set.copyOf(paths));
        } catch (Exception ignored) {
            return ChangePlan.fullScan();
        }
    }

    private record ChangePlan(boolean full, Set<String> paths) {
        private static ChangePlan fullScan() {
            return new ChangePlan(true, Set.of());
        }
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
