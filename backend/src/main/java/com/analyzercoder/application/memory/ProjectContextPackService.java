package com.analyzercoder.application.memory;

import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 为不同开发智能体生成绑定当前仓库版本、可追溯的任务上下文包。 */
@Service
public class ProjectContextPackService {
    private static final Pattern QUERY_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}_.$/-]{2,80}");
    private static final List<String> FOUNDATION_QUERIES =
            List.of(
                    "RULE",
                    "TASK",
                    "AGENTS.md",
                    "CLAUDE.md",
                    "README.md",
                    "DESIGN.md",
                    "ARCHITECTURE.md");

    private final RegisterRepositoryUseCase repositories;
    private final CodeChunkStore chunks;

    public ProjectContextPackService(
            RegisterRepositoryUseCase repositories, CodeChunkStore chunks) {
        this.repositories = repositories;
        this.chunks = chunks;
    }

    public ContextPack generate(
            CodeRepositoryId repositoryId, String task, Integer requestedItems, Integer requestedChars) {
        String normalizedTask = normalizeTask(task);
        int maxItems = bound(requestedItems, 12, 5, 24);
        int maxChars = bound(requestedChars, 14_000, 4_000, 30_000);
        CodeRepository repository = repositories.get(repositoryId);
        if (repository.currentSnapshotId() == null) {
            throw new IllegalStateException("仓库尚未发布可用的项目快照");
        }

        Map<UUID, CodeChunk> selected = new LinkedHashMap<>();
        for (String query : FOUNDATION_QUERIES) {
            append(selected, repositoryId, query, 4);
        }
        for (String query : taskQueries(normalizedTask)) {
            append(selected, repositoryId, query, 8);
        }

        List<ContextItem> items =
                selected.values().stream()
                        .filter(
                                chunk ->
                                        chunk.snapshotId().equals(repository.currentSnapshotId()))
                        .sorted(ProjectContextPackService::compare)
                        .limit(maxItems)
                        .map(ProjectContextPackService::item)
                        .toList();
        String markdown = markdown(repository, normalizedTask, items, maxChars);
        return new ContextPack(
                repository.id().value(),
                repository.name(),
                repository.currentSnapshotId().value(),
                repository.currentCommit(),
                normalizedTask,
                items,
                markdown);
    }

    private void append(
            Map<UUID, CodeChunk> selected,
            CodeRepositoryId repositoryId,
            String query,
            int limit) {
        for (CodeChunk chunk : chunks.searchByRepositoryId(repositoryId, query, limit, 0)) {
            selected.putIfAbsent(chunk.id().value(), chunk);
        }
    }

    private static List<String> taskQueries(String task) {
        List<String> result = new ArrayList<>();
        result.add(task);
        Matcher matcher = QUERY_TOKEN.matcher(task);
        while (matcher.find() && result.size() < 8) {
            String token = matcher.group();
            if (token.length() <= 40 && result.stream().noneMatch(token::equalsIgnoreCase)) {
                result.add(token);
            }
        }
        return result;
    }

    private static int compare(CodeChunk left, CodeChunk right) {
        int type = Integer.compare(priority(left.assetType()), priority(right.assetType()));
        if (type != 0) return type;
        int path = left.filePath().compareToIgnoreCase(right.filePath());
        if (path != 0) return path;
        return Integer.compare(value(left.startLine()), value(right.startLine()));
    }

    private static int priority(RepositoryAssetType type) {
        return switch (type) {
            case RULE -> 0;
            case TASK -> 1;
            case CODE -> 2;
            case DOCUMENT -> 3;
            case CONFIG -> 4;
        };
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static ContextItem item(CodeChunk chunk) {
        String excerpt = chunk.content().replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 240) excerpt = excerpt.substring(0, 240) + "…";
        return new ContextItem(
                chunk.id().value(),
                chunk.assetType(),
                chunk.filePath(),
                chunk.symbolName(),
                chunk.startLine(),
                chunk.endLine(),
                excerpt,
                chunk.content(),
                chunk.contentHash());
    }

    private static String markdown(
            CodeRepository repository, String task, List<ContextItem> items, int maxChars) {
        StringBuilder output =
                new StringBuilder("# Agent Context Pack\n\n")
                        .append("- 项目：")
                        .append(repository.name())
                        .append("\n- 任务：")
                        .append(task)
                        .append("\n- 分支：")
                        .append(repository.defaultBranch())
                        .append("\n- Commit：")
                        .append(repository.currentCommit())
                        .append("\n- Snapshot：")
                        .append(repository.currentSnapshotId().value())
                        .append("\n\n> 只依据以下当前版本资产工作；规则和任务约束优先，修改前核对原文件。\n");
        int index = 1;
        for (ContextItem item : items) {
            String section =
                    "\n## S"
                            + index++
                            + " · "
                            + item.assetType().name()
                            + " · "
                            + item.filePath()
                            + lines(item.startLine(), item.endLine())
                            + "\n\n```text\n"
                            + item.content()
                            + "\n```\n";
            if (output.length() + section.length() > maxChars) {
                int remaining = maxChars - output.length();
                if (remaining > 180) output.append(section, 0, Math.min(remaining, section.length()));
                output.append("\n\n> Context Pack 已达到长度上限。\n");
                break;
            }
            output.append(section);
        }
        return output.toString();
    }

    private static String lines(Integer start, Integer end) {
        if (start == null) return "";
        return ":" + start + (end == null || end.equals(start) ? "" : "-" + end);
    }

    private static String normalizeTask(String task) {
        String normalized = task == null ? "" : task.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) throw new IllegalArgumentException("任务描述不能为空");
        if (normalized.length() > 1_000) throw new IllegalArgumentException("任务描述不能超过 1000 个字符");
        return normalized;
    }

    private static int bound(Integer value, int fallback, int minimum, int maximum) {
        int resolved = value == null ? fallback : value;
        return Math.max(minimum, Math.min(maximum, resolved));
    }

    public record ContextPack(
            UUID repositoryId,
            String repositoryName,
            UUID snapshotId,
            String commitSha,
            String task,
            List<ContextItem> items,
            String markdown) {}

    public record ContextItem(
            UUID chunkId,
            RepositoryAssetType assetType,
            String filePath,
            String symbolName,
            Integer startLine,
            Integer endLine,
            String excerpt,
            String content,
            String contentHash) {}
}
