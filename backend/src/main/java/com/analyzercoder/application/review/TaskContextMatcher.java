package com.analyzercoder.application.review;

import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 将真实改动与已发布、已审批知识做确定性匹配，并汇总测试、审批和未知项。 */
@Component
public class TaskContextMatcher {
    private final KnowledgeScopeMatcher scopeMatcher;

    public TaskContextMatcher(KnowledgeScopeMatcher scopeMatcher) {
        this.scopeMatcher = scopeMatcher;
    }

    public TaskContextResult match(MatchInput input) {
        Objects.requireNonNull(input, "input must not be null");
        if (input.repositoryId() == null) {
            throw new IllegalArgumentException("仓库 ID 不能为空");
        }
        if (input.change() == null) {
            throw new IllegalArgumentException("真实变更不能为空");
        }

        LinkedHashMap<UUID, KnowledgeMatch.Candidate> eligible = eligibleKnowledge(input);
        List<TaskReviewFinding> unknowns = new ArrayList<>();
        addChangeUnknowns(input, unknowns);
        if (input.snapshotId() == null) {
            unknowns.add(
                    unknown(
                            input.repositoryId(),
                            "SNAPSHOT_MISSING",
                            null,
                            null,
                            null,
                            "缺少当前发布快照，不能生成版本化知识命中"));
            return result(
                    List.of(),
                    references(input, eligible, Set.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    unknowns);
        }

        String commitSha = effectiveCommit(input.change());
        if (commitSha == null || commitSha.isBlank()) {
            unknowns.add(
                    unknown(
                            input.repositoryId(),
                            "CHANGE_COMMIT_MISSING",
                            null,
                            null,
                            null,
                            "真实变更缺少可验证的提交版本"));
            return result(
                    List.of(),
                    references(input, eligible, Set.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    unknowns);
        }

        List<KnowledgeMatch> applicable = new ArrayList<>();
        List<KnowledgeMatch> stale = new ArrayList<>();
        LinkedHashSet<UUID> deterministicMatches = new LinkedHashSet<>();
        for (KnowledgeMatch.Candidate knowledge : eligible.values()) {
            MatchAggregation aggregation = matchKnowledge(input, commitSha, knowledge);
            aggregation.unknowns().stream()
                    .map(item -> scopeUnknown(input.repositoryId(), knowledge.knowledgeId(), item))
                    .forEach(unknowns::add);
            if (aggregation.reasons().isEmpty()) {
                continue;
            }
            deterministicMatches.add(knowledge.knowledgeId());
            KnowledgeMatch match = knowledgeMatch(knowledge, aggregation.reasons());
            switch (normalizedStatus(knowledge.sourceVersionStatus())) {
                case "CURRENT" -> applicable.add(match);
                case "SUSPECT", "STALE" -> stale.add(match);
                default ->
                        unknowns.add(
                                unknown(
                                        input.repositoryId(),
                                        "KNOWLEDGE_SOURCE_UNVERIFIED",
                                        knowledge.knowledgeId(),
                                        firstEvidencePath(aggregation.reasons()),
                                        null,
                                        "确定性范围已命中，但知识没有 CURRENT/SUSPECT/STALE 版本状态"));
            }
        }

        applicable.sort(knowledgeOrder());
        stale.sort(knowledgeOrder());
        List<TaskReviewFinding> requiredTests = requiredTests(applicable);
        List<TaskReviewFinding> requiredApprovals = requiredApprovals(applicable);
        return result(
                applicable,
                references(input, eligible, deterministicMatches),
                requiredTests,
                requiredApprovals,
                stale,
                deduplicateUnknowns(unknowns));
    }

    private MatchAggregation matchKnowledge(
            MatchInput input, String commitSha, KnowledgeMatch.Candidate knowledge) {
        LinkedHashSet<KnowledgeMatchReason> reasons = new LinkedHashSet<>();
        LinkedHashSet<KnowledgeScopeMatcher.ScopeUnknown> unknowns = new LinkedHashSet<>();
        for (RepositoryChange.FileChange file : input.change().changes()) {
            KnowledgeScopeMatcher.ChangeTarget target =
                    target(input, commitSha, file, input.changedSymbols());
            KnowledgeScopeMatcher.MatchResult result =
                    scopeMatcher.match(knowledge.scope(), knowledge.codeReferences(), target);
            reasons.addAll(result.reasons());
            unknowns.addAll(result.unknowns());
        }
        return new MatchAggregation(List.copyOf(reasons), List.copyOf(unknowns));
    }

    private static KnowledgeScopeMatcher.ChangeTarget target(
            MatchInput input,
            String commitSha,
            RepositoryChange.FileChange file,
            ChangedSymbolResolver.ResolutionResult resolution) {
        Set<String> paths = paths(file);
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        if (resolution != null) {
            resolution.symbols().stream()
                    .filter(symbol -> paths.contains(symbol.filePath()))
                    .filter(
                            symbol ->
                                    symbol.resolution()
                                            != ChangedSymbolResolver.Resolution.FILE_LEVEL)
                    .map(ChangedSymbolResolver.ChangedSymbol::name)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(symbols::add);
        }
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        paths.forEach(path -> modules.addAll(input.modulesByPath().getOrDefault(path, Set.of())));
        ContentTransition hashes = transition(input, file);
        return new KnowledgeScopeMatcher.ChangeTarget(
                input.repositoryId(),
                input.snapshotId(),
                commitSha,
                file.oldPath(),
                file.newPath(),
                symbols,
                modules,
                input.moduleGraphAvailable(),
                hashes.oldContentHash(),
                hashes.newContentHash());
    }

    private static ContentTransition transition(
            MatchInput input, RepositoryChange.FileChange file) {
        String key = file.newPath() == null ? file.oldPath() : file.newPath();
        ContentTransition value = input.contentTransitions().get(key);
        return value == null ? ContentTransition.empty() : value;
    }

    private static LinkedHashMap<UUID, KnowledgeMatch.Candidate> eligibleKnowledge(
            MatchInput input) {
        LinkedHashMap<UUID, KnowledgeMatch.Candidate> result = new LinkedHashMap<>();
        for (KnowledgeMatch.Candidate candidate : input.knowledge()) {
            if (candidate == null
                    || candidate.knowledgeId() == null
                    || !input.repositoryId().equals(candidate.repositoryId())
                    || !"PUBLISHED".equals(normalizedStatus(candidate.publicationStatus()))
                    || !"APPROVED".equals(normalizedStatus(candidate.reviewStatus()))) {
                continue;
            }
            result.putIfAbsent(candidate.knowledgeId(), candidate);
        }
        return result;
    }

    private static List<TaskReviewFinding> requiredTests(List<KnowledgeMatch> matches) {
        LinkedHashMap<String, MutableObligation> obligations = new LinkedHashMap<>();
        for (KnowledgeMatch match : matches) {
            if (match.enforcement() != KnowledgeEnforcement.REQUIRED) {
                continue;
            }
            for (String test : match.obligations().requiredTests()) {
                if (test == null || test.isBlank()) {
                    continue;
                }
                obligations
                        .computeIfAbsent(test, ignored -> new MutableObligation(test))
                        .add(match);
            }
        }
        return obligations.values().stream()
                .map(
                        obligation ->
                                obligation.finding(
                                        TaskReviewFinding.FindingKind.REQUIRED_TEST,
                                        TaskReviewFinding.FindingStatus.REQUIRED_NOT_REPORTED,
                                        "必须执行测试"))
                .toList();
    }

    private static List<TaskReviewFinding> requiredApprovals(List<KnowledgeMatch> matches) {
        LinkedHashMap<UUID, MutableObligation> obligations = new LinkedHashMap<>();
        for (KnowledgeMatch match : matches) {
            if (match.enforcement() != KnowledgeEnforcement.REQUIRED) {
                continue;
            }
            for (UUID approver : match.obligations().requiredApproverAccountIds()) {
                if (approver != null) {
                    obligations
                            .computeIfAbsent(approver, id -> new MutableObligation(id.toString()))
                            .add(match);
                }
            }
        }
        return obligations.values().stream()
                .map(
                        obligation ->
                                obligation.finding(
                                        TaskReviewFinding.FindingKind.REQUIRED_APPROVAL,
                                        TaskReviewFinding.FindingStatus.REQUIRED,
                                        "需要审批"))
                .toList();
    }

    private static List<KnowledgeMatch.ReferenceCandidate> references(
            MatchInput input,
            Map<UUID, KnowledgeMatch.Candidate> eligible,
            Set<UUID> deterministicMatches) {
        LinkedHashMap<UUID, KnowledgeMatch.ReferenceCandidate> result = new LinkedHashMap<>();
        for (KnowledgeMatch.RetrievalReference reference : input.retrievalReferences()) {
            if (reference == null
                    || reference.knowledgeId() == null
                    || deterministicMatches.contains(reference.knowledgeId())) {
                continue;
            }
            KnowledgeMatch.Candidate knowledge = eligible.get(reference.knowledgeId());
            if (knowledge == null) {
                continue;
            }
            result.putIfAbsent(
                    reference.knowledgeId(),
                    new KnowledgeMatch.ReferenceCandidate(
                            knowledge.knowledgeId(),
                            knowledge.title(),
                            knowledge.kind(),
                            knowledge.sourceVersionStatus(),
                            safe(reference.source(), "RETRIEVAL"),
                            safe(reference.detail(), "仅供参考的检索候选，未命中确定性适用范围"),
                            Provenance.retrievalCandidate(
                                    input.repositoryId(),
                                    knowledge.knowledgeId(),
                                    knowledge.revision(),
                                    knowledge.reviewStatus(),
                                    safe(reference.source(), "RETRIEVAL"),
                                    safe(reference.detail(), "仅供参考的检索候选，未命中确定性适用范围"))));
        }
        return List.copyOf(result.values());
    }

    private static void addChangeUnknowns(MatchInput input, List<TaskReviewFinding> unknowns) {
        input.change().limitations().stream()
                .map(
                        limitation ->
                                unknown(
                                        input.repositoryId(),
                                        limitation.code(),
                                        null,
                                        null,
                                        null,
                                        limitation.detail()))
                .forEach(unknowns::add);
        if (input.changedSymbols() != null) {
            input.changedSymbols().unknowns().stream()
                    .map(
                            item ->
                                    unknown(
                                            input.repositoryId(),
                                            item.code(),
                                            null,
                                            item.filePath(),
                                            null,
                                            item.detail()))
                    .forEach(unknowns::add);
        }
    }

    private static TaskReviewFinding scopeUnknown(
            UUID repositoryId,
            UUID knowledgeId,
            KnowledgeScopeMatcher.ScopeUnknown item) {
        return unknown(
                repositoryId, item.code(), knowledgeId, null, item.rule(), item.detail());
    }

    private static TaskReviewFinding unknown(
            UUID repositoryId,
            String code,
            UUID knowledgeId,
            String filePath,
            String rule,
            String detail) {
        String safeCode = safe(code, "UNKNOWN");
        TaskReviewFinding.UnknownReason reason =
                new TaskReviewFinding.UnknownReason(
                        safeCode, knowledgeId, filePath, rule, safe(detail, "无法确定"));
        return new TaskReviewFinding(
                TaskReviewFinding.FindingKind.UNKNOWN,
                safeCode + ":" + safe(filePath, "") + ":" + safe(rule, ""),
                "无法确定",
                TaskReviewFinding.FindingStatus.UNKNOWN,
                knowledgeId == null ? List.of() : List.of(knowledgeId),
                List.of(),
                reason,
                List.of(
                        Provenance.unknown(
                                repositoryId,
                                knowledgeId,
                                filePath,
                                safeCode,
                                reason.detail())));
    }

    private static KnowledgeMatch knowledgeMatch(
            KnowledgeMatch.Candidate candidate, List<KnowledgeMatchReason> reasons) {
        return new KnowledgeMatch(
                candidate.knowledgeId(),
                candidate.title(),
                candidate.kind(),
                candidate.severity(),
                candidate.enforcement(),
                candidate.ownerAccountId(),
                candidate.revision(),
                candidate.sourceVersionStatus(),
                candidate.obligations(),
                reasons,
                sources(candidate, reasons));
    }

    private static List<Provenance> sources(
            KnowledgeMatch.Candidate candidate, List<KnowledgeMatchReason> reasons) {
        LinkedHashSet<Provenance> sources = new LinkedHashSet<>();
        sources.add(
                Provenance.verifiedKnowledge(
                        candidate.repositoryId(),
                        candidate.knowledgeId(),
                        candidate.revision(),
                        candidate.reviewStatus(),
                        "已发布、已人工审核且来源版本为 " + candidate.sourceVersionStatus()));
        reasons.stream().map(TaskContextMatcher::provenance).forEach(sources::add);
        return List.copyOf(sources);
    }

    private static Provenance provenance(KnowledgeMatchReason reason) {
        KnowledgeMatchReason.ScopeEvidence evidence = reason.evidence();
        if (evidence.sourceType() == KnowledgeMatchReason.EvidenceSource.GIT_FACT) {
            return Provenance.gitFact(
                    evidence.repositoryId(),
                    evidence.snapshotId(),
                    evidence.commitSha(),
                    null,
                    evidence.filePath(),
                    evidence.detail());
        }
        if (evidence.sourceType() == KnowledgeMatchReason.EvidenceSource.GRAPH_INFERENCE) {
            String graphArtifactId = evidence.snapshotId() + ":architecture-map";
            List<String> path =
                    List.of(
                            safe(evidence.filePath(), "unknown-file"),
                            safe(evidence.moduleId(), reason.target()));
            return Provenance.graphInference(
                    evidence.repositoryId(),
                    evidence.snapshotId(),
                    evidence.commitSha(),
                    graphArtifactId,
                    path,
                    evidence.filePath(),
                    evidence.detail());
        }
        return Provenance.codeFact(
                evidence.repositoryId(),
                evidence.snapshotId(),
                evidence.commitSha(),
                null,
                evidence.filePath(),
                evidence.symbolName(),
                null,
                null,
                null,
                null,
                evidence.detail());
    }

    private static List<TaskReviewFinding> deduplicateUnknowns(List<TaskReviewFinding> unknowns) {
        LinkedHashMap<String, TaskReviewFinding> result = new LinkedHashMap<>();
        for (TaskReviewFinding unknown : unknowns) {
            TaskReviewFinding.UnknownReason reason = unknown.unknownReason();
            String key =
                    unknown.key()
                            + ":"
                            + (reason.knowledgeId() == null ? "" : reason.knowledgeId())
                            + ":"
                            + reason.detail();
            result.putIfAbsent(key, unknown);
        }
        return List.copyOf(result.values());
    }

    private static TaskContextResult result(
            List<KnowledgeMatch> applicable,
            List<KnowledgeMatch.ReferenceCandidate> references,
            List<TaskReviewFinding> tests,
            List<TaskReviewFinding> approvals,
            List<KnowledgeMatch> stale,
            List<TaskReviewFinding> unknowns) {
        return new TaskContextResult(applicable, references, tests, approvals, stale, unknowns);
    }

    private static Comparator<KnowledgeMatch> knowledgeOrder() {
        return Comparator.comparing(KnowledgeMatch::title, Comparator.nullsLast(String::compareTo))
                .thenComparing(match -> match.knowledgeId().toString());
    }

    private static Set<String> paths(RepositoryChange.FileChange file) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (file.oldPath() != null) {
            result.add(file.oldPath().replace('\\', '/'));
        }
        if (file.newPath() != null) {
            result.add(file.newPath().replace('\\', '/'));
        }
        return Set.copyOf(result);
    }

    private static String effectiveCommit(RepositoryChange change) {
        return change.headCommit() == null ? change.baseCommit() : change.headCommit();
    }

    private static String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String firstEvidencePath(List<KnowledgeMatchReason> reasons) {
        return reasons.stream()
                .map(KnowledgeMatchReason::evidence)
                .filter(Objects::nonNull)
                .map(KnowledgeMatchReason.ScopeEvidence::filePath)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record MatchInput(
            UUID repositoryId,
            UUID snapshotId,
            RepositoryChange change,
            ChangedSymbolResolver.ResolutionResult changedSymbols,
            List<KnowledgeMatch.Candidate> knowledge,
            List<KnowledgeMatch.RetrievalReference> retrievalReferences,
            Map<String, Set<String>> modulesByPath,
            boolean moduleGraphAvailable,
            Map<String, ContentTransition> contentTransitions) {
        public MatchInput {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            retrievalReferences =
                    retrievalReferences == null ? List.of() : List.copyOf(retrievalReferences);
            modulesByPath = immutableSetMap(modulesByPath);
            contentTransitions =
                    contentTransitions == null ? Map.of() : Map.copyOf(contentTransitions);
        }

        private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> value) {
            if (value == null || value.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
            value.forEach(
                    (path, modules) ->
                            copy.put(
                                    path.replace('\\', '/'),
                                    modules == null ? Set.of() : Set.copyOf(modules)));
            return Map.copyOf(copy);
        }
    }

    public record ContentTransition(String oldContentHash, String newContentHash) {
        static ContentTransition empty() {
            return new ContentTransition(null, null);
        }
    }

    public record TaskContextResult(
            List<KnowledgeMatch> applicableKnowledge,
            List<KnowledgeMatch.ReferenceCandidate> referenceCandidates,
            List<TaskReviewFinding> requiredTests,
            List<TaskReviewFinding> requiredApprovals,
            List<KnowledgeMatch> staleKnowledge,
            List<TaskReviewFinding> unknowns) {
        public TaskContextResult {
            applicableKnowledge = immutable(applicableKnowledge);
            referenceCandidates = immutable(referenceCandidates);
            requiredTests = immutable(requiredTests);
            requiredApprovals = immutable(requiredApprovals);
            staleKnowledge = immutable(staleKnowledge);
            unknowns = immutable(unknowns);
        }

        private static <T> List<T> immutable(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    private record MatchAggregation(
            List<KnowledgeMatchReason> reasons,
            List<KnowledgeScopeMatcher.ScopeUnknown> unknowns) {}

    private static final class MutableObligation {
        private final String key;
        private final LinkedHashSet<UUID> knowledgeIds = new LinkedHashSet<>();
        private final LinkedHashSet<KnowledgeMatchReason> evidence = new LinkedHashSet<>();
        private final LinkedHashSet<Provenance> sources = new LinkedHashSet<>();

        private MutableObligation(String key) {
            this.key = key;
        }

        private void add(KnowledgeMatch match) {
            knowledgeIds.add(match.knowledgeId());
            evidence.addAll(match.reasons());
            sources.addAll(match.sources());
        }

        private TaskReviewFinding finding(
                TaskReviewFinding.FindingKind kind,
                TaskReviewFinding.FindingStatus status,
                String title) {
            return new TaskReviewFinding(
                    kind,
                    key,
                    title,
                    status,
                    List.copyOf(knowledgeIds),
                    List.copyOf(evidence),
                    null,
                    List.copyOf(sources));
        }
    }
}
