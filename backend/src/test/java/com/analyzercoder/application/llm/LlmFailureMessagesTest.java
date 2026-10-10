package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmFailureMessagesTest {
    @org.junit.jupiter.api.Test
    void preservesStackLocationsWithoutExceptionMessagesOrSql() {
        var cause = new java.io.IOException("Bearer private-key; private source body");
        var failure = new IllegalStateException("SELECT password FROM private_table", cause);
        org.assertj.core.api.Assertions.assertThat(LlmFailureMessages.stackTrace(failure))
                .contains(
                        "IllegalStateException",
                        "IOException",
                        "Caused by:",
                        "LlmFailureMessagesTest.java")
                .doesNotContain(
                        "private-key", "private source body", "SELECT password", "private_table");
    }

    @Test
    void redactsCredentialsBeforeBoundingDiagnosticText() {
        String message =
                "HTTP 400 token limit exceeded; Authorization: Bearer header-secret; api_key=other-secret; "
                        + "{\"password\":\"json-secret\"} https://user:pass@host/v1?token=url-secret actual-secret sk-privatekey123";
        String safe = LlmFailureMessages.safe(message, "actual-secret");
        assertThat(safe)
                .contains("HTTP 400 token limit exceeded")
                .doesNotContain(
                        "header-secret",
                        "other-secret",
                        "json-secret",
                        "user:pass",
                        "url-secret",
                        "actual-secret",
                        "sk-privatekey123");
        assertThat(LlmFailureMessages.safe("x".repeat(2200) + " actual-secret", "actual-secret"))
                .hasSize(2000)
                .doesNotContain("actual-secret");
    }

    @Test
    void repeatedRedactionPreservesFollowingRequestMetadata() {
        String message = "Bearer private-header；模型=bge-m3，请求超时=5000ms";
        assertThat(LlmFailureMessages.safe(LlmFailureMessages.safe(message)))
                .contains("模型=bge-m3", "请求超时=5000ms")
                .doesNotContain("private-header");
        assertThat(LlmFailureMessages.safe("https://user:secret@host/path；模型=bge-m3"))
                .contains("模型=bge-m3")
                .doesNotContain("user:secret");
    }

    @Test
    void compactsPreviousBatchReasonWithoutRepeatingModelAndRequestMetadata() {
        assertThat(
                        LlmFailureMessages.brief(
                                "调用ID=previous；向量服务 HTTP 413：batch too large；模型=bge-m3，输入条数=16，超时=30000ms"))
                .contains("调用ID=previous", "HTTP 413", "batch too large")
                .doesNotContain("输入条数=16", "超时=30000ms", "模型=bge-m3");
        assertThat(LlmFailureMessages.brief("x".repeat(1000))).hasSize(180);
    }

    @Test
    void flattensControlCharactersAndHandlesMissingMessages() {
        assertThat(LlmFailureMessages.safe("HTTP 429\n retry\t later\u0000"))
                .isEqualTo("HTTP 429 retry later");
        assertThat(LlmFailureMessages.safe(null)).isNotBlank();
    }
}
