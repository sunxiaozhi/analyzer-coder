package com.analyzercoder.application.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.application.review.KnowledgeMatch;
import com.analyzercoder.application.review.KnowledgeMatchReason;
import com.analyzercoder.application.review.KnowledgeMatchReason.EvidenceSource;
import com.analyzercoder.application.review.KnowledgeMatchReason.MatchKind;
import com.analyzercoder.application.review.KnowledgeMatchReason.ScopeEvidence;
import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CiCheckServiceTest {
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final UUID SNAPSHOT_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID APPROVER_ID = UUID.randomUUID();
    private static final String HEAD = "a".repeat(40);

    private TaskReviewService reviews;
    private IntelligenceService intelligence;
    private CiCheckService service;

    @BeforeEach
    void setUp() {
        reviews = mock(TaskReviewService.class);
        intelligence = mock(IntelligenceService.class);
        service = new CiCheckService(reviews, intelligence, new RepositoryGlobMatcher());
    }

    @Test
    void failsOnlyForTheFiveExplicitDeterministicPolicies() {
        UUID applicableId = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        KnowledgeMatchReason direct = directReason("protected/config.yml");
        KnowledgeMatch applicable =
                knowledge(
                        applicableId,
                        KnowledgeSeverity.CRITICAL,
                        "CURRENT",
                        new KnowledgeObligations(
                                List.of("backend-tests"),
                                List.of(APPROVER_ID),
                                List.of(),
                                List.of("protected/**"),
                                true),
                        direct);
        KnowledgeMatch stale =
                knowledge(
                        staleId,
                        KnowledgeSeverity.CRITICAL,
                        "STALE",
                        KnowledgeObligations.empty(),
                        direct);
        TaskReviewResult review =
                review(
                        false,
                        List.of(applicable),
                        List.of(),
                        List.of(requirement(TaskReviewFinding.FindingKind.REQUIRED_TEST, "backend-tests", direct)),
                        List.of(requirement(TaskReviewFinding.FindingKind.REQUIRED_APPROVAL, APPROVER_ID.toString(), direct)),
                        List.of(stale),
                        List.of(),
                        TaskReviewResult.ModelSummaryState.notRequested());
        when(reviews.get(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID)).thenReturn(review);
        when(intelligence.cards(REPOSITORY_ID, true)).thenReturn(List.of());

        CiCheckService.CiCheckResult result =
                service.check(
                        CodeRepositoryId.of(REPOSITORY_ID),
                        REVIEW_ID,
                        new CiCheckService.CiCheckRequest(HEAD, List.of(), List.of()));

        assertThat(result.decision()).isEqualTo(CiCheckService.Decision.FAIL);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.blockingFindings())
                .extracting(CiCheckService.CiFinding::code)
                .containsExactlyInAnyOrder(
                        "PROHIBITED_PATH_CHANGED",
                        "REQUIRED_TEST_NOT_REPORTED",
                        "REQUIRED_APPROVAL_MISSING",
                        "CRITICAL_REQUIRED_KNOWLEDGE_STALE",
                        "REQUIRED_KNOWLEDGE_UPDATE_MISSING");
    }

    @Test
    void passesWhenExplicitTestsApprovalsAndKnowledgeUpdateAreVerified() {
        UUID knowledgeId = UUID.randomUUID();
        KnowledgeMatchReason direct = directReason("src/App.java");
        KnowledgeMatch applicable =
                knowledge(
                        knowledgeId,
                        KnowledgeSeverity.WARNING,
                        "CURRENT",
                        new KnowledgeObligations(
                                List.of("backend-tests"),
                                List.of(APPROVER_ID),
                                List.of(),
                                List.of("deploy/**"),
                                true),
                        direct);
        TaskReviewResult review =
                review(
                        false,
                        List.of(applicable),
                        List.of(),
                        List.of(requirement(TaskReviewFinding.FindingKind.REQUIRED_TEST, "backend-tests", direct)),
                        List.of(requirement(TaskReviewFinding.FindingKind.REQUIRED_APPROVAL, APPROVER_ID.toString(), direct)),
                        List.of(),
                        List.of(),
                        TaskReviewResult.ModelSummaryState.notRequested());
        when(reviews.get(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID)).thenReturn(review);
        when(intelligence.cards(REPOSITORY_ID, true))
                .thenReturn(List.of(currentKnowledge(knowledgeId, 2)));

        CiCheckService.CiCheckResult result =
                service.check(
                        CodeRepositoryId.of(REPOSITORY_ID),
                        REVIEW_ID,
                        new CiCheckService.CiCheckRequest(
                                HEAD,
                                List.of(
                                        new CiCheckService.TestReport(
                                                "backend-tests",
                                                CiCheckService.TestStatus.PASSED,
                                                "https://ci.example/tests/1")),
                                List.of(
                                        new CiCheckService.ApprovalReport(
                                                APPROVER_ID,
                                                CiCheckService.ApprovalStatus.APPROVED,
                                                "https://ci.example/approvals/1"))));

        assertThat(result.decision()).isEqualTo(CiCheckService.Decision.PASS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.blockingFindings()).isEmpty();
    }

    @Test
    void verifiesAKnowledgeUpdateInTheCrossRepositoryKnowledgeSource() {
        UUID sourceRepositoryId = UUID.randomUUID();
        UUID knowledgeId = UUID.randomUUID();
        KnowledgeMatch crossRepositoryKnowledge =
                new KnowledgeMatch(
                        knowledgeId,
                        "跨仓契约知识",
                        KnowledgeKind.BUSINESS_RULE,
                        KnowledgeSeverity.WARNING,
                        KnowledgeEnforcement.REQUIRED,
                        UUID.randomUUID(),
                        1,
                        "CURRENT",
                        new KnowledgeObligations(
                                List.of(), List.of(), List.of(), List.of(), true),
                        List.of(directReason("src/App.java")),
                        List.of(
                                Provenance.verifiedKnowledge(
                                        sourceRepositoryId,
                                        knowledgeId,
                                        1,
                                        "APPROVED",
                                        "来源仓库知识")));
        when(reviews.get(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID))
                .thenReturn(
                        review(
                                false,
                                List.of(crossRepositoryKnowledge),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                TaskReviewResult.ModelSummaryState.notRequested()));
        when(intelligence.cards(REPOSITORY_ID, true)).thenReturn(List.of());
        when(intelligence.cards(sourceRepositoryId, true))
                .thenReturn(List.of(currentKnowledge(sourceRepositoryId, knowledgeId, 2)));

        CiCheckService.CiCheckResult result =
                service.check(
                        CodeRepositoryId.of(REPOSITORY_ID),
                        REVIEW_ID,
                        new CiCheckService.CiCheckRequest(HEAD, List.of(), List.of()));

        assertThat(result.decision()).isEqualTo(CiCheckService.Decision.PASS);
        assertThat(result.blockingFindings()).isEmpty();
    }

    @Test
    void keepsGraphRetrievalModelUnknownAndPartialSignalsAdvisory() {
        UUID knowledgeId = UUID.randomUUID();
        KnowledgeMatchReason graph = graphReason("src/App.java");
        Provenance unknownSource =
                Provenance.unknown(REPOSITORY_ID, knowledgeId, "src/App.java", "unknown-1", "无法确认");
        TaskReviewFinding unknown =
                new TaskReviewFinding(
                        TaskReviewFinding.FindingKind.UNKNOWN,
                        "unknown-1",
                        "未知影响",
                        TaskReviewFinding.FindingStatus.UNKNOWN,
                        List.of(knowledgeId),
                        List.of(),
                        new TaskReviewFinding.UnknownReason(
                                "GRAPH_INCOMPLETE", knowledgeId, "src/App.java", "symbol", "图谱不完整"),
                        List.of(unknownSource));
        KnowledgeMatch graphKnowledge =
                knowledge(
                        knowledgeId,
                        KnowledgeSeverity.CRITICAL,
                        "STALE",
                        new KnowledgeObligations(
                                List.of(), List.of(), List.of(), List.of(), true),
                        graph);
        KnowledgeMatch.ReferenceCandidate candidate =
                new KnowledgeMatch.ReferenceCandidate(
                        knowledgeId,
                        "检索候选",
                        KnowledgeKind.REFERENCE,
                        "CURRENT",
                        "KEYWORD",
                        "仅召回",
                        Provenance.retrievalCandidate(
                                REPOSITORY_ID, knowledgeId, 1, "APPROVED", "KEYWORD", "仅召回"));
        TaskReviewResult review =
                review(
                        true,
                        List.of(graphKnowledge),
                        List.of(candidate),
                        List.of(requirement(TaskReviewFinding.FindingKind.REQUIRED_TEST, "graph-test", graph)),
                        List.of(requirement(TaskReviewFinding.FindingKind.REQUIRED_APPROVAL, APPROVER_ID.toString(), graph)),
                        List.of(graphKnowledge),
                        List.of(unknown),
                        TaskReviewResult.ModelSummaryState.unavailable("MODEL_OFFLINE", "模型不可用"));
        when(reviews.get(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID)).thenReturn(review);
        when(intelligence.cards(REPOSITORY_ID, true)).thenReturn(List.of());

        CiCheckService.CiCheckResult result =
                service.check(
                        CodeRepositoryId.of(REPOSITORY_ID),
                        REVIEW_ID,
                        new CiCheckService.CiCheckRequest(HEAD, List.of(), List.of()));

        assertThat(result.decision()).isEqualTo(CiCheckService.Decision.PASS);
        assertThat(result.blockingFindings()).isEmpty();
        assertThat(result.advisories())
                .extracting(CiCheckService.CiFinding::code)
                .contains(
                        "GRAPH_ONLY_TEST_REQUIREMENT",
                        "GRAPH_ONLY_APPROVAL_REQUIREMENT",
                        "GRAPH_ONLY_STALE_KNOWLEDGE",
                        "GRAPH_ONLY_KNOWLEDGE_UPDATE",
                        "PARTIAL_CHANGE_DOES_NOT_FAIL_CI",
                        "UNKNOWNS_DO_NOT_FAIL_CI",
                        "RETRIEVAL_CANDIDATES_IGNORED",
                        "MODEL_SUGGESTIONS_IGNORED");
    }

    @Test
    void rejectsAHeadThatDoesNotMatchTheImmutableReview() {
        when(reviews.get(CodeRepositoryId.of(REPOSITORY_ID), REVIEW_ID))
                .thenReturn(
                        review(
                                false,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                TaskReviewResult.ModelSummaryState.notRequested()));

        assertThatThrownBy(
                        () ->
                                service.check(
                                        CodeRepositoryId.of(REPOSITORY_ID),
                                        REVIEW_ID,
                                        new CiCheckService.CiCheckRequest(
                                                "b".repeat(40), List.of(), List.of())))
                .isInstanceOf(CiCheckException.class)
                .extracting("code")
                .isEqualTo("CI_HEAD_MISMATCH");
    }

    private static TaskReviewResult review(
            boolean partial,
            List<KnowledgeMatch> applicable,
            List<KnowledgeMatch.ReferenceCandidate> references,
            List<TaskReviewFinding> tests,
            List<TaskReviewFinding> approvals,
            List<KnowledgeMatch> stale,
            List<TaskReviewFinding> unknowns,
            TaskReviewResult.ModelSummaryState modelState) {
        RepositoryChange change =
                new RepositoryChange(
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "0".repeat(40),
                        HEAD,
                        null,
                        partial,
                        List.of(
                                new RepositoryChange.FileChange(
                                        RepositoryChange.ChangeType.MODIFIED,
                                        "protected/config.yml",
                                        "protected/config.yml",
                                        false,
                                        1L,
                                        1L,
                                        List.of())),
                        List.of());
        return new TaskReviewResult(
                REVIEW_ID,
                TaskReviewResult.Status.COMPLETED,
                REPOSITORY_ID,
                SNAPSHOT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "检查改动",
                "COMMIT_RANGE",
                "0".repeat(40),
                HEAD,
                change,
                List.of(),
                applicable,
                references,
                tests,
                approvals,
                stale,
                unknowns,
                "确定性审查",
                null,
                modelState,
                null,
                Instant.now(),
                Instant.now());
    }

    private static KnowledgeMatch knowledge(
            UUID id,
            KnowledgeSeverity severity,
            String sourceStatus,
            KnowledgeObligations obligations,
            KnowledgeMatchReason reason) {
        return new KnowledgeMatch(
                id,
                "知识 " + id,
                KnowledgeKind.BUSINESS_RULE,
                severity,
                KnowledgeEnforcement.REQUIRED,
                UUID.randomUUID(),
                1,
                sourceStatus,
                obligations,
                List.of(reason),
                List.of(Provenance.verifiedKnowledge(REPOSITORY_ID, id, 1, "APPROVED", "已审核")));
    }

    private static TaskReviewFinding requirement(
            TaskReviewFinding.FindingKind kind, String key, KnowledgeMatchReason reason) {
        return new TaskReviewFinding(
                kind,
                key,
                key,
                TaskReviewFinding.FindingStatus.REQUIRED_NOT_REPORTED,
                List.of(UUID.randomUUID()),
                List.of(reason),
                null,
                List.of(Provenance.gitFact(REPOSITORY_ID, SNAPSHOT_ID, HEAD, null, reason.evidence().filePath(), "Git 事实")));
    }

    private static KnowledgeMatchReason directReason(String path) {
        return new KnowledgeMatchReason(
                MatchKind.PATH_PATTERN,
                "src/**",
                path,
                new ScopeEvidence(
                        EvidenceSource.GIT_FACT,
                        REPOSITORY_ID,
                        SNAPSHOT_ID,
                        HEAD,
                        path,
                        null,
                        null,
                        null,
                        "Git 路径命中"));
    }

    private static KnowledgeMatchReason graphReason(String path) {
        return new KnowledgeMatchReason(
                MatchKind.SYMBOL,
                "Service",
                "AppService",
                new ScopeEvidence(
                        EvidenceSource.GRAPH_INFERENCE,
                        REPOSITORY_ID,
                        SNAPSHOT_ID,
                        HEAD,
                        path,
                        "AppService",
                        null,
                        null,
                        "仅图谱推断"));
    }

    private static IntelligenceService.KnowledgeCard currentKnowledge(UUID id, int revision) {
        return currentKnowledge(REPOSITORY_ID, id, revision);
    }

    private static IntelligenceService.KnowledgeCard currentKnowledge(
            UUID repositoryId, UUID id, int revision) {
        return new IntelligenceService.KnowledgeCard(
                id,
                repositoryId,
                "已同步知识",
                "RULE",
                "content",
                "content",
                List.of(),
                KnowledgeKind.BUSINESS_RULE,
                KnowledgeSeverity.WARNING,
                KnowledgeEnforcement.REQUIRED,
                UUID.randomUUID(),
                new KnowledgeScope(List.of("src/**"), List.of(), List.of()),
                KnowledgeObligations.empty(),
                SNAPSHOT_ID,
                "已验证",
                "PUBLISHED",
                revision,
                Instant.now(),
                Instant.now(),
                HEAD,
                "CURRENT",
                Instant.now(),
                "APPROVED",
                UUID.randomUUID(),
                Instant.now(),
                List.of(),
                List.of());
    }
}
