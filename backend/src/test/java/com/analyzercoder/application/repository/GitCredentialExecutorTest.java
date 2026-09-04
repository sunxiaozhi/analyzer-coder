package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCredentialExecutorTest {
    @TempDir Path workspace;

    @Test
    void fetchesAnIsolatedReviewRefWithoutChangingTheCheckedOutBranch() throws IOException {
        Path origin = workspace.resolve("origin.git");
        Path seed = workspace.resolve("seed");
        Path checkout = workspace.resolve("checkout");
        Files.createDirectories(seed);
        git(workspace, "init", "--bare", origin.toString());
        git(seed, "init");
        git(seed, "config", "user.name", "Analyzer Test");
        git(seed, "config", "user.email", "analyzer@example.com");
        Files.writeString(seed.resolve("app.txt"), "baseline\n", StandardCharsets.UTF_8);
        git(seed, "add", "app.txt");
        git(seed, "commit", "-m", "baseline");
        git(seed, "branch", "-M", "main");
        git(seed, "remote", "add", "origin", origin.toString());
        git(seed, "push", "origin", "main");
        String baseline = git(seed, "rev-parse", "HEAD").trim();

        Files.writeString(seed.resolve("app.txt"), "review head\n", StandardCharsets.UTF_8);
        git(seed, "commit", "-am", "review head");
        String reviewHead = git(seed, "rev-parse", "HEAD").trim();
        git(seed, "push", "origin", "HEAD:refs/pull/7/head");
        git(workspace, "clone", "--branch", "main", origin.toString(), checkout.toString());

        String fetched = new GitCredentialExecutor().fetchReviewHead(checkout, "GITHUB", 7, null);

        assertThat(fetched).isEqualTo(reviewHead);
        assertThat(git(checkout, "rev-parse", "HEAD").trim()).isEqualTo(baseline);
        assertThat(
                        Files.readString(checkout.resolve("app.txt"), StandardCharsets.UTF_8)
                                .replace("\r\n", "\n"))
                .isEqualTo("baseline\n");
        assertThat(git(checkout, "show", fetched + ":app.txt").replace("\r\n", "\n"))
                .isEqualTo("review head\n");
    }

    private static String git(Path directory, String... arguments) throws IOException {
        ArrayList<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new AssertionError("Git test setup failed: " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Git test setup interrupted", exception);
        }
        return output;
    }
}
