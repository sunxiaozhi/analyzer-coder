package com.analyzercoder.application.knowledge;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeService;
import com.analyzercoder.application.review.ChangedSymbolResolver;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeDriftMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftCandidateRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftEventRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftReferenceRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在当前快照索引完成后，用真实 Diff 精准识别需要重新验证的工程知识。 */
@Service
public class KnowledgeDriftService {
    private static final int MAX_REASONS_PER_CARD = 100;
    private static final TypeReference<List<DriftReason>> REASON_LIST = new TypeReference<>() {};

    private final CodeRepositoryStore repositories;
    private final RepositoryChangeService changes;
    private final ChangedSymbolResolver symbols;
    private final CodeChunkStore chunks;
    private final KnowledgeDriftMapper mapper;
    private final RepositoryGlobMatcher globs;
    private final ObjectMapper json;

    public KnowledgeDriftService(
            CodeRepositoryStore repositories,
            RepositoryChangeService changes,
            ChangedSymbolResolver symbols,
            CodeChunkStore chunks,
            KnowledgeDriftMapper mapper,
            RepositoryGlobMatcher globs,
            ObjectMapper json) {
        this.repositories = repositories;
        this.changes = changes;
        this.symbols = symbols;
        this.chunks = chunks;
        this.mapper = mapper;
        this.globs = globs;
        this.json = json;
    }

    @Transactional
    public InspectionReport inspect(CodeRepository repository) {
        requirePublished(repository);
        List<KnowledgeDriftCandidateRow> candidates =
                mapper.candidates(repository.id().value()).stream()
                        .filter(candidate -> !Objects.equals(candidate.verifiedCommit(), repository.currentCommit()))
                        .toList();
        int suspect = 0;
        int unchanged = 0;
        int failed = 0;
        Map<String, List<KnowledgeDriftCandidateRow>> byCommit =
                candidates.stream()
                        .collect(
                                Collectors.groupingBy(
                                        KnowledgeDriftCandidateRow::verifiedCommit,
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        for (Map.Entry<String, List<KnowledgeDriftCandidateRow>> entry : byCommit.entrySet()) {
            try {
                RepositoryChange change =
                        changes.analyze(
                                GitChangeRequest.commitRange(
                                        repository.path(), entry.getKey(), repository.currentCommit()));
                ChangedSymbolResolver.ResolutionResult resolution = symbols.resolve(repository, change);
                for (KnowledgeDriftCandidateRow candidate : entry.getValue()) {
                    List<DriftReason> reasons = reasons(repository, candidate, change, resolution);
                    if (reasons.isEmpty()) {
                        mapper.touchCurrent(
                                repository.id().value(),
                                candidate.id(),
                                candidate.revision(),
                                candidate.verifiedCommit());
                        unchanged++;
                    } else if (markSuspect(repository, candidate, reasons)) {
                        suspect++;
                    }
                }
                if (change.partial()) {
                    failed += entry.getValue().size();
                }
            } catch (RuntimeException exception) {
                // 无法形成完整 Git 事实时不猜测具体知识受影响，索引结果显式降级并可在后续重试。
                failed += entry.getValue().size();
            }
        }
        return new InspectionReport(candidates.size(), suspect, unchanged, failed, failed > 0);
    }

    @Transactional
    public DriftEvent reviewSource(
            CodeRepositoryId repositoryId,
            UUID cardId,
            UUID actorId,
            SourceReviewRequest request) {
        if (request == null || request.expectedRevision() <= 0) {
            throw new IllegalArgumentException("expectedRevision 必须是正整数");
        }
        SourceReviewAction action = request.normalizedAction();
        String note = cleanNote(request.note());
        CodeRepository repository =
                repositories
                        .findById(repositoryId)
                        .orElseThrow(
                                () ->
                                        new KnowledgeDriftException(
                                                "REPOSITORY_NOT_FOUND", "代码仓库不存在"));
        requirePublished(repository);
        KnowledgeDriftCandidateRow candidate =
                mapper.findCandidate(repositoryId.value(), cardId);
        if (candidate == null) {
            throw new KnowledgeDriftException("KNOWLEDGE_CARD_NOT_FOUND", "知识卡片不存在");
        }
        String resultStatus =
                action == SourceReviewAction.CONFIRM_CURRENT ? "CURRENT" : "STALE";
        if (mapper.reviewSource(
                        repositoryId.value(),
                        cardId,
                        request.expectedRevision(),
                        resultStatus,
                        repository.currentCommit(),
                        repository.currentSnapshotId().value(),
                        note,
                        actorId)
                != 1) {
            throw new KnowledgeDriftException(
                    "KNOWLEDGE_REVISION_CONFLICT", "知识修订已变化，请刷新后重新核对");
        }
        DriftReason manualReason =
                new DriftReason(
                        action == SourceReviewAction.CONFIRM_CURRENT
                                ? ReasonKind.MANUAL_CONFIRMATION
                                : ReasonKind.MANUAL_STALE_DECISION,
                        action.name(),
                        null,
                        null,
                        null,
                        null,
                        action == SourceReviewAction.CONFIRM_CURRENT
                                ? "维护者已核对当前代码快照与知识内容"
                                : "维护者确认知识内容已不适用于当前代码");
        KnowledgeDriftEventRow row =
                eventRow(
                        repository,
                        candidate,
                        resultStatus,
                        action == SourceReviewAction.CONFIRM_CURRENT
                                ? "MANUAL_CONFIRM_CURRENT"
                                : "MANUAL_MARK_STALE",
                        List.of(manualReason),
                        note,
                        actorId);
        mapper.insertEvent(row);
        return event(row);
    }

    @Transactional(readOnly = true)
    public DriftEvent latestEvent(CodeRepositoryId repositoryId, UUID cardId) {
        KnowledgeDriftEventRow row = mapper.latestEvent(repositoryId.value(), cardId);
        return row == null ? null : event(row);
    }

    private List<DriftReason> reasons(
            CodeRepository repository,
            KnowledgeDriftCandidateRow candidate,
            RepositoryChange change,
            ChangedSymbolResolver.ResolutionResult resolution) {
        LinkedHashSet<DriftReason> result = new LinkedHashSet<>();
        List<KnowledgeDriftReferenceRow> references =
                mapper.references(repository.id().value(), candidate.id(), candidate.revision());
        Set<String> affectedPaths = affectedPaths(change);
        for (KnowledgeDriftReferenceRow reference : references) {
            if (!affectedPaths.contains(reference.filePath())) {
                continue;
            }
            boolean hashStillExists =
                    chunks.findByRepositoryPath(repository.id(), reference.filePath()).stream()
                            .anyMatch(chunk -> Objects.equals(reference.contentHash(), chunk.contentHash()));
            if (!hashStillExists) {
                RepositoryChange.FileChange file = changeFor(change, reference.filePath());
                result.add(
                        new DriftReason(
                                ReasonKind.CODE_REFERENCE_HASH_CHANGED,
                                reference.contentHash(),
                                reference.filePath(),
                                reference.startLine(),
                                reference.endLine(),
                                file == null ? null : file.type().name(),
                                "知识绑定代码内容在当前快照中已不存在相同哈希"));
            }
        }

        KnowledgeScope scope = readScope(candidate.scopePayload());
        for (String rule : scope.pathPatterns()) {
            for (RepositoryChange.FileChange file : change.changes()) {
                for (String path : paths(file)) {
                    if (globs.matches(rule, path)) {
                        result.add(
                                new DriftReason(
                                        ReasonKind.PATH_SCOPE_MATCHED,
                                        rule,
                                        path,
                                        firstLine(file),
                                        lastLine(file),
                                        file.type().name(),
                                        "真实 Git 变更路径命中知识 Scope"));
                    }
                }
            }
        }

        Set<String> symbolRules = new LinkedHashSet<>(scope.symbols());
        resolution.symbols().stream()
                .filter(symbol -> symbol.resolution() != ChangedSymbolResolver.Resolution.FILE_LEVEL)
                .filter(symbol -> symbolRules.contains(symbol.name()) || symbolRules.contains(symbol.symbolId()))
                .forEach(
                        symbol ->
                                result.add(
                                        new DriftReason(
                                                ReasonKind.SYMBOL_SCOPE_MATCHED,
                                                symbolRules.contains(symbol.symbolId())
                                                        ? symbol.symbolId()
                                                        : symbol.name(),
                                                symbol.filePath(),
                                                symbol.declarationStartLine(),
                                                symbol.declarationEndLine(),
                                                symbol.changeType().name(),
                                                "真实改动符号与知识 Scope 精确一致")));
        return result.stream().limit(MAX_REASONS_PER_CARD).toList();
    }

    private boolean markSuspect(
            CodeRepository repository,
            KnowledgeDriftCandidateRow candidate,
            List<DriftReason> reasons) {
        if (mapper.markSuspect(
                        repository.id().value(),
                        candidate.id(),
                        candidate.revision(),
                        candidate.verifiedCommit())
                != 1) {
            return false;
        }
        mapper.insertEvent(
                eventRow(
                        repository,
                        candidate,
                        "SUSPECT",
                        "AUTOMATIC_DIFF",
                        reasons,
                        "代码变化命中知识适用范围，等待人工重新验证",
                        null));
        return true;
    }

    private KnowledgeDriftEventRow eventRow(
            CodeRepository repository,
            KnowledgeDriftCandidateRow candidate,
            String resultStatus,
            String triggerType,
            List<DriftReason> reasons,
            String note,
            UUID actorId) {
        return new KnowledgeDriftEventRow(
                UUID.randomUUID(),
                repository.id().value(),
                candidate.id(),
                candidate.revision(),
                candidate.lastVerifiedSnapshotId(),
                repository.currentSnapshotId().value(),
                candidate.verifiedCommit(),
                repository.currentCommit(),
                candidate.sourceVersionStatus(),
                resultStatus,
                triggerType,
                writeReasons(reasons),
                note,
                actorId,
                Instant.now());
    }

    private DriftEvent event(KnowledgeDriftEventRow row) {
        try {
            return new DriftEvent(
                    row.id(),
                    row.repositoryId(),
                    row.cardId(),
                    row.cardRevision(),
                    row.fromSnapshotId(),
                    row.toSnapshotId(),
                    row.fromCommit(),
                    row.toCommit(),
                    row.previousStatus(),
                    row.resultStatus(),
                    row.triggerType(),
                    json.readValue(row.reasonsPayload(), REASON_LIST),
                    row.note(),
                    row.actorId(),
                    row.createdAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识漂移审计数据无法读取", exception);
        }
    }

    private KnowledgeScope readScope(String payload) {
        if (payload == null || payload.isBlank()) {
            return KnowledgeScope.empty();
        }
        try {
            return json.readValue(payload, KnowledgeScope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识适用范围无法读取", exception);
        }
    }

    private String writeReasons(List<DriftReason> reasons) {
        try {
            return json.writeValueAsString(reasons);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识漂移证据无法保存", exception);
        }
    }

    private static Set<String> affectedPaths(RepositoryChange change) {
        return change.changes().stream()
                .flatMap(file -> paths(file).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> paths(RepositoryChange.FileChange file) {
        List<String> result = new ArrayList<>(2);
        if (file.oldPath() != null) result.add(file.oldPath());
        if (file.newPath() != null && !Objects.equals(file.oldPath(), file.newPath())) {
            result.add(file.newPath());
        }
        return result;
    }

    private static RepositoryChange.FileChange changeFor(
            RepositoryChange change, String path) {
        return change.changes().stream()
                .filter(file -> paths(file).contains(path))
                .findFirst()
                .orElse(null);
    }

    private static Integer firstLine(RepositoryChange.FileChange file) {
        if (file.hunks().isEmpty()) return null;
        RepositoryChange.Hunk hunk = file.hunks().get(0);
        return file.newPath() == null ? hunk.oldStart() : hunk.newStart();
    }

    private static Integer lastLine(RepositoryChange.FileChange file) {
        if (file.hunks().isEmpty()) return null;
        RepositoryChange.Hunk hunk = file.hunks().get(0);
        int start = file.newPath() == null ? hunk.oldStart() : hunk.newStart();
        int count = file.newPath() == null ? hunk.oldCount() : hunk.newCount();
        return Math.max(1, start) + Math.max(1, count) - 1;
    }

    private static String cleanNote(String value) {
        String note = value == null ? "" : value.trim();
        if (note.isEmpty() || note.length() > 1_000) {
            throw new IllegalArgumentException("复核说明长度必须为 1 到 1000 个字符");
        }
        return note;
    }

    private static void requirePublished(CodeRepository repository) {
        if (repository == null
                || repository.currentSnapshotId() == null
                || repository.currentCommit() == null) {
            throw new KnowledgeDriftException(
                    "CURRENT_SNAPSHOT_REQUIRED", "仓库尚未发布可用于知识复核的代码快照");
        }
    }

    public enum ReasonKind {
        CODE_REFERENCE_HASH_CHANGED,
        PATH_SCOPE_MATCHED,
        SYMBOL_SCOPE_MATCHED,
        MANUAL_CONFIRMATION,
        MANUAL_STALE_DECISION
    }

    public enum SourceReviewAction {
        CONFIRM_CURRENT,
        MARK_STALE
    }

    public record SourceReviewRequest(String action, int expectedRevision, String note) {
        SourceReviewAction normalizedAction() {
            try {
                return SourceReviewAction.valueOf(
                        action == null ? "" : action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("source review action 无效");
            }
        }
    }

    public record DriftReason(
            ReasonKind kind,
            String rule,
            String filePath,
            Integer startLine,
            Integer endLine,
            String changeType,
            String detail) {}

    public record DriftEvent(
            UUID id,
            UUID repositoryId,
            UUID cardId,
            int cardRevision,
            UUID fromSnapshotId,
            UUID toSnapshotId,
            String fromCommit,
            String toCommit,
            String previousStatus,
            String resultStatus,
            String triggerType,
            List<DriftReason> reasons,
            String note,
            UUID actorId,
            Instant createdAt) {}

    public record InspectionReport(
            int checkedCards,
            int suspectCards,
            int unchangedCards,
            int failedCards,
            boolean degraded) {}
}
