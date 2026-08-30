package com.analyzercoder.infrastructure.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 通过无 Shell 拼接的受限子进程执行只读 Git 命令。 */
@Component
public class ProcessGitClient {
    private static final long COMMAND_TIMEOUT_SECONDS = 30;
    private static final int STDERR_LIMIT_BYTES = 32 * 1024;
    private static final int DIGEST_METADATA_LIMIT_BYTES = 64 * 1024 * 1024;

    public CommandResult run(Path repositoryRoot, int stdoutLimitBytes, List<String> arguments) {
        return run(repositoryRoot, stdoutLimitBytes, new byte[0], arguments);
    }

    public CommandResult run(
            Path repositoryRoot, int stdoutLimitBytes, byte[] stdin, List<String> arguments) {
        if (stdoutLimitBytes < 1) {
            throw new IllegalArgumentException("Git 标准输出上限必须大于零");
        }
        Path root = repositoryRoot.toAbsolutePath().normalize();
        List<String> safeArguments = arguments == null ? List.of() : List.copyOf(arguments);
        safeArguments.forEach(ProcessGitClient::validateArgument);

        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.hooksPath=" + disabledHooksPath(),
                                "-c",
                                "protocol.ext.allow=never",
                                "-C",
                                root.toString()));
        command.addAll(safeArguments);

        java.lang.ProcessBuilder builder = new java.lang.ProcessBuilder(command);
        builder.environment().keySet().removeIf(ProcessGitClient::isGitEnvironmentVariable);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
        try {
            Process process = builder.start();
            CompletableFuture<OutputCapture> stdout =
                    CompletableFuture.supplyAsync(
                            () -> readBounded(process.getInputStream(), stdoutLimitBytes));
            CompletableFuture<OutputCapture> stderr =
                    CompletableFuture.supplyAsync(
                            () -> readBounded(process.getErrorStream(), STDERR_LIMIT_BYTES));
            try (OutputStream processInput = process.getOutputStream()) {
                if (stdin != null && stdin.length > 0) {
                    processInput.write(stdin);
                }
            }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new GitClientException("GIT_COMMAND_TIMEOUT", "Git 命令执行超过 30 秒");
            }
            OutputCapture stdoutCapture = stdout.join();
            OutputCapture stderrCapture = stderr.join();
            return new CommandResult(
                    process.exitValue(),
                    stdoutCapture.bytes(),
                    stderrCapture.bytes(),
                    stdoutCapture.truncated(),
                    stderrCapture.truncated());
        } catch (IOException exception) {
            throw new GitClientException("GIT_EXECUTABLE_UNAVAILABLE", "Git 命令行工具不可用", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitClientException("GIT_COMMAND_INTERRUPTED", "Git 命令执行被中断", exception);
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new GitClientException("GIT_OUTPUT_UNAVAILABLE", "无法读取 Git 命令输出", cause);
        }
    }

    /** 摘要覆盖 Git 状态、Raw Diff、受版本控制文件和未跟踪文件的实际内容。 */
    public String worktreeDigest(Path repositoryRoot) {
        CommandResult paths =
                requireSuccess(
                        run(
                                repositoryRoot,
                                DIGEST_METADATA_LIMIT_BYTES,
                                List.of("ls-files", "-co", "--exclude-standard", "-z")),
                        "WORKTREE_DIGEST_FAILED");
        CommandResult status =
                requireSuccess(
                        run(
                                repositoryRoot,
                                DIGEST_METADATA_LIMIT_BYTES,
                                List.of("status", "--porcelain=v1", "-z", "--untracked-files=all")),
                        "WORKTREE_DIGEST_FAILED");
        CommandResult rawDiff =
                requireSuccess(
                        run(
                                repositoryRoot,
                                DIGEST_METADATA_LIMIT_BYTES,
                                List.of(
                                        "diff",
                                        "--raw",
                                        "-z",
                                        "--no-ext-diff",
                                        "--no-textconv",
                                        "HEAD",
                                        "--")),
                        "WORKTREE_DIGEST_FAILED");
        if (paths.stdoutTruncated() || status.stdoutTruncated() || rawDiff.stdoutTruncated()) {
            throw new GitClientException("WORKTREE_DIGEST_LIMIT_EXCEEDED", "Git 工作区元数据超过安全读取上限");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateBlock(digest, status.stdout());
            updateBlock(digest, rawDiff.stdout());
            Path root = repositoryRoot.toAbsolutePath().normalize();
            List<String> relativePaths = splitNullPaths(paths.stdout()).stream().sorted().toList();
            byte[] buffer = new byte[8192];
            for (String value : relativePaths) {
                String repositoryPath = safeRepositoryPath(value);
                Path file = root.resolve(Path.of(repositoryPath)).normalize();
                if (!file.startsWith(root)) {
                    throw new GitClientException("INVALID_GIT_PATH", "Git 返回了仓库范围之外的文件路径");
                }
                updateBlock(digest, repositoryPath.getBytes(StandardCharsets.UTF_8));
                if (Files.isSymbolicLink(file)) {
                    digest.update((byte) 'L');
                    updateBlock(
                            digest,
                            Files.readSymbolicLink(file)
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8));
                } else if (Files.isRegularFile(file)) {
                    digest.update((byte) 'F');
                    digest.update((byte) (Files.isExecutable(file) ? 1 : 0));
                    try (InputStream input = Files.newInputStream(file)) {
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                } else if (Files.isDirectory(file)) {
                    digest.update((byte) 'D');
                } else {
                    digest.update((byte) 'M');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new GitClientException("WORKTREE_DIGEST_FAILED", "无法计算 Git 工作区内容摘要", exception);
        }
    }

    private static CommandResult requireSuccess(CommandResult result, String errorCode) {
        if (result.exitCode() != 0) {
            throw new GitClientException(errorCode, "Git 工作区摘要命令执行失败");
        }
        return result;
    }

    private static void updateBlock(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static List<String> splitNullPaths(byte[] bytes) {
        List<String> paths = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        for (byte value : bytes) {
            if (value == 0) {
                if (current.size() > 0) {
                    paths.add(current.toString(StandardCharsets.UTF_8));
                    current.reset();
                }
            } else {
                current.write(value);
            }
        }
        if (current.size() > 0) {
            paths.add(current.toString(StandardCharsets.UTF_8));
        }
        return paths;
    }

    private static String safeRepositoryPath(String requestedPath) {
        String path = requestedPath.replace('\\', '/');
        if (path.isBlank()
                || path.startsWith("/")
                || path.matches("^[A-Za-z]:.*")
                || path.chars().anyMatch(ProcessGitClient::isControlCharacter)) {
            throw new GitClientException("INVALID_GIT_PATH", "Git 返回了非法仓库路径");
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new GitClientException("INVALID_GIT_PATH", "Git 返回的路径超出仓库范围");
            }
        }
        try {
            if (Path.of(path).isAbsolute()) {
                throw new GitClientException("INVALID_GIT_PATH", "Git 返回了绝对路径");
            }
        } catch (InvalidPathException exception) {
            throw new GitClientException("INVALID_GIT_PATH", "Git 返回了无效路径", exception);
        }
        return path;
    }

    private static void validateArgument(String argument) {
        if (argument == null || argument.chars().anyMatch(ProcessGitClient::isControlCharacter)) {
            throw new IllegalArgumentException("Git 命令参数包含控制字符");
        }
    }

    private static boolean isControlCharacter(int value) {
        return value < 32 || value == 127;
    }

    private static boolean isGitEnvironmentVariable(String name) {
        return name.toUpperCase(Locale.ROOT).startsWith("GIT_");
    }

    private static String disabledHooksPath() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "NUL"
                : "/dev/null";
    }

    private static OutputCapture readBounded(InputStream input, int maximumBytes) {
        try (input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = maximumBytes - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
            return new OutputCapture(output.toByteArray(), truncated);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    public record CommandResult(
            int exitCode,
            byte[] stdout,
            byte[] stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
        public CommandResult {
            stdout = stdout == null ? new byte[0] : stdout.clone();
            stderr = stderr == null ? new byte[0] : stderr.clone();
        }
    }

    private record OutputCapture(byte[] bytes, boolean truncated) {}

    public static class GitClientException extends RuntimeException {
        private final String code;

        public GitClientException(String code, String message) {
            super(message);
            this.code = code;
        }

        public GitClientException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
