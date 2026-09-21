package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.indexing.ScannedRepositoryFile;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositoryContentVersion;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.MarkdownKnowledgeSourceMapper;
import com.analyzercoder.security.ApiSecurityException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarkdownKnowledgeSourceServiceTest {
    private static final UUID REPOSITORY_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CONTENT_VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID BRANCH_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    private MarkdownKnowledgeSourceMapper mapper;
    private CodeRepositoryStore repositories;
    private IntelligenceService intelligence;
    private MarkdownKnowledgeSourceService service;
    private CodeRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(MarkdownKnowledgeSourceMapper.class);
        repositories = mock(CodeRepositoryStore.class);
        intelligence = mock(IntelligenceService.class);
        service = new MarkdownKnowledgeSourceService(mapper, repositories, intelligence);
        repository = repository(CONTENT_VERSION_ID);
        when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
    }

    @Test
    void synchronizeExtractsWholeFileHashAndHeadingAndCleansManifest() {
        String content = "前言\r\n### 深入设计\r\n完整正文";
        ScannedRepositoryFile markdown =
                new ScannedRepositoryFile(
                        "docs/design.md", "markdown", RepositoryAssetType.DOCUMENT, content, 3);
        ScannedRepositoryFile code =
                new ScannedRepositoryFile(
                        "src/Main.java", "java", RepositoryAssetType.CODE, "class Main {}", 1);

        service.synchronize(repository, List.of(markdown, code), false, Set.of());

        verify(mapper)
                .upsertSource(
                        any(UUID.class),
                        eq(REPOSITORY_ID),
                        eq(CONTENT_VERSION_ID),
                        eq("docs/design.md"),
                        eq(sha256(content)),
                        eq("深入设计"),
                        eq("DOCUMENT"),
                        eq(content),
                        eq(3),
                        eq((long) content.getBytes(StandardCharsets.UTF_8).length));
        verify(mapper).deleteMissingSources(REPOSITORY_ID, List.of("docs/design.md"));
        verify(mapper).reconcileLinkedCards(REPOSITORY_ID, CONTENT_VERSION_ID, "current-commit", true);
    }

    @Test
    void pendingSourceCreatesDraftAndWritesProvenance() {
        String content = "# Project\n\nRepository knowledge.";
        String hash = sha256(content);
        UUID sourceId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        IntelligenceService.KnowledgeCard created = card(UUID.randomUUID(), 1, content, "DRAFT");
        when(mapper.lockSource(REPOSITORY_ID, "README.md"))
                .thenReturn(lockRow(sourceId, CONTENT_VERSION_ID, "README.md", hash));
        when(mapper.findSource(REPOSITORY_ID, CONTENT_VERSION_ID, "README.md"))
                .thenReturn(
                        sourceRow(
                                sourceId,
                                CONTENT_VERSION_ID,
                                "README.md",
                                content,
                                hash,
                                "PENDING",
                                null,
                                null));
        when(mapper.findChunkIds(REPOSITORY_ID, CONTENT_VERSION_ID, "README.md", 30))
                .thenReturn(List.of(chunkId));
        when(intelligence.createCard(eq(REPOSITORY_ID), eq(BRANCH_ID), eq(ACTOR_ID), any())).thenReturn(created);
        when(intelligence.cards(REPOSITORY_ID, true)).thenReturn(List.of(created));

        IntelligenceService.KnowledgeCard result =
                service.generate(
                        REPOSITORY_ID,
                        ACTOR_ID,
                        new MarkdownKnowledgeSourceService.GenerateInput(
                                "README.md", CONTENT_VERSION_ID, hash));

        ArgumentCaptor<IntelligenceService.CardInput> input =
                ArgumentCaptor.forClass(IntelligenceService.CardInput.class);
        verify(intelligence).createCard(eq(REPOSITORY_ID), eq(BRANCH_ID), eq(ACTOR_ID), input.capture());
        assertEquals("项目说明", input.getValue().cardType());
        assertEquals(content, input.getValue().content());
        assertEquals(
                List.of(new IntelligenceService.CodeReferenceInput(chunkId)),
                input.getValue().codeReferences());
        verify(mapper)
                .insertBranchProvenance(
                        created.id(),
                        created.revision(),
                        sourceId,
                        REPOSITORY_ID,
                        BRANCH_ID,
                        CONTENT_VERSION_ID,
                        "README.md",
                        hash);
        assertEquals(created.id(), result.id());
    }

    @Test
    void currentSourceIsIdempotent() {
        String content = "# Current";
        String hash = sha256(content);
        UUID sourceId = UUID.randomUUID();
        IntelligenceService.KnowledgeCard existing = card(UUID.randomUUID(), 4, content, "DRAFT");
        when(mapper.lockSource(REPOSITORY_ID, "docs/current.md"))
                .thenReturn(lockRow(sourceId, CONTENT_VERSION_ID, "docs/current.md", hash));
        when(mapper.findSource(REPOSITORY_ID, CONTENT_VERSION_ID, "docs/current.md"))
                .thenReturn(
                        sourceRow(
                                sourceId,
                                CONTENT_VERSION_ID,
                                "docs/current.md",
                                content,
                                hash,
                                "CURRENT",
                                existing.id(),
                                existing.revision()));
        when(intelligence.cards(REPOSITORY_ID, true)).thenReturn(List.of(existing));

        IntelligenceService.KnowledgeCard result =
                service.generate(
                        REPOSITORY_ID,
                        ACTOR_ID,
                        new MarkdownKnowledgeSourceService.GenerateInput(
                                "docs/current.md", CONTENT_VERSION_ID, hash));

        assertSame(existing, result);
        verify(intelligence, never()).createCard(any(), any(), any(), any());
        verify(intelligence, never()).updateCard(any(), any(), any(), any());
        verify(mapper, never())
                .insertBranchProvenance(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleSourceUpdatesSameCardAsNewDraftRevision() {
        String oldContent = "# Guide\nOld";
        String content = "# Guide\nNew";
        String hash = sha256(content);
        UUID sourceId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        IntelligenceService.KnowledgeCard previous = card(cardId, 2, oldContent, "PUBLISHED");
        IntelligenceService.KnowledgeCard updated = card(cardId, 3, content, "DRAFT");
        when(mapper.lockSource(REPOSITORY_ID, "docs/guide.md"))
                .thenReturn(lockRow(sourceId, CONTENT_VERSION_ID, "docs/guide.md", hash));
        when(mapper.findSource(REPOSITORY_ID, CONTENT_VERSION_ID, "docs/guide.md"))
                .thenReturn(
                        sourceRow(
                                sourceId,
                                CONTENT_VERSION_ID,
                                "docs/guide.md",
                                content,
                                hash,
                                "STALE",
                                cardId,
                                previous.revision()));
        when(mapper.findChunkIds(REPOSITORY_ID, CONTENT_VERSION_ID, "docs/guide.md", 30))
                .thenReturn(List.of());
        when(intelligence.cards(REPOSITORY_ID, true))
                .thenReturn(List.of(previous))
                .thenReturn(List.of(updated));
        when(intelligence.updateCard(eq(REPOSITORY_ID), eq(cardId), eq(ACTOR_ID), any()))
                .thenReturn(updated);

        IntelligenceService.KnowledgeCard result =
                service.generate(
                        REPOSITORY_ID,
                        ACTOR_ID,
                        new MarkdownKnowledgeSourceService.GenerateInput(
                                "docs/guide.md", CONTENT_VERSION_ID, hash));

        ArgumentCaptor<IntelligenceService.CardInput> input =
                ArgumentCaptor.forClass(IntelligenceService.CardInput.class);
        verify(intelligence)
                .updateCard(eq(REPOSITORY_ID), eq(cardId), eq(ACTOR_ID), input.capture());
        assertEquals(content, input.getValue().content());
        verify(mapper)
                .insertBranchProvenance(
                        cardId, 3, sourceId, REPOSITORY_ID, BRANCH_ID, CONTENT_VERSION_ID, "docs/guide.md", hash);
        assertEquals(cardId, result.id());
        assertEquals(3, result.revision());
    }

    @Test
    void changedExpectedContentVersionReturnsConflict() {
        UUID otherContentVersion = UUID.randomUUID();
        String hash = sha256("# Changed");

        ApiSecurityException error =
                assertThrows(
                        ApiSecurityException.class,
                        () ->
                                service.generate(
                                        REPOSITORY_ID,
                                        ACTOR_ID,
                                        new MarkdownKnowledgeSourceService.GenerateInput(
                                                "README.md", otherContentVersion, hash)));

        assertEquals(409, error.status());
        assertEquals("MARKDOWN_SOURCE_CHANGED", error.code());
        verify(mapper, never()).lockSource(any(), any());
    }

    @Test
    void changedExpectedHashReturnsConflict() {
        String actualHash = sha256("# Actual");
        String expectedHash = sha256("# Expected");
        UUID sourceId = UUID.randomUUID();
        when(mapper.lockSource(REPOSITORY_ID, "README.md"))
                .thenReturn(lockRow(sourceId, CONTENT_VERSION_ID, "README.md", actualHash));

        ApiSecurityException error =
                assertThrows(
                        ApiSecurityException.class,
                        () ->
                                service.generate(
                                        REPOSITORY_ID,
                                        ACTOR_ID,
                                        new MarkdownKnowledgeSourceService.GenerateInput(
                                                "README.md", CONTENT_VERSION_ID, expectedHash)));

        assertEquals(409, error.status());
        assertEquals("MARKDOWN_SOURCE_CHANGED", error.code());
        verify(intelligence, never()).createCard(any(), any(), any(), any());
    }

    @Test
    void sameHashAcrossContentVersionsRemainsCurrentWhileDifferentHashIsStale() {
        UUID oldContentVersion = UUID.randomUUID();
        String currentHash = sha256("# Unchanged");
        String changedHash = sha256("# Changed");
        when(mapper.listSources(REPOSITORY_ID, CONTENT_VERSION_ID))
                .thenReturn(
                        List.of(
                                metadataRow(
                                        UUID.randomUUID(),
                                        CONTENT_VERSION_ID,
                                        "README.md",
                                        currentHash,
                                        "CURRENT",
                                        UUID.randomUUID(),
                                        oldContentVersion,
                                        currentHash),
                                metadataRow(
                                        UUID.randomUUID(),
                                        CONTENT_VERSION_ID,
                                        "docs/guide.md",
                                        changedHash,
                                        "STALE",
                                        UUID.randomUUID(),
                                        oldContentVersion,
                                        sha256("# Old"))));

        MarkdownKnowledgeSourceService.MarkdownSourceList result = service.list(REPOSITORY_ID);

        assertEquals(1, result.counts().current());
        assertEquals(1, result.counts().stale());
        assertEquals(oldContentVersion, result.items().get(0).generatedContentVersion());
        assertEquals(currentHash, result.items().get(0).generatedContentHash());
    }

    private static Map<String, Object> lockRow(
            UUID sourceId, UUID contentVersion, String path, String hash) {
        return Map.ofEntries(
                Map.entry("source_id", sourceId),
                Map.entry("source_content_version", contentVersion),
                Map.entry("repository_content_version", contentVersion),
                Map.entry("source_path", path),
                Map.entry("source_content_hash", hash));
    }

    private static Map<String, Object> sourceRow(
            UUID sourceId,
            UUID contentVersion,
            String path,
            String content,
            String hash,
            String status,
            UUID cardId,
            Integer cardRevision) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("source_id", sourceId);
        row.put("source_branch_id", BRANCH_ID);
        row.put("source_content_version", contentVersion);
        row.put("source_path", path);
        row.put("source_content_hash", hash);
        row.put("title", "Project");
        row.put("asset_type", "DOCUMENT");
        row.put("content", content);
        row.put("line_count", content.lines().count());
        row.put("byte_size", content.getBytes(StandardCharsets.UTF_8).length);
        row.put("source_status", status);
        if (cardId != null) {
            row.put("card_id", cardId);
            row.put("card_revision", cardRevision);
            row.put("generated_content_version", UUID.randomUUID());
            row.put("generated_content_hash", hash);
        }
        return row;
    }

    private static Map<String, Object> metadataRow(
            UUID sourceId,
            UUID contentVersion,
            String path,
            String hash,
            String status,
            UUID cardId,
            UUID generatedContentVersion,
            String generatedHash) {
        return Map.ofEntries(
                Map.entry("source_id", sourceId),
                Map.entry("source_content_version", contentVersion),
                Map.entry("source_path", path),
                Map.entry("source_content_hash", hash),
                Map.entry("title", path),
                Map.entry("asset_type", "DOCUMENT"),
                Map.entry("line_count", 1),
                Map.entry("byte_size", 10L),
                Map.entry("source_status", status),
                Map.entry("card_id", cardId),
                Map.entry("card_revision", 1),
                Map.entry("generated_content_version", generatedContentVersion),
                Map.entry("generated_content_hash", generatedHash));
    }

    private static IntelligenceService.KnowledgeCard card(
            UUID cardId, int revision, String content, String status) {
        Instant now = Instant.parse("2026-08-23T10:00:00Z");
        return new IntelligenceService.KnowledgeCard(
                cardId,
                REPOSITORY_ID,
                "Project",
                "项目文档",
                content,
                content,
                List.of("markdown"),
                com.analyzercoder.domain.knowledge.KnowledgeKind.REFERENCE,
                com.analyzercoder.domain.knowledge.KnowledgeSeverity.INFO,
                com.analyzercoder.domain.knowledge.KnowledgeEnforcement.REFERENCE,
                null,
                com.analyzercoder.domain.knowledge.KnowledgeScope.empty(),
                com.analyzercoder.domain.knowledge.KnowledgeObligations.empty(),
                null,
                null,
                status,
                revision,
                now,
                now,
                "current-commit",
                "CURRENT",
                now,
                "UNREVIEWED",
                null,
                null,
                List.of(),
                List.of());
    }

    private static CodeRepository repository(UUID contentVersion) {
        Instant now = Instant.parse("2026-08-23T10:00:00Z");
        Path path = Path.of("repository").toAbsolutePath().normalize();
        return new CodeRepository(
                CodeRepositoryId.of(REPOSITORY_ID),
                "repository",
                path,
                RepositorySourceType.LOCAL_GIT,
                "main",
                "current-commit",
                "worktree-digest",
                false,
                RepositoryContentVersion.of(contentVersion),
                path,
                path.resolve(".codegraph"),
                now,
                now,
                now,
                now);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
