package com.analyzercoder.application.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GitDiffServiceTest {
    @Test
    void parsesAddModifyDeleteRenameAndCopyWithoutLosingOldPath() {
        byte[] output =
                ("A\0new.java\0"
                                + "M\0changed.java\0"
                                + "D\0gone.java\0"
                                + "R100\0old.java\0renamed.java\0"
                                + "C100\0source.java\0copy.java\0")
                        .getBytes(StandardCharsets.UTF_8);

        GitDiffService.DiffResult result = GitDiffService.parseNameStatus(output);

        assertThat(result.changes())
                .extracting(GitDiffService.FileChange::type)
                .containsExactly(
                        GitDiffService.ChangeType.ADDED,
                        GitDiffService.ChangeType.MODIFIED,
                        GitDiffService.ChangeType.DELETED,
                        GitDiffService.ChangeType.RENAMED,
                        GitDiffService.ChangeType.COPIED);
        assertThat(result.affectedPaths())
                .isEqualTo(
                        Set.of(
                                "new.java",
                                "changed.java",
                                "gone.java",
                                "old.java",
                                "renamed.java",
                                "copy.java"));
        assertThat(result.indexPaths())
                .isEqualTo(Set.of("new.java", "changed.java", "renamed.java", "copy.java"));
    }

    @Test
    void rejectsPathsEscapingRepository() {
        byte[] output = "M\0../outside.java\0".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> GitDiffService.parseNameStatus(output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("越界");
    }
}
