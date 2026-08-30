package com.analyzercoder.application.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.infrastructure.git.ProcessGitClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryChangeServiceTest {
    private static final String INITIAL_MESSAGE = "initial";

    @TempDir Path workspace;

    private Path repository;
    private RepositoryChangeService service;
    private String initialCommit;

    @BeforeEach
    void initializeRepository() throws Exception {
        repository = Files.createDirectory(workspace.resolve("repository"));
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");
        write("modified.txt", "before\n");
        write("deleted.txt", "delete me\n");
        write("rename-old.txt", "rename me\n");
        write("copy-source.txt", "copy me\n");
        Files.write(repository.resolve("binary.dat"), new byte[] {0, 1, 2, 3});
        git("add", "-A");
        git("commit", "-m", INITIAL_MESSAGE);
        initialCommit = git("rev-parse", "HEAD").trim();
        service = new RepositoryChangeService(new ProcessGitClient());
    }

    @Test
    void worktreeAnalysisUsesGitFactsAndIncludesUntrackedAndBinaryFiles() throws Exception {
        write("modified.txt", "before\nafter\n");
        Files.delete(repository.resolve("deleted.txt"));
        git("mv", "rename-old.txt", "rename-new.txt");
        Files.write(repository.resolve("binary.dat"), new byte[] {0, 9, 2, 3});
        write("untracked.txt", "alpha\nbeta");

        RepositoryChange result = service.analyze(GitChangeRequest.worktree(repository));

        assertThat(result.source()).isEqualTo(GitChangeRequest.Source.WORKTREE);
        assertThat(result.baseCommit()).isEqualTo(initialCommit);
        assertThat(result.headCommit()).isNull();
        assertThat(result.worktreeDigest()).hasSize(64);
        assertThat(result.partial()).isFalse();
        assertThat(result.limitations()).isEmpty();
        assertThat(result.changes())
                .extracting(
                        change -> change.newPath() == null ? change.oldPath() : change.newPath())
                .containsExactlyInAnyOrder(
                        "binary.dat",
                        "deleted.txt",
                        "modified.txt",
                        "rename-new.txt",
                        "untracked.txt");
        assertThat(result.changes())
                .extracting(RepositoryChange.FileChange::type)
                .contains(
                        RepositoryChange.ChangeType.MODIFIED,
                        RepositoryChange.ChangeType.DELETED,
                        RepositoryChange.ChangeType.RENAMED,
                        RepositoryChange.ChangeType.ADDED);

        RepositoryChange.FileChange rename = change(result, "rename-new.txt");
        assertThat(rename.type()).isEqualTo(RepositoryChange.ChangeType.RENAMED);
        assertThat(rename.oldPath()).isEqualTo("rename-old.txt");
        assertThat(rename.newPath()).isEqualTo("rename-new.txt");

        RepositoryChange.FileChange binary = change(result, "binary.dat");
        assertThat(binary.binary()).isTrue();
        assertThat(binary.additions()).isNull();
        assertThat(binary.deletions()).isNull();

        RepositoryChange.FileChange untracked = change(result, "untracked.txt");
        assertThat(untracked.type()).isEqualTo(RepositoryChange.ChangeType.ADDED);
        assertThat(untracked.binary()).isFalse();
        assertThat(untracked.additions()).isEqualTo(2L);
        assertThat(untracked.deletions()).isZero();
        assertThat(untracked.hunks()).containsExactly(new RepositoryChange.Hunk(0, 0, 1, 2));
        assertThat(change(result, "modified.txt").hunks())
                .containsExactly(new RepositoryChange.Hunk(1, 0, 2, 1));
    }

    @Test
    void singleCommitAndRangeHandleRootCommitAndCopies() throws Exception {
        RepositoryChange rootCommit =
                service.analyze(GitChangeRequest.singleCommit(repository, initialCommit));

        assertThat(rootCommit.baseCommit()).isNull();
        assertThat(rootCommit.headCommit()).isEqualTo(initialCommit);
        assertThat(rootCommit.changes())
                .allSatisfy(
                        change ->
                                assertThat(change.type())
                                        .isEqualTo(RepositoryChange.ChangeType.ADDED));

        Files.copy(
                repository.resolve("copy-source.txt"),
                repository.resolve("copy-target.txt"),
                StandardCopyOption.COPY_ATTRIBUTES);
        git("add", "-A");
        git("commit", "-m", "copy file");
        String copyCommit = git("rev-parse", "HEAD").trim();

        RepositoryChange single =
                service.analyze(GitChangeRequest.singleCommit(repository, copyCommit));
        RepositoryChange range =
                service.analyze(
                        GitChangeRequest.commitRange(repository, initialCommit, copyCommit));

        assertThat(change(single, "copy-target.txt").type())
                .isEqualTo(RepositoryChange.ChangeType.COPIED);
        assertThat(change(single, "copy-target.txt").oldPath()).isEqualTo("copy-source.txt");
        assertThat(change(range, "copy-target.txt").type())
                .isEqualTo(RepositoryChange.ChangeType.COPIED);
        assertThat(range.baseCommit()).isEqualTo(initialCommit);
        assertThat(range.headCommit()).isEqualTo(copyCommit);
        assertThat(range.worktreeDigest()).isNull();
    }

    @Test
    void rejectsUnsafeRefsSubdirectoriesAndNonGitDirectories() throws Exception {
        assertThatThrownBy(() -> GitChangeRequest.singleCommit(repository, "--help"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不安全");
        assertThatThrownBy(() -> GitChangeRequest.singleCommit(repository, "HEAD\n--help"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不安全");
        assertThatThrownBy(() -> GitChangeRequest.singleCommit(repository, "a".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");

        Path child = Files.createDirectory(repository.resolve("child"));
        assertThatThrownBy(() -> service.analyze(GitChangeRequest.worktree(child)))
                .isInstanceOfSatisfying(
                        RepositoryChangeException.class,
                        exception ->
                                assertThat(exception.code()).isEqualTo("REPOSITORY_ROOT_REQUIRED"));

        Path plainDirectory = Files.createDirectory(workspace.resolve("plain"));
        assertThatThrownBy(() -> service.analyze(GitChangeRequest.worktree(plainDirectory)))
                .isInstanceOfSatisfying(
                        RepositoryChangeException.class,
                        exception -> assertThat(exception.code()).isEqualTo("NOT_GIT_REPOSITORY"));
    }

    @Test
    void rejectsMixedWorktreeResultWhenDigestChangesDuringAnalysis() {
        ProcessGitClient unstable =
                new ProcessGitClient() {
                    private int calls;

                    @Override
                    public String worktreeDigest(Path repositoryRoot) {
                        return calls++ == 0 ? "before" : "after";
                    }
                };
        RepositoryChangeService unstableService = new RepositoryChangeService(unstable);

        assertThatThrownBy(() -> unstableService.analyze(GitChangeRequest.worktree(repository)))
                .isInstanceOfSatisfying(
                        RepositoryChangeException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo("WORKTREE_CHANGED_DURING_ANALYSIS"));
    }

    @Test
    void marksTruncatedPatchAsPartialInsteadOfSilentlyPublishingIt() {
        String objectId = "a".repeat(40);
        ProcessGitClient truncatedPatchClient =
                new ProcessGitClient() {
                    @Override
                    public CommandResult run(
                            Path repositoryRoot, int stdoutLimitBytes, List<String> arguments) {
                        if (arguments.contains("--show-toplevel")) {
                            return success(repository.toString());
                        }
                        if (arguments.contains("rev-parse")) {
                            return success(objectId);
                        }
                        if (arguments.contains("--name-status")) {
                            return successBytes("M\0src/App.java\0");
                        }
                        if (arguments.contains("--numstat")) {
                            return successBytes("1\t1\tsrc/App.java\0");
                        }
                        if (arguments.contains("--unified=0")) {
                            byte[] patch =
                                    ("diff --git a/src/App.java b/src/App.java\n" + "@@ -1 +1 @@\n")
                                            .getBytes(StandardCharsets.UTF_8);
                            return new CommandResult(0, patch, new byte[0], true, false);
                        }
                        throw new AssertionError("Unexpected Git command: " + arguments);
                    }
                };
        RepositoryChangeService partialService = new RepositoryChangeService(truncatedPatchClient);

        RepositoryChange result =
                partialService.analyze(
                        GitChangeRequest.commitRange(repository, objectId, objectId));

        assertThat(result.partial()).isTrue();
        assertThat(result.limitations())
                .extracting(RepositoryChange.Limitation::code)
                .containsExactly("PATCH_SIZE_LIMIT_EXCEEDED");
        assertThat(result.changes()).hasSize(1);
    }

    @Test
    void parsersPreserveRenameStatsAndZeroLengthHunks() {
        RepositoryChangeService.ParsedStats stats =
                RepositoryChangeService.parseNumstat(
                        "3\t2\t\0old name.java\0new name.java\0".getBytes(StandardCharsets.UTF_8));
        List<List<RepositoryChange.Hunk>> hunks =
                RepositoryChangeService.parseHunks(
                        ("diff --git a/old b/new\n" + "@@ -0,0 +1,3 @@\n" + "@@ -9,2 +12 @@\n")
                                .getBytes(StandardCharsets.UTF_8),
                        1);

        assertThat(stats.incomplete()).isFalse();
        assertThat(stats.stats()).hasSize(1);
        assertThat(stats.stats().get(0).oldPath()).isEqualTo("old name.java");
        assertThat(stats.stats().get(0).newPath()).isEqualTo("new name.java");
        assertThat(hunks.get(0))
                .containsExactly(
                        new RepositoryChange.Hunk(0, 0, 1, 3),
                        new RepositoryChange.Hunk(9, 2, 12, 1));
    }

    @Test
    void parserRejectsEscapingPathsAndMarksTheFileLimit() {
        assertThatThrownBy(
                        () ->
                                RepositoryChangeService.parseNameStatus(
                                        "M\0../outside.java\0".getBytes(StandardCharsets.UTF_8),
                                        RepositoryChangeService.MAX_FILES))
                .isInstanceOfSatisfying(
                        RepositoryChangeException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_GIT_PATH"));

        StringBuilder output = new StringBuilder();
        for (int index = 0; index <= RepositoryChangeService.MAX_FILES; index++) {
            output.append("M\0src/File-").append(index).append(".java\0");
        }
        RepositoryChangeService.ParsedChanges result =
                RepositoryChangeService.parseNameStatus(
                        output.toString().getBytes(StandardCharsets.UTF_8),
                        RepositoryChangeService.MAX_FILES);

        assertThat(result.changes()).hasSize(RepositoryChangeService.MAX_FILES);
        assertThat(result.limitExceeded()).isTrue();
        assertThat(result.incomplete()).isFalse();
    }

    private RepositoryChange.FileChange change(RepositoryChange result, String path) {
        return result.changes().stream()
                .filter(change -> path.equals(change.newPath()) || path.equals(change.oldPath()))
                .findFirst()
                .orElseThrow();
    }

    private void write(String relativePath, String content) throws IOException {
        Files.writeString(repository.resolve(relativePath), content, StandardCharsets.UTF_8);
    }

    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(new String(output, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    private static ProcessGitClient.CommandResult success(String output) {
        return successBytes(output + "\n");
    }

    private static ProcessGitClient.CommandResult successBytes(String output) {
        return new ProcessGitClient.CommandResult(
                0, output.getBytes(StandardCharsets.UTF_8), new byte[0], false, false);
    }
}
