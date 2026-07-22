package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.GitRepositorySnapshot;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySnapshotPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileSystemRepositorySnapshotAdapter implements RepositorySnapshotPort {

    private final Path snapshotRoot;
    private final int maxFiles;
    private final long maxTotalBytes;

    public FileSystemRepositorySnapshotAdapter(
        @Value("${app.repository.snapshot-root:${java.io.tmpdir}/analyzer-coder/snapshots}") String snapshotRoot,
        @Value("${app.repository.snapshot-max-files:20000}") int maxFiles,
        @Value("${app.repository.snapshot-max-total-bytes:2147483648}") long maxTotalBytes
    ) {
        this.snapshotRoot = Path.of(snapshotRoot).toAbsolutePath().normalize();
        this.maxFiles = maxFiles;
        this.maxTotalBytes = maxTotalBytes;
        try {
            Files.createDirectories(this.snapshotRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create managed snapshot root", exception);
        }
    }

    @Override
    public ManagedRepositorySnapshot create(
        CodeRepositoryId repositoryId,
        Path sourceRoot,
        GitRepositorySnapshot sourceVersion
    ) {
        RepositorySnapshotId snapshotId = RepositorySnapshotId.newId();
        Path repositoryRoot = snapshotRoot.resolve(repositoryId.value().toString()).resolve("snapshots");
        Path temporary = repositoryRoot.resolve(".tmp-" + snapshotId.value());
        Path target = repositoryRoot.resolve(snapshotId.value().toString());
        Path content = temporary.resolve("content");
        try {
            Files.createDirectories(content);
            copyVersionedFiles(sourceRoot, content, sourceVersion.worktreeDigest());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("Managed snapshot storage does not support atomic publication", exception);
            }
            makeReadOnly(target);
            return new ManagedRepositorySnapshot(
                snapshotId,
                repositoryId,
                target.resolve("content"),
                sourceVersion.commit(),
                sourceVersion.worktreeDigest(),
                Instant.now()
            );
        } catch (RuntimeException | IOException exception) {
            deleteTree(temporary);
            throw exception instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Unable to create managed repository snapshot", exception);
        }
    }

    @Override
    public void delete(ManagedRepositorySnapshot snapshot) {
        deleteTree(snapshot.contentPath().getParent());
    }

    @Override
    public void deleteRepository(CodeRepositoryId repositoryId) {
        deleteTree(snapshotRoot.resolve(repositoryId.value().toString()));
    }

    private void copyVersionedFiles(Path sourceRoot, Path content, String expectedDigest) throws IOException {
        List<String> paths = gitPaths(sourceRoot).stream().sorted().toList();
        if (paths.size() > maxFiles) {
            throw new IllegalArgumentException("Repository exceeds configured snapshot file limit");
        }
        MessageDigest digest = sha256();
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        for (String relativeName : paths) {
            Path relative = Path.of(relativeName).normalize();
            Path source = sourceRoot.resolve(relative).normalize();
            if (relative.isAbsolute() || !source.startsWith(sourceRoot)) {
                throw new IllegalArgumentException("Git returned a path outside the repository");
            }
            if (Files.isSymbolicLink(source)) {
                throw new IllegalArgumentException("Repository snapshots do not allow symbolic links");
            }
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Repository contains a non-regular versioned file");
            }
            long fileSize = Files.size(source);
            totalBytes = Math.addExact(totalBytes, fileSize);
            if (totalBytes > maxTotalBytes) {
                throw new IllegalArgumentException("Repository exceeds configured snapshot size limit");
            }

            byte[] name = relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
            digest.update(name);
            digest.update((byte) 'F');

            Path destination = content.resolve(relative).normalize();
            if (!destination.startsWith(content)) {
                throw new IllegalArgumentException("Snapshot destination escaped managed storage");
            }
            Files.createDirectories(destination.getParent());
            try (InputStream input = Files.newInputStream(source);
                 OutputStream output = Files.newOutputStream(destination)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        }
        String actualDigest = HexFormat.of().formatHex(digest.digest());
        if (!actualDigest.equals(expectedDigest)) {
            throw new IllegalStateException("Source changed while the managed snapshot was being created; retry");
        }
    }

    private static List<String> gitPaths(Path root) {
        List<String> command = new ArrayList<>(List.of(
            "git", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=NUL",
            "-c", "protocol.ext.allow=never", "-C", root.toString(),
            "ls-files", "-co", "--exclude-standard", "-z"
        ));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        try {
            Process process = builder.start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<byte[]> error = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git snapshot file enumeration timed out");
            }
            error.join();
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("Unable to enumerate Git snapshot files");
            }
            return splitPaths(output.join());
        } catch (IOException exception) {
            throw new IllegalStateException("Git CLI is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git snapshot enumeration was interrupted", exception);
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

    private static byte[] read(InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Git command output", exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().setWritable(false, false));
        }
    }

    private void deleteTree(Path target) {
        if (target == null) return;
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(snapshotRoot) || normalized.equals(snapshotRoot) || !Files.exists(normalized)) return;
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    path.toFile().setWritable(true, false);
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to delete managed snapshot", exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to delete managed snapshot", exception);
        }
    }
}
