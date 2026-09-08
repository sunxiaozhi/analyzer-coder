package com.analyzercoder.application.code;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 汇总当前文件的确定性知识匹配和不可变审查引用，供代码工作台展示。 */
@Service
public class CodeEvidenceContextService {
    private final CodeRepositoryStore repositories;
    private final IntelligenceService intelligence;
    private final TaskReviewService reviews;
    private final RepositoryGlobMatcher globMatcher;

    public CodeEvidenceContextService(
            CodeRepositoryStore repositories,
            IntelligenceService intelligence,
            TaskReviewService reviews,
            RepositoryGlobMatcher globMatcher) {
        this.repositories = repositories;
        this.intelligence = intelligence;
        this.reviews = reviews;
        this.globMatcher = globMatcher;
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
                        .map(
                                card ->
                                        knowledgeReference(
                                                repository,
                                                card,
                                                normalizedPath,
                                                normalizedSymbol))
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
                                "DETERMINISTIC_KNOWLEDGE_MATCHING_ONLY",
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

    private KnowledgeReference knowledgeReference(
            CodeRepository repository,
            IntelligenceService.KnowledgeCard card,
            String filePath,
            String symbol) {
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
        LinkedHashSet<ApplicabilityReason> applicability = new LinkedHashSet<>();
        if (!bindings.isEmpty()) {
            applicability.add(
                    new ApplicabilityReason(
                            "DIRECT_BINDING", filePath, "知识修订直接绑定到该文件的代码片段"));
        }
        card.scope().pathPatterns().forEach(
                rule -> {
                    try {
                        if (globMatcher.matches(rule, filePath)) {
                            applicability.add(
                                    new ApplicabilityReason(
                                            "PATH_SCOPE", rule, "文件路径命中知识卡片的适用范围"));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 无效的旧范围规则不能扩大适用结论；知识治理页负责修正该规则。
                    }
                });
        if (symbol != null) {
            card.scope().symbols().stream()
                    .filter(symbol::equals)
                    .forEach(
                            rule ->
                                    applicability.add(
                                            new ApplicabilityReason(
                                                    "SYMBOL_SCOPE",
                                                    rule,
                                                    "当前符号与知识卡片的适用符号精确一致")));
        }
        if (card.scope().repositoryIds().contains(repository.id().value())) {
            applicability.add(
                    new ApplicabilityReason(
                            "REPOSITORY_SCOPE",
                            repository.id().value().toString(),
                            "当前仓库位于知识卡片的显式仓库范围内"));
        }
        if (applicability.isEmpty()) {
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
                bindings,
                List.copyOf(applicability));
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
            List<CodeBinding> bindings,
            List<ApplicabilityReason> applicability) {
        public KnowledgeReference {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            applicability = applicability == null ? List.of() : List.copyOf(applicability);
        }
    }

    public record ApplicabilityReason(String kind, String rule, String detail) {}

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
