package com.analyzercoder.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GitBranchSnapshotFactoryTest {
    @TempDir Path temp;

    @AfterEach
    void restorePermissionsForTempCleanup() throws Exception {
        try (var paths = Files.walk(temp)) {
            paths.forEach(path -> path.toFile().setWritable(true, false));
        }
    }

    @Test
    void exportsDifferentBranchesWithoutTouchingDirtyWorktree() throws Exception {
        Path source = repository();
        Files.writeString(source.resolve("README.md"), "main version\n");
        git(source, "add", "README.md");
        git(source, "commit", "-m", "main");
        git(source, "branch", "release/1.0");
        Files.writeString(source.resolve("README.md"), "new main version\n");
        git(source, "add", "README.md");
        git(source, "commit", "-m", "update");
        Files.writeString(source.resolve("README.md"), "uncommitted user work\n");
        Files.writeString(source.resolve("untracked.txt"), "keep me");
        String before = git(source, "status", "--porcelain");
        var factory =
                new GitBranchSnapshotFactory(temp.resolve("snapshots").toString(), 100, 10000);
        var id = CodeRepositoryId.of(UUID.randomUUID());
        var main = factory.create(id, source, factory.resolve(source, "main"));
        var release = factory.create(id, source, factory.resolve(source, "release/1.0"));
        assertThat(main.id()).isNotEqualTo(release.id());
        assertThat(Files.readString(main.contentPath().resolve("README.md")))
                .isEqualTo("new main version\n");
        assertThat(Files.readString(release.contentPath().resolve("README.md")))
                .isEqualTo("main version\n");
        assertThat(Files.readString(source.resolve("README.md")))
                .isEqualTo("uncommitted user work\n");
        assertThat(git(source, "status", "--porcelain")).isEqualTo(before);
        assertThat(git(source, "symbolic-ref", "--short", "HEAD")).isEqualTo("main");
        assertThat(main.contentPath().resolve("untracked.txt")).doesNotExist();
        Files.createDirectory(main.contentPath().resolve(".codegraph"));
        assertThatThrownBy(() -> factory.resolve(source, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedExportAndRemovesUnpublishedFiles() throws Exception {
        Path source = repository();
        Files.writeString(source.resolve("large.txt"), "0123456789".repeat(100));
        git(source, "add", "large.txt");
        git(source, "commit", "-m", "large");
        Path root = temp.resolve("snapshots");
        var factory = new GitBranchSnapshotFactory(root.toString(), 10, 20);
        assertThatThrownBy(
                        () ->
                                factory.create(
                                        CodeRepositoryId.of(UUID.randomUUID()),
                                        source,
                                        factory.resolve(source, "main")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("大小限制");
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void reusesLatestBranchWorkspaceAndUpdatesOnlyChangedFiles() throws Exception {
        Path source = repository();
        Files.writeString(source.resolve("keep.txt"), "keep\n");
        Files.writeString(source.resolve("change.txt"), "before\n");
        Files.writeString(source.resolve("delete.txt"), "delete\n");
        git(source, "add", "keep.txt", "change.txt", "delete.txt");
        git(source, "commit", "-m", "first");

        Path root = temp.resolve("latest-workspaces");
        var factory = new GitBranchSnapshotFactory(root.toString(), 100, 10000);
        var repositoryId = CodeRepositoryId.of(UUID.randomUUID());
        UUID branchId = UUID.randomUUID();
        String firstCommit = git(source, "rev-parse", "HEAD");
        var first = factory.createLatest(repositoryId, branchId, source, firstCommit);
        Path marker = first.contentPath().resolve(".codegraph");
        Files.createDirectories(marker);
        Files.writeString(marker.resolve("state"), "graph-state");
        FileTime unchangedTime = FileTime.fromMillis(946684800000L);
        Files.setLastModifiedTime(first.contentPath().resolve("keep.txt"), unchangedTime);

        Files.writeString(source.resolve("change.txt"), "after\n");
        Files.delete(source.resolve("delete.txt"));
        Files.writeString(source.resolve("new.txt"), "new\n");
        git(source, "add", "-A");
        git(source, "commit", "-m", "second");
        String secondCommit = git(source, "rev-parse", "HEAD");
        var second = factory.createLatest(repositoryId, branchId, source, secondCommit);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.contentPath()).isEqualTo(first.contentPath());
        assertThat(second.contentPath().resolve("keep.txt")).hasContent("keep\n");
        assertThat(Files.getLastModifiedTime(second.contentPath().resolve("keep.txt")))
                .isEqualTo(unchangedTime);
        assertThat(second.contentPath().resolve("change.txt")).hasContent("after\n");
        assertThat(second.contentPath().resolve("new.txt")).hasContent("new\n");
        assertThat(second.contentPath().resolve("delete.txt")).doesNotExist();
        assertThat(second.contentPath().resolve(".codegraph/state"))
                .hasContent("graph-state");
        assertThat(second.contentPath().getParent().resolve("current-commit"))
                .hasContent(secondCommit + System.lineSeparator());
        assertThat(git(source, "rev-parse", "refs/analyzer/workspaces/" + branchId))
                .isEqualTo(secondCommit);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "--upload-pack=x",
                "../main",
                "a..b",
                "a@{1}",
                "a.lock",
                "a/.hidden",
                "a:b",
                "a b",
                "a\\b",
                "a//b",
                "/main",
                "a?b"
            })
    void rejectsUnsafeBranchNames(String branch) {
        assertThatThrownBy(() -> GitBranchSnapshotFactory.validateBranch(branch))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path repository() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        git(source, "init", "-b", "main");
        git(source, "config", "user.email", "test@example.invalid");
        git(source, "config", "user.name", "Branch test");
        git(source, "config", "commit.gpgsign", "false");
        git(source, "config", "core.autocrlf", "false");
        return source;
    }

    private String git(Path source, String... arguments) throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "core.hooksPath=" + GitRuntimePolicy.disabledHooksPath(),
                                "-C",
                                source.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String output =
                new String(
                                process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .trim();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
