package com.analyzercoder.application.change;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 基于当前快照的检索证据和模块依赖生成只读、可追溯的变更影响分析。 */
@Service
public class ChangeImpactAnalysisService {
    private static final int MAX_EVIDENCE = 12;
    private static final int MAX_DEPENDENCIES = 16;

    private final RegisterRepositoryUseCase repositories;
    private final IntelligenceService intelligence;
    private final ProjectArchitectureMapService architecture;
    private final ChangeIntentParser intentParser;

    public ChangeImpactAnalysisService(
            RegisterRepositoryUseCase repositories,
            IntelligenceService intelligence,
            ProjectArchitectureMapService architecture,
            ChangeIntentParser intentParser) {
        this.repositories = repositories;
        this.intelligence = intelligence;
        this.architecture = architecture;
        this.intentParser = intentParser;
    }

    public ChangeImpactAnalysis analyze(CodeRepositoryId repositoryId, String task) {
        return analyze(repositoryId, task, null);
    }

    public ChangeImpactAnalysis analyze(
            CodeRepositoryId repositoryId, String task, UUID modelConfigId) {
        String normalizedTask = normalizeTask(task);
        CodeRepository repository = repositories.get(repositoryId);
        if (repository.currentSnapshotId() == null) {
            throw new IllegalStateException("仓库尚未发布可用于分析的项目快照");
        }
        UUID analysisSnapshotId = repository.currentSnapshotId().value();
        EvidenceAudit evidenceAudit = new EvidenceAudit();

        ChangeIntentParser.IntentInterpretation intent =
                intentParser.parse(normalizedTask, modelConfigId);
        List<QueryPlan> codeQueries = codeQueries(normalizedTask, intent);
        Map<String, EvidenceAggregate> aggregated = new LinkedHashMap<>();
        List<RetrievalQuery> retrievalQueries = new ArrayList<>();
        for (QueryPlan query : codeQueries) {
            List<IntelligenceService.Evidence> hits =
                    intelligence.unifiedSearch(
                            repositoryId.value(), query.query(), query.limit());
            retrievalQueries.add(
                    new RetrievalQuery(query.query(), query.purpose(), hits.size()));
            mergeEvidence(
                    aggregated,
                    hits,
                    query.query(),
                    analysisSnapshotId,
                    evidenceAudit);
        }
        List<EvidenceAggregate> selectedAggregates =
                aggregated.values().stream()
                        .sorted(
                                Comparator.comparingDouble(EvidenceAggregate::aggregateScore)
                                        .reversed()
                                        .thenComparing(item -> evidenceKey(item.evidence())))
                        .limit(MAX_EVIDENCE)
                        .toList();
        List<IntelligenceService.Evidence> selected =
                selectedAggregates.stream().map(EvidenceAggregate::evidence).toList();

        String testQuery = testQuery(normalizedTask, intent);
        List<IntelligenceService.Evidence> testEvidence =
                intelligence.unifiedSearch(repositoryId.value(), testQuery, 8);
        retrievalQueries.add(new RetrievalQuery(testQuery, "测试覆盖", testEvidence.size()));
        ProjectArchitectureMapService.ArchitectureMap loadedMap =
                loadArchitecture(repositoryId, evidenceAudit);
        ProjectArchitectureMapService.ArchitectureMap map;
        if (loadedMap != null && !analysisSnapshotId.toString().equals(loadedMap.snapshotId())) {
            evidenceAudit.architectureSnapshotMismatch++;
            map = null;
        } else {
            map = loadedMap;
        }
        List<IntelligenceService.Evidence> currentTestEvidence =
                currentEvidence(testEvidence, analysisSnapshotId, evidenceAudit);

        List<CandidateEvidence> candidates =
                selectedAggregates.stream().map(item -> candidate(item, map)).toList();
        Set<String> directModules = new LinkedHashSet<>();
        candidates.stream()
                .map(CandidateEvidence::moduleId)
                .filter(value -> value != null && !value.isBlank())
                .forEach(directModules::add);
        if (map != null && directModules.isEmpty()) {
            inferTaskModules(normalizedTask, map).forEach(directModules::add);
        }

        List<DependencyImpact> dependencies =
                dependencies(map, directModules, analysisSnapshotId, evidenceAudit);
        Set<String> impactedModules = new LinkedHashSet<>(directModules);
        dependencies.forEach(
                edge -> {
                    impactedModules.add(edge.source());
                    impactedModules.add(edge.target());
                });
        List<ModuleImpact> modules = modules(map, directModules, impactedModules, candidates);
        List<ProjectArchitectureMapService.ArchitectureRisk> risks = risks(map, impactedModules);
        List<TestSuggestion> tests = tests(currentTestEvidence, selected);
        List<AnalysisUnknown> unknowns =
                unknowns(
                        repository,
                        map,
                        intent,
                        candidates,
                        directModules,
                        tests,
                        evidenceAudit);
        EvidenceCoverage evidenceCoverage =
                evidenceCoverage(repository, map, intent, candidates, directModules, unknowns);

        return new ChangeImpactAnalysis(
                UUID.randomUUID(),
                repositoryId.value(),
                analysisSnapshotId,
                repository.currentCommit(),
                Instant.now(),
                normalizedTask,
                intent,
                List.copyOf(retrievalQueries),
                evidenceCoverage,
                candidates,
                modules,
                dependencies,
                risks,
                tests,
                unknowns);
    }

    private static List<QueryPlan> codeQueries(
            String task, ChangeIntentParser.IntentInterpretation intent) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(task);
        intent.searchQueries().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(queries::add);
        List<QueryPlan> result = new ArrayList<>();
        int index = 0;
        for (String query : queries) {
            if (index >= 4) break;
            result.add(new QueryPlan(query, index == 0 ? "原始任务" : "语义扩展", index == 0 ? 12 : 8));
            index++;
        }
        return List.copyOf(result);
    }

    private static String testQuery(
            String task, ChangeIntentParser.IntentInterpretation intent) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(task);
        intent.candidateSymbols().stream().limit(3).forEach(terms::add);
        terms.add("test 测试 spec");
        String query = String.join(" ", terms);
        return query.length() <= 500 ? query : query.substring(0, 500);
    }

    private static void mergeEvidence(
            Map<String, EvidenceAggregate> aggregated,
            List<IntelligenceService.Evidence> hits,
            String query,
            UUID currentSnapshotId,
            EvidenceAudit evidenceAudit) {
        for (IntelligenceService.Evidence evidence : hits) {
            if (!hasFile(evidence) || !acceptEvidence(evidence, currentSnapshotId, evidenceAudit)) {
                continue;
            }
            String key = evidenceKey(evidence);
            EvidenceAggregate current = aggregated.get(key);
            if (current == null) {
                aggregated.put(
                        key,
                        new EvidenceAggregate(evidence, evidence.score(), List.of(query)));
                continue;
            }
            LinkedHashSet<String> matched = new LinkedHashSet<>(current.matchedQueries());
            matched.add(query);
            IntelligenceService.Evidence best =
                    evidence.score() > current.evidence().score()
                            ? evidence
                            : current.evidence();
            double boosted =
                    Math.min(
                            1.0,
                            Math.max(current.aggregateScore(), evidence.score())
                                    + (matched.size() > current.matchedQueries().size()
                                            ? 0.035
                                            : 0));
            aggregated.put(
                    key, new EvidenceAggregate(best, boosted, List.copyOf(matched)));
        }
    }

    private ProjectArchitectureMapService.ArchitectureMap loadArchitecture(
            CodeRepositoryId repositoryId, EvidenceAudit evidenceAudit) {
        try {
            return architecture.map(repositoryId);
        } catch (ProjectArchitectureMapService.ArchitectureSnapshotChangedException exception) {
            evidenceAudit.architectureSnapshotMismatch++;
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static CandidateEvidence candidate(
            EvidenceAggregate aggregate,
            ProjectArchitectureMapService.ArchitectureMap map) {
        IntelligenceService.Evidence evidence = aggregate.evidence();
        return new CandidateEvidence(
                evidence.chunkId(),
                evidence.sourceType(),
                evidence.snapshotId(),
                evidence.filePath(),
                evidence.symbolName(),
                evidence.symbolKind(),
                evidence.startLine(),
                evidence.endLine(),
                excerpt(evidence.content()),
                evidence.contentHash(),
                round(aggregate.aggregateScore()),
                evidence.channels(),
                aggregate.matchedQueries(),
                moduleFor(evidence.filePath(), map));
    }

    private static List<String> inferTaskModules(
            String task, ProjectArchitectureMapService.ArchitectureMap map) {
        String normalized = task.toLowerCase(Locale.ROOT);
        return map.nodes().stream()
                .filter(node -> "MODULE".equals(node.kind()))
                .filter(
                        node ->
                                normalized.contains(node.id().toLowerCase(Locale.ROOT))
                                        || normalized.contains(
                                                node.label().toLowerCase(Locale.ROOT)))
                .map(ProjectArchitectureMapService.ArchitectureNode::id)
                .limit(4)
                .toList();
    }

    private static List<DependencyImpact> dependencies(
            ProjectArchitectureMapService.ArchitectureMap map,
            Set<String> directModules,
            UUID currentSnapshotId,
            EvidenceAudit evidenceAudit) {
        if (map == null || directModules.isEmpty()) return List.of();
        return map.edges().stream()
                .filter(edge -> !"CONTAINS".equals(edge.relation()))
                .filter(
                        edge ->
                                directModules.contains(edge.source())
                                        || directModules.contains(edge.target()))
                .sorted(
                        Comparator.comparingInt(
                                        ProjectArchitectureMapService.ArchitectureEdge::weight)
                                .reversed()
                                .thenComparing(
                                        ProjectArchitectureMapService.ArchitectureEdge::source)
                                .thenComparing(
                                        ProjectArchitectureMapService.ArchitectureEdge::target))
                .limit(MAX_DEPENDENCIES)
                .map(edge -> dependency(edge, currentSnapshotId, evidenceAudit))
                .toList();
    }

    private static DependencyImpact dependency(
            ProjectArchitectureMapService.ArchitectureEdge edge,
            UUID currentSnapshotId,
            EvidenceAudit evidenceAudit) {
        List<DependencyEvidenceSample> samples =
                edge.evidenceSamples().stream()
                        .filter(
                                sample -> {
                                    if (!currentSnapshotId.toString().equals(sample.snapshotId())) {
                                        evidenceAudit.mixedSnapshotEvidence++;
                                        return false;
                                    }
                                    if (!isContentHash(sample.contentHash())) {
                                        evidenceAudit.missingContentHash++;
                                        return false;
                                    }
                                    return true;
                                })
                        .map(
                                sample ->
                                        new DependencyEvidenceSample(
                                                sample.filePath(),
                                                sample.relatedFilePath(),
                                                UUID.fromString(sample.snapshotId()),
                                                sample.contentHash()))
                        .toList();
        if (edge.weight() > 0 && samples.isEmpty()) evidenceAudit.dependencyProvenanceMissing++;
        return new DependencyImpact(
                edge.source(), edge.target(), edge.relation(), edge.weight(), samples);
    }

    private static List<ModuleImpact> modules(
            ProjectArchitectureMapService.ArchitectureMap map,
            Set<String> directModules,
            Set<String> impactedModules,
            List<CandidateEvidence> candidates) {
        if (map == null) return List.of();
        Map<String, Long> evidenceCounts = new LinkedHashMap<>();
        candidates.stream()
                .map(CandidateEvidence::moduleId)
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> evidenceCounts.merge(value, 1L, Long::sum));
        return map.nodes().stream()
                .filter(node -> "MODULE".equals(node.kind()))
                .filter(node -> impactedModules.contains(node.id()))
                .map(
                        node -> {
                            int incoming =
                                    map.edges().stream()
                                            .filter(edge -> node.id().equals(edge.target()))
                                            .mapToInt(
                                                    ProjectArchitectureMapService.ArchitectureEdge::weight)
                                            .sum();
                            int outgoing =
                                    map.edges().stream()
                                            .filter(edge -> node.id().equals(edge.source()))
                                            .mapToInt(
                                                    ProjectArchitectureMapService.ArchitectureEdge::weight)
                                            .sum();
                            return new ModuleImpact(
                                    node.id(),
                                    node.label(),
                                    directModules.contains(node.id()) ? "DIRECT" : "RELATED",
                                    evidenceCounts.getOrDefault(node.id(), 0L).intValue(),
                                    incoming,
                                    outgoing);
                        })
                .sorted(
                        Comparator.comparing(
                                        (ModuleImpact item) -> "DIRECT".equals(item.role()) ? 0 : 1)
                                .thenComparing(
                                        Comparator.comparingInt(ModuleImpact::evidenceCount)
                                                .reversed())
                                .thenComparing(ModuleImpact::moduleId))
                .toList();
    }

    private static List<ProjectArchitectureMapService.ArchitectureRisk> risks(
            ProjectArchitectureMapService.ArchitectureMap map, Set<String> impactedModules) {
        if (map == null || impactedModules.isEmpty()) return List.of();
        return map.risks().stream()
                .filter(risk -> risk.modules().stream().anyMatch(impactedModules::contains))
                .toList();
    }

    private static List<TestSuggestion> tests(
            List<IntelligenceService.Evidence> testEvidence,
            List<IntelligenceService.Evidence> primary) {
        Map<String, IntelligenceService.Evidence> matches = new LinkedHashMap<>();
        java.util.stream.Stream.concat(testEvidence.stream(), primary.stream())
                .filter(ChangeImpactAnalysisService::hasFile)
                .filter(item -> isTestPath(item.filePath()))
                .forEach(item -> matches.putIfAbsent(item.filePath(), item));
        if (matches.isEmpty()) {
            return List.of(
                    new TestSuggestion(
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            "未检索到直接相关的现有测试；修改前需要按候选入口补充或人工定位测试。"));
        }
        return matches.values().stream()
                .limit(6)
                .map(
                        item ->
                                new TestSuggestion(
                                        item.filePath(),
                                        item.startLine(),
                                        item.endLine(),
                                        item.snapshotId(),
                                        item.contentHash(),
                                        true,
                                        "现有测试与任务关键词或候选实现匹配"))
                .toList();
    }

    private static List<AnalysisUnknown> unknowns(
            CodeRepository repository,
            ProjectArchitectureMapService.ArchitectureMap map,
            ChangeIntentParser.IntentInterpretation intent,
            List<CandidateEvidence> candidates,
            Set<String> directModules,
            List<TestSuggestion> tests,
            EvidenceAudit evidenceAudit) {
        List<AnalysisUnknown> result = new ArrayList<>();
        if (evidenceAudit.mixedSnapshotEvidence > 0) {
            result.add(
                    new AnalysisUnknown(
                            "MIXED_SNAPSHOT_EVIDENCE_EXCLUDED",
                            "HIGH",
                            "已排除 "
                                    + evidenceAudit.mixedSnapshotEvidence
                                    + " 条与分析快照不一致的代码证据；结果可能缺少并发切换版本期间的候选。"));
        }
        if (evidenceAudit.architectureSnapshotMismatch > 0) {
            result.add(
                    new AnalysisUnknown(
                            "ARCHITECTURE_SNAPSHOT_MISMATCH",
                            "HIGH",
                            "架构关系不属于本次分析快照，模块、依赖和架构风险已全部排除。"));
        }
        if (evidenceAudit.missingContentHash > 0) {
            result.add(
                    new AnalysisUnknown(
                            "EVIDENCE_HASH_MISSING",
                            "HIGH",
                            "已排除 "
                                    + evidenceAudit.missingContentHash
                                    + " 条缺少内容摘要的代码证据，无法确认其精确内容版本。"));
        }
        if (evidenceAudit.dependencyProvenanceMissing > 0) {
            result.add(
                    new AnalysisUnknown(
                            "DEPENDENCY_PROVENANCE_MISSING",
                            "MEDIUM",
                            evidenceAudit.dependencyProvenanceMissing
                                    + " 条依赖关系没有可核对的文件版本样例，关系权重不能替代源码凭据。"));
        }
        if ("RULES".equals(intent.parserMode())) {
            result.add(
                    new AnalysisUnknown(
                            "SEMANTIC_MODEL_FALLBACK",
                            "MEDIUM",
                            semanticFallbackDetail(intent.fallbackReason())));
        }
        if (repository.worktreeDirty()) {
            result.add(
                    new AnalysisUnknown(
                            "UNPUBLISHED_CHANGES",
                            "HIGH",
                            "工作区存在未进入当前快照的变更，本次结果不会覆盖这些内容。"));
        }
        if (map == null) {
            result.add(
                    new AnalysisUnknown(
                            "ARCHITECTURE_UNAVAILABLE",
                            "HIGH",
                            "模块依赖分析不可用，当前只能提供检索候选，不能判断跨模块影响。"));
        } else if (map.coverage().partial()) {
            result.add(
                    new AnalysisUnknown(
                            "PARTIAL_ARCHITECTURE",
                            "HIGH",
                            map.coverage().notes().isEmpty()
                                    ? "部分代码文件未参与架构扫描。"
                                    : map.coverage().notes().get(0)));
        }
        if (candidates.isEmpty()) {
            result.add(
                    new AnalysisUnknown(
                            "NO_DIRECT_EVIDENCE",
                            "HIGH",
                            "没有检索到与任务直接匹配的当前快照代码片段，需要改写任务或手动指定入口。"));
        } else if (directModules.isEmpty()) {
            result.add(
                    new AnalysisUnknown(
                            "MODULE_NOT_RESOLVED",
                            "MEDIUM",
                            "候选代码无法稳定映射到架构模块，跨模块影响可能不完整。"));
        }
        if (tests.stream().noneMatch(TestSuggestion::existing)) {
            result.add(
                    new AnalysisUnknown(
                            "TEST_NOT_FOUND",
                            "MEDIUM",
                            "未发现直接相关测试，不能据此证明修改后的行为已被覆盖。"));
        }
        result.add(
                new AnalysisUnknown(
                        "DYNAMIC_BEHAVIOR",
                        "LOW",
                        "反射、运行时注入、动态路由和配置驱动调用不在静态模块关系的完整覆盖范围内。"));
        return List.copyOf(result);
    }

    private static EvidenceCoverage evidenceCoverage(
            CodeRepository repository,
            ProjectArchitectureMapService.ArchitectureMap map,
            ChangeIntentParser.IntentInterpretation intent,
            List<CandidateEvidence> candidates,
            Set<String> directModules,
            List<AnalysisUnknown> unknowns) {
        boolean highUnknown = unknowns.stream().anyMatch(item -> "HIGH".equals(item.severity()));
        if (candidates.isEmpty() || map == null || repository.worktreeDirty()) {
            return new EvidenceCoverage(
                    "LOW",
                    "证据覆盖低",
                    "当前证据或版本状态不足以支持完整影响判断。先处理高优先级未知项。");
        }
        if (highUnknown || candidates.size() < 3 || directModules.isEmpty()) {
            return new EvidenceCoverage(
                    "MEDIUM",
                    "证据覆盖中",
                    "候选代码来自当前快照，但跨模块或测试覆盖仍需要人工核验。");
        }
        if ("RULES".equals(intent.parserMode())) {
            return new EvidenceCoverage(
                    "MEDIUM",
                    "证据覆盖中",
                    "代码与模块证据较完整，但任务通过规则解析，需要人工确认目标和约束。 ");
        }
        return new EvidenceCoverage(
                "HIGH",
                "证据覆盖高",
                "已找到多条当前快照证据并关联模块依赖，仍需逐条确认后再修改。");
    }

    private static String semanticFallbackDetail(String reason) {
        return switch (reason == null ? "UNKNOWN" : reason) {
            case "MODEL_NOT_SELECTED" -> "未选择语义模型，本次使用本地规则拆解任务；同义词和隐含约束可能遗漏。";
            case "MODEL_UNAVAILABLE" -> "语义模型调用未返回结果，本次已自动使用本地规则解析。";
            case "MODEL_OUTPUT_INVALID" -> "语义模型输出未通过结构校验，本次已自动使用本地规则解析。";
            case "MODEL_REQUEST_FAILED" -> "语义模型配置或请求不可用，本次已自动使用本地规则解析。";
            default -> "语义模型不可用，本次已自动使用本地规则解析。";
        };
    }

    private static String moduleFor(
            String filePath, ProjectArchitectureMapService.ArchitectureMap map) {
        if (filePath == null || map == null) return null;
        String normalizedPath = filePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return map.nodes().stream()
                .filter(node -> "MODULE".equals(node.kind()))
                .filter(node -> moduleMatches(normalizedPath, node.id()))
                .sorted(
                        Comparator.comparingInt(
                                        (ProjectArchitectureMapService.ArchitectureNode node) ->
                                                node.id().length())
                                .reversed())
                .map(ProjectArchitectureMapService.ArchitectureNode::id)
                .findFirst()
                .orElse(null);
    }

    private static boolean moduleMatches(String normalizedPath, String moduleId) {
        String normalizedModule = moduleId.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalizedPath.startsWith(normalizedModule + "/")) return true;
        String[] parts = normalizedModule.split("/");
        if (parts.length < 2 || !normalizedPath.startsWith(parts[0] + "/")) return false;
        return normalizedPath.contains("/" + parts[parts.length - 1] + "/");
    }

    private static boolean isTestPath(String path) {
        String value = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return value.contains("/test/")
                || value.contains("/tests/")
                || value.contains("/__tests__/")
                || value.matches(".*(?:test|tests|spec)\\.[a-z0-9]+$")
                || value.matches(".*test\\.java$");
    }

    private static boolean hasFile(IntelligenceService.Evidence evidence) {
        return evidence.filePath() != null && !evidence.filePath().isBlank();
    }

    private static List<IntelligenceService.Evidence> currentEvidence(
            List<IntelligenceService.Evidence> evidence,
            UUID currentSnapshotId,
            EvidenceAudit evidenceAudit) {
        return evidence.stream()
                .filter(ChangeImpactAnalysisService::hasFile)
                .filter(item -> acceptEvidence(item, currentSnapshotId, evidenceAudit))
                .toList();
    }

    private static boolean acceptEvidence(
            IntelligenceService.Evidence evidence,
            UUID currentSnapshotId,
            EvidenceAudit evidenceAudit) {
        if (!currentSnapshotId.equals(evidence.snapshotId())) {
            evidenceAudit.mixedSnapshotEvidence++;
            return false;
        }
        if (!isContentHash(evidence.contentHash())) {
            evidenceAudit.missingContentHash++;
            return false;
        }
        return true;
    }

    private static boolean isContentHash(String contentHash) {
        return contentHash != null && contentHash.matches("[0-9a-f]{64}");
    }

    private static String evidenceKey(IntelligenceService.Evidence evidence) {
        if (evidence.chunkId() != null) return evidence.chunkId().toString();
        return evidence.filePath() + ":" + evidence.startLine();
    }

    private static String excerpt(String content) {
        if (content == null) return "";
        String value = content.replaceAll("\\s+", " ").trim();
        return value.length() <= 280 ? value : value.substring(0, 280) + "…";
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String normalizeTask(String task) {
        String normalized = task == null ? "" : task.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) throw new IllegalArgumentException("改动目标不能为空");
        if (normalized.length() > 1000) throw new IllegalArgumentException("改动目标不能超过 1000 个字符");
        return normalized;
    }

    public record ChangeImpactAnalysis(
            UUID analysisId,
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            Instant generatedAt,
            String task,
            ChangeIntentParser.IntentInterpretation intent,
            List<RetrievalQuery> retrievalQueries,
            EvidenceCoverage evidenceCoverage,
            List<CandidateEvidence> candidates,
            List<ModuleImpact> modules,
            List<DependencyImpact> dependencies,
            List<ProjectArchitectureMapService.ArchitectureRisk> risks,
            List<TestSuggestion> tests,
            List<AnalysisUnknown> unknowns) {}

    public record EvidenceCoverage(String level, String label, String detail) {}

    public record CandidateEvidence(
            UUID chunkId,
            String sourceType,
            UUID snapshotId,
            String filePath,
            String symbolName,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            String excerpt,
            String contentHash,
            double score,
            List<String> channels,
            List<String> matchedQueries,
            String moduleId) {}

    public record ModuleImpact(
            String moduleId,
            String label,
            String role,
            int evidenceCount,
            int incomingWeight,
            int outgoingWeight) {}

    public record DependencyImpact(
            String source,
            String target,
            String relation,
            int weight,
            List<DependencyEvidenceSample> samples) {}

    public record DependencyEvidenceSample(
            String filePath,
            String relatedFilePath,
            UUID snapshotId,
            String contentHash) {}

    public record TestSuggestion(
            String filePath,
            Integer startLine,
            Integer endLine,
            UUID snapshotId,
            String contentHash,
            boolean existing,
            String reason) {}

    public record AnalysisUnknown(String code, String severity, String detail) {}

    public record RetrievalQuery(String query, String purpose, int hitCount) {}

    private record QueryPlan(String query, String purpose, int limit) {}

    private record EvidenceAggregate(
            IntelligenceService.Evidence evidence,
            double aggregateScore,
            List<String> matchedQueries) {}

    private static final class EvidenceAudit {
        private int mixedSnapshotEvidence;
        private int missingContentHash;
        private int architectureSnapshotMismatch;
        private int dependencyProvenanceMissing;
    }
}
