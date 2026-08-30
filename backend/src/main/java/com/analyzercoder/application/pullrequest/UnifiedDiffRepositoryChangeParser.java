package com.analyzercoder.application.pullrequest;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 将提供方返回的统一 Diff 转成与本地 Git 分析相同的事实模型，不执行 Shell 或 Checkout。 */
@Component
public class UnifiedDiffRepositoryChangeParser {
    static final int MAX_PATCH_BYTES = 5 * 1024 * 1024;
    static final int MAX_FILES = 5_000;
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public RepositoryChange parse(PullRequestProvider.PullRequestSnapshot snapshot) {
        byte[] bytes = snapshot.patch().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PATCH_BYTES) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_PATCH_TOO_LARGE", "PR/MR Patch 超过 5 MiB，未生成不完整审查");
        }
        ArrayList<RepositoryChange.FileChange> files = new ArrayList<>();
        FileBuilder current = null;
        for (String rawLine : snapshot.patch().split("\\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    files.add(current.build());
                    if (files.size() > MAX_FILES) {
                        throw new PullRequestIntegrationException(
                                "PROVIDER_FILE_LIMIT_EXCEEDED", "PR/MR 变更文件超过 5000 个");
                    }
                }
                current = FileBuilder.fromHeader(line.substring("diff --git ".length()));
                continue;
            }
            if (current == null) {
                continue;
            }
            current.accept(line);
        }
        if (current != null) {
            files.add(current.build());
        }
        if (files.size() > MAX_FILES) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_FILE_LIMIT_EXCEEDED", "PR/MR 变更文件超过 5000 个");
        }
        if (files.isEmpty() && !snapshot.patch().isBlank()) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_PATCH_INVALID", "提供方返回的 Patch 不是可识别的统一 Diff");
        }
        return new RepositoryChange(
                GitChangeRequest.Source.COMMIT_RANGE,
                normalizeCommit(snapshot.baseSha()),
                normalizeCommit(snapshot.headSha()),
                null,
                snapshot.partial(),
                List.copyOf(files),
                snapshot.limitations());
    }

    private static String normalizeCommit(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{40,64}")) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_COMMIT_INVALID", "提供方没有返回有效的 Base/Head 提交 ID");
        }
        return normalized;
    }

    private static final class FileBuilder {
        private RepositoryChange.ChangeType type = RepositoryChange.ChangeType.MODIFIED;
        private String oldPath;
        private String newPath;
        private boolean binary;
        private long additions;
        private long deletions;
        private final List<RepositoryChange.Hunk> hunks = new ArrayList<>();
        private boolean inHunk;

        private static FileBuilder fromHeader(String value) {
            FileBuilder builder = new FileBuilder();
            int separator = value.lastIndexOf(" b/");
            if (separator > 0) {
                builder.oldPath = path(value.substring(0, separator));
                builder.newPath = path(value.substring(separator + 1));
            }
            return builder;
        }

        private void accept(String line) {
            if (line.equals("new file mode") || line.startsWith("new file mode ")) {
                type = RepositoryChange.ChangeType.ADDED;
                oldPath = null;
                return;
            }
            if (line.equals("deleted file mode") || line.startsWith("deleted file mode ")) {
                type = RepositoryChange.ChangeType.DELETED;
                newPath = null;
                return;
            }
            if (line.startsWith("rename from ")) {
                type = RepositoryChange.ChangeType.RENAMED;
                oldPath = safePath(line.substring("rename from ".length()));
                return;
            }
            if (line.startsWith("rename to ")) {
                type = RepositoryChange.ChangeType.RENAMED;
                newPath = safePath(line.substring("rename to ".length()));
                return;
            }
            if (line.startsWith("copy from ")) {
                type = RepositoryChange.ChangeType.COPIED;
                oldPath = safePath(line.substring("copy from ".length()));
                return;
            }
            if (line.startsWith("copy to ")) {
                type = RepositoryChange.ChangeType.COPIED;
                newPath = safePath(line.substring("copy to ".length()));
                return;
            }
            if (line.startsWith("--- ")) {
                String value = line.substring(4);
                oldPath = "/dev/null".equals(value) ? null : path(value);
                if (oldPath == null) {
                    type = RepositoryChange.ChangeType.ADDED;
                }
                return;
            }
            if (line.startsWith("+++ ")) {
                String value = line.substring(4);
                newPath = "/dev/null".equals(value) ? null : path(value);
                if (newPath == null) {
                    type = RepositoryChange.ChangeType.DELETED;
                }
                return;
            }
            if (line.startsWith("Binary files ") || line.equals("GIT binary patch")) {
                binary = true;
                return;
            }
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (matcher.matches()) {
                hunks.add(
                        new RepositoryChange.Hunk(
                                integer(matcher.group(1)),
                                count(matcher.group(2)),
                                integer(matcher.group(3)),
                                count(matcher.group(4))));
                inHunk = true;
                return;
            }
            if (inHunk && line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (inHunk && line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }

        private RepositoryChange.FileChange build() {
            if (oldPath == null && newPath == null) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_PATCH_INVALID", "Patch 中存在无法确认仓库相对路径的文件");
            }
            return new RepositoryChange.FileChange(
                    type,
                    oldPath,
                    newPath,
                    binary,
                    binary ? null : additions,
                    binary ? null : deletions,
                    List.copyOf(hunks));
        }

        private static int integer(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_PATCH_INVALID", "Patch Hunk 行号超出支持范围", exception);
            }
        }

        private static int count(String value) {
            return value == null ? 1 : integer(value);
        }

        private static String path(String raw) {
            String value = raw;
            int tab = value.indexOf('\t');
            if (tab >= 0) {
                value = value.substring(0, tab);
            }
            value = unquote(value.trim());
            if (value.startsWith("a/") || value.startsWith("b/")) {
                value = value.substring(2);
            }
            return safePath(value);
        }

        private static String unquote(String value) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
                return value.replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return value;
        }

        private static String safePath(String raw) {
            String value = unquote(raw.trim()).replace('\\', '/');
            if (value.isBlank()
                    || value.length() > 1_000
                    || value.startsWith("/")
                    || value.matches("^[A-Za-z]:/.*")
                    || value.indexOf('\0') >= 0
                    || value.chars().anyMatch(character -> character < 32 || character == 127)
                    || List.of(value.split("/")).contains("..")) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_PATH_UNSAFE", "Patch 包含不安全或无效的仓库路径");
            }
            return value;
        }
    }
}
