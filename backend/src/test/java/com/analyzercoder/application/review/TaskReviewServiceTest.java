package com.analyzercoder.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.application.project.EngineeringProjectService;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.TaskReviewMapper;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewRow;
import com.analyzercoder.infrastructure.repository.InMemoryCodeRepositoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskReviewServiceTest {
    @TempDir Path workspace;

    private final UUID actorId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final String commit = "b".repeat(40);
    private final InMemoryCodeRepositoryStore repositories = new InMemoryCodeRepositoryStore();
    private final RepositoryChangeService changes = mock(RepositoryChangeService.class);
    private final ChangedSymbolResolver symbols = mock(ChangedSymbolResolver.class);
    private final IntelligenceService intelligence = mock(IntelligenceService.class);
    private final EngineeringProjectService engineeringProjects =
            mock(EngineeringProjectService.class);
    private final ProjectArchitectureMapService architecture =
            mock(ProjectArchitectureMapService.class);
    private final TaskReviewModelSummaryService modelSummaries =
            mock(TaskReviewModelSummaryService.class);
    private final FakeTaskReviewMapper mapper = new FakeTaskReviewMapper();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private CodeRepository repository;
    private TaskReviewService service;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        repository =
                new CodeRepository(
                        CodeRepositoryId.newId(),
                        "review-project",
                        workspace,
                        RepositorySourceType.LOCAL_GIT,
                        "main",
                        commit,
                        null,
                        false,
                        RepositorySnapshotId.of(snapshotId),
                        workspace,
                        workspace.resolve(".codegraph"),
                        now,
                        now,
                        now,
                        now);
        repositories.save(repository);
        service =
                new TaskReviewService(
                        repositories,
                        changes,
                        symbols,
                        new TaskContextMatcher(
                                new KnowledgeScopeMatcher(new RepositoryGlobMatcher())),
                        intelligence,
                        engineeringProjects,
                        architecture,
                        modelSummaries,
                        mapper,
                        json);
        when(modelSummaries.summarize(
                        org.mockito.ArgumentMatchers.any(TaskReviewResult.class)))
                .thenAnswer(
                        invocation -> {
                            TaskReviewResult review = invocation.getArgument(0);
                            TaskReviewResult.ModelSummaryState state =
                                    review.modelConfigId() == null
                                            ? TaskReviewResult.ModelSummaryState.notRequested()
                                            : TaskReviewResult.ModelSummaryState.unavailable(
                                                    "MODEL_PROVIDER_UNAVAILABLE",
                                                    "测试模型不可用");
                            return new TaskReviewModelSummaryService.Attempt(null, state);
                        });
        when(changes.analyze(org.mockito.ArgumentMatchers.any())).thenReturn(change());
        when(symbols.resolve(repository, change())).thenReturn(changedSymbols());
        when(intelligence.cards(repository.id().value(), true)).thenReturn(List.of(card()));
        when(engineeringProjects.reviewTopology(repository.id().value(), actorId))
                .thenReturn(new EngineeringProjectService.ReviewTopology(List.of()));
        when(intelligence.reviewKnowledgeReferences(repository.id().value(), "增加退款审批", 10))
                .thenReturn(List.of());
        when(architecture.map(repository.id())).thenReturn(architectureMap());
    }

    @Test
    void createsRestorableCompletedReviewAndReusesTheIdempotentResult() {
        UUID requestId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        TaskReviewRequest request =
                new TaskReviewRequest(
                        requestId,
                        "增加退款审批",
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "base",
                        "head",
                        modelConfigId);

        TaskReviewResult first = service.create(repository.id(), actorId, request);
        TaskReviewResult repeated = service.create(repository.id(), actorId, request);
        TaskReviewResult restored = service.get(repository.id(), first.reviewId());

        assertThat(first.status()).isEqualTo(TaskReviewResult.Status.COMPLETED);
        assertThat(first.snapshotId()).isEqualTo(snapshotId);
        assertThat(first.modelConfigId()).isEqualTo(modelConfigId);
        assertThat(first.modelSummary()).isNull();
        assertThat(first.modelSummaryState().status())
                .isEqualTo(TaskReviewResult.ModelSummaryStatus.UNAVAILABLE);
        assertThat(first.changedSymbols()).hasSize(1);
        assertThat(first.applicableKnowledge()).hasSize(1);
        assertThat(first.requiredTests())
                .singleElement()
                .satisfies(
                        finding ->
                                assertThat(finding.status())
                                        .isEqualTo(
                                                TaskReviewFinding.FindingStatus
                                                        .REQUIRED_NOT_REPORTED));
        assertThat(repeated).isEqualTo(first);
        assertThat(restored).isEqualTo(first);
        assertThat(service.list(repository.id(), 50, 0))
                .singleElement()
                .satisfies(
                        summary -> {
                            assertThat(summary.reviewId()).isEqualTo(first.reviewId());
                            assertThat(summary.changedFileCount()).isEqualTo(1);
                            assertThat(summary.applicableKnowledgeCount()).isEqualTo(1);
                        });
        verify(changes, times(1)).analyze(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsReusingTheSameClientRequestForDifferentInput() {
        UUID requestId = UUID.randomUUID();
        service.create(
                repository.id(),
                actorId,
                new TaskReviewRequest(
                        requestId,
                        "增加退款审批",
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "base",
                        "head",
                        null));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        repository.id(),
                                        actorId,
                                        new TaskReviewRequest(
                                                requestId,
                                                "不同任务",
                                                GitChangeRequest.Source.COMMIT_RANGE,
                                                "base",
                                                "head",
                                                null)))
                .isInstanceOfSatisfying(
                        TaskReviewException.class,
                        exception ->
                                assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void snapshotSwitchPreventsPublishingAMixedResult() {
        mapper.allowComplete = false;

        TaskReviewResult result =
                service.create(
                        repository.id(),
                        actorId,
                        new TaskReviewRequest(
                                UUID.randomUUID(),
                                "增加退款审批",
                                GitChangeRequest.Source.COMMIT_RANGE,
                                "base",
                                "head",
                                null));

        assertThat(result.status()).isEqualTo(TaskReviewResult.Status.FAILED);
        assertThat(result.error().code()).isEqualTo("SNAPSHOT_CHANGED_DURING_REVIEW");
        assertThat(result.change()).isNull();
        assertThat(mapper.rows.values())
                .singleElement()
                .satisfies(row -> assertThat(row.resultPayload()).isNull());
    }

    @Test
    void rejectsRepositoryWithoutPublishedSnapshotBeforeCreatingARecord() {
        Instant now = Instant.now();
        CodeRepository missing =
                new CodeRepository(
                        CodeRepositoryId.newId(),
                        "missing-snapshot",
                        workspace,
                        RepositorySourceType.LOCAL_GIT,
                        "main",
                        commit,
                        null,
                        false,
                        null,
                        null,
                        workspace.resolve(".codegraph"),
                        null,
                        now,
                        now,
                        now);
        repositories.save(missing);

        assertThatThrownBy(
                        () ->
                                service.create(
                                        missing.id(),
                                        actorId,
                                        new TaskReviewRequest(
                                                UUID.randomUUID(),
                                                null,
                                                GitChangeRequest.Source.WORKTREE,
                                                "HEAD",
                                                null,
                                                null)))
                .isInstanceOfSatisfying(
                        TaskReviewException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo("CURRENT_SNAPSHOT_REQUIRED"));
        assertThat(mapper.rows).isEmpty();
    }

    @Test
    void externalReviewUsesTheProvidedPatchFactsWithoutReReadingLocalGit() {
        RepositoryChange external = change();
        TaskReviewRequest request =
                new TaskReviewRequest(
                        UUID.randomUUID(),
                        "增加退款审批",
                        GitChangeRequest.Source.COMMIT_RANGE,
                        external.baseCommit(),
                        external.headCommit(),
                        null);

        TaskReviewResult result =
                service.createExternal(repository.id(), actorId, request, external);

        assertThat(result.status()).isEqualTo(TaskReviewResult.Status.COMPLETED);
        assertThat(result.change()).isEqualTo(external);
        verifyNoInteractions(changes);
    }

    @Test
    void externalReviewRejectsMismatchedCommitBoundariesBeforeCreatingARecord() {
        TaskReviewRequest request =
                new TaskReviewRequest(
                        UUID.randomUUID(),
                        null,
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "c".repeat(40),
                        commit,
                        null);

        assertThatThrownBy(
                        () -> service.createExternal(repository.id(), actorId, request, change()))
                .isInstanceOfSatisfying(
                        TaskReviewException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo("EXTERNAL_CHANGE_VERSION_MISMATCH"));
        assertThat(mapper.rows).isEmpty();
    }

    @Test
    void loadsVisibleKnowledgeFromAConfiguredSharedEngineeringProject() {
        UUID sourceRepositoryId = UUID.randomUUID();
        UUID engineeringProjectId = UUID.randomUUID();
        IntelligenceService.KnowledgeCard crossCard = crossRepositoryCard(sourceRepositoryId);
        when(engineeringProjects.reviewTopology(repository.id().value(), actorId))
                .thenReturn(
                        new EngineeringProjectService.ReviewTopology(
                                List.of(
                                        new EngineeringProjectService.RepositoryBinding(
                                                engineeringProjectId,
                                                sourceRepositoryId,
                                                "refund-service",
                                                List.of()))));
        when(intelligence.cards(sourceRepositoryId, true)).thenReturn(List.of(crossCard));

        TaskReviewResult result =
                service.create(
                        repository.id(),
                        actorId,
                        new TaskReviewRequest(
                                UUID.randomUUID(),
                                "增加退款审批",
                                GitChangeRequest.Source.COMMIT_RANGE,
                                "base",
                                "head",
                                null));

        assertThat(result.applicableKnowledge())
                .filteredOn(item -> item.knowledgeId().equals(crossCard.id()))
                .singleElement()
                .satisfies(
                        match -> {
                            assertThat(match.reasons())
                                    .extracting(KnowledgeMatchReason::kind)
                                    .contains(
                                            KnowledgeMatchReason.MatchKind.SERVICE,
                                            KnowledgeMatchReason.MatchKind.PATH_PATTERN);
                            assertThat(match.sources())
                                    .extracting(source -> source.sourceType().name())
                                    .contains("PLATFORM_FACT");
                        });
    }

    @Test
    void findsOnlyDeterministicReferencesForTheRequestedRepositoryRelativeFile() {
        service.create(
                repository.id(),
                actorId,
                new TaskReviewRequest(
                        UUID.randomUUID(),
                        "增加退款审批",
                        GitChangeRequest.Source.COMMIT_RANGE,
                        "base",
                        "head",
                        null));

        TaskReviewService.ReviewReferenceResult result =
                service.references(repository.id(), "src/refund/RefundService.java", 10);

        assertThat(result.scannedReviewCount()).isEqualTo(1);
        assertThat(result.historyTruncated()).isFalse();
        assertThat(result.references())
                .singleElement()
                .satisfies(
                        reference -> {
                            assertThat(reference.currentSnapshot()).isTrue();
                            assertThat(reference.roles())
                                    .containsExactly(
                                            "CHANGED_FILE",
                                            "CHANGED_SYMBOL",
                                            "KNOWLEDGE_EVIDENCE",
                                            "REQUIRED_TEST",
                                            "REQUIRED_APPROVAL");
                            assertThat(reference.symbols()).containsExactly("approveRefund");
                        });
        assertThatThrownBy(() -> service.references(repository.id(), "../secret", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RepositoryChange change() {
        return new RepositoryChange(
                GitChangeRequest.Source.COMMIT_RANGE,
                "a".repeat(40),
                commit,
                null,
                false,
                List.of(
                        new RepositoryChange.FileChange(
                                RepositoryChange.ChangeType.MODIFIED,
                                "src/refund/RefundService.java",
                                "src/refund/RefundService.java",
                                false,
                                1L,
                                1L,
                                List.of(new RepositoryChange.Hunk(10, 1, 10, 1)))),
                List.of());
    }

    private ChangedSymbolResolver.ResolutionResult changedSymbols() {
        return new ChangedSymbolResolver.ResolutionResult(
                List.of(
                        new ChangedSymbolResolver.ChangedSymbol(
                                "refund#approveRefund",
                                "approveRefund",
                                "METHOD",
                                "src/refund/RefundService.java",
                                8,
                                14,
                                RepositoryChange.ChangeType.MODIFIED,
                                10,
                                10,
                                0,
                                false,
                                ChangedSymbolResolver.Resolution.SOURCE_DECLARATION,
                                List.of())),
                List.of());
    }

    private IntelligenceService.KnowledgeCard card() {
        Instant now = Instant.now();
        return new IntelligenceService.KnowledgeCard(
                UUID.randomUUID(),
                repository.id().value(),
                "退款变更规则",
                "RULE",
                "退款变更必须运行测试",
                "退款变更必须运行测试",
                List.of("refund"),
                KnowledgeKind.BUSINESS_RULE,
                KnowledgeSeverity.CRITICAL,
                KnowledgeEnforcement.REQUIRED,
                UUID.randomUUID(),
                new KnowledgeScope(
                        List.of("src/refund/**"), List.of("approveRefund"), List.of("src")),
                new KnowledgeObligations(List.of("./mvnw test"), List.of(actorId), List.of()),
                snapshotId,
                "verified",
                "PUBLISHED",
                1,
                now,
                now,
                commit,
                "CURRENT",
                now,
                "APPROVED",
                actorId,
                now,
                List.of(),
                List.of());
    }

    private IntelligenceService.KnowledgeCard crossRepositoryCard(UUID sourceRepositoryId) {
        Instant now = Instant.now();
        return new IntelligenceService.KnowledgeCard(
                UUID.randomUUID(),
                sourceRepositoryId,
                "跨仓退款契约",
                "API",
                "退款服务变更必须执行契约测试",
                "退款服务变更必须执行契约测试",
                List.of("refund"),
                KnowledgeKind.API_CONTRACT,
                KnowledgeSeverity.CRITICAL,
                KnowledgeEnforcement.REQUIRED,
                UUID.randomUUID(),
                new KnowledgeScope(
                        List.of("src/refund/**"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("refund-service"),
                        List.of()),
                new KnowledgeObligations(List.of("contract-test"), List.of(), List.of()),
                UUID.randomUUID(),
                "verified",
                "PUBLISHED",
                3,
                now,
                now,
                commit,
                "CURRENT",
                now,
                "APPROVED",
                actorId,
                now,
                List.of(),
                List.of());
    }

    private ProjectArchitectureMapService.ArchitectureMap architectureMap() {
        return new ProjectArchitectureMapService.ArchitectureMap(
                repository.id().value().toString(),
                snapshotId.toString(),
                commit,
                Instant.now(),
                List.of(
                        new ProjectArchitectureMapService.ArchitectureNode(
                                "src", "src", "src", "MODULE", 1, 1, "java", null)),
                List.of(),
                List.of(),
                new ProjectArchitectureMapService.AnalysisCoverage(
                        1, 1, 0, 0, 0, false, List.of()));
    }

    private static final class FakeTaskReviewMapper implements TaskReviewMapper {
        private final Map<UUID, TaskReviewRow> rows = new LinkedHashMap<>();
        private boolean allowComplete = true;

        @Override
        public int insertRunning(TaskReviewRow row) {
            boolean duplicate =
                    rows.values().stream()
                            .anyMatch(
                                    existing ->
                                            existing.repositoryId().equals(row.repositoryId())
                                                    && existing.createdBy().equals(row.createdBy())
                                                    && existing.clientRequestId()
                                                            .equals(row.clientRequestId()));
            if (duplicate) {
                return 0;
            }
            rows.put(row.id(), row);
            return 1;
        }

        @Override
        public TaskReviewRow findByClientRequest(
                UUID repositoryId, UUID createdBy, UUID clientRequestId) {
            return rows.values().stream()
                    .filter(row -> row.repositoryId().equals(repositoryId))
                    .filter(row -> row.createdBy().equals(createdBy))
                    .filter(row -> row.clientRequestId().equals(clientRequestId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public TaskReviewRow findById(UUID repositoryId, UUID id) {
            TaskReviewRow row = rows.get(id);
            return row != null && row.repositoryId().equals(repositoryId) ? row : null;
        }

        @Override
        public List<TaskReviewRow> findByRepository(UUID repositoryId, int limit, int offset) {
            return rows.values().stream()
                    .filter(row -> row.repositoryId().equals(repositoryId))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public int complete(
                UUID repositoryId,
                UUID id,
                String baseCommit,
                String headCommit,
                String worktreeDigest,
                String resultPayload) {
            TaskReviewRow row = findById(repositoryId, id);
            if (!allowComplete || row == null || !"RUNNING".equals(row.status())) {
                return 0;
            }
            rows.put(
                    id,
                    terminal(
                            row,
                            baseCommit,
                            headCommit,
                            worktreeDigest,
                            "COMPLETED",
                            resultPayload,
                            null,
                            null));
            return 1;
        }

        @Override
        public int fail(
                UUID repositoryId,
                UUID id,
                String baseCommit,
                String headCommit,
                String worktreeDigest,
                String errorCode,
                String errorMessage) {
            TaskReviewRow row = findById(repositoryId, id);
            if (row == null || !"RUNNING".equals(row.status())) {
                return 0;
            }
            rows.put(
                    id,
                    terminal(
                            row,
                            baseCommit,
                            headCommit,
                            worktreeDigest,
                            "FAILED",
                            null,
                            errorCode,
                            errorMessage));
            return 1;
        }

        private static TaskReviewRow terminal(
                TaskReviewRow row,
                String baseCommit,
                String headCommit,
                String worktreeDigest,
                String status,
                String payload,
                String errorCode,
                String errorMessage) {
            return new TaskReviewRow(
                    row.id(),
                    row.repositoryId(),
                    row.createdBy(),
                    row.clientRequestId(),
                    row.task(),
                    row.changeSource(),
                    row.baseRef(),
                    row.headRef(),
                    row.modelConfigId(),
                    baseCommit,
                    headCommit,
                    row.snapshotId(),
                    worktreeDigest,
                    status,
                    payload,
                    errorCode,
                    errorMessage,
                    row.createdAt(),
                    Instant.now());
        }
    }
}
