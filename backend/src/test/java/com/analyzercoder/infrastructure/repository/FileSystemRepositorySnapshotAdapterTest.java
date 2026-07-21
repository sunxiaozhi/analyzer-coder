package com.analyzercoder.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemRepositorySnapshotAdapterTest {
    @TempDir Path temp;

    @Test
    void publishedSnapshotDoesNotChangeWithSourceWorktree() throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectories(source);
        git(source, "init");
        git(source, "config", "user.email", "test@example.com");
        git(source, "config", "user.name", "Test User");
        Files.writeString(source.resolve("sample.txt"), "snapshot-value");
        git(source, "add", "sample.txt");
        git(source, "commit", "-m", "initial");

        var version = new GitCliLocalGitInspector().inspect(source);
        var adapter = new FileSystemRepositorySnapshotAdapter(temp.resolve("managed").toString(), 100, 1024 * 1024);
        var snapshot = adapter.create(CodeRepositoryId.newId(), source, version);

        Files.writeString(source.resolve("sample.txt"), "changed-after-publication");
        assertThat(Files.readString(snapshot.contentPath().resolve("sample.txt"))).isEqualTo("snapshot-value");
        assertThat(snapshot.contentPath()).isNotEqualTo(source);
        adapter.delete(snapshot);
        assertThat(snapshot.contentPath()).doesNotExist();
    }

    private static void git(Path root, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        assertThat(exit).withFailMessage(new String(output)).isZero();
    }
}
