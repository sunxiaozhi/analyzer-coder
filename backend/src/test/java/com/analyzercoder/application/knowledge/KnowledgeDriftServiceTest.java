package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeService;
import com.analyzercoder.application.review.ChangedSymbolResolver;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeDriftMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftCandidateRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftEventRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeDriftReferenceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeDriftServiceTest {
    private CodeRepositoryStore repositories;
    private RepositoryChangeService changes;
    private ChangedSymbolResolver symbols;
    private CodeChunkStore chunks;
    private KnowledgeDriftMapper mapper;
    private KnowledgeDriftService service;
    private CodeRepository repository;

    @BeforeEach
    void setUp() {
        repositories = mock(CodeRepositoryStore.class);
        changes = mock(RepositoryChangeService.class);
        symbols = mock(ChangedSymbolResolver.class);
        chunks = mock(CodeChunkStore.class);
        mapper = mock(KnowledgeDriftMapper.class);
        service =
                new KnowledgeDriftService(
                        repositories,
                        changes,
                        symbols,
                        chunks,
                        mapper,
                        new RepositoryGlobMatcher(),
                        new ObjectMapper());
        repository = repository();
    }

    @Test
    void unrelatedCodeChangeKeepsKnowledgeCurrent() {
        KnowledgeDriftCandidateRow candidate = candidate("{\"pathPatterns\":[\"src/payment/**\"],\"symbols\":[],\"modules\":[]}", "CURRENT");
        RepositoryChange change = change("src/catalog/Product.java");
        arrange(candidate, change, new ChangedSymbolResolver.ResolutionResult(List.of(), List.of()));

        KnowledgeDriftService.InspectionReport report = service.inspect(repository);

        assertThat(report.suspectCards()).isZero();
        assertThat(report.unchangedCards()).isEqualTo(1);
        verify(mapper).touchCurrent(repository.id().value(), candidate.id(), 4, "old-commit");
        verify(mapper, never()).markSuspect(any(), any(), any(Integer.class), any());
        verify(mapper, never()).insertEvent(any());
    }

    @Test
    void pathScopeMatchMarksOnlyThatCardSuspectAndAuditsDiffReason() {
        KnowledgeDriftCandidateRow candidate = candidate("{\"pathPatterns\":[\"src/payment/**\"],\"symbols\":[],\"modules\":[]}", "CURRENT");
        RepositoryChange change = change("src/payment/RefundService.java");
        arrange(candidate, change, new ChangedSymbolResolver.ResolutionResult(List.of(), List.of()));
        when(mapper.markSuspect(repository.id().value(), candidate.id(), 4, "old-commit"))
                .thenReturn(1);

        KnowledgeDriftService.InspectionReport report = service.inspect(repository);

        assertThat(report.suspectCards()).isEqualTo(1);
        ArgumentCaptor<KnowledgeDriftEventRow> event =
                ArgumentCaptor.forClass(KnowledgeDriftEventRow.class);
        verify(mapper).insertEvent(event.capture());
        assertThat(event.getValue().resultStatus()).isEqualTo("SUSPECT");
        assertThat(event.getValue().reasonsPayload())
                .contains("PATH_SCOPE_MATCHED", "src/payment/RefundService.java");
    }

    @Test
    void changedBoundCodeHashMarksKnowledgeSuspect() {
        KnowledgeDriftCandidateRow candidate = candidate(emptyScope(), "CURRENT");
        String path = "src/payment/RefundService.java";
        RepositoryChange change = change(path);
        arrange(candidate, change, new ChangedSymbolResolver.ResolutionResult(List.of(), List.of()));
        when(mapper.references(repository.id().value(), candidate.id(), 4))
                .thenReturn(
                        List.of(
                                new KnowledgeDriftReferenceRow(
                                        path, "refund", 10, 20, "old-content-hash")));
        when(chunks.findByRepositoryPath(repository.id(), path)).thenReturn(List.of());
        when(mapper.markSuspect(repository.id().value(), candidate.id(), 4, "old-commit"))
                .thenReturn(1);

        service.inspect(repository);

        ArgumentCaptor<KnowledgeDriftEventRow> event =
                ArgumentCaptor.forClass(KnowledgeDriftEventRow.class);
        verify(mapper).insertEvent(event.capture());
        assertThat(event.getValue().reasonsPayload())
                .contains("CODE_REFERENCE_HASH_CHANGED", "old-content-hash");
    }

    @Test
    void boundCodeWithSameHashRemainsCurrentEvenWhenFileHasOtherChanges() {
        KnowledgeDriftCandidateRow candidate = candidate(emptyScope(), "CURRENT");
        String path = "src/payment/RefundService.java";
        RepositoryChange change = change(path);
        arrange(candidate, change, new ChangedSymbolResolver.ResolutionResult(List.of(), List.of()));
        when(mapper.references(repository.id().value(), candidate.id(), 4))
                .thenReturn(
                        List.of(
                                new KnowledgeDriftReferenceRow(
                                        path, "refund", 10, 20, "same-content-hash")));
        CodeChunk unchangedChunk = mock(CodeChunk.class);
        when(unchangedChunk.contentHash()).thenReturn("same-content-hash");
        when(chunks.findByRepositoryPath(repository.id(), path))
                .thenReturn(List.of(unchangedChunk));

        KnowledgeDriftService.InspectionReport report = service.inspect(repository);

        assertThat(report.unchangedCards()).isEqualTo(1);
        verify(mapper, never()).markSuspect(any(), any(), any(Integer.class), any());
    }

    @Test
    void manualConfirmationUsesOptimisticRevisionAndCurrentVersion() {
        KnowledgeDriftCandidateRow candidate = candidate(emptyScope(), "SUSPECT");
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(mapper.findCandidate(repository.id().value(), candidate.id())).thenReturn(candidate);
        when(mapper.reviewSource(
                        repository.id().value(),
                        candidate.id(),
                        4,
                        "CURRENT",
                        repository.currentCommit(),
                        repository.currentSnapshotId().value(),
                        "已核对退款实现",
                        ACCOUNT_ID))
                .thenReturn(1);
        when(mapper.insertEvent(any())).thenReturn(1);

        KnowledgeDriftService.DriftEvent event =
                service.reviewSource(
                        repository.id(),
                        candidate.id(),
                        ACCOUNT_ID,
                        new KnowledgeDriftService.SourceReviewRequest(
                                "CONFIRM_CURRENT", 4, "已核对退款实现"));

        assertThat(event.previousStatus()).isEqualTo("SUSPECT");
        assertThat(event.resultStatus()).isEqualTo("CURRENT");
        assertThat(event.toCommit()).isEqualTo("current-commit");
        assertThat(event.toSnapshotId()).isEqualTo(repository.currentSnapshotId().value());
    }

    @Test
    void staleRevisionFailsWithoutWritingAudit() {
        KnowledgeDriftCandidateRow candidate = candidate(emptyScope(), "SUSPECT");
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
        when(mapper.findCandidate(repository.id().value(), candidate.id())).thenReturn(candidate);

        assertThatThrownBy(
                        () ->
                                service.reviewSource(
                                        repository.id(),
                                        candidate.id(),
                                        ACCOUNT_ID,
                                        new KnowledgeDriftService.SourceReviewRequest(
                                                "MARK_STALE", 3, "内容已经错误")))
                .isInstanceOf(KnowledgeDriftException.class)
                .extracting(error -> ((KnowledgeDriftException) error).code())
                .isEqualTo("KNOWLEDGE_REVISION_CONFLICT");
        verify(mapper, never()).insertEvent(any());
    }

    private void arrange(
            KnowledgeDriftCandidateRow candidate,
            RepositoryChange change,
            ChangedSymbolResolver.ResolutionResult resolution) {
        when(mapper.candidates(repository.id().value())).thenReturn(List.of(candidate));
        when(changes.analyze(any(GitChangeRequest.class))).thenReturn(change);
        when(symbols.resolve(repository, change)).thenReturn(resolution);
        when(mapper.references(repository.id().value(), candidate.id(), 4)).thenReturn(List.of());
    }

    private static RepositoryChange change(String path) {
        return new RepositoryChange(
                GitChangeRequest.Source.COMMIT_RANGE,
                "old-commit",
                "current-commit",
                null,
                false,
                List.of(
                        new RepositoryChange.FileChange(
                                RepositoryChange.ChangeType.MODIFIED,
                                path,
                                path,
                                false,
                                2L,
                                1L,
                                List.of(new RepositoryChange.Hunk(10, 1, 10, 2)))),
                List.of());
    }

    private static KnowledgeDriftCandidateRow candidate(String scope, String status) {
        return new KnowledgeDriftCandidateRow(
                CARD_ID,
                4,
                scope,
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                "old-commit",
                status);
    }

    private static String emptyScope() {
        return "{\"pathPatterns\":[],\"symbols\":[],\"modules\":[]}";
    }

    private static CodeRepository repository() {
        Instant now = Instant.parse("2026-08-30T08:00:00Z");
        Path path = Path.of("repository").toAbsolutePath().normalize();
        return new CodeRepository(
                CodeRepositoryId.of(REPOSITORY_ID),
                "repository",
                path,
                RepositorySourceType.LOCAL_GIT,
                "main",
                "current-commit",
                "digest",
                false,
                RepositorySnapshotId.of(
                        UUID.fromString("20000000-0000-0000-0000-000000000002")),
                path,
                path.resolve(".codegraph"),
                now,
                now,
                now,
                now);
    }

    private static final UUID REPOSITORY_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CARD_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000005");
}
