package com.analyzercoder.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCliLocalGitInspectorTest {

    @TempDir Path repository;

    @Test
    void capturesCommitAndChangesDigestForTrackedAndUntrackedContent()
            throws IOException, InterruptedException {
        run("init");
        run("config", "user.email", "test@example.com");
        run("config", "user.name", "Test User");
        Files.writeString(repository.resolve("tracked.txt"), "first");
        run("add", "tracked.txt");
        run("commit", "-m", "initial");

        GitCliLocalGitInspector inspector = new GitCliLocalGitInspector();
        var clean = inspector.inspect(repository);
        assertThat(clean.commit()).hasSize(40);
        assertThat(clean.worktreeDigest()).hasSize(64);
        assertThat(clean.dirty()).isFalse();

        Files.writeString(repository.resolve("tracked.txt"), "second");
        Files.writeString(repository.resolve("untracked.txt"), "new");
        var dirty = inspector.inspect(repository);
        assertThat(dirty.dirty()).isTrue();
        assertThat(dirty.worktreeDigest()).isNotEqualTo(clean.worktreeDigest());
    }

    @Test
    void explainsThatRepositoryWithoutCommitsHasNoReadableHead()
            throws IOException, InterruptedException {
        run("init");

        assertThatThrownBy(() -> new GitCliLocalGitInspector().inspect(repository))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Git 仓库没有可读取的 HEAD 提交，请先推送至少一个提交，或选择实际存在的分支");
    }

    private void run(String... arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        assertThat(exit).withFailMessage(new String(output)).isZero();
    }
}
