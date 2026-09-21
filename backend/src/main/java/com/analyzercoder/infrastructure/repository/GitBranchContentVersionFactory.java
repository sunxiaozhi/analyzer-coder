package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.ManagedRepositoryContentVersion;
import com.analyzercoder.domain.repository.RepositoryContentVersion;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exports committed objects only; never checks out or modifies the user's worktree. */
@Component
public class GitBranchContentVersionFactory {
    private final Path root;
    private final int maxFiles;
    private final long maxBytes;

    public GitBranchContentVersionFactory(
            @Value("${app.repository.workspace-root}") String root,
            @Value("${app.repository.workspace-max-files:50000}") int maxFiles,
            @Value("${app.repository.workspace-max-total-bytes:2147483648}") long maxBytes) {
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
                                "refs/heads/" + branch + "^{commit}"));
        if (!result.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalArgumentException("本地分支不存在或不是提交");
        return result;
    }

    /** Resolves the committed content of an imported workspace whose logical branch is WORKSPACE. */
    public String resolveWorkspace(Path source) {
        String result = output(source, List.of("rev-parse", "--verify", "HEAD^{commit}"));
        if (!result.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalArgumentException("工作区尚未包含有效提交");
        return result;
    }

    /**
     * Prepares a private publication slot for a branch. The slot is not visible to readers until
     * the caller commits the matching database update; it is then confirmed and older slots are
     * removed. This is a deployment primitive, not retained source history.
     */
    public ManagedRepositoryContentVersion createLatest(
            CodeRepositoryId repositoryId, UUID branchId, Path source, String commit) {
        if (branchId == null) throw new IllegalArgumentException("分支标识不能为空");
        if (commit == null || !commit.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalArgumentException("提交标识无效");
        RepositoryContentVersion id = RepositoryContentVersion.newId();
        Path branchWorkspace =
                root.resolve(repositoryId.value().toString())
                        .resolve("branches")
                        .resolve(branchId.toString());
        Path publication = branchWorkspace.resolve("publication-" + id.value());
        Path content = publication.resolve("content");
        Path commitFile = publication.resolve("current-commit");
        Path staging = publication.resolve(".staging");
        try {
            validateTree(source, commit);
            Files.createDirectories(publication);
            refreshWorkspace(source, commit, publication, content, staging);
            retainCommit(source, branchId, commit, branchWorkspace);
            writeChanges(publication.resolve("current-changes"), commit, null);
            writeCommit(commitFile, commit);
            return new ManagedRepositoryContentVersion(
                    id, repositoryId, content, commit, commit, Instant.now());
        } catch (IOException | RuntimeException error) {
            cleanup(publication);
            throw new IllegalStateException("无法更新分支工作区：" + error.getMessage(), error);
        }
    }

    public boolean isLatestWorkspace(
            CodeRepositoryId repositoryId, UUID branchId, Path contentPath) {
        if (repositoryId == null || branchId == null || contentPath == null) return false;
        Path branchWorkspace =
                root.resolve(repositoryId.value().toString())
                        .resolve("branches")
                        .resolve(branchId.toString())
                        .normalize();
        Path actual = contentPath.toAbsolutePath().normalize();
        Path publication = actual.getParent();
        return publication != null
                && actual.equals(publication.resolve("content"))
                && publication.getParent() != null
                && publication.getParent().equals(branchWorkspace)
                && publication.getFileName().toString().startsWith("publication-")
                && Files.isDirectory(actual);
    }

    /** Confirms database publication and removes all superseded transient slots for the branch. */
    public void confirmPublished(ManagedRepositoryContentVersion contentVersion) {
        Path publication = publicationPath(contentVersion);
        Path branchWorkspace = publication.getParent();
        if (branchWorkspace == null) throw new IllegalArgumentException("非法分支发布路径");
        try (var children = Files.list(branchWorkspace)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("publication-"))
                    .filter(path -> !path.equals(publication))
                    .forEach(this::cleanup);
        } catch (IOException ignored) {
            /* The current database pointer is authoritative; orphan cleanup can retry later. */
        }
    }

    public void deleteRepository(CodeRepositoryId repositoryId) {
        cleanup(root.resolve(repositoryId.value().toString()));
    }

    private void validateTree(Path source, String commit) {
        String tree = output(source, List.of("ls-tree", "-r", "-l", commit));
        long treeBytes = 0;
        int treeFiles = 0;
        for (String line : tree.lines().toList()) {
            String[] fields = line.split("\\s+", 5);
            if (fields.length < 5) throw new IllegalArgumentException("Git 文件清单无效");
            if (fields[4].equals(".codegraph") || fields[4].startsWith(".codegraph/")) continue;
            if (line.startsWith("120000 ") || line.startsWith("160000 "))
                throw new IllegalArgumentException("分支包含符号链接或子模块，暂不支持准备该分支");
            treeBytes = Math.addExact(treeBytes, Long.parseLong(fields[3]));
            if (++treeFiles > maxFiles) throw new IllegalArgumentException("分支文件数超过限制");
            if (treeBytes > maxBytes) throw new IllegalArgumentException("分支内容超过大小限制");
        }
    }

    private List<ChangedPath> updateChangedFiles(
            Path source, String previous, String commit, Path content, Path staging)
            throws IOException {
        if (previous == null || !previous.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalStateException("没有可复用的上次提交");
        String raw =
                rawOutput(
                        source,
                        List.of(
                                "diff",
                                "--name-status",
                                "-z",
                                "--no-renames",
                                previous,
                                commit));
        String[] tokens = raw.split("\u0000", -1);
        List<ChangedPath> changes = new ArrayList<>();
        int totalPathCharacters = 0;
        for (int index = 0; index + 1 < tokens.length; index += 2) {
            if (tokens[index].isBlank()) continue;
            char status = tokens[index].charAt(0);
            if ("AMDT".indexOf(status) < 0)
                throw new IllegalStateException("Git 差异状态不受支持");
            String path = validateArchivePath(tokens[index + 1]);
            if (path.equals(".codegraph") || path.startsWith(".codegraph/")) continue;
            changes.add(new ChangedPath(status, path));
            totalPathCharacters += path.length();
        }
        if (changes.isEmpty()) return changes;
        if (changes.size() > 5_000 || totalPathCharacters > 200_000)
            throw new IllegalStateException("变更文件过多，改用完整刷新");

        Path archive = staging.resolve("changed.zip");
        Path next = staging.resolve("changed");
        Files.createDirectories(next);
        List<String> updatedPaths =
                changes.stream().filter(change -> change.status() != 'D').map(ChangedPath::path).toList();
        if (!updatedPaths.isEmpty()) {
            List<String> arguments =
                    new ArrayList<>(List.of("archive", "--format=zip", "--output=" + archive, commit, "--"));
            arguments.addAll(updatedPaths);
            run(source, arguments, staging.resolve("git.out"));
            extract(archive, next);
        }

        for (ChangedPath change : changes) {
            Path target = resolveContentPath(content, change.path());
            if (Files.exists(target)) target.toFile().setWritable(true, false);
            Files.deleteIfExists(target);
            if (change.status() != 'D') {
                Path replacement = resolveContentPath(next, change.path());
                if (!Files.isRegularFile(replacement))
                    throw new IOException("Git 增量导出缺少文件：" + change.path());
                Files.createDirectories(target.getParent());
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        cleanup(staging);
        return changes;
    }

    private void refreshWorkspace(
            Path source, String commit, Path workspace, Path content, Path staging)
            throws IOException {
        Path archive = staging.resolve("source.zip");
        Path next = staging.resolve("content");
        Files.createDirectories(next);
        run(
                source,
                List.of("archive", "--format=zip", "--output=" + archive, commit),
                staging.resolve("git.out"));
        extract(archive, next);

        Path previous = workspace.resolve(".previous-" + UUID.randomUUID());
        boolean movedPrevious = false;
        try {
            if (Files.isDirectory(content)) {
                Files.move(content, previous);
                movedPrevious = true;
                Path marker = previous.resolve(".codegraph");
                if (Files.isDirectory(marker)) {
                    Files.move(marker, next.resolve(".codegraph"));
                }
            }
            Files.move(next, content);
            if (movedPrevious) cleanup(previous);
            cleanup(staging);
        } catch (IOException failure) {
            if (!Files.exists(content) && movedPrevious) {
                Path marker = next.resolve(".codegraph");
                if (Files.isDirectory(marker)) Files.move(marker, previous.resolve(".codegraph"));
                Files.move(previous, content);
            }
            throw failure;
        }
    }

    private void extract(Path archive, Path destination) throws IOException {
        int count = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = validateArchivePath(entry.getName());
                if (name.equals(".codegraph") || name.startsWith(".codegraph/")) continue;
                Path path = resolveContentPath(destination, name);
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
    }

    private static String validateArchivePath(String name) {
        if (name == null
                || name.isBlank()
                || name.contains("\\")
                || name.contains(":")
                || name.startsWith("/")
                || Arrays.stream(name.split("/")).anyMatch(part -> part.equals("..") || part.equalsIgnoreCase(".git")))
            throw new IllegalArgumentException("Git 导出路径越界");
        return name;
    }

    private static Path resolveContentPath(Path root, String name) {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Git 导出路径越界");
        return path;
    }

    private static String readCommit(Path commitFile) throws IOException {
        if (!Files.isRegularFile(commitFile)) return null;
        String value = Files.readString(commitFile, StandardCharsets.US_ASCII).trim();
        return value.matches("[0-9a-fA-F]{40,64}") ? value : null;
    }

    private static void writeCommit(Path commitFile, String commit) throws IOException {
        Path temporary = commitFile.resolveSibling("current-commit.tmp");
        Files.writeString(
                temporary,
                commit + System.lineSeparator(),
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(
                    temporary,
                    commitFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, commitFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeChanges(
            Path manifest, String commit, List<ChangedPath> changes) throws IOException {
        StringBuilder content = new StringBuilder(commit).append('\n');
        if (changes == null) {
            content.append("FULL\n");
        } else {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            for (ChangedPath change : changes) {
                content.append(change.status())
                        .append('\t')
                        .append(
                                encoder.encodeToString(
                                        change.path().getBytes(StandardCharsets.UTF_8)))
                        .append('\n');
            }
        }
        Path temporary = manifest.resolveSibling("current-changes.tmp");
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(
                    temporary,
                    manifest,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void retainCommit(Path source, UUID branchId, String commit, Path workspace) {
        Path output = workspace.resolve("git-ref.out");
        try {
            run(
                    source,
                    List.of("update-ref", "refs/analyzer/workspaces/" + branchId, commit),
                    output);
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
            }
        }
    }

    private record ChangedPath(char status, String path) {}

    private String output(Path source, List<String> args) {
        return commandOutput(source, args, true);
    }

    private String rawOutput(Path source, List<String> args) {
        return commandOutput(source, args, false);
    }

    private String commandOutput(Path source, List<String> args, boolean trim) {
        Path temp = null;
        try {
            temp = Files.createTempFile("atlas-git-read-", ".txt");
            run(source, args, temp);
            if (Files.size(temp) > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Git 文件清单超过限制");
            String value = Files.readString(temp, StandardCharsets.UTF_8);
            return trim ? value.trim() : value;
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

    /** Only call for a prepared publication whose database transaction failed. */
    public void discardUnpublished(ManagedRepositoryContentVersion contentVersion) {
        cleanup(publicationPath(contentVersion));
    }

    private Path publicationPath(ManagedRepositoryContentVersion contentVersion) {
        Path content = contentVersion.contentPath().toAbsolutePath().normalize();
        Path repositoryRoot = root.resolve(contentVersion.repositoryId().value().toString()).normalize();
        Path publication = content.getParent();
        if (publication == null
                || !content.equals(publication.resolve("content"))
                || !publication.startsWith(repositoryRoot.resolve("branches"))
                || !publication.getFileName().toString()
                        .equals("publication-" + contentVersion.id().value()))
            throw new IllegalArgumentException("非法分支发布路径");
        return publication;
    }

    private void run(Path source, List<String> args, Path output) {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.hooksPath=" + GitRuntimePolicy.disabledHooksPath()));
        command.addAll(args);
        Process process = null;
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .directory(source.toFile())
                            .redirectOutput(output.toFile())
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            GitRuntimePolicy.sanitizeEnvironment(builder.environment());
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
