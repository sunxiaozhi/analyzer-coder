package com.analyzercoder.application.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeEvidenceContextServiceTest {
    @Test
    void returnsOnlyDirectBindingsAndKeepsTrustAndVersionFactsExplicit() {
        CodeRepositoryStore repositories = mock(CodeRepositoryStore.class);
        IntelligenceService intelligence = mock(IntelligenceService.class);
        TaskReviewService reviews = mock(TaskReviewService.class);
        CodeEvidenceContextService service =
                new CodeEvidenceContextService(repositories, intelligence, reviews);
        CodeRepository repository = repository();
        UUID repositoryId = repository.id().value();
        String filePath = "src/refund/RefundService.java";
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(intelligence.cards(repositoryId, true))
                .thenReturn(List.of(card(repository, filePath), card(repository, "src/Other.java")));
        when(reviews.references(repository.id(), filePath, 20))
                .thenReturn(new TaskReviewService.ReviewReferenceResult(List.of(), 3, false));

        CodeEvidenceContextService.CodeEvidenceContext result =
                service.context(repository.id(), filePath, "approveRefund", true);

        assertThat(result.snapshotId()).isEqualTo(repository.currentSnapshotId().value());
        assertThat(result.knowledgeReferences())
                .singleElement()
                .satisfies(
                        knowledge -> {
                            assertThat(knowledge.title()).isEqualTo("退款规则");
                            assertThat(knowledge.trusted()).isTrue();
                            assertThat(knowledge.bindings())
                                    .singleElement()
                                    .satisfies(
                                            binding -> {
                                                assertThat(binding.currentSnapshot()).isTrue();
                                                assertThat(binding.contentHash()).isEqualTo("hash");
                                            });
                        });
        assertThat(result.limitations()).containsExactly("DIRECT_KNOWLEDGE_BINDINGS_ONLY");
        assertThat(result.scannedReviewCount()).isEqualTo(3);
        verify(intelligence).cards(repositoryId, true);
    }

    private static IntelligenceService.KnowledgeCard card(
            CodeRepository repository, String filePath) {
        Instant now = Instant.now();
        return new IntelligenceService.KnowledgeCard(
                UUID.randomUUID(),
                repository.id().value(),
                "退款规则",
                "RULE",
                "退款必须审批",
                "退款必须审批",
                List.of("refund"),
                KnowledgeKind.BUSINESS_RULE,
                KnowledgeSeverity.CRITICAL,
                KnowledgeEnforcement.REQUIRED,
                UUID.randomUUID(),
                KnowledgeScope.empty(),
                KnowledgeObligations.empty(),
                repository.currentSnapshotId().value(),
                "verified",
                "PUBLISHED",
                2,
                now,
                now,
                repository.currentCommit(),
                "CURRENT",
                now,
                "APPROVED",
                UUID.randomUUID(),
                now,
                List.of(),
                List.of(
                        new IntelligenceService.CodeReference(
                                repository.id().value(),
                                UUID.randomUUID(),
                                repository.currentSnapshotId().value(),
                                filePath,
                                "approveRefund",
                                10,
                                20,
                                "hash",
                                false)));
    }

    private static CodeRepository repository() {
        Instant now = Instant.now();
        return new CodeRepository(
                CodeRepositoryId.newId(),
                "sample",
                Path.of("sample"),
                RepositorySourceType.LOCAL_GIT,
                "main",
                "a".repeat(40),
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
}
