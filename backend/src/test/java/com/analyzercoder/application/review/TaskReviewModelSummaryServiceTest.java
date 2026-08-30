package com.analyzercoder.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.evidence.TruthSource;
import com.analyzercoder.application.llm.LlmSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskReviewModelSummaryServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LlmSettingsService llm = mock(LlmSettingsService.class);
    private final TaskReviewModelSummaryService service =
            new TaskReviewModelSummaryService(llm, json);

    @Test
    void acceptsStrictJsonAndExpandsOnlyExistingEvidenceAsModelSuggestion() {
        TaskReviewResult review = completed(UUID.randomUUID());
        when(llm.generate(eq(review.modelConfigId()), anyString()))
                .thenAnswer(
                        invocation -> {
                            JsonNode prompt = json.readTree(invocation.getArgument(1, String.class));
                            String evidenceId =
                                    prompt.path("completedTaskReview")
                                            .path("evidence")
                                            .path(0)
                                            .path("id")
                                            .asText();
                            return Optional.of(
                                    new LlmSettingsService.GenerationResult(
                                            """
                                            {"summary":"本次修改涉及退款服务。","findings":[
                                              {"text":"需要核对这处真实文件变化。","evidenceIds":["%s"]}
                                            ],"unknowns":["运行时行为仍需执行测试确认"]}
                                            """
                                                    .formatted(evidenceId),
                                            "fixture/model"));
                        });

        TaskReviewModelSummaryService.Attempt attempt = service.summarize(review);

        assertThat(attempt.state().status())
                .isEqualTo(TaskReviewResult.ModelSummaryStatus.COMPLETED);
        assertThat(attempt.summary().sourceType()).isEqualTo("MODEL_SUGGESTION");
        assertThat(attempt.summary().provider()).isEqualTo("fixture/model");
        assertThat(attempt.summary().findings())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.evidence()).singleElement();
                            assertThat(finding.sources())
                                    .singleElement()
                                    .satisfies(
                                            source -> {
                                                assertThat(source.sourceType())
                                                        .isEqualTo(TruthSource.MODEL_SUGGESTION);
                                                assertThat(source.findingId())
                                                        .isEqualTo(finding.evidenceIds().get(0));
                                            });
                        });
        assertThat(review.changedSymbols()).hasSize(1);
        assertThat(review.requiredTests()).isEmpty();
    }

    @Test
    void rejectsTheWholeSummaryWhenAnyEvidenceIdDoesNotExist() {
        TaskReviewResult review = completed(UUID.randomUUID());
        when(llm.generate(eq(review.modelConfigId()), anyString()))
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        """
                                        {"summary":"总结","findings":[
                                          {"text":"伪造结论","evidenceIds":["00000000-0000-4000-8000-000000000000"]}
                                        ],"unknowns":[]}
                                        """,
                                        "fixture/model")));

        TaskReviewModelSummaryService.Attempt attempt = service.summarize(review);

        assertThat(attempt.summary()).isNull();
        assertThat(attempt.state().status())
                .isEqualTo(TaskReviewResult.ModelSummaryStatus.REJECTED);
        assertThat(attempt.state().code()).isEqualTo("MODEL_SUMMARY_UNKNOWN_EVIDENCE");
    }

    @Test
    void rejectsMarkdownTrailingTextAndUnexpectedFields() {
        TaskReviewResult review = completed(UUID.randomUUID());
        when(llm.generate(eq(review.modelConfigId()), anyString()))
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        """
                                        ```json
                                        {"summary":"总结","findings":[],"unknowns":[],"files":["invented.java"]}
                                        ```
                                        """,
                                        "fixture/model")));

        TaskReviewModelSummaryService.Attempt attempt = service.summarize(review);

        assertThat(attempt.summary()).isNull();
        assertThat(attempt.state().status())
                .isEqualTo(TaskReviewResult.ModelSummaryStatus.REJECTED);
        assertThat(attempt.state().code()).isEqualTo("MODEL_SUMMARY_SCHEMA_INVALID");
    }

    @Test
    void keepsDeterministicReviewCompleteWhenProviderReturnsNoResult() {
        TaskReviewResult review = completed(UUID.randomUUID());
        when(llm.generate(eq(review.modelConfigId()), anyString())).thenReturn(Optional.empty());

        TaskReviewModelSummaryService.Attempt attempt = service.summarize(review);
        TaskReviewResult persisted = review.withModelSummary(attempt.summary(), attempt.state());

        assertThat(persisted.status()).isEqualTo(TaskReviewResult.Status.COMPLETED);
        assertThat(persisted.changedSymbols()).isEqualTo(review.changedSymbols());
        assertThat(persisted.modelSummary()).isNull();
        assertThat(persisted.modelSummaryState().status())
                .isEqualTo(TaskReviewResult.ModelSummaryStatus.UNAVAILABLE);
    }

    @Test
    void skipsModelEntirelyWhenNoModelWasRequestedAndRejectsNonCompletedInput() {
        TaskReviewResult withoutModel = completed(null);

        TaskReviewModelSummaryService.Attempt attempt = service.summarize(withoutModel);

        assertThat(attempt.state().status())
                .isEqualTo(TaskReviewResult.ModelSummaryStatus.NOT_REQUESTED);
        verify(llm, never()).generate(eq(null), anyString());
        assertThatThrownBy(
                        () ->
                                service.summarize(
                                        new TaskReviewResult(
                                                withoutModel.reviewId(),
                                                TaskReviewResult.Status.FAILED,
                                                withoutModel.repositoryId(),
                                                withoutModel.snapshotId(),
                                                withoutModel.createdBy(),
                                                withoutModel.clientRequestId(),
                                                null,
                                                withoutModel.task(),
                                                withoutModel.changeSource(),
                                                withoutModel.baseRef(),
                                                withoutModel.headRef(),
                                                null,
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                null,
                                                new TaskReviewResult.ErrorDetail("FAILED", "failed"),
                                                withoutModel.createdAt(),
                                                Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TaskReviewResult completed(UUID modelConfigId) {
        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String head = "b".repeat(40);
        RepositoryChange change =
                new RepositoryChange(
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "a".repeat(40),
                        head,
                        null,
                        false,
                        List.of(
                                new RepositoryChange.FileChange(
                                        RepositoryChange.ChangeType.MODIFIED,
                                        "src/refund/RefundService.java",
                                        "src/refund/RefundService.java",
                                        false,
                                        1L,
                                        1L,
                                        List.of(new RepositoryChange.Hunk(10, 1, 10, 1)))),
                        List.of());
        ChangedSymbolResolver.ChangedSymbol symbol =
                new ChangedSymbolResolver.ChangedSymbol(
                        "refund#approveRefund",
                        "approveRefund",
                        "METHOD",
                        "src/refund/RefundService.java",
                        8,
                        14,
                        RepositoryChange.ChangeType.MODIFIED,
                        10,
                        10,
                        0,
                        false,
                        ChangedSymbolResolver.Resolution.SOURCE_DECLARATION,
                        List.of());
        Instant now = Instant.now();
        return new TaskReviewResult(
                UUID.randomUUID(),
                TaskReviewResult.Status.COMPLETED,
                repositoryId,
                snapshotId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                modelConfigId,
                "增加退款审批",
                GitChangeRequest.Source.COMMIT_RANGE.name(),
                "base",
                "head",
                change,
                List.of(symbol),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                now,
                now);
    }
}
