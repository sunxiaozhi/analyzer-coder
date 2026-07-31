package com.analyzercoder.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
}