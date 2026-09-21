package com.analyzercoder.application.branch;

import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositoryContentVersion;
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
                || source.currentContentVersion() == null
                || !source.currentContentVersion().value().equals(context.contentVersion())
                || !context.contentPath().equals(source.currentContentVersionPath()))
            throw new IllegalArgumentException("内容索引必须使用目标分支内容版本的代码");
        checkpoint.run();
        if (indexed(context)) return;
        String commit = context.commitSha();
        var repoId = context.repositoryId();
        ChangePlan plan = changePlan(context);
        UUID previousContentVersion = plan.full() ? null : previousIndexedContentVersion(context);
        boolean incremental = previousContentVersion != null;
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
                                RepositoryContentVersion.of(context.contentVersion()),
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
                                RepositoryContentVersion.of(context.contentVersion()),
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
                    // Lock the branch and re-check its publication token so stale tasks cannot
                    // publish derived data after a newer sync.
                    db.queryForObject(
                            "SELECT id FROM repository_branches WHERE repo_id=? AND id=? AND content_version=? FOR UPDATE",
                            java.util.UUID.class,
                            repoId,
                            context.branchId(),
                            context.contentVersion());
                    checkpoint.run();
                    if (indexed(context)) return;
                    if (incremental) {
                        copyUnchangedChunks(
                                repoId,
                                previousContentVersion,
                                context.contentVersion(),
                                commit,
                                plan.paths());
                    }
                    if (!chunks.isEmpty()) {
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
                                    statement.setObject(3, context.branchId());
                                    statement.setObject(4, context.contentVersion());
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
                    }

                    Long total =
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM code_chunks WHERE repo_id=? AND branch_id=? AND content_version=?",
                                    Long.class,
                                    repoId,
                                    context.branchId(),
                                    context.contentVersion());
                    if (total == null || total == 0)
                        throw new IllegalArgumentException("分支没有可索引的文本文件");

                    if (Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT content_version=? FROM repository_branches WHERE repo_id=? AND id=?",
                                    Boolean.class,
                                    context.contentVersion(),
                                    repoId,
                                    context.branchId()))) {
                        markdown.synchronizeBranch(
                                repoId, context.branchId(), context.contentVersion(), markdownFiles);
                    }
                    db.update(
                            "UPDATE repository_branches SET content_indexed_at=CURRENT_TIMESTAMP,previous_content_version=NULL WHERE repo_id=? AND id=? AND content_version=?",
                            repoId,
                            context.branchId(),
                            context.contentVersion());
                    if (previousContentVersion != null) {
                        db.update(
                                "DELETE FROM codegraph_artifacts WHERE repo_id=? AND content_version=?",
                                repoId,
                                previousContentVersion);
                        db.update(
                                "DELETE FROM code_chunks WHERE repo_id=? AND branch_id=? AND content_version=?",
                                repoId,
                                context.branchId(),
                                previousContentVersion);
                    }
                });
    }

    private UUID previousIndexedContentVersion(BranchReadContext context) {
        return db.query(
                        """
                        SELECT previous_content_version
                        FROM repository_branches
                        WHERE repo_id=? AND id=? AND content_version=?
                          AND previous_content_version IS NOT NULL
                        """,
                        (row, number) -> row.getObject("previous_content_version", UUID.class),
                        context.repositoryId(),
                        context.branchId(),
                        context.contentVersion())
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void copyUnchangedChunks(
            UUID repoId,
            UUID previousContentVersion,
            UUID contentVersion,
            String commit,
            Set<String> changedPaths) {
        StringBuilder sql =
                new StringBuilder(
                        """
                        INSERT INTO code_chunks(id,repo_id,branch_id,content_version,commit_sha,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,created_at,symbol_id,symbol_name,symbol_kind)
                        SELECT gen_random_uuid(),repo_id,branch_id,?,?,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,CURRENT_TIMESTAMP,symbol_id,symbol_name,symbol_kind
                        FROM code_chunks WHERE repo_id=? AND branch_id=? AND content_version=?
                        """);
        ArrayList<Object> arguments =
                new ArrayList<>(
                        List.of(
                                contentVersion,
                                commit,
                                repoId,
                                // content versions are branch-local publications; copy only the
                                // branch selected by the caller.
                                branchIdForVersion(repoId, contentVersion),
                                previousContentVersion));
        if (!changedPaths.isEmpty()) {
            sql.append(" AND file_path NOT IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(changedPaths.size(), "?")));
            sql.append(')');
            arguments.addAll(changedPaths);
        }
        db.update(sql.toString(), arguments.toArray());
    }

    private UUID branchIdForVersion(UUID repoId, UUID contentVersion) {
        return db.queryForObject(
                "SELECT id FROM repository_branches WHERE repo_id=? AND content_version=?",
                UUID.class,
                repoId,
                contentVersion);
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
                        "SELECT content_indexed_at IS NOT NULL FROM repository_branches WHERE repo_id=? AND id=? AND content_version=?",
                        Boolean.class,
                        context.repositoryId(),
                        context.branchId(),
                        context.contentVersion()));
    }
}
