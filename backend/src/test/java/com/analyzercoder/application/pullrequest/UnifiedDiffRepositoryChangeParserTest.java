package com.analyzercoder.application.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.application.change.RepositoryChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffRepositoryChangeParserTest {
    private final UnifiedDiffRepositoryChangeParser parser =
            new UnifiedDiffRepositoryChangeParser();

    @Test
    void parsesFilesLinesHunksRenamesAndProviderLimitations() {
        String patch =
                """
                diff --git a/src/Old.java b/src/New.java
                similarity index 90%
                rename from src/Old.java
                rename to src/New.java
                --- a/src/Old.java
                +++ b/src/New.java
                @@ -10,2 +10,3 @@
                -old
                +new
                +extra
                 context
                diff --git a/assets/logo.png b/assets/logo.png
                new file mode 100644
                Binary files /dev/null and b/assets/logo.png differ
                """;
        RepositoryChange result =
                parser.parse(snapshot(patch, true, List.of(new RepositoryChange.Limitation("TOO_LARGE", "one file"))));

        assertThat(result.partial()).isTrue();
        assertThat(result.limitations()).extracting(RepositoryChange.Limitation::code).containsExactly("TOO_LARGE");
        assertThat(result.changes()).hasSize(2);
        assertThat(result.changes().get(0))
                .satisfies(
                        change -> {
                            assertThat(change.type()).isEqualTo(RepositoryChange.ChangeType.RENAMED);
                            assertThat(change.oldPath()).isEqualTo("src/Old.java");
                            assertThat(change.newPath()).isEqualTo("src/New.java");
                            assertThat(change.additions()).isEqualTo(2);
                            assertThat(change.deletions()).isEqualTo(1);
                            assertThat(change.hunks()).containsExactly(new RepositoryChange.Hunk(10, 2, 10, 3));
                        });
        assertThat(result.changes().get(1).binary()).isTrue();
        assertThat(result.changes().get(1).additions()).isNull();
    }

    @Test
    void rejectsUnsafePathsAndInvalidCommitsInsteadOfPublishingGuesses() {
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        snapshot(
                                                "diff --git a/../secret b/../secret\n",
                                                false,
                                                List.of())))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PROVIDER_PATH_UNSAFE"));

        PullRequestProvider.PullRequestSnapshot invalid =
                new PullRequestProvider.PullRequestSnapshot(
                        PullRequestProvider.ProviderKind.GITHUB,
                        "acme/app#1",
                        1,
                        "title",
                        null,
                        "dev",
                        false,
                        "not-a-commit",
                        "b".repeat(40),
                        "",
                        false,
                        List.of(),
                        Instant.now());
        assertThatThrownBy(() -> parser.parse(invalid))
                .isInstanceOfSatisfying(
                        PullRequestIntegrationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PROVIDER_COMMIT_INVALID"));
    }

    private static PullRequestProvider.PullRequestSnapshot snapshot(
            String patch, boolean partial, List<RepositoryChange.Limitation> limitations) {
        return new PullRequestProvider.PullRequestSnapshot(
                PullRequestProvider.ProviderKind.GITHUB,
                "acme/app#7",
                7,
                "change",
                "https://github.com/acme/app/pull/7",
                "dev",
                false,
                "a".repeat(40),
                "b".repeat(40),
                patch,
                partial,
                limitations,
                Instant.now());
    }
}
