package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ModelCallLogContextTest {
    private final Map<String, String> before = MDC.getCopyOfContextMap();

    @AfterEach
    void restore() {
        if (before == null) MDC.clear();
        else MDC.setContextMap(before);
    }

    @Test
    void nestedModelCallsInheritTheTaskAndRestoreItsStageAfterFailure() {
        UUID repo = UUID.randomUUID(), task = UUID.randomUUID();
        MDC.put("unrelated", "preserved");
        try (var outer = ModelCallLogContext.open(task.toString(), repo, null, task, null)) {
            ModelCallLogContext.stage("EMBEDDING");
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        try (var nested = ModelCallLogContext.open(null, repo, null, null, null)) {
                            ModelCallLogContext.stage("VECTOR_BATCH_WRITE");
                            assertThat(ModelCallLogContext.fields())
                                    .contains(
                                            "traceId=" + task,
                                            "taskId=" + task,
                                            "repoId=" + repo,
                                            "stage=VECTOR_BATCH_WRITE");
                            throw new IllegalStateException("failed");
                        }
                    });
            assertThat(ModelCallLogContext.fields()).contains("stage=EMBEDDING");
        }
        assertThat(MDC.get("modelTaskId"))
                .isEqualTo(before == null ? null : before.get("modelTaskId"));
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    @Test
    void aDifferentRootRequestDoesNotInheritThePreviousTask() {
        UUID task = UUID.randomUUID();
        try (var outer =
                ModelCallLogContext.open(task.toString(), UUID.randomUUID(), null, task, null)) {
            try (var question =
                    ModelCallLogContext.open(
                            UUID.randomUUID().toString(), UUID.randomUUID(), null, null, null)) {
                assertThat(ModelCallLogContext.fields())
                        .contains("taskId=-")
                        .doesNotContain(task.toString());
            }
            assertThat(ModelCallLogContext.fields()).contains("taskId=" + task);
        }
    }

    @Test
    void reusedWorkerThreadsDoNotRetainAnEarlierRequestsIdentifiers() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        UUID task = UUID.randomUUID();
        try {
            executor.submit(
                            () -> {
                                try (var ignored =
                                        ModelCallLogContext.open(
                                                task.toString(),
                                                UUID.randomUUID(),
                                                null,
                                                task,
                                                null)) {
                                    assertThat(ModelCallLogContext.fields())
                                            .contains(task.toString());
                                }
                            })
                    .get(5, TimeUnit.SECONDS);
            assertThat(executor.submit(ModelCallLogContext::fields).get(5, TimeUnit.SECONDS))
                    .contains("traceId=-", "taskId=-")
                    .doesNotContain(task.toString());
        } finally {
            executor.shutdownNow();
        }
    }
}
