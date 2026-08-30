package com.analyzercoder.application.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskContextMatcherTest {
    private final TaskContextMatcher matcher =
            new TaskContextMatcher(new KnowledgeScopeMatcher(new RepositoryGlobMatcher()));
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();

    @Test
    void aggregatesEachCurrentKnowledgeOnceAndDeduplicatesRequiredObligations() {
        KnowledgeMatch.Candidate pathRule =
                candidate(
                        "refund path rule",
                        KnowledgeEnforcement.REQUIRED,
                        "CURRENT",
                        new KnowledgeScope(
                                List.of("src/refund/**"),
                                List.of("approveRefund"),
                                List.of("payments")),
                        obligations());
        KnowledgeMatch.Candidate symbolRule =
                candidate(
                        "refund symbol rule",
                        KnowledgeEnforcement.REQUIRED,
                        "CURRENT",
                        new KnowledgeScope(List.of(), List.of("approveRefund"), List.of()),
                        obligations());

        TaskContextMatcher.TaskContextResult result =
                matcher.match(
                        input(
                                List.of(pathRule, pathRule, symbolRule),
                                List.of(
                                        new KnowledgeMatch.RetrievalReference(
                                                pathRule.knowledgeId(),
                                                "VECTOR",
                                                "semantic recall")),
                                true));

        assertThat(result.applicableKnowledge())
                .extracting(KnowledgeMatch::knowledgeId)
                .containsExactlyInAnyOrder(pathRule.knowledgeId(), symbolRule.knowledgeId());
        assertThat(result.applicableKnowledge())
                .filteredOn(match -> match.knowledgeId().equals(pathRule.knowledgeId()))
                .singleElement()
                .satisfies(match -> assertThat(match.reasons()).hasSize(3));
        assertThat(result.requiredTests())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.key()).isEqualTo("./mvnw test");
                            assertThat(finding.status())
                                    .isEqualTo(
                                            TaskReviewFinding.FindingStatus.REQUIRED_NOT_REPORTED);
                            assertThat(finding.knowledgeIds())
                                    .containsExactlyInAnyOrder(
                                            pathRule.knowledgeId(), symbolRule.knowledgeId());
                            assertThat(finding.evidence()).isNotEmpty();
                            assertThat(finding.sources()).isNotEmpty();
                        });
        assertThat(result.requiredApprovals())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.key()).isEqualTo(approverId.toString());
                            assertThat(finding.status())
                                    .isEqualTo(TaskReviewFinding.FindingStatus.REQUIRED);
                        });
        assertThat(result.referenceCandidates()).isEmpty();
        assertThat(result.unknowns()).isEmpty();
    }

    @Test
    void staleKnowledgeIsVisibleButCannotProduceObligations() {
        KnowledgeMatch.Candidate stale =
                candidate(
                        "stale payment rule",
                        KnowledgeEnforcement.REQUIRED,
                        "STALE",
                        new KnowledgeScope(List.of("src/refund/**"), List.of(), List.of()),
                        obligations());
        KnowledgeMatch.Candidate suspect =
                candidate(
                        "suspect payment rule",
                        KnowledgeEnforcement.REQUIRED,
                        "SUSPECT",
                        new KnowledgeScope(List.of(), List.of("approveRefund"), List.of()),
                        obligations());

        TaskContextMatcher.TaskContextResult result =
                matcher.match(input(List.of(stale, suspect), List.of(), true));

        assertThat(result.applicableKnowledge()).isEmpty();
        assertThat(result.staleKnowledge())
                .extracting(KnowledgeMatch::knowledgeId)
                .containsExactlyInAnyOrder(stale.knowledgeId(), suspect.knowledgeId());
        assertThat(result.requiredTests()).isEmpty();
        assertThat(result.requiredApprovals()).isEmpty();
    }

    @Test
    void retrievalOnlyCandidateStaysReferenceAndDraftOrUnapprovedKnowledgeIsExcluded() {
        KnowledgeMatch.Candidate reference =
                candidate(
                        "reference only",
                        KnowledgeEnforcement.REFERENCE,
                        "CURRENT",
                        KnowledgeScope.empty(),
                        KnowledgeObligations.empty());
        KnowledgeMatch.Candidate draft =
                withWorkflow(
                        candidate(
                                "draft",
                                KnowledgeEnforcement.REQUIRED,
                                "CURRENT",
                                new KnowledgeScope(List.of("src/**"), List.of(), List.of()),
                                obligations()),
                        "DRAFT",
                        "APPROVED");
        KnowledgeMatch.Candidate unapproved =
                withWorkflow(
                        candidate(
                                "unapproved",
                                KnowledgeEnforcement.REQUIRED,
                                "CURRENT",
                                new KnowledgeScope(List.of("src/**"), List.of(), List.of()),
                                obligations()),
                        "PUBLISHED",
                        "UNREVIEWED");

        TaskContextMatcher.TaskContextResult result =
                matcher.match(
                        input(
                                List.of(reference, draft, unapproved),
                                List.of(
                                        new KnowledgeMatch.RetrievalReference(
                                                reference.knowledgeId(),
                                                "KNOWLEDGE_VECTOR",
                                                "仅语义相似"),
                                        new KnowledgeMatch.RetrievalReference(
                                                draft.knowledgeId(), "KEYWORD", "draft recall")),
                                true));

        assertThat(result.applicableKnowledge()).isEmpty();
        assertThat(result.referenceCandidates())
                .singleElement()
                .satisfies(
                        candidate -> {
                            assertThat(candidate.knowledgeId()).isEqualTo(reference.knowledgeId());
                            assertThat(candidate.retrievalSource()).isEqualTo("KNOWLEDGE_VECTOR");
                            assertThat(candidate.detail()).isEqualTo("仅语义相似");
                        });
        assertThat(result.requiredTests()).isEmpty();
        assertThat(result.requiredApprovals()).isEmpty();
    }

    @Test
    void insufficientFactsBecomeUnknownInsteadOfRuleViolations() {
        KnowledgeMatch.Candidate moduleRule =
                candidate(
                        "module rule",
                        KnowledgeEnforcement.REQUIRED,
                        "CURRENT",
                        new KnowledgeScope(List.of(), List.of(), List.of("payments")),
                        obligations());
        KnowledgeMatch.Candidate unverified =
                candidate(
                        "unverified path rule",
                        KnowledgeEnforcement.ADVISORY,
                        "UNVERIFIED",
                        new KnowledgeScope(List.of("src/refund/**"), List.of(), List.of()),
                        KnowledgeObligations.empty());
        RepositoryChange limited =
                new RepositoryChange(
                        change().source(),
                        change().baseCommit(),
                        change().headCommit(),
                        null,
                        true,
                        change().changes(),
                        List.of(new RepositoryChange.Limitation("PATCH_LIMIT", "patch truncated")));
        ChangedSymbolResolver.ResolutionResult symbols =
                new ChangedSymbolResolver.ResolutionResult(
                        changedSymbols().symbols(),
                        List.of(
                                new ChangedSymbolResolver.ResolutionUnknown(
                                        "SOURCE_UNREADABLE",
                                        "src/refund/RefundService.java",
                                        0,
                                        "cannot decode")));
        TaskContextMatcher.MatchInput input =
                new TaskContextMatcher.MatchInput(
                        repositoryId,
                        snapshotId,
                        limited,
                        symbols,
                        List.of(moduleRule, unverified),
                        List.of(),
                        Map.of(),
                        false,
                        Map.of());

        TaskContextMatcher.TaskContextResult result = matcher.match(input);

        assertThat(result.applicableKnowledge()).isEmpty();
        assertThat(result.requiredTests()).isEmpty();
        assertThat(result.unknowns())
                .extracting(finding -> finding.unknownReason().code())
                .contains(
                        "PATCH_LIMIT",
                        "SOURCE_UNREADABLE",
                        "MODULE_GRAPH_UNAVAILABLE",
                        "KNOWLEDGE_SOURCE_UNVERIFIED");
        assertThat(result.unknowns())
                .allSatisfy(
                        finding -> {
                            assertThat(finding.kind())
                                    .isEqualTo(TaskReviewFinding.FindingKind.UNKNOWN);
                            assertThat(finding.unknownReason()).isNotNull();
                            assertThat(finding.evidence()).isEmpty();
                            assertThat(finding.sources()).isNotEmpty();
                        });
    }

    private TaskContextMatcher.MatchInput input(
            List<KnowledgeMatch.Candidate> knowledge,
            List<KnowledgeMatch.RetrievalReference> references,
            boolean moduleGraphAvailable) {
        return new TaskContextMatcher.MatchInput(
                repositoryId,
                snapshotId,
                change(),
                changedSymbols(),
                knowledge,
                references,
                Map.of("src/refund/RefundService.java", Set.of("payments")),
                moduleGraphAvailable,
                Map.of());
    }

    private RepositoryChange change() {
        return new RepositoryChange(
                GitChangeRequest.Source.COMMIT_RANGE,
                "a".repeat(40),
                "b".repeat(40),
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
    }

    private ChangedSymbolResolver.ResolutionResult changedSymbols() {
        return new ChangedSymbolResolver.ResolutionResult(
                List.of(
                        new ChangedSymbolResolver.ChangedSymbol(
                                "java:src/refund/RefundService.java:METHOD:approveRefund",
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
                                List.of(
                                        new ChangedSymbolResolver.Provenance(
                                                ChangedSymbolResolver.ProvenanceType.SOURCE_TEXT,
                                                repositoryId,
                                                snapshotId,
                                                "b".repeat(40),
                                                null,
                                                "src/refund/RefundService.java",
                                                8,
                                                14,
                                                ChangedSymbolResolver.Side.NEW,
                                                "source declaration")))),
                List.of());
    }

    private KnowledgeMatch.Candidate candidate(
            String title,
            KnowledgeEnforcement enforcement,
            String sourceStatus,
            KnowledgeScope scope,
            KnowledgeObligations obligations) {
        return new KnowledgeMatch.Candidate(
                UUID.randomUUID(),
                repositoryId,
                title,
                KnowledgeKind.BUSINESS_RULE,
                KnowledgeSeverity.WARNING,
                enforcement,
                UUID.randomUUID(),
                scope,
                obligations,
                1,
                "PUBLISHED",
                "APPROVED",
                sourceStatus,
                List.of());
    }

    private KnowledgeObligations obligations() {
        return new KnowledgeObligations(
                List.of("./mvnw test"), List.of(approverId), List.of("核对退款边界"));
    }

    private static KnowledgeMatch.Candidate withWorkflow(
            KnowledgeMatch.Candidate candidate, String publicationStatus, String reviewStatus) {
        return new KnowledgeMatch.Candidate(
                candidate.knowledgeId(),
                candidate.repositoryId(),
                candidate.title(),
                candidate.kind(),
                candidate.severity(),
                candidate.enforcement(),
                candidate.ownerAccountId(),
                candidate.scope(),
                candidate.obligations(),
                candidate.revision(),
                publicationStatus,
                reviewStatus,
                candidate.sourceVersionStatus(),
                candidate.codeReferences());
    }
}
