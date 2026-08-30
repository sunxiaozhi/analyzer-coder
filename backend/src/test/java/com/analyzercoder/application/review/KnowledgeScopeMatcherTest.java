package com.analyzercoder.application.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeScopeMatcherTest {
    private final KnowledgeScopeMatcher matcher =
            new KnowledgeScopeMatcher(new RepositoryGlobMatcher());
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();

    @Test
    void everyDeterministicMatchReturnsRuleTargetAndVersionedEvidence() {
        UUID chunkId = UUID.randomUUID();
        KnowledgeScope scope =
                new KnowledgeScope(
                        List.of("backend/src/**/refund/**"),
                        List.of("approveRefund"),
                        List.of("backend"));
        KnowledgeScopeMatcher.ChangeTarget target =
                target(
                        "backend/src/main/java/refund/RefundService.java",
                        Set.of("approveRefund"),
                        Set.of("backend"),
                        true,
                        "old-hash",
                        "new-hash");

        KnowledgeScopeMatcher.MatchResult result =
                matcher.match(
                        scope,
                        List.of(
                                new KnowledgeScopeMatcher.BoundCodeReference(
                                        chunkId,
                                        "backend/src/main/java/refund/RefundService.java",
                                        "approveRefund",
                                        "old-hash")),
                        target);

        assertThat(result.matched()).isTrue();
        assertThat(result.unknowns()).isEmpty();
        assertThat(result.reasons())
                .extracting(KnowledgeMatchReason::kind)
                .containsExactly(
                        KnowledgeMatchReason.MatchKind.CODE_REFERENCE,
                        KnowledgeMatchReason.MatchKind.PATH_PATTERN,
                        KnowledgeMatchReason.MatchKind.SYMBOL,
                        KnowledgeMatchReason.MatchKind.MODULE);
        assertThat(result.reasons())
                .allSatisfy(
                        reason -> {
                            assertThat(reason.rule()).isNotBlank();
                            assertThat(reason.target()).isNotBlank();
                            assertThat(reason.evidence().repositoryId()).isEqualTo(repositoryId);
                            assertThat(reason.evidence().snapshotId()).isEqualTo(snapshotId);
                            assertThat(reason.evidence().commitSha()).isEqualTo("commit-sha");
                            assertThat(reason.evidence().detail()).isNotBlank();
                        });
        assertThat(result.reasons().get(0).evidence().knowledgeChunkId()).isEqualTo(chunkId);
    }

    @Test
    void codeReferenceCanMatchChangedContentHashAcrossRename() {
        KnowledgeScopeMatcher.MatchResult result =
                matcher.match(
                        KnowledgeScope.empty(),
                        List.of(
                                new KnowledgeScopeMatcher.BoundCodeReference(
                                        UUID.randomUUID(),
                                        "legacy/location/RefundService.java",
                                        "approveRefund",
                                        "bound-hash")),
                        new KnowledgeScopeMatcher.ChangeTarget(
                                repositoryId,
                                snapshotId,
                                "commit-sha",
                                "old/RefundService.java",
                                "new/RefundService.java",
                                Set.of(),
                                Set.of(),
                                true,
                                "bound-hash",
                                "changed-hash"));

        assertThat(result.reasons()).hasSize(1);
        assertThat(result.reasons().get(0).kind())
                .isEqualTo(KnowledgeMatchReason.MatchKind.CODE_REFERENCE);
        assertThat(result.reasons().get(0).target()).isEqualTo("new/RefundService.java");
        assertThat(result.reasons().get(0).evidence().detail()).contains("内容哈希");
    }

    @Test
    void normalizesWindowsPathsInEveryEvidenceRecord() {
        KnowledgeScopeMatcher.MatchResult result =
                matcher.match(
                        new KnowledgeScope(
                                List.of("src/**"), List.of("approveRefund"), List.of("backend")),
                        List.of(),
                        target(
                                "src\\refund\\RefundService.java",
                                Set.of("approveRefund"),
                                Set.of("backend"),
                                true,
                                null,
                                null));

        assertThat(result.reasons()).hasSize(3);
        assertThat(result.reasons())
                .extracting(reason -> reason.evidence().filePath())
                .containsOnly("src/refund/RefundService.java");
    }

    @Test
    void invalidChangePathCannotProduceSymbolModuleOrHashMatches() {
        KnowledgeScopeMatcher.MatchResult result =
                matcher.match(
                        new KnowledgeScope(List.of(), List.of("approveRefund"), List.of("backend")),
                        List.of(
                                new KnowledgeScopeMatcher.BoundCodeReference(
                                        UUID.randomUUID(),
                                        "src/RefundService.java",
                                        "approveRefund",
                                        "bound-hash")),
                        target(
                                "C:\\outside\\RefundService.java",
                                Set.of("approveRefund"),
                                Set.of("backend"),
                                true,
                                "bound-hash",
                                "changed-hash"));

        assertThat(result.matched()).isFalse();
        assertThat(result.unknowns())
                .extracting(KnowledgeScopeMatcher.ScopeUnknown::code)
                .containsExactly("INVALID_CHANGE_PATH");
    }

    @Test
    void unavailableModuleGraphProducesUnknownInsteadOfInventingMatch() {
        KnowledgeScope scope =
                new KnowledgeScope(List.of("src/**"), List.of(), List.of("payments", "backend"));
        KnowledgeScopeMatcher.MatchResult result =
                matcher.match(
                        scope,
                        List.of(),
                        target("src/Payment.java", Set.of(), Set.of(), false, null, null));

        assertThat(result.reasons())
                .extracting(KnowledgeMatchReason::kind)
                .containsExactly(KnowledgeMatchReason.MatchKind.PATH_PATTERN);
        assertThat(result.unknowns())
                .extracting(KnowledgeScopeMatcher.ScopeUnknown::code)
                .containsExactly("MODULE_GRAPH_UNAVAILABLE", "MODULE_GRAPH_UNAVAILABLE");
        assertThat(result.unknowns())
                .extracting(KnowledgeScopeMatcher.ScopeUnknown::rule)
                .containsExactly("payments", "backend");
    }

    @Test
    void invalidLegacyRulesAndChangePathsBecomeStableUnknowns() {
        KnowledgeScopeMatcher.MatchResult result =
                matcher.match(
                        new KnowledgeScope(List.of("../outside/**"), List.of(), List.of()),
                        List.of(),
                        target("C:\\outside\\App.java", Set.of(), Set.of(), true, null, null));

        assertThat(result.matched()).isFalse();
        assertThat(result.unknowns())
                .extracting(KnowledgeScopeMatcher.ScopeUnknown::code)
                .containsExactly("INVALID_CHANGE_PATH", "INVALID_SCOPE_PATH");
    }

    @Test
    void matcherHasNoRetrievalCandidateEvidenceChannel() {
        assertThat(KnowledgeMatchReason.EvidenceSource.values())
                .containsExactly(
                        KnowledgeMatchReason.EvidenceSource.GIT_FACT,
                        KnowledgeMatchReason.EvidenceSource.CODE_FACT,
                        KnowledgeMatchReason.EvidenceSource.GRAPH_INFERENCE);
    }

    private KnowledgeScopeMatcher.ChangeTarget target(
            String path,
            Set<String> symbols,
            Set<String> modules,
            boolean moduleGraphAvailable,
            String oldHash,
            String newHash) {
        return new KnowledgeScopeMatcher.ChangeTarget(
                repositoryId,
                snapshotId,
                "commit-sha",
                path,
                path,
                symbols,
                modules,
                moduleGraphAvailable,
                oldHash,
                newHash);
    }
}
