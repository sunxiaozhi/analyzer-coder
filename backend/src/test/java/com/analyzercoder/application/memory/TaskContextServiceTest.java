package com.analyzercoder.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.review.KnowledgeMatch;
import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.chunk.InMemoryCodeChunkStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskContextServiceTest {

    @Test
    void prioritizesVerifiedRequiredKnowledgeUnknownsAndCurrentCodeFacts() {
        Fixture fixture = fixture();
        UUID reviewId = UUID.randomUUID();
        UUID knowledgeId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        KnowledgeObligations obligations =
                new KnowledgeObligations(
                        List.of("./mvnw test"), List.of(approverId), List.of("保持事务边界"));
        IntelligenceService.KnowledgeCard card =
                card(fixture.repository(), knowledgeId, obligations, "必须保留事务边界。".repeat(200));
        KnowledgeMatch match =
                new KnowledgeMatch(
                        knowledgeId,
                        card.title(),
                        KnowledgeKind.BUSINESS_RULE,
                        KnowledgeSeverity.CRITICAL,
                        KnowledgeEnforcement.REQUIRED,
                        card.ownerAccountId(),
                        1,
                        "CURRENT",
                        obligations,
                        List.of(reason(fixture.repository())),
                        List.of(
                                Provenance.verifiedKnowledge(
                                        fixture.repository().id().value(),
                                        knowledgeId,
                                        1,
                                        "APPROVED",
                                        "reviewed knowledge"),
                                Provenance.gitFact(
                                        fixture.repository().id().value(),
                                        fixture.repository().currentSnapshotId().value(),
                                        fixture.repository().currentCommit(),
                                        null,
                                        "src/OrderService.java",
                                        "changed path")));
        TaskReviewFinding unknown = unknown(fixture.repository(), "DYNAMIC_BEHAVIOR");
        TaskReviewResult review =
                review(
                        fixture.repository(),
                        reviewId,
                        List.of(match),
                        List.of(unknown));
        when(fixture.reviews().get(fixture.repository().id(), reviewId)).thenReturn(review);
        when(fixture.intelligence().cards(fixture.repository().id().value(), false))
                .thenReturn(List.of(card));

        TaskContextService.TaskContext result =
                fixture.service().generate(
                        fixture.repository().id(),
                        "修改 OrderService 创建订单流程",
                        reviewId,
                        8,
                        6_000,
                        1_000);

        assertThat(result.snapshotId())
                .isEqualTo(fixture.repository().currentSnapshotId().value());
        assertThat(result.commitSha()).isEqualTo(fixture.repository().currentCommit());
        assertThat(result.entries())
                .extracting(TaskContextService.ContextEntry::type)
                .containsExactly(
                        TaskContextService.EntryType.VERIFIED_KNOWLEDGE,
                        TaskContextService.EntryType.UNKNOWN,
                        TaskContextService.EntryType.CODE_FACT);
        assertThat(result.requiredTests()).containsExactly("./mvnw test");
        assertThat(result.requiredApprovals()).containsExactly(approverId);
        assertThat(result.unknowns()).extracting(TaskContextService.ContextUnknown::code)
                .containsExactly("DYNAMIC_BEHAVIOR");
        assertThat(result.budget().usedChars()).isLessThanOrEqualTo(4_000);
        assertThat(result.markdown())
                .contains("CRITICAL / REQUIRED", "./mvnw test", "src/OrderService.java");
    }

    @Test
    void withoutReviewLabelsKnowledgeAsRetrievalCandidateAndEmitsUnknownBoundary() {
        Fixture fixture = fixture();
        UUID knowledgeId = UUID.randomUUID();
        IntelligenceService.KnowledgeCard card =
                card(
                        fixture.repository(),
                        knowledgeId,
                        KnowledgeObligations.empty(),
                        "可参考的订单约定");
        when(fixture.intelligence().cards(fixture.repository().id().value(), false))
                .thenReturn(List.of(card));
        when(fixture.intelligence().reviewKnowledgeReferences(
                        fixture.repository().id().value(),
                        "修改 OrderService 创建订单流程",
                        10))
                .thenReturn(
                        List.of(
                                new IntelligenceService.KnowledgeReferenceHit(
                                        knowledgeId,
                                        "KNOWLEDGE_KEYWORD",
                                        "关键词召回，未命中确定性范围")));

        TaskContextService.TaskContext result =
                fixture.service().generate(
                        fixture.repository().id(),
                        "修改 OrderService 创建订单流程",
                        null,
                        10,
                        8_000,
                        null);

        assertThat(result.entries())
                .extracting(TaskContextService.ContextEntry::type)
                .containsExactly(
                        TaskContextService.EntryType.UNKNOWN,
                        TaskContextService.EntryType.CODE_FACT,
                        TaskContextService.EntryType.RETRIEVAL_CANDIDATE);
        TaskContextService.ContextEntry retrieval = result.entries().get(2);
        assertThat(retrieval.requiredTests()).isEmpty();
        assertThat(retrieval.sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceType().name()).isEqualTo("RETRIEVAL_CANDIDATE");
            assertThat(source.retrievalChannel()).isEqualTo("KNOWLEDGE_KEYWORD");
        });
        assertThat(result.requiredTests()).isEmpty();
    }

    @Test
    void refusesToMixHistoricalReviewWithCurrentSnapshot() {
        Fixture fixture = fixture();
        UUID reviewId = UUID.randomUUID();
        TaskReviewResult historical =
                new TaskReviewResult(
                        reviewId,
                        TaskReviewResult.Status.COMPLETED,
                        fixture.repository().id().value(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "修改 OrderService 创建订单流程",
                        "COMMIT_RANGE",
                        "base",
                        "head",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        Instant.now(),
                        Instant.now());
        when(fixture.reviews().get(fixture.repository().id(), reviewId)).thenReturn(historical);
        when(fixture.intelligence().cards(fixture.repository().id().value(), false))
                .thenReturn(List.of());

        assertThatThrownBy(
                        () ->
                                fixture.service().generate(
                                        fixture.repository().id(),
                                        "修改 OrderService 创建订单流程",
                                        reviewId,
                                        10,
                                        8_000,
                                        null))
                .isInstanceOf(TaskContextException.class)
                .extracting("code")
                .isEqualTo("TASK_REVIEW_SNAPSHOT_MISMATCH");
    }

    private static Fixture fixture() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        TaskReviewService reviews = mock(TaskReviewService.class);
        InMemoryCodeChunkStore chunks = new InMemoryCodeChunkStore();
        CodeRepository repository = repository();
        when(repositories.get(repository.id())).thenReturn(repository);
        chunks.replaceRepositoryChunks(
                repository.id(),
                List.of(
                        CodeChunk.fileChunk(
                                repository.id(),
                                repository.currentSnapshotId(),
                                repository.currentCommit(),
                                "src/OrderService.java",
                                "java",
                                RepositoryAssetType.CODE,
                                1,
                                1,
                                "class OrderService { void createOrder() {} }")));
        return new Fixture(
                repository,
                intelligence,
                reviews,
                new TaskContextService(repositories, chunks, intelligence, reviews));
    }

    private static IntelligenceService.KnowledgeCard card(
            CodeRepository repository,
            UUID id,
            KnowledgeObligations obligations,
            String content) {
        Instant now = Instant.now();
        return new IntelligenceService.KnowledgeCard(
                id,
                repository.id().value(),
                "订单事务规则",
                "RULE",
                content,
                content,
                List.of("order"),
                KnowledgeKind.BUSINESS_RULE,
                KnowledgeSeverity.CRITICAL,
                KnowledgeEnforcement.REQUIRED,
                UUID.randomUUID(),
                KnowledgeScope.empty(),
                obligations,
                repository.currentSnapshotId().value(),
                "verified",
                "PUBLISHED",
                1,
                now,
                now,
                repository.currentCommit(),
                "CURRENT",
                now,
                "APPROVED",
                UUID.randomUUID(),
                now,
                List.of(),
                List.of());
    }

    private static com.analyzercoder.application.review.KnowledgeMatchReason reason(
            CodeRepository repository) {
        return new com.analyzercoder.application.review.KnowledgeMatchReason(
                com.analyzercoder.application.review.KnowledgeMatchReason.MatchKind.PATH_PATTERN,
                "src/**",
                "src/OrderService.java",
                new com.analyzercoder.application.review.KnowledgeMatchReason.ScopeEvidence(
                        com.analyzercoder.application.review.KnowledgeMatchReason.EvidenceSource.GIT_FACT,
                        repository.id().value(),
                        repository.currentSnapshotId().value(),
                        repository.currentCommit(),
                        "src/OrderService.java",
                        null,
                        null,
                        null,
                        "path matched"));
    }

    private static TaskReviewFinding unknown(CodeRepository repository, String code) {
        Provenance source =
                Provenance.unknown(
                        repository.id().value(), null, null, code, "动态行为无法静态确认");
        return new TaskReviewFinding(
                TaskReviewFinding.FindingKind.UNKNOWN,
                code,
                "无法确定",
                TaskReviewFinding.FindingStatus.UNKNOWN,
                List.of(),
                List.of(),
                new TaskReviewFinding.UnknownReason(code, null, null, null, source.detail()),
                List.of(source));
    }

    private static TaskReviewResult review(
            CodeRepository repository,
            UUID reviewId,
            List<KnowledgeMatch> knowledge,
            List<TaskReviewFinding> unknowns) {
        Instant now = Instant.now();
        return new TaskReviewResult(
                reviewId,
                TaskReviewResult.Status.COMPLETED,
                repository.id().value(),
                repository.currentSnapshotId().value(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "修改 OrderService 创建订单流程",
                "COMMIT_RANGE",
                "base",
                "head",
                new RepositoryChange(
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "a".repeat(40),
                        repository.currentCommit(),
                        null,
                        false,
                        List.of(),
                        List.of()),
                List.of(),
                knowledge,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                unknowns,
                null,
                null,
                now,
                now);
    }

    private static CodeRepository repository() {
        Instant now = Instant.now();
        return new CodeRepository(
                CodeRepositoryId.newId(),
                "orders",
                Path.of("orders"),
                RepositorySourceType.LOCAL_GIT,
                "main",
                "abc123",
                "digest",
                false,
                RepositorySnapshotId.newId(),
                Path.of("snapshot"),
                Path.of("snapshot/.codegraph"),
                now,
                now,
                now,
                now);
    }

    private record Fixture(
            CodeRepository repository,
            IntelligenceService intelligence,
            TaskReviewService reviews,
            TaskContextService service) {}
}
