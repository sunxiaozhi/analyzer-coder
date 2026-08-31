package com.analyzercoder.application.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.ApprovalResult;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.ApprovalStatus;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.FeedbackInput;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.FeedbackKind;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.FeedbackTargetType;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.KnowledgeUpdateAssessment;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.OutcomeRequest;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.TestResult;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService.TestStatus;
import com.analyzercoder.application.review.KnowledgeMatch;
import com.analyzercoder.application.review.KnowledgeMatchReason;
import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.TaskReviewOutcomeMapper;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewFeedbackRow;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewOutcomeRow;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskReviewOutcomeServiceTest {
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_ID = UUID.randomUUID();
    private static final UUID APPROVER_ID = UUID.randomUUID();
    private static final String HEAD = "a".repeat(40);

    private final TaskReviewService reviews = mock(TaskReviewService.class);
    private final TaskReviewOutcomeMapper mapper = mock(TaskReviewOutcomeMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final TaskReviewOutcomeService service =
            new TaskReviewOutcomeService(reviews, mapper, auth, new ObjectMapper());
    private final AuthenticatedAccount actor =
            new AuthenticatedAccount(
                    UUID.randomUUID(), "developer", "开发者", AccountRole.NORMAL, false, Instant.now());
    private final AtomicReference<TaskReviewOutcomeRow> persisted = new AtomicReference<>();
    private final List<TaskReviewFeedbackRow> feedback = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(reviews.get(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID)).thenReturn(review());
        when(mapper.insertOutcome(any()))
                .thenAnswer(
                        invocation -> {
                            persisted.set(invocation.getArgument(0));
                            return 1;
                        });
        when(mapper.findById(any(), any(), any())).thenAnswer(ignored -> persisted.get());
        doAnswer(
                        invocation -> {
                            feedback.add(invocation.getArgument(0));
                            return 1;
                        })
                .when(mapper)
                .insertFeedback(any());
        when(mapper.feedback(any())).thenAnswer(ignored -> List.copyOf(feedback));
    }

    @Test
    void reportsAnImmutableVersionBoundOutcomeWithHumanFeedback() {
        UUID requestId = UUID.randomUUID();
        OutcomeRequest request =
                new OutcomeRequest(
                        requestId,
                        HEAD.toUpperCase(),
                        "退款改动已完成并通过验证",
                        List.of(new TestResult("backend-tests", TestStatus.PASSED, "https://ci.example/t/1")),
                        List.of(
                                new ApprovalResult(
                                        APPROVER_ID,
                                        ApprovalStatus.APPROVED,
                                        "https://ci.example/a/1")),
                        List.of(
                                new FeedbackInput(
                                        FeedbackKind.FALSE_POSITIVE,
                                        FeedbackTargetType.REQUIRED_TEST,
                                        "backend-tests",
                                        null,
                                        null,
                                        "规则覆盖过宽，但本次仍执行了测试",
                                        List.of()),
                                new FeedbackInput(
                                        FeedbackKind.KNOWLEDGE_UPDATE,
                                        FeedbackTargetType.KNOWLEDGE,
                                        KNOWLEDGE_ID.toString(),
                                        KNOWLEDGE_ID,
                                        KnowledgeUpdateAssessment.NOT_NEEDED,
                                        "实现没有改变业务约束",
                                        List.of("https://review.example/evidence/1"))));

        TaskReviewOutcomeService.OutcomeView result =
                service.report(
                        CodeRepositoryId.of(REPOSITORY_ID),
                        REVIEW_ID,
                        actor,
                        request,
                        "127.0.0.1");

        assertThat(result.finalCommit()).isEqualTo(HEAD);
        assertThat(result.commitBinding())
                .isEqualTo(TaskReviewOutcomeService.CommitBinding.EXACT_REVIEW_HEAD);
        assertThat(result.reporterDisplayName()).isEqualTo("开发者");
        assertThat(result.feedback()).hasSize(2);
        assertThat(result.coverage().missingRequiredTests()).isEmpty();
        assertThat(result.coverage().missingRequiredApprovals()).isEmpty();
        assertThat(persisted.get().payloadHash()).hasSize(64);
        verify(auth)
                .audit(
                        actor.id(),
                        null,
                        REPOSITORY_ID,
                        "TASK_REVIEW_OUTCOME_REPORTED",
                        "SUCCESS",
                        "127.0.0.1");
    }

    @Test
    void replaysTheSameIdempotentReportAndRejectsDifferentContent() {
        UUID requestId = UUID.randomUUID();
        OutcomeRequest request = basicRequest(requestId, "已完成");
        service.report(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID, actor, request, "127.0.0.1");
        TaskReviewOutcomeRow first = persisted.get();
        when(mapper.insertOutcome(any())).thenReturn(0);
        when(mapper.findByClientRequest(REVIEW_ID, actor.id(), requestId)).thenReturn(first);

        assertThat(
                        service.report(
                                        CodeRepositoryId.of(REPOSITORY_ID),
                                        REVIEW_ID,
                                        actor,
                                        request,
                                        "127.0.0.1")
                                .id())
                .isEqualTo(first.id());
        assertThatThrownBy(
                        () ->
                                service.report(
                                        CodeRepositoryId.of(REPOSITORY_ID),
                                        REVIEW_ID,
                                        actor,
                                        basicRequest(requestId, "另一份结果"),
                                        "127.0.0.1"))
                .isInstanceOf(TaskReviewOutcomeException.class)
                .extracting("code")
                .isEqualTo("TASK_OUTCOME_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void labelsALaterCommitAsReporterAssertedAndKeepsUnreportedObligationsVisible() {
        OutcomeRequest request =
                new OutcomeRequest(
                        UUID.randomUUID(),
                        "b".repeat(40),
                        "实现后产生了新的最终提交",
                        List.of(),
                        List.of(),
                        List.of(
                                new FeedbackInput(
                                        FeedbackKind.FALSE_NEGATIVE,
                                        FeedbackTargetType.FILE,
                                        "src/Missed.java",
                                        null,
                                        null,
                                        "人工复核发现审查漏掉了该文件",
                                        List.of())));

        TaskReviewOutcomeService.OutcomeView result =
                service.report(
                        CodeRepositoryId.of(REPOSITORY_ID),
                        REVIEW_ID,
                        actor,
                        request,
                        "127.0.0.1");

        assertThat(result.commitBinding())
                .isEqualTo(TaskReviewOutcomeService.CommitBinding.REPORTER_ASSERTED_FINAL);
        assertThat(result.coverage().missingRequiredTests()).containsExactly("backend-tests");
        assertThat(result.coverage().missingRequiredApprovals())
                .containsExactly(APPROVER_ID.toString());
        assertThat(result.feedback()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(FeedbackKind.FALSE_NEGATIVE);
            assertThat(item.targetKey()).isEqualTo("src/Missed.java");
        });
    }

    @Test
    void rejectsFalsePositiveFeedbackThatCannotBeTracedToTheReview() {
        OutcomeRequest request =
                new OutcomeRequest(
                        UUID.randomUUID(),
                        HEAD,
                        "已完成",
                        List.of(),
                        List.of(),
                        List.of(
                                new FeedbackInput(
                                        FeedbackKind.FALSE_POSITIVE,
                                        FeedbackTargetType.REQUIRED_TEST,
                                        "invented-test",
                                        null,
                                        null,
                                        "审查中不存在的对象",
                                        List.of())));

        assertThatThrownBy(
                        () ->
                                service.report(
                                        CodeRepositoryId.of(REPOSITORY_ID),
                                        REVIEW_ID,
                                        actor,
                                        request,
                                        "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("真实存在");
    }

    private static OutcomeRequest basicRequest(UUID requestId, String summary) {
        return new OutcomeRequest(
                requestId, HEAD, summary, List.of(), List.of(), List.of());
    }

    private static TaskReviewResult review() {
        KnowledgeMatchReason reason =
                new KnowledgeMatchReason(
                        KnowledgeMatchReason.MatchKind.PATH_PATTERN,
                        "src/**",
                        "src/Refund.java",
                        new KnowledgeMatchReason.ScopeEvidence(
                                KnowledgeMatchReason.EvidenceSource.GIT_FACT,
                                REPOSITORY_ID,
                                UUID.randomUUID(),
                                HEAD,
                                "src/Refund.java",
                                null,
                                null,
                                null,
                                "Git 路径"));
        KnowledgeMatch knowledge =
                new KnowledgeMatch(
                        KNOWLEDGE_ID,
                        "退款规则",
                        KnowledgeKind.BUSINESS_RULE,
                        KnowledgeSeverity.WARNING,
                        KnowledgeEnforcement.REQUIRED,
                        UUID.randomUUID(),
                        1,
                        "CURRENT",
                        KnowledgeObligations.empty(),
                        List.of(reason),
                        List.of(
                                Provenance.verifiedKnowledge(
                                        REPOSITORY_ID,
                                        KNOWLEDGE_ID,
                                        1,
                                        "APPROVED",
                                        "已审核")));
        TaskReviewFinding test =
                finding(
                        TaskReviewFinding.FindingKind.REQUIRED_TEST,
                        "backend-tests",
                        reason);
        TaskReviewFinding approval =
                finding(
                        TaskReviewFinding.FindingKind.REQUIRED_APPROVAL,
                        APPROVER_ID.toString(),
                        reason);
        RepositoryChange change =
                new RepositoryChange(
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "0".repeat(40),
                        HEAD,
                        null,
                        false,
                        List.of(
                                new RepositoryChange.FileChange(
                                        RepositoryChange.ChangeType.MODIFIED,
                                        "src/Refund.java",
                                        "src/Refund.java",
                                        false,
                                        1L,
                                        1L,
                                        List.of())),
                        List.of());
        return new TaskReviewResult(
                REVIEW_ID,
                TaskReviewResult.Status.COMPLETED,
                REPOSITORY_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "修改退款规则",
                "COMMIT_RANGE",
                "0".repeat(40),
                HEAD,
                change,
                List.of(),
                List.of(knowledge),
                List.of(),
                List.of(test),
                List.of(approval),
                List.of(),
                List.of(),
                "完成",
                null,
                TaskReviewResult.ModelSummaryState.notRequested(),
                null,
                Instant.now(),
                Instant.now());
    }

    private static TaskReviewFinding finding(
            TaskReviewFinding.FindingKind kind,
            String key,
            KnowledgeMatchReason reason) {
        return new TaskReviewFinding(
                kind,
                key,
                key,
                TaskReviewFinding.FindingStatus.REQUIRED_NOT_REPORTED,
                List.of(KNOWLEDGE_ID),
                List.of(reason),
                null,
                List.of(
                        Provenance.gitFact(
                                REPOSITORY_ID,
                                UUID.randomUUID(),
                                HEAD,
                                null,
                                "src/Refund.java",
                                "Git 事实")));
    }
}
