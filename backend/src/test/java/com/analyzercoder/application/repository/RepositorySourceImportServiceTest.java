package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepositorySourceImportServiceTest {
    @Test
    void translatesMissingRemoteBranchWithoutLeakingEnglishOutput() {
        String message =
                RepositorySourceImportService.gitFailureMessage(
                        List.of(
                                "clone",
                                "--depth",
                                "1",
                                "--branch",
                                "main",
                                "https://example/repo.git",
                                "target"),
                        "warning: Could not find remote branch main to clone.\n"
                                + "fatal: Remote branch main not found");

        assertThat(message).isEqualTo("远程仓库中不存在分支“main”，请确认分支名称后重试");
        assertThat(message).doesNotContain("fatal", "Remote branch");
    }

    @Test
    void translatesAuthenticationAndNetworkFailures() {
        assertThat(
                        RepositorySourceImportService.gitFailureMessage(
                                List.of("clone"), "fatal: Authentication failed"))
                .contains("身份验证失败");
        assertThat(
                        RepositorySourceImportService.gitFailureMessage(
                                List.of("clone"),
                                "fatal: unable to access: Could not resolve host"))
                .contains("无法解析远程仓库域名");
        assertThat(
                        RepositorySourceImportService.gitFailureMessage(
                                List.of("clone"), "fatal: repository not found"))
                .contains("仓库不存在");
    }
}
