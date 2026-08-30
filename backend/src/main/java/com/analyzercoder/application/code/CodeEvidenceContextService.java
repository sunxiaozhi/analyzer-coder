package com.analyzercoder.application.code;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 汇总当前文件的直接知识绑定和不可变审查引用，供代码工作台展示。 */
@Service
public class CodeEvidenceContextService {
    private final CodeRepositoryStore repositories;
    private final IntelligenceService intelligence;
    private final TaskReviewService reviews;

    public CodeEvidenceContextService(
            CodeRepositoryStore repositories,
            IntelligenceService intelligence,
            TaskReviewService reviews) {
        this.repositories = repositories;
        this.intelligence = intelligence;
        this.reviews = reviews;
    }

    public CodeEvidenceContext context(
            CodeRepositoryId repositoryId,
            String filePath,
            String symbol,
            boolean includeDraftKnowledge) {
        CodeRepository repository =
                repositories
                        .findById(repositoryId)
                        .orElseThrow(() -> new IllegalArgumentException("代码仓库不存在"));
        String normalizedPath = normalizeFilePath(filePath);
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim();
        List<KnowledgeReference> knowledge =
                intelligence.cards(repositoryId.value(), includeDraftKnowledge).stream()
                        .map(card -> knowledgeReference(repository, card, normalizedPath))
                        .filter(Objects::nonNull)
                        .sorted(
                                Comparator.comparing(KnowledgeReference::trusted)
                                        .reversed()
                                        .thenComparing(KnowledgeReference::title))
                        .toList();
        TaskReviewService.ReviewReferenceResult reviewReferences =
                reviews.references(repositoryId, normalizedPath, 20);
        List<String> limitations =
                java.util.stream.Stream.of(
                                "DIRECT_KNOWLEDGE_BINDINGS_ONLY",
                                normalizedSymbol == null ? "SYMBOL_REQUIRED_FOR_CODEGRAPH" : null,
                                reviewReferences.historyTruncated()
                                        ? "REVIEW_HISTORY_TRUNCATED"
                                        : null)
                        .filter(Objects::nonNull)
                        .toList();
        return new CodeEvidenceContext(
                repositoryId.value(),
                repository.currentSnapshotId() == null
                        ? null
                        : repository.currentSnapshotId().value(),
                repository.currentCommit(),
                normalizedPath,
                normalizedSymbol,
                knowledge,
                reviewReferences.references(),
                reviewReferences.scannedReviewCount(),
                limitations,
                Instant.now());
    }

    private static KnowledgeReference knowledgeReference(
            CodeRepository repository,
            IntelligenceService.KnowledgeCard card,
            String filePath) {
        List<CodeBinding> bindings =
                card.codeReferences().stream()
                        .filter(reference -> filePath.equals(normalizeNullablePath(reference.filePath())))
                        .map(
                                reference ->
                                        new CodeBinding(
                                                reference.chunkId(),
                                                reference.snapshotId(),
                                                reference.symbolName(),
                                                reference.startLine(),
                                                reference.endLine(),
                                                reference.contentHash(),
                                                reference.stale(),
                                                repository.currentSnapshotId() != null
                                                        && repository
                                                                .currentSnapshotId()
                                                                .value()
                                                                .equals(reference.snapshotId())))
                        .toList();
        if (bindings.isEmpty()) {
            return null;
        }
        boolean trusted =
                "PUBLISHED".equals(card.publicationStatus())
                        && "APPROVED".equals(card.reviewStatus())
                        && "CURRENT".equals(card.sourceVersionStatus())
                        && bindings.stream().noneMatch(CodeBinding::stale);
        return new KnowledgeReference(
                card.id(),
                card.title(),
                card.knowledgeKind().name(),
                card.severity().name(),
                card.enforcement().name(),
                card.ownerAccountId(),
                card.revision(),
                card.publicationStatus(),
                card.reviewStatus(),
                card.sourceVersionStatus(),
                trusted,
                bindings);
    }

    private static String normalizeFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        String normalized = filePath.trim().replace('\\', '/');
        if (normalized.length() > 1000
                || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:/.*")
                || List.of(normalized.split("/")).contains("..")) {
            throw new IllegalArgumentException("文件路径必须是安全的仓库相对路径");
        }
        return normalized;
    }

    private static String normalizeNullablePath(String filePath) {
        return filePath == null ? null : filePath.replace('\\', '/');
    }

    public record CodeEvidenceContext(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String filePath,
            String symbol,
            List<KnowledgeReference> knowledgeReferences,
            List<TaskReviewService.ReviewReference> reviewReferences,
            int scannedReviewCount,
            List<String> limitations,
            Instant generatedAt) {
        public CodeEvidenceContext {
            knowledgeReferences =
                    knowledgeReferences == null ? List.of() : List.copyOf(knowledgeReferences);
            reviewReferences =
                    reviewReferences == null ? List.of() : List.copyOf(reviewReferences);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record KnowledgeReference(
            UUID knowledgeId,
            String title,
            String kind,
            String severity,
            String enforcement,
            UUID ownerAccountId,
            int revision,
            String publicationStatus,
            String reviewStatus,
            String sourceVersionStatus,
            boolean trusted,
            List<CodeBinding> bindings) {
        public KnowledgeReference {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }
    }

    public record CodeBinding(
            UUID chunkId,
            UUID snapshotId,
            String symbolName,
            Integer startLine,
            Integer endLine,
            String contentHash,
            boolean stale,
            boolean currentSnapshot) {}
}
