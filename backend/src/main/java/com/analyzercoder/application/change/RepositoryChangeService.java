package com.analyzercoder.application.change;

import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.infrastructure.git.ProcessGitClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 从受控 Git 命令构造可追溯的文件、行数和 Hunk 变化事实。 */
@Service
public class RepositoryChangeService {
    static final int MAX_FILES = 5_000;
    static final int MAX_PATCH_BYTES = 5 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 48 * 1024 * 1024;
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private final ProcessGitClient git;

    public RepositoryChangeService(ProcessGitClient git) {
        this.git = git;
    }

    public RepositoryChange analyze(GitChangeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Path root = verifyRepositoryRoot(request.repositoryRoot());
        List<RepositoryChange.Limitation> limitations = new ArrayList<>();
        Comparison comparison = comparison(root, request, limitations);

        String initialDigest = null;
        String initialHead = null;
        if (request.source() == GitChangeRequest.Source.WORKTREE) {
            initialHead = comparison.baseCommit();
            initialDigest = worktreeDigest(root);
        }

        Analysis analysis = analyzeComparison(root, comparison, request.source(), limitations);

        if (request.source() == GitChangeRequest.Source.WORKTREE) {
            String finalDigest = worktreeDigest(root);
            String finalHead = resolveRef(root, "HEAD");
            if (!Objects.equals(initialDigest, finalDigest)
                    || !Objects.equals(initialHead, finalHead)) {
                throw new RepositoryChangeException(
                        "WORKTREE_CHANGED_DURING_ANALYSIS", "分析期间 Git 工作区发生变化，请重试");
            }
        }

        return new RepositoryChange(
                request.source(),
                comparison.baseCommit(),
                comparison.headCommit(),
                initialDigest,
                analysis.partial(),
                analysis.changes(),
                List.copyOf(limitations));
    }

    private Analysis analyzeComparison(
            Path root,
            Comparison comparison,
            GitChangeRequest.Source source,
            List<RepositoryChange.Limitation> limitations) {
        ProcessGitClient.CommandResult names =
                requireSuccess(
                        run(root, MAX_METADATA_BYTES, diffArguments("--name-status", comparison)),
                        "GIT_DIFF_FAILED",
                        "无法读取 Git 文件变化");
        ParsedChanges parsed = parseNameStatus(names.stdout(), MAX_FILES);
        boolean partial = names.stdoutTruncated() || parsed.incomplete() || parsed.limitExceeded();
        if (names.stdoutTruncated() || parsed.incomplete()) {
            addLimitation(limitations, "CHANGE_METADATA_INCOMPLETE", "Git 文件变化输出超过读取上限或缺少完整字段");
        }
        if (parsed.limitExceeded()) {
            addLimitation(
                    limitations, "FILE_COUNT_LIMIT_EXCEEDED", "单次分析最多返回 " + MAX_FILES + " 个文件");
        }
        if (parsed.unsupportedStatus()) {
            partial = true;
            addLimitation(limitations, "UNMERGED_CHANGE_UNSUPPORTED", "存在无法完整表达的合并冲突或未知 Git 状态");
        }

        ProcessGitClient.CommandResult numstat =
                requireSuccess(
                        run(root, MAX_METADATA_BYTES, diffArguments("--numstat", comparison)),
                        "GIT_DIFF_FAILED",
                        "无法读取 Git 行数变化");
        ParsedStats parsedStats = parseNumstat(numstat.stdout());
        if (numstat.stdoutTruncated() || parsedStats.incomplete()) {
            partial = true;
            addLimitation(limitations, "NUMSTAT_INCOMPLETE", "Git 行数统计超过读取上限或格式不完整");
        }

        ProcessGitClient.CommandResult patch =
                requireSuccess(
                        run(root, MAX_PATCH_BYTES, patchArguments(comparison)),
                        "GIT_DIFF_FAILED",
                        "无法读取 Git Hunk");
        if (patch.stdoutTruncated()) {
            partial = true;
            addLimitation(
                    limitations, "PATCH_SIZE_LIMIT_EXCEEDED", "Git Patch 超过 5 MiB，Hunk 已显式标记为部分结果");
        }

        List<List<RepositoryChange.Hunk>> hunks =
                parseHunks(patch.stdout(), parsed.changes().size());
        List<RepositoryChange.FileChange> changes =
                mergeTrackedChanges(
                        parsed.changes(),
                        parsedStats.stats(),
                        hunks,
                        numstat.stdoutTruncated() || parsedStats.incomplete());

        if (source == GitChangeRequest.Source.WORKTREE && changes.size() < MAX_FILES) {
            ProcessGitClient.CommandResult untracked =
                    requireSuccess(
                            run(
                                    root,
                                    MAX_METADATA_BYTES,
                                    List.of("ls-files", "--others", "--exclude-standard", "-z")),
                            "GIT_DIFF_FAILED",
                            "无法读取未跟踪文件");
            UntrackedResult extra =
                    untrackedChanges(root, untracked.stdout(), changes, MAX_FILES - changes.size());
            changes = append(changes, extra.changes());
            if (untracked.stdoutTruncated() || extra.incomplete()) {
                partial = true;
                addLimitation(limitations, "UNTRACKED_FILES_INCOMPLETE", "未跟踪文件列表超过读取上限或格式不完整");
            }
            if (extra.limitExceeded()) {
                partial = true;
                addLimitation(
                        limitations, "FILE_COUNT_LIMIT_EXCEEDED", "单次分析最多返回 " + MAX_FILES + " 个文件");
            }
        } else if (source == GitChangeRequest.Source.WORKTREE) {
            ProcessGitClient.CommandResult untracked =
                    requireSuccess(
                            run(
                                    root,
                                    1,
                                    List.of("ls-files", "--others", "--exclude-standard", "-z")),
                            "GIT_DIFF_FAILED",
                            "无法检查未跟踪文件");
            if (untracked.stdout().length > 0 || untracked.stdoutTruncated()) {
                partial = true;
                addLimitation(
                        limitations, "FILE_COUNT_LIMIT_EXCEEDED", "单次分析最多返回 " + MAX_FILES + " 个文件");
            }
        }
        return new Analysis(List.copyOf(changes), partial);
    }

    private Comparison comparison(
            Path root, GitChangeRequest request, List<RepositoryChange.Limitation> limitations) {
        return switch (request.source()) {
            case WORKTREE -> {
                String head = resolveRef(root, "HEAD");
                yield new Comparison(head, null, head, null);
            }
            case COMMIT_RANGE -> {
                String base = resolveRef(root, request.baseRef());
                String head = resolveRef(root, request.headRef());
                yield new Comparison(base, head, base, head);
            }
            case SINGLE_COMMIT -> singleCommitComparison(root, request.headRef(), limitations);
        };
    }

    private Comparison singleCommitComparison(
            Path root, String requestedRef, List<RepositoryChange.Limitation> limitations) {
        String head = resolveRef(root, requestedRef);
        ProcessGitClient.CommandResult parents =
                requireSuccess(
                        run(root, 4_096, List.of("rev-list", "--parents", "-n", "1", head)),
                        "GIT_REVISION_FAILED",
                        "无法读取提交父版本");
        String[] tokens = text(parents.stdout()).trim().split("\\s+");
        if (tokens.length > 2) {
            addLimitation(limitations, "MERGE_COMMIT_FIRST_PARENT", "合并提交按第一父提交计算变更范围");
        }
        if (tokens.length >= 2) {
            return new Comparison(tokens[1], head, tokens[1], head);
        }
        ProcessGitClient.CommandResult emptyTree =
                requireSuccess(
                        run(
                                root,
                                256,
                                new byte[0],
                                List.of("hash-object", "-t", "tree", "--stdin")),
                        "GIT_REVISION_FAILED",
                        "无法构造根提交比较基线");
        String emptyTreeId = objectId(emptyTree.stdout(), "空树对象");
        return new Comparison(null, head, emptyTreeId, head);
    }

    private List<String> diffArguments(String format, Comparison comparison) {
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "diff",
                                format,
                                "-z",
                                "--no-ext-diff",
                                "--no-textconv",
                                "-M",
                                "-C",
                                "--find-copies-harder"));
        arguments.add(comparison.baseArgument());
        if (comparison.headArgument() != null) {
            arguments.add(comparison.headArgument());
        }
        arguments.add("--");
        return arguments;
    }

    private List<String> patchArguments(Comparison comparison) {
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "diff",
                                "--unified=0",
                                "--no-color",
                                "--no-ext-diff",
                                "--no-textconv",
                                "-M",
                                "-C",
                                "--find-copies-harder"));
        arguments.add(comparison.baseArgument());
        if (comparison.headArgument() != null) {
            arguments.add(comparison.headArgument());
        }
        arguments.add("--");
        return arguments;
    }

    private Path verifyRepositoryRoot(Path requestedRoot) {
        Path root = requestedRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new RepositoryChangeException("NOT_GIT_REPOSITORY", "该路径不是可读取的 Git 仓库");
        }
        ProcessGitClient.CommandResult result =
                run(root, 16_384, List.of("rev-parse", "--show-toplevel"));
        if (result.exitCode() != 0 || result.stdoutTruncated()) {
            throw new RepositoryChangeException("NOT_GIT_REPOSITORY", "该路径不是可读取的 Git 仓库");
        }
        String topLevel = text(result.stdout()).trim();
        try {
            if (topLevel.isBlank() || !Files.isSameFile(root, Path.of(topLevel))) {
                throw new RepositoryChangeException(
                        "REPOSITORY_ROOT_REQUIRED", "仓库路径必须指向 Git 工作区根目录");
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof RepositoryChangeException repositoryChangeException) {
                throw repositoryChangeException;
            }
            throw new RepositoryChangeException("NOT_GIT_REPOSITORY", "无法确认 Git 工作区根目录", exception);
        }
        return root;
    }

    private String resolveRef(Path root, String ref) {
        ProcessGitClient.CommandResult result =
                run(
                        root,
                        4_096,
                        List.of("rev-parse", "--verify", "--end-of-options", ref + "^{commit}"));
        if (result.exitCode() != 0 || result.stdoutTruncated()) {
            throw new RepositoryChangeException("GIT_REF_NOT_FOUND", "Git Ref 不存在或不是提交对象");
        }
        return objectId(result.stdout(), "Git Ref");
    }

    private String objectId(byte[] output, String label) {
        String value = text(output).trim();
        if (!value.matches("(?i)[0-9a-f]{40,64}")) {
            throw new RepositoryChangeException("GIT_REVISION_FAILED", label + "未返回有效对象 ID");
        }
        return value.toLowerCase();
    }

    private String worktreeDigest(Path root) {
        try {
            return git.worktreeDigest(root);
        } catch (ProcessGitClient.GitClientException exception) {
            throw new RepositoryChangeException(exception.code(), "无法读取一致的 Git 工作区", exception);
        }
    }

    private ProcessGitClient.CommandResult run(Path root, int limit, List<String> arguments) {
        try {
            return git.run(root, limit, arguments);
        } catch (ProcessGitClient.GitClientException exception) {
            throw new RepositoryChangeException(
                    exception.code(), exception.getMessage(), exception);
        }
    }

    private ProcessGitClient.CommandResult run(
            Path root, int limit, byte[] stdin, List<String> arguments) {
        try {
            return git.run(root, limit, stdin, arguments);
        } catch (ProcessGitClient.GitClientException exception) {
            throw new RepositoryChangeException(
                    exception.code(), exception.getMessage(), exception);
        }
    }

    private static ProcessGitClient.CommandResult requireSuccess(
            ProcessGitClient.CommandResult result, String code, String message) {
        if (result.exitCode() != 0) {
            String detail = safeError(result.stderr());
            throw new RepositoryChangeException(
                    code, detail.isBlank() ? message : message + ": " + detail);
        }
        return result;
    }

    static ParsedChanges parseNameStatus(byte[] output, int maximumFiles) {
        String[] tokens = text(output).split("\u0000", -1);
        List<RawChange> changes = new ArrayList<>();
        boolean incomplete = !endsWithNull(output);
        boolean limitExceeded = false;
        boolean unsupportedStatus = false;
        for (int index = 0; index < tokens.length; ) {
            String status = tokens[index++];
            if (status.isBlank()) {
                continue;
            }
            if (index >= tokens.length) {
                incomplete = true;
                break;
            }
            char code = status.charAt(0);
            String first = normalizePath(tokens[index++]);
            String second = null;
            if (code == 'R' || code == 'C') {
                if (index >= tokens.length || tokens[index].isEmpty()) {
                    incomplete = true;
                    break;
                }
                second = normalizePath(tokens[index++]);
            }
            RepositoryChange.ChangeType type =
                    switch (code) {
                        case 'A' -> RepositoryChange.ChangeType.ADDED;
                        case 'D' -> RepositoryChange.ChangeType.DELETED;
                        case 'R' -> RepositoryChange.ChangeType.RENAMED;
                        case 'C' -> RepositoryChange.ChangeType.COPIED;
                        case 'M', 'T' -> RepositoryChange.ChangeType.MODIFIED;
                        default -> RepositoryChange.ChangeType.MODIFIED;
                    };
            unsupportedStatus |= "ACDMRT".indexOf(code) < 0;
            RawChange change =
                    new RawChange(
                            type,
                            type == RepositoryChange.ChangeType.ADDED ? null : first,
                            type == RepositoryChange.ChangeType.DELETED
                                    ? null
                                    : second == null ? first : second);
            if (changes.size() < maximumFiles) {
                changes.add(change);
            } else {
                limitExceeded = true;
            }
        }
        return new ParsedChanges(
                List.copyOf(changes), incomplete, limitExceeded, unsupportedStatus);
    }

    static ParsedStats parseNumstat(byte[] output) {
        String[] tokens = text(output).split("\u0000", -1);
        List<NumStat> stats = new ArrayList<>();
        boolean incomplete = !endsWithNull(output);
        for (int index = 0; index < tokens.length; ) {
            String token = tokens[index++];
            if (token.isEmpty()) {
                continue;
            }
            int firstTab = token.indexOf('\t');
            int secondTab = firstTab < 0 ? -1 : token.indexOf('\t', firstTab + 1);
            if (firstTab < 0 || secondTab < 0) {
                incomplete = true;
                break;
            }
            String additions = token.substring(0, firstTab);
            String deletions = token.substring(firstTab + 1, secondTab);
            String path = token.substring(secondTab + 1);
            String oldPath;
            String newPath;
            if (path.isEmpty()) {
                if (index + 1 >= tokens.length) {
                    incomplete = true;
                    break;
                }
                oldPath = normalizePath(tokens[index++]);
                newPath = normalizePath(tokens[index++]);
            } else {
                oldPath = normalizePath(path);
                newPath = oldPath;
            }
            boolean binary = "-".equals(additions) || "-".equals(deletions);
            try {
                stats.add(
                        new NumStat(
                                oldPath,
                                newPath,
                                binary,
                                binary ? null : Long.parseLong(additions),
                                binary ? null : Long.parseLong(deletions)));
            } catch (NumberFormatException exception) {
                incomplete = true;
                break;
            }
        }
        return new ParsedStats(List.copyOf(stats), incomplete);
    }

    static List<List<RepositoryChange.Hunk>> parseHunks(byte[] patch, int changeCount) {
        List<List<RepositoryChange.Hunk>> hunks = new ArrayList<>();
        for (int index = 0; index < changeCount; index++) {
            hunks.add(new ArrayList<>());
        }
        int section = -1;
        for (String line : text(patch).split("\\n", -1)) {
            if (line.startsWith("diff --git ")) {
                section++;
                continue;
            }
            if (line.startsWith("diff --")) {
                section = -1;
                continue;
            }
            Matcher matcher =
                    HUNK_HEADER.matcher(
                            line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (section >= 0 && section < hunks.size() && matcher.matches()) {
                hunks.get(section)
                        .add(
                                new RepositoryChange.Hunk(
                                        Integer.parseInt(matcher.group(1)),
                                        count(matcher.group(2)),
                                        Integer.parseInt(matcher.group(3)),
                                        count(matcher.group(4))));
            }
        }
        return hunks.stream().map(List::copyOf).toList();
    }

    private static List<RepositoryChange.FileChange> mergeTrackedChanges(
            List<RawChange> rawChanges,
            List<NumStat> stats,
            List<List<RepositoryChange.Hunk>> hunks,
            boolean statsPartial) {
        List<RepositoryChange.FileChange> changes = new ArrayList<>();
        Set<Integer> usedStats = new HashSet<>();
        for (int index = 0; index < rawChanges.size(); index++) {
            RawChange raw = rawChanges.get(index);
            NumStat stat = null;
            for (int statIndex = 0; statIndex < stats.size(); statIndex++) {
                if (!usedStats.contains(statIndex) && stats.get(statIndex).matches(raw)) {
                    stat = stats.get(statIndex);
                    usedStats.add(statIndex);
                    break;
                }
            }
            changes.add(
                    new RepositoryChange.FileChange(
                            raw.type(),
                            raw.oldPath(),
                            raw.newPath(),
                            stat != null && stat.binary(),
                            stat == null ? (statsPartial ? null : 0L) : stat.additions(),
                            stat == null ? (statsPartial ? null : 0L) : stat.deletions(),
                            index < hunks.size() ? hunks.get(index) : List.of()));
        }
        return List.copyOf(changes);
    }

    private static UntrackedResult untrackedChanges(
            Path root,
            byte[] output,
            List<RepositoryChange.FileChange> trackedChanges,
            int remainingCapacity) {
        String[] tokens = text(output).split("\u0000", -1);
        Set<String> existing = new HashSet<>();
        for (RepositoryChange.FileChange change : trackedChanges) {
            if (change.oldPath() != null) {
                existing.add(change.oldPath());
            }
            if (change.newPath() != null) {
                existing.add(change.newPath());
            }
        }
        List<RepositoryChange.FileChange> changes = new ArrayList<>();
        boolean limitExceeded = false;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            String path = normalizePath(token);
            if (!existing.add(path)) {
                continue;
            }
            if (changes.size() >= remainingCapacity) {
                limitExceeded = true;
                continue;
            }
            NewFileFacts facts = inspectUntrackedFile(root, path);
            List<RepositoryChange.Hunk> hunks =
                    facts.binary() || facts.lines() == 0
                            ? List.of()
                            : List.of(new RepositoryChange.Hunk(0, 0, 1, facts.lines()));
            changes.add(
                    new RepositoryChange.FileChange(
                            RepositoryChange.ChangeType.ADDED,
                            null,
                            path,
                            facts.binary(),
                            facts.binary() ? null : (long) facts.lines(),
                            facts.binary() ? null : 0L,
                            hunks));
        }
        return new UntrackedResult(List.copyOf(changes), !endsWithNull(output), limitExceeded);
    }

    private static NewFileFacts inspectUntrackedFile(Path root, String repositoryPath) {
        Path file = root.resolve(Path.of(repositoryPath)).normalize();
        if (!file.startsWith(root)) {
            throw new RepositoryChangeException("INVALID_GIT_PATH", "未跟踪文件路径超出仓库范围");
        }
        try {
            if (Files.isSymbolicLink(file)) {
                return facts(
                        Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                boolean binary = false;
                long total = 0;
                long newlines = 0;
                int last = -1;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    for (int index = 0; index < read; index++) {
                        int value = buffer[index] & 0xff;
                        if (total + index < 8_000 && value == 0) {
                            binary = true;
                        }
                        if (value == '\n') {
                            newlines++;
                        }
                        last = value;
                    }
                    total += read;
                }
                long lines = total == 0 ? 0 : newlines + (last == '\n' ? 0 : 1);
                if (lines > Integer.MAX_VALUE) {
                    throw new RepositoryChangeException(
                            "FILE_LINE_COUNT_EXCEEDED", "未跟踪文件行数超过可表达范围");
                }
                return new NewFileFacts(binary, (int) lines);
            }
        } catch (IOException exception) {
            throw new RepositoryChangeException(
                    "WORKTREE_FILE_UNAVAILABLE", "分析期间无法读取未跟踪文件", exception);
        }
    }

    private static NewFileFacts facts(byte[] content) {
        boolean binary = false;
        long newlines = 0;
        for (int index = 0; index < content.length; index++) {
            int value = content[index] & 0xff;
            binary |= index < 8_000 && value == 0;
            if (value == '\n') {
                newlines++;
            }
        }
        long lines =
                content.length == 0 ? 0 : newlines + (content[content.length - 1] == '\n' ? 0 : 1);
        return new NewFileFacts(binary, Math.toIntExact(lines));
    }

    private static String normalizePath(String requestedPath) {
        try {
            return RepositoryGlobMatcher.normalizeRepositoryPath(requestedPath);
        } catch (IllegalArgumentException exception) {
            throw new RepositoryChangeException(
                    "INVALID_GIT_PATH", exception.getMessage(), exception);
        }
    }

    private static int count(String value) {
        return value == null ? 1 : Integer.parseInt(value);
    }

    private static List<RepositoryChange.FileChange> append(
            List<RepositoryChange.FileChange> first, List<RepositoryChange.FileChange> second) {
        List<RepositoryChange.FileChange> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static void addLimitation(
            List<RepositoryChange.Limitation> limitations, String code, String detail) {
        RepositoryChange.Limitation limitation = new RepositoryChange.Limitation(code, detail);
        if (!limitations.contains(limitation)) {
            limitations.add(limitation);
        }
    }

    private static String safeError(byte[] stderr) {
        String detail = text(stderr).trim().replaceAll("[\\r\\n]+", " ");
        return detail.substring(0, Math.min(300, detail.length()));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean endsWithNull(byte[] bytes) {
        return bytes.length == 0 || bytes[bytes.length - 1] == 0;
    }

    record RawChange(RepositoryChange.ChangeType type, String oldPath, String newPath) {}

    record ParsedChanges(
            List<RawChange> changes,
            boolean incomplete,
            boolean limitExceeded,
            boolean unsupportedStatus) {}

    record NumStat(String oldPath, String newPath, boolean binary, Long additions, Long deletions) {
        boolean matches(RawChange change) {
            if (change.type() == RepositoryChange.ChangeType.RENAMED
                    || change.type() == RepositoryChange.ChangeType.COPIED) {
                return Objects.equals(oldPath, change.oldPath())
                        && Objects.equals(newPath, change.newPath());
            }
            String path = change.newPath() == null ? change.oldPath() : change.newPath();
            return Objects.equals(oldPath, path) && Objects.equals(newPath, path);
        }
    }

    record ParsedStats(List<NumStat> stats, boolean incomplete) {}

    private record Comparison(
            String baseCommit, String headCommit, String baseArgument, String headArgument) {}

    private record Analysis(List<RepositoryChange.FileChange> changes, boolean partial) {}

    private record UntrackedResult(
            List<RepositoryChange.FileChange> changes, boolean incomplete, boolean limitExceeded) {}

    private record NewFileFacts(boolean binary, int lines) {}
}
