package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RepositoryGlobMatcherTest {
    private final RepositoryGlobMatcher matcher = new RepositoryGlobMatcher();

    @Test
    void supportsSingleDoubleStarAndQuestionMarkWithStableSeparators() {
        assertThat(matcher.matches("backend/src/**/refund/**", "backend\\src\\refund\\Refund.java"))
                .isTrue();
        assertThat(
                        matcher.matches(
                                "backend/src/**/refund/**",
                                "backend/src/main/java/refund/Refund.java"))
                .isTrue();
        assertThat(matcher.matches("src/*.java", "src/Refund.java")).isTrue();
        assertThat(matcher.matches("src/*.java", "src/main/Refund.java")).isFalse();
        assertThat(matcher.matches("docs/?.md", "docs/a.md")).isTrue();
        assertThat(matcher.matches("docs/?.md", "docs/ab.md")).isFalse();
    }

    @Test
    void remainsCaseSensitiveAndTreatsRegexCharactersAsLiterals() {
        assertThat(matcher.matches("src/Refund*.java", "src/refundService.java")).isFalse();
        assertThat(matcher.matches("src/a+b.java", "src/a+b.java")).isTrue();
        assertThat(matcher.matches("src/a+b.java", "src/ab.java")).isFalse();
    }

    @Test
    void rejectsAbsoluteTraversalControlAndOversizedPatterns() {
        assertThatThrownBy(() -> matcher.matches("/etc/**", "etc/passwd"))
                .hasMessageContaining("仓库相对路径");
        assertThatThrownBy(() -> matcher.matches("C:\\repo\\**", "src/App.java"))
                .hasMessageContaining("仓库相对路径");
        assertThatThrownBy(() -> matcher.matches("src/../secret", "src/App.java"))
                .hasMessageContaining("仓库相对路径");
        assertThatThrownBy(() -> matcher.matches("src/\u0000/**", "src/App.java"))
                .hasMessageContaining("仓库相对路径");
        assertThatThrownBy(() -> matcher.matches("a".repeat(301), "src/App.java"))
                .hasMessageContaining("300");
    }
}
