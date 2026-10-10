package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.application.llm.LlmConnectionException;
import com.analyzercoder.security.ApiSecurityException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class BranchTaskFailureMessageTest {
    private String message(RuntimeException failure) {
        return ReflectionTestUtils.invokeMethod(
                BranchPreparationJobs.class, "failureMessage", failure);
    }

    @Test
    void retainsTypedModelErrorThroughWrappersAndRedactsCredentials() {
        var failure =
                new IllegalStateException(
                        "private wrapper",
                        new LlmConnectionException(
                                "LLM_TIMEOUT", "第 2 批失败；请求超时=30000ms；token=private-token"));
        assertThat(message(failure))
                .contains("[LLM_TIMEOUT]", "第 2 批", "请求超时=30000ms")
                .doesNotContain("private wrapper", "private-token");
    }

    @Test
    void separatesDatabaseAndPermissionFailuresFromModelFailures() {
        assertThat(message(new DataIntegrityViolationException("private SQL and values")))
                .contains("[DATABASE_WRITE_FAILED]", "任务编号")
                .doesNotContain("private SQL");
        assertThat(message(new ApiSecurityException(403, "REPOSITORY_FORBIDDEN", "没有项目维护权限")))
                .contains("[REPOSITORY_FORBIDDEN]", "没有项目维护权限");
    }

    @Test
    void keepsUnexpectedDetailsPrivateButPreservesTypeAndGraphErrors() {
        assertThat(message(new IllegalStateException("private filesystem details")))
                .contains("IllegalStateException", "任务编号")
                .doesNotContain("private filesystem");
        assertThat(message(new IllegalStateException("CodeGraph 执行超时")))
                .isEqualTo("CodeGraph 执行超时");
    }
}
