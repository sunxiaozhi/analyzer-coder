package com.analyzercoder.application.review;

import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 使用代码引用、路径、符号和架构模块对工程知识适用范围做确定性匹配。 */
@Component
public class KnowledgeScopeMatcher {
    private final RepositoryGlobMatcher globMatcher;

    public KnowledgeScopeMatcher(RepositoryGlobMatcher globMatcher) {
        this.globMatcher = globMatcher;
    }

    public MatchResult match(
            KnowledgeScope requestedScope,
            List<BoundCodeReference> requestedReferences,
            ChangeTarget target) {
        return match(requestedScope, requestedReferences, target, List.of());
    }

    public MatchResult match(
            KnowledgeScope requestedScope,
            List<BoundCodeReference> requestedReferences,
            ChangeTarget target,
            List<KnowledgeMatch.CrossRepositoryBinding> requestedBindings) {
        KnowledgeScope scope = requestedScope == null ? KnowledgeScope.empty() : requestedScope;
        List<BoundCodeReference> references =
                requestedReferences == null ? List.of() : requestedReferences;
        List<KnowledgeMatch.CrossRepositoryBinding> bindings =
                requestedBindings == null ? List.of() : requestedBindings;
        if (target == null) {
            throw new IllegalArgumentException("变更目标不能为空");
        }

        LinkedHashSet<ScopeUnknown> unknowns = new LinkedHashSet<>();
        List<String> paths = paths(target, unknowns);
        LinkedHashSet<KnowledgeMatchReason> crossReasons = new LinkedHashSet<>();
        matchCrossRepository(scope, target, paths, bindings, crossReasons, unknowns);
        boolean foreignKnowledge =
                bindings.stream()
                        .anyMatch(binding -> !target.repositoryId().equals(binding.sourceRepositoryId()));
        boolean crossScoped = hasCrossScope(scope);
        if ((foreignKnowledge || crossScoped) && crossReasons.isEmpty()) {
            return new MatchResult(List.of(), List.copyOf(unknowns));
        }

        LinkedHashSet<KnowledgeMatchReason> localReasons = new LinkedHashSet<>();
        matchCodeReferences(references, target, paths, localReasons, unknowns);
        matchPaths(scope, target, paths, localReasons, unknowns);
        matchSymbols(scope, target, paths, localReasons);
        matchModules(scope, target, paths, localReasons, unknowns);
        if (hasLocalScope(scope, references) && localReasons.isEmpty()) {
            return new MatchResult(List.of(), List.copyOf(unknowns));
        }

        LinkedHashSet<KnowledgeMatchReason> reasons = new LinkedHashSet<>(crossReasons);
        reasons.addAll(localReasons);

        return new MatchResult(List.copyOf(reasons), List.copyOf(unknowns));
    }

    private static void matchCrossRepository(
            KnowledgeScope scope,
            ChangeTarget target,
            List<String> paths,
            List<KnowledgeMatch.CrossRepositoryBinding> bindings,
            Set<KnowledgeMatchReason> reasons,
            Set<ScopeUnknown> unknowns) {
        if (!hasCrossScope(scope) || paths.isEmpty()) {
            return;
        }
        String path = paths.get(paths.size() - 1);
        if (scope.repositoryIds().contains(target.repositoryId())) {
            if (bindings.isEmpty()) {
                reasons.add(
                        crossReason(
                                KnowledgeMatchReason.MatchKind.REPOSITORY,
                                target.repositoryId().toString(),
                                target.repositoryId().toString(),
                                target,
                                path,
                                null,
                                null,
                                null,
                                "目标仓库 ID 与知识的显式仓库范围一致"));
            } else {
                bindings.forEach(
                        binding ->
                                reasons.add(
                                        crossReason(
                                                KnowledgeMatchReason.MatchKind.REPOSITORY,
                                                target.repositoryId().toString(),
                                                target.repositoryId().toString(),
                                                target,
                                                path,
                                                binding.engineeringProjectId(),
                                                binding.targetServiceName(),
                                                null,
                                                "同一工程项目中的目标仓库 ID 与显式范围一致")));
            }
        }
        for (KnowledgeMatch.CrossRepositoryBinding binding : bindings) {
            String targetService = normalized(binding.targetServiceName());
            if (scope.serviceNames().stream()
                    .map(KnowledgeScopeMatcher::normalized)
                    .anyMatch(targetService::equals)) {
                reasons.add(
                        crossReason(
                                KnowledgeMatchReason.MatchKind.SERVICE,
                                targetService,
                                targetService,
                                target,
                                path,
                                binding.engineeringProjectId(),
                                targetService,
                                null,
                                "工程项目成员中记录的目标服务身份与知识范围一致"));
            }
            for (KnowledgeMatch.ContractScopeBinding contract : binding.contracts()) {
                if (!scope.contractIds().contains(contract.contractId())
                        || !paths.contains(contract.targetEvidencePath())) {
                    continue;
                }
                if (!contract.current()) {
                    unknowns.add(
                            new ScopeUnknown(
                                    "CONTRACT_EVIDENCE_STALE",
                                    contract.contractId().toString(),
                                    "契约证据路径已变化或不在当前内容索引，不能扩大为跨仓库结论"));
                    continue;
                }
                reasons.add(
                        crossReason(
                                KnowledgeMatchReason.MatchKind.CONTRACT,
                                contract.contractId().toString(),
                                contract.targetEvidencePath(),
                                target,
                                contract.targetEvidencePath(),
                                binding.engineeringProjectId(),
                                targetService,
                                contract.contractId(),
                                "变更路径与经过两端当前代码指纹验证的契约证据一致"));
            }
        }
    }

    private static KnowledgeMatchReason crossReason(
            KnowledgeMatchReason.MatchKind kind,
            String rule,
            String resolvedTarget,
            ChangeTarget target,
            String filePath,
            UUID engineeringProjectId,
            String serviceName,
            UUID contractId,
            String detail) {
        return new KnowledgeMatchReason(
                kind,
                rule,
                resolvedTarget,
                new KnowledgeMatchReason.ScopeEvidence(
                        KnowledgeMatchReason.EvidenceSource.PLATFORM_FACT,
                        target.repositoryId(),
                        target.snapshotId(),
                        target.commitSha(),
                        filePath,
                        null,
                        null,
                        null,
                        detail,
                        engineeringProjectId,
                        serviceName,
                        contractId));
    }

    private static boolean hasCrossScope(KnowledgeScope scope) {
        return !scope.repositoryIds().isEmpty()
                || !scope.serviceNames().isEmpty()
                || !scope.contractIds().isEmpty();
    }

    private static boolean hasLocalScope(
            KnowledgeScope scope, List<BoundCodeReference> references) {
        return !references.isEmpty()
                || !scope.pathPatterns().isEmpty()
                || !scope.symbols().isEmpty()
                || !scope.modules().isEmpty();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void matchCodeReferences(
            List<BoundCodeReference> references,
            ChangeTarget target,
            List<String> paths,
            Set<KnowledgeMatchReason> reasons,
            Set<ScopeUnknown> unknowns) {
        for (BoundCodeReference reference : references) {
            if (reference == null) {
                continue;
            }
            String referencePath;
            try {
                referencePath = RepositoryGlobMatcher.normalizeRepositoryPath(reference.filePath());
            } catch (IllegalArgumentException exception) {
                unknowns.add(
                        new ScopeUnknown(
                                "INVALID_CODE_REFERENCE",
                                reference.filePath(),
                                exception.getMessage()));
                continue;
            }
            String matchedPath =
                    paths.stream().filter(referencePath::equals).findFirst().orElse(null);
            boolean hashChanged =
                    reference.contentHash() != null
                            && reference.contentHash().equals(target.oldContentHash())
                            && !Objects.equals(target.oldContentHash(), target.newContentHash());
            if (matchedPath == null && (!hashChanged || paths.isEmpty())) {
                continue;
            }
            String resolvedPath = matchedPath == null ? paths.get(paths.size() - 1) : matchedPath;
            reasons.add(
                    new KnowledgeMatchReason(
                            KnowledgeMatchReason.MatchKind.CODE_REFERENCE,
                            referencePath,
                            resolvedPath,
                            evidence(
                                    KnowledgeMatchReason.EvidenceSource.CODE_FACT,
                                    target,
                                    resolvedPath,
                                    reference.symbolName(),
                                    null,
                                    reference.chunkId(),
                                    matchedPath != null
                                            ? "变更文件与知识绑定代码路径一致"
                                            : "知识绑定内容哈希对应的代码内容已变化")));
        }
    }

    private void matchPaths(
            KnowledgeScope scope,
            ChangeTarget target,
            List<String> paths,
            Set<KnowledgeMatchReason> reasons,
            Set<ScopeUnknown> unknowns) {
        for (String rule : scope.pathPatterns()) {
            try {
                String normalizedRule = RepositoryGlobMatcher.normalizePattern(rule);
                for (String path : paths) {
                    if (globMatcher.matches(normalizedRule, path)) {
                        reasons.add(
                                new KnowledgeMatchReason(
                                        KnowledgeMatchReason.MatchKind.PATH_PATTERN,
                                        normalizedRule,
                                        path,
                                        evidence(
                                                KnowledgeMatchReason.EvidenceSource.GIT_FACT,
                                                target,
                                                path,
                                                null,
                                                null,
                                                null,
                                                "Git 变更路径命中仓库相对 Glob")));
                    }
                }
            } catch (IllegalArgumentException exception) {
                unknowns.add(new ScopeUnknown("INVALID_SCOPE_PATH", rule, exception.getMessage()));
            }
        }
    }

    private static void matchSymbols(
            KnowledgeScope scope,
            ChangeTarget target,
            List<String> paths,
            Set<KnowledgeMatchReason> reasons) {
        if (paths.isEmpty()) {
            return;
        }
        String effectivePath = paths.get(paths.size() - 1);
        for (String rule : scope.symbols()) {
            if (target.symbols().contains(rule)) {
                reasons.add(
                        new KnowledgeMatchReason(
                                KnowledgeMatchReason.MatchKind.SYMBOL,
                                rule,
                                rule,
                                evidence(
                                        KnowledgeMatchReason.EvidenceSource.CODE_FACT,
                                        target,
                                        effectivePath,
                                        rule,
                                        null,
                                        null,
                                        "改动符号与知识适用符号精确一致")));
            }
        }
    }

    private static void matchModules(
            KnowledgeScope scope,
            ChangeTarget target,
            List<String> paths,
            Set<KnowledgeMatchReason> reasons,
            Set<ScopeUnknown> unknowns) {
        if (!target.moduleGraphAvailable()) {
            scope.modules()
                    .forEach(
                            rule ->
                                    unknowns.add(
                                            new ScopeUnknown(
                                                    "MODULE_GRAPH_UNAVAILABLE",
                                                    rule,
                                                    "当前版本没有可用的架构模块事实，无法判断模块范围")));
            return;
        }
        if (paths.isEmpty()) {
            return;
        }
        String effectivePath = paths.get(paths.size() - 1);
        for (String rule : scope.modules()) {
            if (target.modules().contains(rule)) {
                reasons.add(
                        new KnowledgeMatchReason(
                                KnowledgeMatchReason.MatchKind.MODULE,
                                rule,
                                rule,
                                evidence(
                                        KnowledgeMatchReason.EvidenceSource.GRAPH_INFERENCE,
                                        target,
                                        effectivePath,
                                        null,
                                        rule,
                                        null,
                                        "当前架构地图将变更目标归入该模块")));
            }
        }
    }

    private static List<String> paths(ChangeTarget target, Set<ScopeUnknown> unknowns) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : new String[] {target.oldPath(), target.newPath()}) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                normalized.add(RepositoryGlobMatcher.normalizeRepositoryPath(path));
            } catch (IllegalArgumentException exception) {
                unknowns.add(new ScopeUnknown("INVALID_CHANGE_PATH", path, exception.getMessage()));
            }
        }
        return List.copyOf(normalized);
    }

    private static KnowledgeMatchReason.ScopeEvidence evidence(
            KnowledgeMatchReason.EvidenceSource source,
            ChangeTarget target,
            String filePath,
            String symbolName,
            String moduleId,
            UUID knowledgeChunkId,
            String detail) {
        return new KnowledgeMatchReason.ScopeEvidence(
                source,
                target.repositoryId(),
                target.snapshotId(),
                target.commitSha(),
                filePath,
                symbolName,
                moduleId,
                knowledgeChunkId,
                detail);
    }

    public record BoundCodeReference(
            UUID chunkId, String filePath, String symbolName, String contentHash) {}

    public record ChangeTarget(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String oldPath,
            String newPath,
            Set<String> symbols,
            Set<String> modules,
            boolean moduleGraphAvailable,
            String oldContentHash,
            String newContentHash) {
        public ChangeTarget {
            symbols = symbols == null ? Set.of() : Set.copyOf(symbols);
            modules = modules == null ? Set.of() : Set.copyOf(modules);
        }
    }

    public record MatchResult(List<KnowledgeMatchReason> reasons, List<ScopeUnknown> unknowns) {
        public boolean matched() {
            return !reasons.isEmpty();
        }
    }

    public record ScopeUnknown(String code, String rule, String detail) {}
}
