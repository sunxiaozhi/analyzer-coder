package com.analyzercoder.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitRuntimePolicyTest {
    @Test
    void usesOperatingSystemNullDeviceForDisabledHooks() {
        String path = GitRuntimePolicy.disabledHooksPath();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertThat(path).isEqualTo("NUL");
        } else {
            assertThat(path).isEqualTo("/dev/null");
        }
    }

    @Test
    void removesInheritedGitContextAndKeepsUnrelatedEnvironment() {
        Map<String, String> environment =
                new HashMap<>(
                        Map.of(
                                "PATH", "test-path",
                                "GIT_DIR", "/unexpected/repository",
                                "git_work_tree", "/unexpected/worktree",
                                "ANALYZER_GIT_SECRET", "stale-secret"));

        GitRuntimePolicy.sanitizeEnvironment(environment);

        assertThat(environment)
                .containsEntry("PATH", "test-path")
                .containsEntry("GIT_TERMINAL_PROMPT", "0")
                .containsEntry("GIT_OPTIONAL_LOCKS", "0")
                .containsEntry("GIT_LFS_SKIP_SMUDGE", "1")
                .doesNotContainKeys("GIT_DIR", "git_work_tree", "ANALYZER_GIT_SECRET");
    }
}
