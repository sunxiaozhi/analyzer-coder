package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exports committed objects only; never checks out or modifies the user's worktree. */
@Component
public class GitBranchSnapshotFactory {
    private final Path root;
    private final int maxFiles;
    private final long maxBytes;

    public GitBranchSnapshotFactory(
            @Value("${app.repository.snapshot-root}") String root,
            @Value("${app.repository.snapshot-max-files:20000}") int maxFiles,
            @Value("${app.repository.snapshot-max-total-bytes:2147483648}") long maxBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    public static String validateBranch(String name) {
        if (name == null
                || name.isBlank()
                || name.length() > 200
                || name.startsWith("-")
                || name.startsWith("/")
                || name.endsWith("/")
                || name.endsWith(".")
                || name.contains("..")
                || name.contains("@{")
                || name.contains("//")
                || name.equals("@")
                || name.chars().anyMatch(c -> c <= 32 || c == 127 || "~^:?*[\\".indexOf(c) >= 0)
                || Arrays.stream(name.split("/"))
                        .anyMatch(s -> s.startsWith(".") || s.endsWith(".lock"))) {
            throw new IllegalArgumentException("Git 分支名称无效");
        }
        return name;
    }

    public String resolve(Path source, String branch) {
        validateBranch(branch);
        String result =
                output(
                        source,
                        List.of(
                                "rev-parse",
                                "--verify",
                                "--end-of-options",
                                "refs/heads/" + branch + "^{commit}"));
        if (!result.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalArgumentException("本地分支不存在或不是提交");
        return result;
    }

    public ManagedRepositorySnapshot create(
            CodeRepositoryId repositoryId, Path source, String commit) {
        if (commit == null || !commit.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalArgumentException("提交标识无效");
        RepositorySnapshotId id = RepositorySnapshotId.newId();
        Path target = root.resolve(repositoryId.value().toString()).resolve("branch-" + id.value());
        Path archive = target.resolve("source.zip"), content = target.resolve("content");
        try {
            Files.createDirectories(content);
            // Reject symlinks and submodules instead of silently exporting misleading source
            // content.
            String tree = output(source, List.of("ls-tree", "-r", "-l", commit));
            if (tree.lines()
                    .anyMatch(line -> line.startsWith("120000 ") || line.startsWith("160000 ")))
                throw new IllegalArgumentException("分支包含符号链接或子模块，暂不支持准备该分支");
            long treeBytes = 0;
            int treeFiles = 0;
            for (String line : tree.lines().toList()) {
                String[] fields = line.split("\\s+", 5);
                if (fields.length < 4) throw new IllegalArgumentException("Git 文件清单无效");
                treeBytes = Math.addExact(treeBytes, Long.parseLong(fields[3]));
                if (++treeFiles > maxFiles) throw new IllegalArgumentException("分支文件数超过限制");
                if (treeBytes > maxBytes) throw new IllegalArgumentException("分支内容超过大小限制");
            }
            run(
                    source,
                    List.of("archive", "--format=zip", "--output=" + archive, commit),
                    target.resolve("git.out"));
            int count = 0;
            long total = 0;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    Path path = content.resolve(name).normalize();
                    if (!path.startsWith(content)
                            || name.contains("\\")
                            || name.contains(":")
                            || name.startsWith("/")
                            || Arrays.stream(name.split("/"))
                                    .anyMatch(s -> s.equalsIgnoreCase(".git")))
                        throw new IllegalArgumentException("Git 导出路径越界");
                    if (entry.isDirectory()) {
                        Files.createDirectories(path);
                        continue;
                    }
                    if (++count > maxFiles) throw new IllegalArgumentException("分支文件数超过限制");
                    Files.createDirectories(path.getParent());
                    try (OutputStream out =
                            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            total += read;
                            if (total > maxBytes) throw new IllegalArgumentException("分支内容超过大小限制");
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }
            Files.deleteIfExists(archive);
            Files.deleteIfExists(target.resolve("git.out"));
            try (var paths = Files.walk(content)) {
                paths.forEach(p -> p.toFile().setWritable(false, false));
            }
            return new ManagedRepositorySnapshot(
                    id, repositoryId, content, commit, commit, Instant.now());
        } catch (IOException | RuntimeException error) {
            cleanup(target);
            throw new IllegalStateException("无法创建分支快照：" + error.getMessage(), error);
        }
    }

    private String output(Path source, List<String> args) {
        Path temp = null;
        try {
            temp = Files.createTempFile("atlas-git-read-", ".txt");
            run(source, args, temp);
            if (Files.size(temp) > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Git 文件清单超过限制");
            return Files.readString(temp, StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取 Git 提交", error);
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
        }
    }

    /** Only call for a snapshot created by this factory whose database publication failed. */
    public void discardUnpublished(ManagedRepositorySnapshot snapshot) {
        Path target =
                root.resolve(snapshot.repositoryId().value().toString())
                        .resolve("branch-" + snapshot.id().value());
        if (!snapshot.contentPath().toAbsolutePath().normalize().equals(target.resolve("content")))
            throw new IllegalArgumentException("非法分支快照清理目标");
        cleanup(target);
    }

    private void run(Path source, List<String> args, Path output) {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.hooksPath=" + GitRuntimePolicy.disabledHooksPath(),
                                "-C",
                                source.toString()));
        command.addAll(args);
        Process process = null;
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .redirectOutput(output.toFile())
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            process = builder.start();
            if (!process.waitFor(120, TimeUnit.SECONDS))
                throw new IllegalStateException("Git 导出超时");
            if (process.exitValue() != 0) throw new IllegalArgumentException("Git 分支或提交不可读取");
        } catch (IOException error) {
            throw new IllegalStateException("Git 不可用", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git 导出被中断", error);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private void cleanup(Path target) {
        if (!target.startsWith(root) || target.equals(root))
            throw new IllegalArgumentException("非法清理目标");
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, false);
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            /* Unpublished files may be safely cleaned by an operator. */
        }
    }
}
