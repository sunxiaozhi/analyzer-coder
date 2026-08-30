package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.knowledge.EngineeringKnowledgePolicy;
import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.infrastructure.persistence.mapper.GraphRetrievalMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntelligenceServiceMultiTurnTest {
    private IntelligenceMapper mapper;
    private GraphRetrievalMapper graphRetrievalMapper;
    private LlmSettingsService llm;
    private IntelligenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IntelligenceMapper.class);
        graphRetrievalMapper = mock(GraphRetrievalMapper.class);
        llm = mock(LlmSettingsService.class);
        service = new IntelligenceService(
                mapper,
                graphRetrievalMapper,
                mock(KnowledgeAttachmentService.class),
                mock(MarkdownRenderingService.class),
                llm,
                new RetrievalQueryAnalyzer(),
                new RetrievalRanker(),
                new AnswerCitationValidator(),
                new EngineeringKnowledgePolicy(),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void rejectsFollowUpForThreadOutsideRepositoryAndAccount() {
        UUID repositoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        when(mapper.findThread(threadId, repositoryId, accountId)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () ->
                        service.ask(
                                repositoryId,
                                accountId,
                                "继续追问",
                                UUID.randomUUID(),
                                threadId,
                                UUID.randomUUID()));

        verify(mapper, never()).lockThread(any());
    }

    @Test
    void restoresAllTurnsInThreadOrder() {
        UUID repositoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Map<String, Object> first = row(firstId, threadId, repositoryId, 1, "第一问");
        Map<String, Object> second = row(secondId, threadId, repositoryId, 2, "第二问");
        when(mapper.findThread(threadId, repositoryId, accountId)).thenReturn(first);
        when(mapper.listThreadTurns(threadId, repositoryId, accountId)).thenReturn(List.of(first, second));

        IntelligenceService.ThreadDetail detail = service.historyDetail(repositoryId, accountId, threadId);

        assertEquals(threadId, detail.threadId());
        assertEquals(List.of(firstId, secondId), detail.turns().stream()
                .map(IntelligenceService.Answer::conversationId).toList());
        assertEquals(List.of(1, 2), detail.turns().stream()
                .map(IntelligenceService.Answer::turnNo).toList());
        verify(mapper).listThreadTurns(eq(threadId), eq(repositoryId), eq(accountId));
    }

    @Test
    void restoresLegacyAnswerPayloadWithoutCitationAssessment() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> legacyRow = new HashMap<>(row(conversationId, threadId, repositoryId, 1, "旧问题"));
        legacyRow.put(
                "answer_payload",
                new ObjectMapper()
                        .writeValueAsString(Map.ofEntries(
                                Map.entry("conversationId", conversationId),
                                Map.entry("threadId", threadId),
                                Map.entry("turnNo", 1),
                                Map.entry("repositoryId", repositoryId),
                                Map.entry("title", "旧会话"),
                                Map.entry("question", "旧问题"),
                                Map.entry("answer", "旧回答 [S1]"),
                                Map.entry("citations", List.of()),
                                Map.entry("provider", "legacy-provider"),
                                Map.entry("evidenceStatus", "SUPPORTED"))));
        when(mapper.findThread(threadId, repositoryId, accountId)).thenReturn(legacyRow);
        when(mapper.listThreadTurns(threadId, repositoryId, accountId)).thenReturn(List.of(legacyRow));

        IntelligenceService.Answer restored =
                service.historyDetail(repositoryId, accountId, threadId).turns().get(0);

        assertEquals("SUPPORTED", restored.evidenceStatus());
        assertEquals(CitationAssessment.empty(), restored.citationAssessment());
    }

    @Test
    void marksModelAnswerCompleteOnlyWhenEveryFactualBlockHasCitation() {
        UUID repositoryId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        stubSingleEvidence(repositoryId);
        when(llm.generate(eq(modelConfigId), anyString()))
                .thenReturn(Optional.of(new LlmSettingsService.GenerationResult(
                        "第一段事实 [S1]。\n\n第二段事实 [S1]。", "test-model")));

        IntelligenceService.Answer answer = service.ask(
                repositoryId,
                UUID.randomUUID(),
                "问题",
                UUID.randomUUID(),
                null,
                modelConfigId);

        assertEquals("CITATION_COMPLETE", answer.evidenceStatus());
        assertEquals(1.0d, answer.citationAssessment().coverageRate());
        assertEquals(0, answer.citationAssessment().uncitedBlockCount());
        assertTrue(answer.citations().get(0).channels().contains("HEURISTIC_CALL_REFERENCE"));
        verify(mapper, never()).deleteHeuristicCallEdges(repositoryId);
    }

    @Test
    void marksModelAnswerIncompleteWhenOneFactualBlockHasNoCitation() {
        UUID repositoryId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        stubSingleEvidence(repositoryId);
        when(llm.generate(eq(modelConfigId), anyString()))
                .thenReturn(Optional.of(new LlmSettingsService.GenerationResult(
                        "第一段事实没有引用。\n\n第二段事实 [S1]。", "test-model")));

        IntelligenceService.Answer answer = service.ask(
                repositoryId,
                UUID.randomUUID(),
                "问题",
                UUID.randomUUID(),
                null,
                modelConfigId);

        assertEquals("CITATION_INCOMPLETE", answer.evidenceStatus());
        assertEquals(0.5d, answer.citationAssessment().coverageRate());
        assertEquals(1, answer.citationAssessment().uncitedBlockCount());
    }

    @Test
    void buildsHeuristicCallReferencesOnlyDuringIndexPreparation() {
        UUID repositoryId = UUID.randomUUID();

        assertTrue(service.prepareRepositoryEmbeddings(repositoryId));

        verify(mapper).deleteHeuristicCallEdges(repositoryId);
        verify(mapper).graphChunks(repositoryId);
    }

    @Test
    void heuristicGraphReportsItsSourceSnapshotAndLimitationsWithoutRebuilding() {
        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        when(mapper.currentSnapshotId(repositoryId)).thenReturn(snapshotId);

        IntelligenceService.GraphResult result = service.graph(repositoryId, "Example", 2, "BOTH");

        assertEquals("HEURISTIC_CALL_REFERENCE", result.relationSource());
        assertEquals(snapshotId, result.snapshotId());
        assertEquals("SYMBOL_TOKEN_FOLLOWED_BY_PARENTHESIS", result.algorithm());
        assertTrue(result.limitations().stream().anyMatch(item -> item.contains("不是 CodeGraph CLI")));
        verify(mapper, never()).deleteHeuristicCallEdges(repositoryId);
    }

    @Test
    void labelsLocalHashRecallAsCharacterSimilarityInsteadOfSemanticSearch() {
        UUID repositoryId = UUID.randomUUID();
        Map<String, Object> evidence = vectorEvidence();
        stubVectorModel("local-hash-64", 64, "CHARACTER_HASH", null);
        when(mapper.searchCodeVector(
                        eq(repositoryId), anyString(), eq("local-hash-64"), eq(64), anyInt()))
                .thenReturn(List.of(evidence));

        IntelligenceService.SearchHit hit =
                service.hybridSearch(repositoryId, "Example", 5).get(0);

        assertEquals("CHARACTER_HASH", hit.similarityKind());
        assertTrue(hit.channels().contains("CODE_CHARACTER_SIMILARITY"));
        assertTrue(hit.channels().stream().noneMatch(channel -> channel.contains("SEMANTIC")));
    }

    @Test
    void reservesSemanticChannelForExternalEmbeddingModels() {
        UUID repositoryId = UUID.randomUUID();
        Map<String, Object> evidence = vectorEvidence();
        stubVectorModel("text-embedding-test", 3, "SEMANTIC_EMBEDDING", "[0.1,0.2,0.3]");
        when(mapper.searchCodeVector(
                        eq(repositoryId), anyString(), eq("text-embedding-test"), eq(3), anyInt()))
                .thenReturn(List.of(evidence));

        IntelligenceService.SearchHit hit =
                service.hybridSearch(repositoryId, "Example", 5).get(0);

        assertEquals("SEMANTIC_EMBEDDING", hit.similarityKind());
        assertTrue(hit.channels().contains("CODE_SEMANTIC"));
    }

    @Test
    void exposesSnapshotModelRecallTimingAndEnabledChannels() {
        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        Map<String, Object> evidence = vectorEvidence();
        when(mapper.currentSnapshotId(repositoryId)).thenReturn(snapshotId);
        stubVectorModel("text-embedding-test", 3, "SEMANTIC_EMBEDDING", "[0.1,0.2,0.3]");
        when(mapper.searchCodeVector(
                        eq(repositoryId), anyString(), eq("text-embedding-test"), eq(3), anyInt()))
                .thenReturn(List.of(evidence));

        IntelligenceService.SearchResponse response =
                service.hybridSearchDetailed(repositoryId, "Example", 5);

        assertEquals(snapshotId, response.retrieval().snapshotId());
        assertEquals("text-embedding-test", response.retrieval().vectorModel());
        assertEquals("SEMANTIC_EMBEDDING", response.retrieval().retrievalCapability());
        assertEquals(1, response.retrieval().recalledCount());
        assertTrue(response.retrieval().durationMs() >= 0);
        assertTrue(response.retrieval().enabledChannels().contains("CODE_SEMANTIC"));
        assertTrue(response.retrieval().unavailableChannels().isEmpty());
    }

    @Test
    void exposesVectorFailureAsUnavailableInsteadOfClaimingFullCapability() {
        UUID repositoryId = UUID.randomUUID();
        when(mapper.searchCodeKeyword(
                        eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(vectorEvidence()));
        when(llm.vectorize(anyString())).thenThrow(new IllegalStateException("embedding timeout"));
        when(llm.activeVectorModelName()).thenReturn("unreachable-model");
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");

        IntelligenceService.SearchResponse response =
                service.hybridSearchDetailed(repositoryId, "Example", 5);

        assertTrue(response.retrieval().degraded());
        assertTrue(response.retrieval().enabledChannels().contains("CODE_KEYWORD"));
        assertTrue(response.retrieval().enabledChannels().stream()
                .noneMatch(channel -> channel.contains("SEMANTIC")));
        assertTrue(response.retrieval().unavailableChannels().stream()
                .anyMatch(channel -> channel.channel().equals("CODE_VECTOR")
                        && channel.reason().equals("VECTOR_RETRIEVAL_FAILED")));
        assertTrue(response.retrieval().degradationReasons().contains(
                "CODE_VECTOR:VECTOR_RETRIEVAL_FAILED"));
    }

    private void stubVectorModel(
            String model, int dimension, String capability, String vector) {
        when(llm.activeVectorModelName()).thenReturn(model);
        when(llm.activeVectorModelDimension()).thenReturn(dimension);
        when(llm.activeRetrievalCapability()).thenReturn(capability);
        when(llm.vectorize(anyString()))
                .thenReturn(new LlmSettingsService.VectorEmbedding(
                        model, dimension, vector, capability));
    }

    private static Map<String, Object> vectorEvidence() {
        return Map.ofEntries(
                Map.entry("id", UUID.randomUUID()),
                Map.entry("snapshot_id", UUID.randomUUID()),
                Map.entry("file_path", "src/Example.java"),
                Map.entry("symbol_name", "Example"),
                Map.entry("symbol_kind", "CLASS"),
                Map.entry("start_line", 1),
                Map.entry("end_line", 3),
                Map.entry("content", "class Example {}"),
                Map.entry("content_hash", "hash"),
                Map.entry("semantic_score", 0.87d));
    }

    private void stubSingleEvidence(UUID repositoryId) {
        Map<String, Object> evidence = Map.ofEntries(
                Map.entry("id", UUID.randomUUID()),
                Map.entry("snapshot_id", UUID.randomUUID()),
                Map.entry("file_path", "src/Example.java"),
                Map.entry("symbol_name", "Example"),
                Map.entry("symbol_kind", "CLASS"),
                Map.entry("start_line", 1),
                Map.entry("end_line", 3),
                Map.entry("content", "class Example {}"),
                Map.entry("content_hash", "hash"),
                Map.entry("lexical_score", 1.0d));
        when(mapper.searchCodeKeyword(
                        eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(evidence));
        when(graphRetrievalMapper.relatedCodeChunks(
                        eq(repositoryId), anyList(), anyInt()))
                .thenReturn(List.of(evidence));
        when(mapper.searchKnowledgeKeyword(
                        eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of());
    }

    private static Map<String, Object> row(
            UUID id, UUID threadId, UUID repositoryId, int turnNo, String question) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("thread_id", threadId),
                Map.entry("turn_no", turnNo),
                Map.entry("repo_id", repositoryId),
                Map.entry("title", "会话"),
                Map.entry("question", question),
                Map.entry("answer", "回答"),
                Map.entry("provider", "deterministic-local"),
                Map.entry("evidence_status", "DEGRADED"),
                Map.entry("created_at", java.time.Instant.now()),
                Map.entry("updated_at", java.time.Instant.now()),
                Map.entry("citation_count", 0),
                Map.entry("turn_count", 2));
    }
}
