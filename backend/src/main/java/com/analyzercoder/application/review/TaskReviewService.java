package com.analyzercoder.application.review;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
import com.analyzercoder.application.architecture.ProjectArchitectureMapService.ArchitectureMap;
import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeException;
import com.analyzercoder.application.change.RepositoryChangeService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.project.EngineeringProjectService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.infrastructure.persistence.mapper.TaskReviewMapper;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 编排真实 Git 变化、符号、工程知识和不可变审查持久化。 */
@Service
public class TaskReviewService {
    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_REFERENCE_SCAN = 100;

    private final CodeRepositoryStore repositories;
    private final RepositoryChangeService changes;
    private final ChangedSymbolResolver symbols;
    private final TaskContextMatcher contextMatcher;
    private final IntelligenceService intelligence;
    private final EngineeringProjectService engineeringProjects;
    private final ProjectArchitectureMapService architecture;
    private final TaskReviewModelSummaryService modelSummaries;
    private final TaskReviewMapper mapper;
    private final ObjectMapper json;

    public TaskReviewService(
            CodeRepositoryStore repositories,
            RepositoryChangeService changes,
            ChangedSymbolResolver symbols,
            TaskContextMatcher contextMatcher,
            IntelligenceService intelligence,
            EngineeringProjectService engineeringProjects,
            ProjectArchitectureMapService architecture,
            TaskReviewModelSummaryService modelSummaries,
            TaskReviewMapper mapper,
            ObjectMapper json) {
        this.repositories = repositories;
        this.changes = changes;
        this.symbols = symbols;
        this.contextMatcher = contextMatcher;
        this.intelligence = intelligence;
        this.engineeringProjects = engineeringProjects;
        this.architecture = architecture;
        this.modelSummaries = modelSummaries;
        this.mapper = mapper;
        this.json = json;
    }

    public TaskReviewResult create(
            CodeRepositoryId repositoryId, UUID createdBy, TaskReviewRequest request) {
        return create(repositoryId, createdBy, request, null);
    }

    /** 使用提供方已经获取并解析的 PR/MR Patch，避免再从本地工作区推断另一份变化。 */
    public TaskReviewResult createExternal(
            CodeRepositoryId repositoryId,
            UUID createdBy,
            TaskReviewRequest request,
            RepositoryChange externalChange) {
        Objects.requireNonNull(externalChange, "externalChange must not be null");
        if (request == null || request.changeSource() != GitChangeRequest.Source.COMMIT_RANGE) {
            throw new IllegalArgumentException("PR/MR 审查必须使用提交范围");
        }
        if (externalChange.source() != GitChangeRequest.Source.COMMIT_RANGE
                || !sameCommit(request.baseRef(), externalChange.baseCommit())
                || !sameCommit(request.headRef(), externalChange.headCommit())) {
            throw new TaskReviewException(
                    "EXTERNAL_CHANGE_VERSION_MISMATCH", "PR/MR Patch 与审查请求的提交边界不一致");
        }
        return create(repositoryId, createdBy, request, () -> externalChange);
    }

    private TaskReviewResult create(
            CodeRepositoryId repositoryId,
            UUID createdBy,
            TaskReviewRequest request,
            Supplier<RepositoryChange> externalChange) {
        if (createdBy == null) {
            throw new IllegalArgumentException("创建账号不能为空");
        }
        Objects.requireNonNull(request, "request must not be null");
        CodeRepository repository = repository(repositoryId);
        if (repository.currentSnapshotId() == null || repository.currentSnapshotPath() == null) {
            throw new TaskReviewException("CURRENT_SNAPSHOT_REQUIRED", "仓库尚未发布可用于审查的代码快照");
        }

        TaskReviewRow running = running(repository, createdBy, request);
        if (mapper.insertRunning(running) == 0) {
            TaskReviewRow existing =
                    mapper.findByClientRequest(
                            repositoryId.value(), createdBy, request.clientRequestId());
            if (existing == null) {
                throw new TaskReviewException("IDEMPOTENCY_LOOKUP_FAILED", "无法读取幂等审查请求");
            }
            requireSameRequest(existing, request);
            return result(existing);
        }

        RepositoryChange change = null;
        try {
            change =
                    externalChange == null
                            ? changes.analyze(request.gitRequest(repository.path()))
                            : externalChange.get();
            requireCurrentSnapshotCommit(repository, change);
            ChangedSymbolResolver.ResolutionResult changedSymbols =
                    symbols.resolve(repository, change);
            ModuleContext modules = moduleContext(repository, change);
            TaskContextMatcher.TaskContextResult context =
                    contextMatcher.match(
                            new TaskContextMatcher.MatchInput(
                                    repository.id().value(),
                                    repository.currentSnapshotId().value(),
                                    change,
                                    changedSymbols,
                                    knowledge(repository.id().value(), createdBy),
                                    references(repository.id().value(), request.task()),
                                    modules.modulesByPath(),
                                    modules.available(),
                                    Map.of()));
            Instant finishedAt = Instant.now();
            TaskReviewResult deterministic =
                    new TaskReviewResult(
                            running.id(),
                            TaskReviewResult.Status.COMPLETED,
                            repository.id().value(),
                            repository.currentSnapshotId().value(),
                            createdBy,
                            request.clientRequestId(),
                            request.modelConfigId(),
                            request.task(),
                            request.changeSource().name(),
                            request.baseRef(),
                            request.headRef(),
                            change,
                            changedSymbols.symbols(),
                            context.applicableKnowledge(),
                            context.referenceCandidates(),
                            context.requiredTests(),
                            context.requiredApprovals(),
                            context.staleKnowledge(),
                            context.unknowns(),
                            null,
                            null,
                            running.createdAt(),
                            finishedAt);
            TaskReviewModelSummaryService.Attempt modelAttempt =
                    modelSummaries.summarize(deterministic);
            TaskReviewResult completed =
                    deterministic.withModelSummary(modelAttempt.summary(), modelAttempt.state());
            int updated =
                    mapper.complete(
                            repository.id().value(),
                            running.id(),
                            change.baseCommit(),
                            change.headCommit(),
                            change.worktreeDigest(),
                            write(completed));
            if (updated != 1) {
                throw new TaskReviewException(
                        "SNAPSHOT_CHANGED_DURING_REVIEW", "审查期间当前发布快照发生变化，请重新发起审查");
            }
            return completed;
        } catch (RuntimeException exception) {
            return fail(running, change, exception);
        }
    }

    public List<TaskReviewResult.ReviewSummary> list(
            CodeRepositoryId repositoryId, int limit, int offset) {
        repository(repositoryId);
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        int safeOffset = Math.max(0, offset);
        return mapper.findByRepository(repositoryId.value(), safeLimit, safeOffset).stream()
                .map(this::summary)
                .toList();
    }

    public TaskReviewResult get(CodeRepositoryId repositoryId, UUID reviewId) {
        repository(repositoryId);
        TaskReviewRow row = mapper.findById(repositoryId.value(), reviewId);
        if (row == null) {
            throw new TaskReviewException("TASK_REVIEW_NOT_FOUND", "任务审查不存在");
        }
        return result(row);
    }

    /** 返回最近审查中对指定文件的确定性引用，不把模型文字或检索候选当作代码关系。 */
    public ReviewReferenceResult references(
            CodeRepositoryId repositoryId, String filePath, int limit) {
        CodeRepository repository = repository(repositoryId);
        String normalizedPath = normalizeFilePath(filePath);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<TaskReviewRow> rows =
                mapper.findByRepository(repositoryId.value(), MAX_REFERENCE_SCAN + 1, 0);
        boolean truncated = rows.size() > MAX_REFERENCE_SCAN;
        List<ReviewReference> references =
                rows.stream()
                        .limit(MAX_REFERENCE_SCAN)
                        .map(this::result)
                        .filter(review -> review.status() == TaskReviewResult.Status.COMPLETED)
                        .map(review -> reviewReference(repository, review, normalizedPath))
                        .filter(Objects::nonNull)
                        .limit(safeLimit)
                        .toList();
        return new ReviewReferenceResult(
                references, Math.min(rows.size(), MAX_REFERENCE_SCAN), truncated);
    }

    private TaskReviewResult fail(
            TaskReviewRow running, RepositoryChange change, RuntimeException exception) {
        Error error = error(exception);
        mapper.fail(
                running.repositoryId(),
                running.id(),
                change == null ? null : change.baseCommit(),
                change == null ? null : change.headCommit(),
                change == null ? null : change.worktreeDigest(),
                error.code(),
                error.message());
        TaskReviewRow failed = mapper.findById(running.repositoryId(), running.id());
        if (failed != null) {
            return result(failed);
        }
        return failedResult(running, error, change, Instant.now());
    }

    private static ReviewReference reviewReference(
            CodeRepository repository, TaskReviewResult review, String filePath) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        if (review.change() != null
                && review.change().changes().stream()
                        .anyMatch(
                                change ->
                                        filePath.equals(normalizeNullablePath(change.oldPath()))
                                                || filePath.equals(
                                                        normalizeNullablePath(change.newPath())))) {
            roles.add("CHANGED_FILE");
        }
        review.changedSymbols().stream()
                .filter(symbol -> filePath.equals(normalizeNullablePath(symbol.filePath())))
                .forEach(
                        symbol -> {
                            roles.add("CHANGED_SYMBOL");
                            symbols.add(symbol.name());
                        });
        review.applicableKnowledge().stream()
                .flatMap(match -> match.reasons().stream())
                .filter(reason -> filePath.equals(normalizeNullablePath(reason.evidence().filePath())))
                .findAny()
                .ifPresent(ignored -> roles.add("KNOWLEDGE_EVIDENCE"));
        addFindingRole(review.requiredTests(), filePath, "REQUIRED_TEST", roles);
        addFindingRole(review.requiredApprovals(), filePath, "REQUIRED_APPROVAL", roles);
        addFindingRole(review.unknowns(), filePath, "UNKNOWN", roles);
        if (roles.isEmpty()) {
            return null;
        }
        boolean currentSnapshot =
                repository.currentSnapshotId() != null
                        && repository.currentSnapshotId().value().equals(review.snapshotId());
        return new ReviewReference(
                review.reviewId(),
                review.task(),
                review.changeSource(),
                review.snapshotId(),
                currentSnapshot,
                List.copyOf(roles),
                List.copyOf(symbols),
                review.createdAt(),
                review.finishedAt());
    }

    private static void addFindingRole(
            List<TaskReviewFinding> findings,
            String filePath,
            String role,
            Set<String> roles) {
        boolean matched =
                findings.stream()
                        .flatMap(finding -> finding.evidence().stream())
                        .anyMatch(
                                reason ->
                                        filePath.equals(
                                                normalizeNullablePath(
                                                        reason.evidence().filePath())));
        if (matched) {
            roles.add(role);
        }
    }

    private static boolean sameCommit(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
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

    private ModuleContext moduleContext(CodeRepository repository, RepositoryChange change) {
        try {
            ArchitectureMap map = architecture.map(repository.id());
            if (!repository.currentSnapshotId().value().toString().equals(map.snapshotId())
                    || !Objects.equals(repository.currentCommit(), map.commitSha())) {
                return ModuleContext.unavailable();
            }
            Set<String> knownModules = new LinkedHashSet<>();
            map.nodes().stream()
                    .filter(node -> "MODULE".equals(node.kind()))
                    .map(ProjectArchitectureMapService.ArchitectureNode::id)
                    .forEach(knownModules::add);
            LinkedHashMap<String, Set<String>> byPath = new LinkedHashMap<>();
            for (RepositoryChange.FileChange file : change.changes()) {
                for (String path : new String[] {file.oldPath(), file.newPath()}) {
                    if (path == null) {
                        continue;
                    }
                    String module = ProjectArchitectureMapService.moduleForPath(path);
                    if (knownModules.contains(module)) {
                        byPath.put(path, Set.of(module));
                    }
                }
            }
            return new ModuleContext(Map.copyOf(byPath), true);
        } catch (RuntimeException exception) {
            return ModuleContext.unavailable();
        }
    }

    private List<KnowledgeMatch.Candidate> knowledge(UUID repositoryId, UUID actorId) {
        EngineeringProjectService.ReviewTopology topology =
                engineeringProjects.reviewTopology(repositoryId, actorId);
        Map<UUID, List<KnowledgeMatch.CrossRepositoryBinding>> bindingsByRepository =
                topology.repositories().stream()
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        EngineeringProjectService.RepositoryBinding::sourceRepositoryId,
                                        LinkedHashMap::new,
                                        java.util.stream.Collectors.mapping(
                                                binding ->
                                                        new KnowledgeMatch.CrossRepositoryBinding(
                                                                binding.engineeringProjectId(),
                                                                binding.sourceRepositoryId(),
                                                                binding.targetServiceName(),
                                                                binding.contracts().stream()
                                                                        .map(
                                                                                contract ->
                                                                                        new KnowledgeMatch
                                                                                                .ContractScopeBinding(
                                                                                                contract.contractId(),
                                                                                                contract.targetEvidencePath(),
                                                                                                contract.current()))
                                                                        .toList()),
                                                java.util.stream.Collectors.toList())));
        LinkedHashSet<UUID> sourceRepositories = new LinkedHashSet<>();
        sourceRepositories.add(repositoryId);
        sourceRepositories.addAll(bindingsByRepository.keySet());
        return sourceRepositories.stream()
                .flatMap(sourceRepositoryId ->
                        intelligence.cards(sourceRepositoryId, true).stream()
                .map(
                        card ->
                                new KnowledgeMatch.Candidate(
                                        card.id(),
                                        card.repositoryId(),
                                        card.title(),
                                        card.knowledgeKind(),
                                        card.severity(),
                                        card.enforcement(),
                                        card.ownerAccountId(),
                                        card.scope(),
                                        card.obligations(),
                                        card.revision(),
                                        card.publicationStatus(),
                                        card.reviewStatus(),
                                        card.sourceVersionStatus(),
                                        card.codeReferences().stream()
                                                .map(
                                                        reference ->
                                                                new KnowledgeScopeMatcher
                                                                        .BoundCodeReference(
                                                                        reference.chunkId(),
                                                                        reference.filePath(),
                                                                        reference.symbolName(),
                                                                        reference.contentHash()))
                                                .toList(),
                                        bindingsByRepository.getOrDefault(
                                                sourceRepositoryId, List.of()))))
                .toList();
    }

    private List<KnowledgeMatch.RetrievalReference> references(UUID repositoryId, String task) {
        if (task == null || task.isBlank()) {
            return List.of();
        }
        return intelligence.reviewKnowledgeReferences(repositoryId, task, 10).stream()
                .map(
                        hit ->
                                new KnowledgeMatch.RetrievalReference(
                                        hit.knowledgeId(), hit.source(), hit.detail()))
                .toList();
    }

    private static void requireCurrentSnapshotCommit(
            CodeRepository repository, RepositoryChange change) {
        String effectiveCommit =
                change.source() == GitChangeRequest.Source.WORKTREE
                        ? change.baseCommit()
                        : change.headCommit();
        if (repository.currentCommit() == null
                || !repository.currentCommit().equals(effectiveCommit)) {
            throw new TaskReviewException("CHANGE_HEAD_NOT_CURRENT_SNAPSHOT", "审查版本与当前发布快照的提交不一致");
        }
    }

    private CodeRepository repository(CodeRepositoryId repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("仓库 ID 不能为空");
        }
        return repositories
                .findById(repositoryId)
                .orElseThrow(() -> new TaskReviewException("REPOSITORY_NOT_FOUND", "代码仓库不存在"));
    }

    private static TaskReviewRow running(
            CodeRepository repository, UUID createdBy, TaskReviewRequest request) {
        return new TaskReviewRow(
                UUID.randomUUID(),
                repository.id().value(),
                createdBy,
                request.clientRequestId(),
                request.task(),
                request.changeSource().name(),
                request.baseRef(),
                request.headRef(),
                request.modelConfigId(),
                null,
                null,
                repository.currentSnapshotId().value(),
                null,
                TaskReviewResult.Status.RUNNING.name(),
                null,
                null,
                null,
                Instant.now(),
                null);
    }

    private static void requireSameRequest(TaskReviewRow existing, TaskReviewRequest request) {
        boolean same =
                Objects.equals(existing.task(), request.task())
                        && Objects.equals(existing.changeSource(), request.changeSource().name())
                        && Objects.equals(existing.baseRef(), request.baseRef())
                        && Objects.equals(existing.headRef(), request.headRef())
                        && Objects.equals(existing.modelConfigId(), request.modelConfigId());
        if (!same) {
            throw new TaskReviewException("IDEMPOTENCY_KEY_CONFLICT", "clientRequestId 已用于不同的审查请求");
        }
    }

    private TaskReviewResult result(TaskReviewRow row) {
        if (row.resultPayload() != null && !row.resultPayload().isBlank()) {
            try {
                return json.readValue(row.resultPayload(), TaskReviewResult.class);
            } catch (JsonProcessingException exception) {
                throw new TaskReviewException(
                        "TASK_REVIEW_RESULT_INVALID", "无法恢复已保存的任务审查结果", exception);
            }
        }
        TaskReviewResult.Status status = TaskReviewResult.Status.valueOf(row.status());
        TaskReviewResult.ErrorDetail error =
                row.errorCode() == null
                        ? null
                        : new TaskReviewResult.ErrorDetail(row.errorCode(), row.errorMessage());
        return new TaskReviewResult(
                row.id(),
                status,
                row.repositoryId(),
                row.snapshotId(),
                row.createdBy(),
                row.clientRequestId(),
                row.modelConfigId(),
                row.task(),
                row.changeSource(),
                row.baseRef(),
                row.headRef(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                error,
                row.createdAt(),
                row.finishedAt());
    }

    private TaskReviewResult.ReviewSummary summary(TaskReviewRow row) {
        TaskReviewResult result = result(row);
        return new TaskReviewResult.ReviewSummary(
                result.reviewId(),
                result.status(),
                result.repositoryId(),
                result.snapshotId(),
                result.createdBy(),
                result.clientRequestId(),
                result.task(),
                result.changeSource(),
                result.change() == null ? 0 : result.change().changes().size(),
                result.changedSymbols().size(),
                result.applicableKnowledge().size(),
                result.requiredTests().size(),
                result.requiredApprovals().size(),
                result.staleKnowledge().size(),
                result.unknowns().size(),
                result.error(),
                result.createdAt(),
                result.finishedAt());
    }

    private String write(TaskReviewResult result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new TaskReviewException(
                    "TASK_REVIEW_SERIALIZATION_FAILED", "无法保存任务审查结果", exception);
        }
    }

    private static Error error(RuntimeException exception) {
        if (exception instanceof TaskReviewException review) {
            return new Error(review.code(), safeMessage(review.getMessage(), "任务审查失败"));
        }
        if (exception instanceof RepositoryChangeException change) {
            return new Error(change.code(), safeMessage(change.getMessage(), "Git 变更分析失败"));
        }
        return new Error(
                "TASK_REVIEW_ANALYSIS_FAILED", safeMessage(exception.getMessage(), "任务审查分析失败"));
    }

    private static String safeMessage(String message, String fallback) {
        String value = message == null || message.isBlank() ? fallback : message;
        return value.substring(0, Math.min(value.length(), 500));
    }

    private static TaskReviewResult failedResult(
            TaskReviewRow running, Error error, RepositoryChange change, Instant finishedAt) {
        return new TaskReviewResult(
                running.id(),
                TaskReviewResult.Status.FAILED,
                running.repositoryId(),
                running.snapshotId(),
                running.createdBy(),
                running.clientRequestId(),
                running.modelConfigId(),
                running.task(),
                running.changeSource(),
                running.baseRef(),
                running.headRef(),
                change,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                new TaskReviewResult.ErrorDetail(error.code(), error.message()),
                running.createdAt(),
                finishedAt);
    }

    private record Error(String code, String message) {}

    public record ReviewReferenceResult(
            List<ReviewReference> references, int scannedReviewCount, boolean historyTruncated) {
        public ReviewReferenceResult {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    public record ReviewReference(
            UUID reviewId,
            String task,
            String changeSource,
            UUID snapshotId,
            boolean currentSnapshot,
            List<String> roles,
            List<String> symbols,
            Instant createdAt,
            Instant finishedAt) {
        public ReviewReference {
            roles = roles == null ? List.of() : List.copyOf(roles);
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    private record ModuleContext(Map<String, Set<String>> modulesByPath, boolean available) {
        static ModuleContext unavailable() {
            return new ModuleContext(Map.of(), false);
        }
    }
}
