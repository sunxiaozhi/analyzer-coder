package com.analyzercoder.application.memory;

import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.review.ChangedSymbolResolver;
import com.analyzercoder.application.review.KnowledgeMatch;
import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 生成按真实性和工程约束排序、绑定当前版本的 Agent 任务上下文。 */
@Service
public class TaskContextService {
    private static final Pattern QUERY_TOKEN = Pattern.compile("[\\p{L}\\p{N}_.$/-]{2,80}");

    private final RegisterRepositoryUseCase repositories;
    private final CodeChunkStore chunks;
    private final IntelligenceService intelligence;
    private final TaskReviewService reviews;

    public TaskContextService(
            RegisterRepositoryUseCase repositories,
            CodeChunkStore chunks,
            IntelligenceService intelligence,
            TaskReviewService reviews) {
        this.repositories = repositories;
        this.chunks = chunks;
        this.intelligence = intelligence;
        this.reviews = reviews;
    }

    public TaskContext generate(
            CodeRepositoryId repositoryId,
            String task,
            UUID taskReviewId,
            Integer requestedItems,
            Integer requestedChars,
            Integer requestedTokens) {
        String normalizedTask = normalizeTask(task);
        int maxItems = bound(requestedItems, 16, 5, 40);
        int maxChars = bound(requestedChars, 16_000, 4_000, 60_000);
        Integer tokenBudget = requestedTokens == null ? null : bound(requestedTokens, 4_000, 500, 15_000);
        int effectiveChars = tokenBudget == null ? maxChars : Math.min(maxChars, tokenBudget * 4);
        CodeRepository repository = repositories.get(repositoryId);
        if (repository.currentSnapshotId() == null) {
            throw new TaskContextException("CURRENT_SNAPSHOT_REQUIRED", "仓库尚未发布可用的项目快照");
        }

        TaskReviewResult review = review(repository, normalizedTask, taskReviewId);
        Map<UUID, IntelligenceService.KnowledgeCard> currentCards = currentCards(repositoryId);
        List<RankedEntry> candidates = new ArrayList<>();
        if (review == null) {
            candidates.add(noReviewUnknown(repository));
        } else {
            appendReviewKnowledge(candidates, repository, review, currentCards);
            appendReviewUnknowns(candidates, review);
        }
        appendCodeFacts(candidates, repository, normalizedTask, review);
        appendRetrievalCandidates(candidates, repository, normalizedTask, review, currentCards);

        candidates.sort(
                Comparator.comparingInt(RankedEntry::priority)
                        .thenComparing(item -> item.entry().title(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(item -> item.entry().id().toString()));
        Selection selection = select(repository, normalizedTask, candidates, maxItems, effectiveChars);
        List<String> requiredTests =
                selection.entries().stream()
                        .flatMap(entry -> entry.requiredTests().stream())
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .toList();
        List<UUID> requiredApprovals =
                selection.entries().stream()
                        .flatMap(entry -> entry.requiredApproverAccountIds().stream())
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        List<ContextUnknown> unknowns =
                selection.entries().stream()
                        .filter(entry -> entry.type() == EntryType.UNKNOWN)
                        .map(entry -> new ContextUnknown(entry.unknownCode(), entry.content(), entry.sources()))
                        .toList();
        return new TaskContext(
                repository.id().value(),
                repository.name(),
                repository.currentSnapshotId().value(),
                repository.currentCommit(),
                normalizedTask,
                taskReviewId,
                selection.entries(),
                requiredTests,
                requiredApprovals,
                unknowns,
                selection.markdown(),
                new ContextBudget(
                        maxItems,
                        maxChars,
                        tokenBudget,
                        effectiveChars,
                        selection.entries().size(),
                        selection.markdown().length(),
                        Math.max(1, (selection.markdown().length() + 3) / 4),
                        selection.omittedEntries(),
                        selection.truncated()));
    }

    private TaskReviewResult review(
            CodeRepository repository, String task, UUID taskReviewId) {
        if (taskReviewId == null) {
            return null;
        }
        TaskReviewResult review = reviews.get(repository.id(), taskReviewId);
        if (review.status() != TaskReviewResult.Status.COMPLETED) {
            throw new TaskContextException("TASK_REVIEW_NOT_COMPLETED", "只有已完成的任务审查才能生成正式上下文");
        }
        if (!repository.currentSnapshotId().value().equals(review.snapshotId())) {
            throw new TaskContextException(
                    "TASK_REVIEW_SNAPSHOT_MISMATCH", "任务审查不属于当前发布快照，不能与当前代码混合");
        }
        if (!task.equals(normalizeTask(review.task()))) {
            throw new TaskContextException("TASK_REVIEW_TASK_MISMATCH", "任务描述与指定审查不一致");
        }
        return review;
    }

    private Map<UUID, IntelligenceService.KnowledgeCard> currentCards(
            CodeRepositoryId repositoryId) {
        List<IntelligenceService.KnowledgeCard> cards =
                intelligence.cards(repositoryId.value(), false);
        LinkedHashMap<UUID, IntelligenceService.KnowledgeCard> result = new LinkedHashMap<>();
        if (cards != null) {
            cards.forEach(card -> result.put(card.id(), card));
        }
        return result;
    }

    private static RankedEntry noReviewUnknown(CodeRepository repository) {
        String code = "TASK_REVIEW_REQUIRED_FOR_DETERMINISTIC_KNOWLEDGE";
        Provenance source =
                Provenance.unknown(
                        repository.id().value(),
                        null,
                        null,
                        code,
                        "未提供真实变更审查，只能返回代码事实和检索候选");
        return new RankedEntry(
                3,
                new ContextEntry(
                        source.id(),
                        EntryType.UNKNOWN,
                        "未提供真实变更事实",
                        source.detail(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(source),
                        code));
    }

    private static void appendReviewKnowledge(
            List<RankedEntry> target,
            CodeRepository repository,
            TaskReviewResult review,
            Map<UUID, IntelligenceService.KnowledgeCard> currentCards) {
        for (KnowledgeMatch match : review.applicableKnowledge()) {
            IntelligenceService.KnowledgeCard card = currentCards.get(match.knowledgeId());
            if (card == null || card.revision() != match.revision()) {
                target.add(changedKnowledgeUnknown(repository, match));
                continue;
            }
            target.add(
                    new RankedEntry(
                            knowledgePriority(match.severity(), match.enforcement()),
                            new ContextEntry(
                                    match.knowledgeId(),
                                    EntryType.VERIFIED_KNOWLEDGE,
                                    match.title(),
                                    truncate(card.content(), 2_000),
                                    match.severity(),
                                    match.enforcement(),
                                    match.knowledgeId(),
                                    match.revision(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    match.obligations().requiredTests(),
                                    match.obligations().requiredApproverAccountIds(),
                                    match.sources(),
                                    null)));
        }
        for (KnowledgeMatch stale : review.staleKnowledge()) {
            Provenance source =
                    Provenance.unknown(
                            repository.id().value(),
                            stale.knowledgeId(),
                            null,
                            "STALE_KNOWLEDGE_EXCLUDED",
                            "知识适用范围命中，但来源状态为 " + stale.sourceVersionStatus());
            target.add(
                    new RankedEntry(
                            3,
                            new ContextEntry(
                                    source.id(),
                                    EntryType.UNKNOWN,
                                    stale.title(),
                                    source.detail(),
                                    stale.severity(),
                                    stale.enforcement(),
                                    stale.knowledgeId(),
                                    stale.revision(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of(),
                                    List.of(),
                                    List.of(source),
                                    "STALE_KNOWLEDGE_EXCLUDED")));
        }
    }

    private static RankedEntry changedKnowledgeUnknown(
            CodeRepository repository, KnowledgeMatch match) {
        String code = "KNOWLEDGE_REVISION_CHANGED_AFTER_REVIEW";
        Provenance source =
                Provenance.unknown(
                        repository.id().value(),
                        match.knowledgeId(),
                        null,
                        code,
                        "审查中的知识修订已不是当前可用修订，未向 Agent 返回旧正文");
        return new RankedEntry(
                3,
                new ContextEntry(
                        source.id(),
                        EntryType.UNKNOWN,
                        match.title(),
                        source.detail(),
                        match.severity(),
                        match.enforcement(),
                        match.knowledgeId(),
                        match.revision(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(source),
                        code));
    }

    private static void appendReviewUnknowns(
            List<RankedEntry> target, TaskReviewResult review) {
        for (TaskReviewFinding finding : review.unknowns()) {
            TaskReviewFinding.UnknownReason reason = finding.unknownReason();
            target.add(
                    new RankedEntry(
                            3,
                            new ContextEntry(
                                    finding.sources().get(0).id(),
                                    EntryType.UNKNOWN,
                                    reason.code(),
                                    reason.detail(),
                                    null,
                                    null,
                                    reason.knowledgeId(),
                                    null,
                                    null,
                                    reason.filePath(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of(),
                                    List.of(),
                                    finding.sources(),
                                    reason.code())));
        }
    }

    private void appendCodeFacts(
            List<RankedEntry> target,
            CodeRepository repository,
            String task,
            TaskReviewResult review) {
        LinkedHashMap<UUID, CodeChunk> selected = new LinkedHashMap<>();
        if (review != null) {
            for (ChangedSymbolResolver.ChangedSymbol symbol : review.changedSymbols()) {
                chunks.findByRepositoryPath(repository.id(), symbol.filePath()).stream()
                        .filter(chunk -> sameSnapshot(repository, chunk))
                        .filter(chunk -> overlaps(symbol, chunk))
                        .findFirst()
                        .ifPresent(chunk -> selected.putIfAbsent(chunk.id().value(), chunk));
            }
        }
        for (String query : taskQueries(task)) {
            for (CodeChunk chunk : chunks.searchByRepositoryId(repository.id(), query, 8, 0)) {
                if (sameSnapshot(repository, chunk)
                        && (chunk.assetType() == RepositoryAssetType.CODE
                                || chunk.assetType() == RepositoryAssetType.CONFIG)) {
                    selected.putIfAbsent(chunk.id().value(), chunk);
                }
            }
        }
        selected.values().stream()
                .limit(20)
                .map(chunk -> new RankedEntry(5, codeEntry(repository, chunk)))
                .forEach(target::add);
    }

    private void appendRetrievalCandidates(
            List<RankedEntry> target,
            CodeRepository repository,
            String task,
            TaskReviewResult review,
            Map<UUID, IntelligenceService.KnowledgeCard> currentCards) {
        if (review != null) {
            for (KnowledgeMatch.ReferenceCandidate reference : review.referenceCandidates()) {
                IntelligenceService.KnowledgeCard card = currentCards.get(reference.knowledgeId());
                if (card != null) {
                    target.add(new RankedEntry(6, retrievalEntry(card, reference.provenance())));
                }
            }
            return;
        }
        List<IntelligenceService.KnowledgeReferenceHit> hits =
                intelligence.reviewKnowledgeReferences(repository.id().value(), task, 10);
        if (hits == null) {
            return;
        }
        for (IntelligenceService.KnowledgeReferenceHit hit : hits) {
            IntelligenceService.KnowledgeCard card = currentCards.get(hit.knowledgeId());
            if (card == null) {
                continue;
            }
            Provenance source =
                    Provenance.retrievalCandidate(
                            repository.id().value(),
                            card.id(),
                            card.revision(),
                            card.reviewStatus(),
                            hit.source(),
                            hit.detail());
            target.add(new RankedEntry(6, retrievalEntry(card, source)));
        }
    }

    private static ContextEntry retrievalEntry(
            IntelligenceService.KnowledgeCard card, Provenance provenance) {
        return new ContextEntry(
                provenance.id(),
                EntryType.RETRIEVAL_CANDIDATE,
                card.title(),
                truncate(card.content(), 1_200),
                card.severity(),
                card.enforcement(),
                card.id(),
                card.revision(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(provenance),
                null);
    }

    private static ContextEntry codeEntry(CodeRepository repository, CodeChunk chunk) {
        Provenance source =
                Provenance.codeFact(
                        repository.id().value(),
                        repository.currentSnapshotId().value(),
                        repository.currentCommit(),
                        null,
                        chunk.filePath(),
                        chunk.symbolName(),
                        chunk.symbolKind(),
                        chunk.startLine(),
                        chunk.endLine(),
                        chunk.contentHash(),
                        "当前发布快照中的代码片段");
        return new ContextEntry(
                chunk.id().value(),
                EntryType.CODE_FACT,
                chunk.symbolName() == null ? chunk.filePath() : chunk.symbolName(),
                truncate(chunk.content(), 1_600),
                null,
                null,
                null,
                null,
                chunk.id().value(),
                chunk.filePath(),
                chunk.symbolName(),
                chunk.startLine(),
                chunk.endLine(),
                chunk.contentHash(),
                List.of(),
                List.of(),
                List.of(source),
                null);
    }

    private static Selection select(
            CodeRepository repository,
            String task,
            List<RankedEntry> candidates,
            int maxItems,
            int maxChars) {
        String header = header(repository, task);
        StringBuilder markdown = new StringBuilder(header);
        List<ContextEntry> selected = new ArrayList<>();
        boolean truncated = false;
        for (RankedEntry candidate : candidates) {
            if (selected.size() >= maxItems) {
                truncated = true;
                break;
            }
            ContextEntry entry = candidate.entry();
            String section = section(selected.size() + 1, entry);
            int remaining = maxChars - markdown.length();
            if (remaining <= 180) {
                truncated = true;
                break;
            }
            if (section.length() > remaining) {
                entry = entry.withContent(truncate(entry.content(), Math.max(80, remaining - 220)));
                section = section(selected.size() + 1, entry);
                if (section.length() > remaining) {
                    truncated = true;
                    break;
                }
                truncated = true;
            }
            selected.add(entry);
            markdown.append(section);
            if (truncated) {
                break;
            }
        }
        int omitted = Math.max(0, candidates.size() - selected.size());
        if (omitted > 0 && markdown.length() + 46 <= maxChars) {
            markdown.append("\n> 已按预算省略 ").append(omitted).append(" 条较低优先级上下文。\n");
        }
        return new Selection(List.copyOf(selected), markdown.toString(), omitted, truncated || omitted > 0);
    }

    private static String header(CodeRepository repository, String task) {
        return new StringBuilder("# Agent Task Context\n\n")
                .append("- 项目：").append(repository.name())
                .append("\n- 任务：").append(task)
                .append("\n- Commit：").append(repository.currentCommit())
                .append("\n- Snapshot：").append(repository.currentSnapshotId().value())
                .append("\n\n> 来源类型表达事实边界，不表达概率；检索候选不能产生工程义务。\n")
                .toString();
    }

    private static String section(int index, ContextEntry entry) {
        StringBuilder result =
                new StringBuilder("\n## C")
                        .append(index)
                        .append(" · ")
                        .append(entry.type())
                        .append(" · ")
                        .append(entry.title())
                        .append("\n");
        if (entry.severity() != null || entry.enforcement() != null) {
            result.append("\n- 级别：").append(entry.severity())
                    .append(" / ").append(entry.enforcement()).append("\n");
        }
        if (entry.filePath() != null) {
            result.append("- 位置：").append(entry.filePath())
                    .append(lines(entry.startLine(), entry.endLine())).append("\n");
        }
        if (!entry.requiredTests().isEmpty()) {
            result.append("- 必须测试：").append(String.join("；", entry.requiredTests())).append("\n");
        }
        if (!entry.requiredApproverAccountIds().isEmpty()) {
            result.append("- 必须审批：")
                    .append(entry.requiredApproverAccountIds().stream().map(UUID::toString).toList())
                    .append("\n");
        }
        result.append("\n```text\n").append(entry.content()).append("\n```\n");
        return result.toString();
    }

    private static int knowledgePriority(
            KnowledgeSeverity severity, KnowledgeEnforcement enforcement) {
        if (enforcement != KnowledgeEnforcement.REQUIRED) {
            return 4;
        }
        if (severity == KnowledgeSeverity.CRITICAL) {
            return 0;
        }
        if (severity == KnowledgeSeverity.WARNING) {
            return 1;
        }
        return 2;
    }

    private static boolean sameSnapshot(CodeRepository repository, CodeChunk chunk) {
        return chunk.snapshotId().equals(repository.currentSnapshotId())
                && Objects.equals(chunk.commitSha(), repository.currentCommit());
    }

    private static boolean overlaps(
            ChangedSymbolResolver.ChangedSymbol symbol, CodeChunk chunk) {
        if (symbol.name() != null && symbol.name().equals(chunk.symbolName())) {
            return true;
        }
        int symbolStart = symbol.declarationStartLine();
        int symbolEnd = symbol.declarationEndLine();
        int chunkStart = chunk.startLine() == null ? 1 : chunk.startLine();
        int chunkEnd = chunk.endLine() == null ? chunkStart : chunk.endLine();
        return symbolStart <= chunkEnd && chunkStart <= symbolEnd;
    }

    private static List<String> taskQueries(String task) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(task);
        Matcher matcher = QUERY_TOKEN.matcher(task);
        while (matcher.find() && result.size() < 8) {
            String token = matcher.group();
            if (token.length() <= 40) {
                result.add(token);
            }
        }
        return List.copyOf(result);
    }

    private static String truncate(String value, int limit) {
        String safe = value == null ? "" : value;
        return safe.length() <= limit ? safe : safe.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static String lines(Integer start, Integer end) {
        if (start == null) return "";
        return ":" + start + (end == null || end.equals(start) ? "" : "-" + end);
    }

    private static String normalizeTask(String task) {
        String normalized = task == null ? "" : task.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) throw new IllegalArgumentException("任务描述不能为空");
        if (normalized.length() > 1_000) throw new IllegalArgumentException("任务描述不能超过 1000 个字符");
        return normalized;
    }

    private static int bound(Integer value, int fallback, int minimum, int maximum) {
        int resolved = value == null ? fallback : value;
        return Math.max(minimum, Math.min(maximum, resolved));
    }

    public record TaskContext(
            UUID repositoryId,
            String repositoryName,
            UUID snapshotId,
            String commitSha,
            String task,
            UUID taskReviewId,
            List<ContextEntry> entries,
            List<String> requiredTests,
            List<UUID> requiredApprovals,
            List<ContextUnknown> unknowns,
            String markdown,
            ContextBudget budget) {}

    public record ContextEntry(
            UUID id,
            EntryType type,
            String title,
            String content,
            KnowledgeSeverity severity,
            KnowledgeEnforcement enforcement,
            UUID knowledgeId,
            Integer knowledgeRevision,
            UUID chunkId,
            String filePath,
            String symbolName,
            Integer startLine,
            Integer endLine,
            String contentHash,
            List<String> requiredTests,
            List<UUID> requiredApproverAccountIds,
            List<Provenance> sources,
            String unknownCode) {
        public ContextEntry {
            requiredTests = requiredTests == null ? List.of() : List.copyOf(requiredTests);
            requiredApproverAccountIds =
                    requiredApproverAccountIds == null
                            ? List.of()
                            : List.copyOf(requiredApproverAccountIds);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        ContextEntry withContent(String nextContent) {
            return new ContextEntry(
                    id, type, title, nextContent, severity, enforcement, knowledgeId,
                    knowledgeRevision, chunkId, filePath, symbolName, startLine, endLine,
                    contentHash, requiredTests, requiredApproverAccountIds, sources, unknownCode);
        }
    }

    public enum EntryType {
        VERIFIED_KNOWLEDGE,
        CODE_FACT,
        RETRIEVAL_CANDIDATE,
        UNKNOWN
    }

    public record ContextUnknown(String code, String detail, List<Provenance> sources) {}

    public record ContextBudget(
            int maxItems,
            int maxChars,
            Integer maxTokens,
            int effectiveMaxChars,
            int selectedItems,
            int usedChars,
            int estimatedTokens,
            int omittedItems,
            boolean truncated) {}

    private record RankedEntry(int priority, ContextEntry entry) {}

    private record Selection(
            List<ContextEntry> entries,
            String markdown,
            int omittedEntries,
            boolean truncated) {}
}
