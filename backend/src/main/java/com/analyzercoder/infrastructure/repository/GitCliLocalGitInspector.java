package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.GitRepositorySnapshot;
import com.analyzercoder.domain.repository.LocalGitInspector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GitCliLocalGitInspector implements LocalGitInspector {

    private static final long COMMAND_TIMEOUT_SECONDS = 15;

    @Override
    public GitRepositorySnapshot inspect(Path repositoryRoot) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        String topLevel = text(run(root, false, "rev-parse", "--show-toplevel")).trim();
        if (!samePath(root, Path.of(topLevel))) {
            throw new IllegalArgumentException("Repository path must point to the Git worktree root");
        }

        String commit = text(run(root, false, "rev-parse", "--verify", "HEAD")).trim();
        CommandResult branchResult = run(root, true, "symbolic-ref", "--short", "-q", "HEAD");
        String branch = branchResult.exitCode() == 0 ? text(branchResult).trim() : null;
        boolean dirty = run(root, false, "status", "--porcelain=v1", "-z", "--untracked-files=all").stdout().length > 0;
        String digest = digestWorktree(root, run(root, false, "ls-files", "-co", "--exclude-standard", "-z").stdout());
        return new GitRepositorySnapshot(branch, commit, digest, dirty, Instant.now());
    }

    private static String digestWorktree(Path root, byte[] nulSeparatedPaths) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> paths = splitPaths(nulSeparatedPaths).stream().sorted().toList();
            byte[] buffer = new byte[8192];
            for (String relativeName : paths) {
                Path relative = Path.of(relativeName).normalize();
                Path file = root.resolve(relative).normalize();
                if (relative.isAbsolute() || !file.startsWith(root)) {
                    throw new IllegalArgumentException("Git returned a path outside the repository");
                }
                byte[] name = relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
                digest.update(name);
                if (Files.isSymbolicLink(file)) {
                    digest.update((byte) 'L');
                    digest.update(Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8));
                } else if (Files.isRegularFile(file)) {
                    digest.update((byte) 'F');
                    try (InputStream input = Files.newInputStream(file)) {
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to calculate Git worktree digest", exception);
        }
    }

    private static List<String> splitPaths(byte[] value) {
        List<String> paths = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        for (byte item : value) {
            if (item == 0) {
                if (current.size() > 0) {
                    paths.add(current.toString(StandardCharsets.UTF_8));
                    current.reset();
                }
            } else {
                current.write(item);
            }
        }
        return paths;
    }

    private static CommandResult run(Path root, boolean allowExitOne, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
            "git", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=NUL",
            "-c", "protocol.ext.allow=never", "-C", root.toString()
        ));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
        try {
            Process process = builder.start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git metadata command timed out");
            }
            CommandResult result = new CommandResult(process.exitValue(), stdout.join(), stderr.join());
            if (result.exitCode() != 0 && !(allowExitOne && result.exitCode() == 1)) {
                throw new IllegalArgumentException("Path is not a readable Git repository");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Git CLI is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git metadata command was interrupted", exception);
        }
    }

    private static byte[] read(InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Git command output", exception);
        }
    }

    private static String text(CommandResult result) {
        return new String(result.stdout(), StandardCharsets.UTF_8);
    }

    private static boolean samePath(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (IOException exception) {
            return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
        }
    }

    private record CommandResult(int exitCode, byte[] stdout, byte[] stderr) {
    }
}
