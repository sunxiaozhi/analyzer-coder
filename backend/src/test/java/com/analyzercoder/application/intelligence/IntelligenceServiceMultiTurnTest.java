package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.knowledge.EngineeringKnowledgePolicy;
import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.infrastructure.persistence.mapper.GraphRetrievalMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
        service =
                new IntelligenceService(
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
    void queriesNeverBuildCorpusEmbeddingsOnTheRequestThread() {
        UUID repositoryId = UUID.randomUUID();
        when(llm.vectorize(anyString()))
                .thenReturn(
                        new LlmSettingsService.VectorEmbedding(
                                "local-hash-64", 64, null, "CHARACTER_HASH"));

        service.unifiedSearchDetailed(repositoryId, "OrderCheckoutWorkflow", 10);

        verify(llm).vectorize(anyString());
        verify(mapper, never()).missingEmbeddings(any(), any(), anyInt(), any());
        verify(mapper, never()).missingKnowledgeEmbeddings(any(), any(), anyInt(), any());
        verify(mapper, never()).upsertEmbedding(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void reusesMatchingRepositoryVectorsWithoutCallingTheModel() {
        UUID repo = UUID.randomUUID(),
                contentVersion = UUID.randomUUID(),
                chunk = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(3);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        when(mapper.missingBranchEmbeddings(repo, contentVersion, "model", 3, "SEMANTIC_EMBEDDING"))
                .thenReturn(
                        List.of(Map.of("id", chunk, "content", "source", "content_hash", "hash")));
        when(mapper.reusableCodeEmbedding(repo, "hash", "model", 3, "SEMANTIC_EMBEDDING"))
                .thenReturn("[1,0,0]");
        service.prepareBranchEmbeddings(repo, contentVersion, () -> {});
        verify(llm, never()).vectorize(anyString());
        verify(llm, never()).openExternalVectorizer();
        verify(mapper)
                .upsertEmbedding(chunk, repo, "model", 3, "SEMANTIC_EMBEDDING", "[1,0,0]", "hash");
    }

    @Test
    void reusesOneExternalVectorConfigurationAcrossAllMissingChunks() {
        UUID repo = UUID.randomUUID(), contentVersion = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(3);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        when(mapper.missingBranchEmbeddings(repo, contentVersion, "model", 3, "SEMANTIC_EMBEDDING"))
                .thenReturn(
                        List.of(
                                Map.of("id", first, "content", "first", "content_hash", "hash-1"),
                                Map.of(
                                        "id",
                                        second,
                                        "content",
                                        "second",
                                        "content_hash",
                                        "hash-2")));
        LlmSettingsService.ExternalVectorizer vectorizer =
                input ->
                        new LlmSettingsService.VectorEmbedding(
                                "model", 3, "[0.1,0.2,0.3]", "SEMANTIC_EMBEDDING");
        when(llm.openExternalVectorizer()).thenReturn(vectorizer);

        service.prepareBranchEmbeddings(repo, contentVersion, () -> {});

        verify(llm, times(1)).openExternalVectorizer();
        verify(llm, never()).vectorize(anyString());
        verify(mapper)
                .upsertEmbedding(
                        first, repo, "model", 3, "SEMANTIC_EMBEDDING", "[0.1,0.2,0.3]", "hash-1");
        verify(mapper)
                .upsertEmbedding(
                        second, repo, "model", 3, "SEMANTIC_EMBEDDING", "[0.1,0.2,0.3]", "hash-2");
    }

    @Test
    void groupsExternalCodeEmbeddingsIntoBatchesOfSixteen() {
        UUID repo = UUID.randomUUID(), contentVersion = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            rows.add(
                    Map.of(
                            "id", UUID.randomUUID(),
                            "content", "content-" + index,
                            "content_hash", "hash-" + index));
        }
        when(mapper.missingBranchEmbeddings(repo, contentVersion, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenReturn(rows);
        LlmSettingsService.ExternalVectorizer vectorizer =
                mock(LlmSettingsService.ExternalVectorizer.class);
        org.mockito.Mockito.doCallRealMethod().when(vectorizer).vectorizeBatch(anyList(), any());
        when(vectorizer.vectorizeBatch(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<String> inputs = invocation.getArgument(0);
                            return inputs.stream()
                                    .map(
                                            input ->
                                                    new LlmSettingsService.VectorEmbedding(
                                                            "model",
                                                            2,
                                                            "[1.0,0.0]",
                                                            "SEMANTIC_EMBEDDING"))
                                    .toList();
                        });
        when(llm.openExternalVectorizer()).thenReturn(vectorizer);

        service.prepareBranchEmbeddings(repo, contentVersion, () -> {});

        verify(vectorizer).vectorizeBatch(argThat(inputs -> inputs.size() == 16));
        verify(vectorizer).vectorizeBatch(argThat(inputs -> inputs.size() == 1));
        verify(llm, times(1)).openExternalVectorizer();
    }

    @Test
    void failedLaterBatchReportsProgressAndKeepsPreviousWrites() {
        UUID repo = UUID.randomUUID(), contentVersion = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 17; index++)
            rows.add(
                    Map.of(
                            "id",
                            UUID.randomUUID(),
                            "content",
                            "content-" + index,
                            "content_hash",
                            "hash-" + index));
        when(mapper.missingBranchEmbeddings(repo, contentVersion, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenReturn(rows);
        LlmSettingsService.ExternalVectorizer vectorizer =
                mock(LlmSettingsService.ExternalVectorizer.class);
        org.mockito.Mockito.doCallRealMethod().when(vectorizer).vectorizeBatch(anyList(), any());
        when(vectorizer.vectorizeBatch(anyList()))
                .thenAnswer(
                        call -> {
                            List<String> inputs = call.getArgument(0);
                            if (inputs.size() == 1)
                                throw new com.analyzercoder.application.llm.LlmConnectionException(
                                        "LLM_TIMEOUT", "模型响应超时");
                            return inputs.stream()
                                    .map(
                                            input ->
                                                    new LlmSettingsService.VectorEmbedding(
                                                            "model",
                                                            2,
                                                            "[1,0]",
                                                            "SEMANTIC_EMBEDDING"))
                                    .toList();
                        });
        when(llm.openExternalVectorizer()).thenReturn(vectorizer);
        var failure =
                assertThrows(
                        com.analyzercoder.application.llm.LlmConnectionException.class,
                        () -> service.prepareBranchEmbeddings(repo, contentVersion, () -> {}));
        assertEquals("LLM_TIMEOUT", failure.code());
        assertTrue(failure.getMessage().contains("第 2 批"));
        assertTrue(failure.getMessage().contains("已处理 16 个片段"));
        assertTrue(failure.getMessage().contains("失败批次 1 个片段"));
        verify(mapper, times(16))
                .upsertEmbedding(
                        any(),
                        eq(repo),
                        eq("model"),
                        eq(2),
                        eq("SEMANTIC_EMBEDDING"),
                        eq("[1,0]"),
                        anyString());
        verify(mapper, never())
                .upsertEmbedding(
                        eq((UUID) rows.get(16).get("id")),
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void retainsFifteenCompletedInputsWithinFailedBatchAndOnlyRetriesTheMissingInput() {
        UUID repo = UUID.randomUUID(), version = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("bge-m3");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<UUID, String> stored = new HashMap<>();
        for (int i = 0; i < 16; i++)
            rows.add(
                    Map.of(
                            "id",
                            UUID.randomUUID(),
                            "content",
                            "input-" + i,
                            "content_hash",
                            "hash-" + i));
        when(mapper.missingBranchEmbeddings(repo, version, "bge-m3", 2, "SEMANTIC_EMBEDDING"))
                .thenAnswer(
                        call ->
                                rows.stream()
                                        .filter(row -> !stored.containsKey(row.get("id")))
                                        .toList());
        org.mockito.Mockito.doAnswer(
                        call -> {
                            stored.put(call.getArgument(0), call.getArgument(5));
                            return null;
                        })
                .when(mapper)
                .upsertEmbedding(
                        any(),
                        eq(repo),
                        eq("bge-m3"),
                        eq(2),
                        eq("SEMANTIC_EMBEDDING"),
                        anyString(),
                        anyString());
        var settingsMapper =
                mock(com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper.class);
        when(settingsMapper.activeVectorModel())
                .thenReturn(
                        Map.of(
                                "provider_type",
                                "OPENAI_COMPATIBLE",
                                "base_url",
                                "https://example.com/v1",
                                "model",
                                "bge-m3",
                                "dimension",
                                2,
                                "request_timeout_ms",
                                5000));
        var client = mock(com.analyzercoder.application.llm.OpenAiCompatibleClient.class);
        when(client.embedBatch(anyString(), eq("bge-m3"), eq(""), anyList(), eq(2), eq(5000)))
                .thenThrow(
                        new com.analyzercoder.application.llm.LlmConnectionException(
                                "LLM_BATCH_UNSUPPORTED", "arrays unsupported"));
        when(client.embed(anyString(), eq("bge-m3"), eq(""), anyString(), eq(2), eq(5000)))
                .thenReturn("[1.0,0.0]");
        when(client.embed(anyString(), eq("bge-m3"), eq(""), eq("input-15"), eq(2), eq(5000)))
                .thenThrow(
                        new com.analyzercoder.application.llm.LlmConnectionException(
                                "LLM_RATE_LIMITED", "HTTP 429: quota exceeded"))
                .thenReturn("[1.0,0.0]");
        var settings =
                new LlmSettingsService(
                        settingsMapper,
                        mock(com.analyzercoder.application.llm.LlmSecretCipher.class),
                        mock(com.analyzercoder.application.llm.LlmEndpointPolicy.class),
                        client,
                        new ObjectMapper(),
                        new com.analyzercoder.application.llm.LlmRuntimeStateService(
                                settingsMapper),
                        15,
                        3);
        when(llm.openExternalVectorizer()).thenAnswer(call -> settings.openExternalVectorizer());
        var failure =
                assertThrows(
                        com.analyzercoder.application.llm.LlmConnectionException.class,
                        () -> service.prepareBranchEmbeddings(repo, version, () -> {}));
        assertEquals("LLM_RATE_LIMITED", failure.code());
        assertTrue(failure.getMessage().contains("本批已写入 15/16"));
        assertEquals(15, stored.size());
        service.prepareBranchEmbeddings(repo, version, () -> {});
        assertEquals(16, stored.size());
        for (int i = 0; i < 15; i++)
            verify(client, times(1))
                    .embed(anyString(), eq("bge-m3"), eq(""), eq("input-" + i), eq(2), eq(5000));
        verify(client, times(2))
                .embed(anyString(), eq("bge-m3"), eq(""), eq("input-15"), eq(2), eq(5000));
    }

    @Test
    void completesAllKnowledgeBatchesBeforeStartingBranchCode() {
        UUID repo = UUID.randomUUID(), version = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < 17; i++)
            cards.add(Map.of("id", UUID.randomUUID(), "revision", 1, "content", "knowledge-" + i));
        when(mapper.missingKnowledgeEmbeddings(repo, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenReturn(cards);
        when(mapper.missingBranchEmbeddings(repo, version, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID(),
                                        "content",
                                        "code",
                                        "content_hash",
                                        "hash")));
        List<String> generated = new ArrayList<>();
        int[] saved = {0};
        org.mockito.Mockito.doAnswer(
                        call -> {
                            saved[0]++;
                            return null;
                        })
                .when(mapper)
                .upsertKnowledgeEmbedding(
                        any(),
                        eq(repo),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString());
        LlmSettingsService.ExternalVectorizer vectorizer =
                input -> {
                    if (input.equals("code")) assertEquals(17, saved[0]);
                    generated.add(input);
                    return new LlmSettingsService.VectorEmbedding(
                            "model", 2, "[1,0]", "SEMANTIC_EMBEDDING");
                };
        when(llm.openExternalVectorizer()).thenReturn(vectorizer);

        service.prepareBranchEmbeddings(repo, version, () -> {});

        assertEquals(18, generated.size());
        assertEquals("knowledge-0", generated.get(0));
        assertEquals("knowledge-16", generated.get(16));
        assertEquals("code", generated.get(17));
        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper, times(17))
                .upsertKnowledgeEmbedding(
                        any(),
                        eq(repo),
                        eq(1),
                        eq("model"),
                        eq(2),
                        eq("SEMANTIC_EMBEDDING"),
                        eq("[1,0]"),
                        anyString());
        order.verify(mapper)
                .missingBranchEmbeddings(repo, version, "model", 2, "SEMANTIC_EMBEDDING");
    }

    @Test
    void knowledgeFailureRetainsCompleteCardsAndRetriesThemBeforeCode() {
        UUID repo = UUID.randomUUID(), version = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        List<Map<String, Object>> cards =
                List.of(
                        Map.of("id", first, "revision", 1, "content", "first"),
                        Map.of("id", second, "revision", 2, "content", "second"));
        Map<UUID, String> stored = new HashMap<>();
        when(mapper.missingKnowledgeEmbeddings(repo, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenAnswer(
                        call ->
                                cards.stream()
                                        .filter(row -> !stored.containsKey(row.get("id")))
                                        .toList());
        org.mockito.Mockito.doAnswer(
                        call -> {
                            stored.put(call.getArgument(0), call.getArgument(6));
                            return null;
                        })
                .when(mapper)
                .upsertKnowledgeEmbedding(
                        any(),
                        eq(repo),
                        anyInt(),
                        anyString(),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString());
        when(mapper.missingBranchEmbeddings(repo, version, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID(),
                                        "content",
                                        "code",
                                        "content_hash",
                                        "hash")));
        List<String> generated = new ArrayList<>();
        boolean[] fail = {true};
        LlmSettingsService.ExternalVectorizer vectorizer =
                new LlmSettingsService.ExternalVectorizer() {
                    @Override
                    public LlmSettingsService.VectorEmbedding vectorize(String input) {
                        generated.add(input);
                        if (input.equals("second") && fail[0]) {
                            fail[0] = false;
                            throw new com.analyzercoder.application.llm.LlmConnectionException(
                                    "LLM_RATE_LIMITED", "HTTP 429");
                        }
                        return new LlmSettingsService.VectorEmbedding(
                                "model", 2, "[1,0]", "SEMANTIC_EMBEDDING");
                    }

                    @Override
                    public void vectorizeBatch(
                            List<String> inputs,
                            java.util.function.BiConsumer<
                                            Integer, LlmSettingsService.VectorEmbedding>
                                    completed) {
                        for (int i = 0; i < inputs.size(); i++)
                            completed.accept(i, vectorize(inputs.get(i)));
                    }
                };
        when(llm.openExternalVectorizer()).thenReturn(vectorizer);

        var failure =
                assertThrows(
                        com.analyzercoder.application.llm.LlmConnectionException.class,
                        () -> service.prepareBranchEmbeddings(repo, version, () -> {}));

        assertEquals("LLM_RATE_LIMITED", failure.code());
        assertTrue(failure.getMessage().contains("知识向量"));
        assertTrue(failure.getMessage().contains("本批已写入 1/2"));
        assertEquals(Map.of(first, "[1,0]"), stored);
        verify(mapper, never()).missingBranchEmbeddings(any(), any(), any(), anyInt(), any());

        service.prepareBranchEmbeddings(repo, version, () -> {});

        assertEquals(2, stored.size());
        assertEquals(List.of("first", "second", "second", "code"), generated);
        verify(mapper, times(1))
                .upsertKnowledgeEmbedding(
                        eq(first),
                        eq(repo),
                        eq(1),
                        anyString(),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString());
        verify(mapper)
                .upsertKnowledgeEmbedding(
                        eq(second),
                        eq(repo),
                        eq(2),
                        anyString(),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString());
    }

    @Test
    void standaloneKnowledgePreparationRestoresCallerLogContextAfterFailure() {
        UUID repo = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        when(mapper.missingKnowledgeEmbeddings(repo, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenAnswer(
                        call -> {
                            String fields =
                                    com.analyzercoder.application.llm.ModelCallLogContext.fields();
                            assertTrue(fields.contains("repoId=" + repo));
                            assertTrue(fields.contains("stage=KNOWLEDGE_VECTOR_PLAN"));
                            throw new com.analyzercoder.application.llm.LlmConnectionException(
                                    "LLM_RATE_LIMITED", "HTTP 429");
                        });
        try (var ignored =
                com.analyzercoder.application.llm.ModelCallLogContext.open(
                        "publication", repo, null, null, null)) {
            com.analyzercoder.application.llm.ModelCallLogContext.stage("PUBLICATION");
            String before = com.analyzercoder.application.llm.ModelCallLogContext.fields();

            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    service, "prepareKnowledgeEmbeddingsSafely", repo);

            assertEquals(before, com.analyzercoder.application.llm.ModelCallLogContext.fields());
        }
    }

    @Test
    void canceledKnowledgeStageStopsBeforeWritingOrStartingCode() {
        UUID repo = UUID.randomUUID(), version = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("model");
        when(llm.activeVectorModelDimension()).thenReturn(2);
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");
        when(mapper.missingKnowledgeEmbeddings(repo, "model", 2, "SEMANTIC_EMBEDDING"))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        UUID.randomUUID(),
                                        "revision",
                                        1,
                                        "content",
                                        "knowledge")));
        LlmSettingsService.ExternalVectorizer vectorizer =
                input ->
                        new LlmSettingsService.VectorEmbedding(
                                "model", 2, "[1,0]", "SEMANTIC_EMBEDDING");
        when(llm.openExternalVectorizer()).thenReturn(vectorizer);
        int[] checkpoints = {0};
        Runnable checkpoint =
                () -> {
                    if (++checkpoints[0] == 2) throw new IllegalStateException("task superseded");
                };

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepareBranchEmbeddings(repo, version, checkpoint));

        assertEquals("task superseded", failure.getMessage());
        verify(mapper, never())
                .upsertKnowledgeEmbedding(
                        any(), any(), anyInt(), any(), anyInt(), any(), any(), any());
        verify(mapper, never()).missingBranchEmbeddings(any(), any(), any(), anyInt(), any());
    }

    @Test
    void branchEmbeddingsPrepareKnowledgeAndOnlyReadPinnedCodeChunks() {
        UUID repo = UUID.randomUUID(),
                contentVersion = UUID.randomUUID(),
                chunk = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("local-hash-64");
        when(llm.activeVectorModelDimension()).thenReturn(64);
        when(llm.activeRetrievalCapability()).thenReturn("CHARACTER_HASH");
        when(mapper.missingBranchEmbeddings(
                        repo, contentVersion, "local-hash-64", 64, "CHARACTER_HASH"))
                .thenReturn(
                        List.of(Map.of("id", chunk, "content", "source", "content_hash", "hash")));
        Runnable checkpoint = mock(Runnable.class);
        service.prepareBranchEmbeddings(repo, contentVersion, checkpoint);
        verify(checkpoint).run();
        verify(llm, never()).vectorize(anyString());
        verify(mapper)
                .upsertEmbedding(
                        eq(chunk),
                        eq(repo),
                        eq("local-hash-64"),
                        eq(64),
                        eq("CHARACTER_HASH"),
                        anyString(),
                        eq("hash"));
        verify(mapper, never()).missingEmbeddings(any(), any(), anyInt(), any());
        verify(mapper).missingKnowledgeEmbeddings(repo, "local-hash-64", 64, "CHARACTER_HASH");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepareBranchEmbeddings(repo, null, checkpoint));
    }

    @Test
    void rejectsFollowUpForThreadOutsideRepositoryAndAccount() {
        UUID repositoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        when(mapper.findThread(threadId, repositoryId, accountId)).thenReturn(null);

        assertThrows(
                IllegalArgumentException.class,
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
        when(mapper.listThreadTurns(threadId, repositoryId, accountId))
                .thenReturn(List.of(first, second));

        IntelligenceService.ThreadDetail detail =
                service.historyDetail(repositoryId, accountId, threadId);

        assertEquals(threadId, detail.threadId());
        assertEquals(
                List.of(firstId, secondId),
                detail.turns().stream().map(IntelligenceService.Answer::conversationId).toList());
        assertEquals(
                List.of(1, 2),
                detail.turns().stream().map(IntelligenceService.Answer::turnNo).toList());
        verify(mapper).listThreadTurns(eq(threadId), eq(repositoryId), eq(accountId));
    }

    @Test
    void restoresLegacyAnswerPayloadWithoutCitationAssessment() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Map<String, Object> legacyRow =
                new HashMap<>(row(conversationId, threadId, repositoryId, 1, "旧问题"));
        legacyRow.put(
                "answer_payload",
                new ObjectMapper()
                        .writeValueAsString(
                                Map.ofEntries(
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
        when(mapper.listThreadTurns(threadId, repositoryId, accountId))
                .thenReturn(List.of(legacyRow));

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
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        "第一段事实 [S1]。\n\n第二段事实 [S1]。", "test-model")));

        IntelligenceService.Answer answer =
                service.ask(
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
    void expandsRelevantCodeIntoCallChainBeforeModelAnalysis() {
        UUID repositoryId = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        Map<String, Object> source =
                Map.ofEntries(
                        Map.entry("id", sourceId),
                        Map.entry("content_version", version),
                        Map.entry("file_path", "src/Checkout.java"),
                        Map.entry("symbol_name", "checkout"),
                        Map.entry("symbol_kind", "METHOD"),
                        Map.entry("start_line", 10),
                        Map.entry("end_line", 20),
                        Map.entry("content", "void checkout() { charge(); }"),
                        Map.entry("content_hash", "source-hash"),
                        Map.entry("lexical_score", 1.0d));
        Map<String, Object> target =
                Map.ofEntries(
                        Map.entry("id", targetId),
                        Map.entry("content_version", version),
                        Map.entry("file_path", "src/Payment.java"),
                        Map.entry("symbol_name", "charge"),
                        Map.entry("symbol_kind", "METHOD"),
                        Map.entry("start_line", 5),
                        Map.entry("end_line", 12),
                        Map.entry("content", "void charge() { gateway.pay(); }"),
                        Map.entry("content_hash", "target-hash"),
                        Map.entry("source_chunk_id", sourceId),
                        Map.entry("target_chunk_id", targetId),
                        Map.entry("source_symbol", "checkout"),
                        Map.entry("target_symbol", "charge"),
                        Map.entry("relation", "CALLS"));
        when(mapper.currentContentVersion(repositoryId)).thenReturn(version);
        when(mapper.searchCodeKeyword(eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(source));
        when(graphRetrievalMapper.callNeighbors(
                        eq(repositoryId), eq(version), eq(List.of(sourceId)), eq(24)))
                .thenReturn(List.of(target));
        when(llm.generate(eq(modelConfigId), anyString()))
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        "结账调用支付。[S1][S2]", "test-model")));

        var answer =
                service.ask(
                        repositoryId,
                        UUID.randomUUID(),
                        "结账如何支付",
                        UUID.randomUUID(),
                        null,
                        modelConfigId);

        assertEquals(2, answer.citations().size());
        assertEquals(targetId, answer.citations().get(1).chunkId());
        verify(llm)
                .generate(
                        eq(modelConfigId),
                        argThat(
                                prompt ->
                                        prompt.contains("[S1] checkout -> [S2] charge")
                                                && prompt.contains("gateway.pay()")
                                                && prompt.contains("启发式调用关系只能作为线索")));
    }

    @Test
    void followsCurrentKnowledgeCodeReferenceIntoCallChain() {
        UUID repositoryId = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        when(mapper.currentContentVersion(repositoryId)).thenReturn(version);
        when(mapper.searchKnowledgeKeyword(
                        eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", cardId,
                                        "revision", 1,
                                        "title", "结账功能",
                                        "content", "结账由支付模块处理",
                                        "content_hash", "card-hash",
                                        "card_type", "FEATURE",
                                        "lexical_score", 1.0d)));
        when(mapper.codeReferences(repositoryId, cardId, 1))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "chunk_id",
                                        sourceId,
                                        "content_version",
                                        version,
                                        "file_path",
                                        "src/Checkout.java",
                                        "symbol_name",
                                        "checkout",
                                        "start_line",
                                        10,
                                        "end_line",
                                        20,
                                        "content_hash",
                                        "source-hash",
                                        "stale",
                                        false)));
        when(mapper.findChunkAtContentVersion(repositoryId, sourceId, version))
                .thenReturn(
                        Map.of(
                                "id",
                                sourceId,
                                "content_version",
                                version,
                                "file_path",
                                "src/Checkout.java",
                                "symbol_name",
                                "checkout",
                                "start_line",
                                10,
                                "end_line",
                                20,
                                "content",
                                "void checkout() { charge(); }",
                                "content_hash",
                                "source-hash"));
        when(graphRetrievalMapper.callNeighbors(
                        eq(repositoryId), eq(version), eq(List.of(sourceId)), eq(24)))
                .thenReturn(
                        List.of(
                                Map.ofEntries(
                                        Map.entry("id", targetId),
                                        Map.entry("content_version", version),
                                        Map.entry("file_path", "src/Payment.java"),
                                        Map.entry("symbol_name", "charge"),
                                        Map.entry("start_line", 5),
                                        Map.entry("end_line", 12),
                                        Map.entry("content", "void charge() { gateway.pay(); }"),
                                        Map.entry("content_hash", "target-hash"),
                                        Map.entry("source_chunk_id", sourceId),
                                        Map.entry("target_chunk_id", targetId),
                                        Map.entry("source_symbol", "checkout"),
                                        Map.entry("target_symbol", "charge"),
                                        Map.entry("relation", "CALLS"))));
        when(llm.generate(eq(modelConfigId), anyString()))
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        "结账功能调用支付。[S1][S2][S3]", "test-model")));

        var answer =
                service.ask(
                        repositoryId,
                        UUID.randomUUID(),
                        "结账功能如何实现",
                        UUID.randomUUID(),
                        null,
                        modelConfigId);

        assertEquals(3, answer.citations().size());
        assertEquals(sourceId, answer.citations().get(1).chunkId());
        assertEquals(targetId, answer.citations().get(2).chunkId());
        verify(llm)
                .generate(
                        eq(modelConfigId),
                        argThat(prompt -> prompt.contains("[S2] checkout -> [S3] charge")));
    }

    @Test
    void preservesTypedVectorFailureWhileKeywordEvidenceRemainsUsable() {
        UUID repositoryId = UUID.randomUUID();
        stubSingleEvidence(repositoryId);
        when(llm.vectorize(anyString()))
                .thenThrow(
                        new com.analyzercoder.application.llm.LlmConnectionException(
                                "LLM_TIMEOUT",
                                "调用ID=test-call；模型=vector-model；请求超时=30000ms；token=private-token"));
        var result = service.unifiedSearchDetailed(repositoryId, "Example", 10);
        assertTrue(result.retrieval().degraded());
        org.assertj.core.api.Assertions.assertThat(result.retrieval().unavailableChannels())
                .anySatisfy(
                        channel -> {
                            assertEquals("LLM_TIMEOUT", channel.reason());
                            org.assertj.core.api.Assertions.assertThat(channel.detail())
                                    .contains("test-call", "vector-model", "30000ms")
                                    .doesNotContain("private-token");
                        });
        assertTrue(!result.evidence().isEmpty());
    }

    @Test
    void failedModelQuestionCanBeRetriedWithTheSameRequestInsteadOfSavingAFallbackAnswer() {
        UUID repositoryId = UUID.randomUUID(),
                modelId = UUID.randomUUID(),
                requestId = UUID.randomUUID(),
                accountId = UUID.randomUUID();
        stubSingleEvidence(repositoryId);
        when(llm.generate(eq(modelId), anyString()))
                .thenThrow(
                        new com.analyzercoder.security.ApiSecurityException(
                                504, "LLM_TIMEOUT", "问答模型调用失败；调用ID=call-1"))
                .thenReturn(
                        Optional.of(new LlmSettingsService.GenerationResult("结果 [S1]。", "model")));
        assertEquals(
                "LLM_TIMEOUT",
                assertThrows(
                                com.analyzercoder.security.ApiSecurityException.class,
                                () ->
                                        service.ask(
                                                repositoryId,
                                                accountId,
                                                "Example",
                                                requestId,
                                                null,
                                                modelId))
                        .code());
        verify(mapper, never())
                .insertConversation(
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
        var answer = service.ask(repositoryId, accountId, "Example", requestId, null, modelId);
        assertEquals("model", answer.provider());
        assertEquals("CITATION_COMPLETE", answer.evidenceStatus());
        verify(llm, times(2)).generate(eq(modelId), anyString());
    }

    @Test
    void localEvidenceModeAnswersWithoutCallingAChatProvider() {
        UUID repositoryId = UUID.randomUUID();
        stubSingleEvidence(repositoryId);
        var answer =
                service.ask(
                        repositoryId, UUID.randomUUID(), "Example", UUID.randomUUID(), null, null);
        assertEquals("LOCAL_EVIDENCE_MODE", answer.fallbackReason());
        assertEquals("deterministic-local", answer.provider());
        assertTrue(!answer.citations().isEmpty());
        verify(llm, never()).generate(any(), anyString());
    }

    @Test
    void marksModelAnswerIncompleteWhenOneFactualBlockHasNoCitation() {
        UUID repositoryId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        stubSingleEvidence(repositoryId);
        when(llm.generate(eq(modelConfigId), anyString()))
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        "第一段事实没有引用。\n\n第二段事实 [S1]。", "test-model")));

        IntelligenceService.Answer answer =
                service.ask(
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
    void builtInModelIndexesCodeAndKnowledgeWithoutPerItemModelLookups() {
        UUID repositoryId = UUID.randomUUID();
        UUID firstChunk = UUID.randomUUID();
        UUID secondChunk = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        when(llm.activeVectorModelName()).thenReturn("local-hash-64");
        when(llm.activeVectorModelDimension()).thenReturn(64);
        when(llm.activeRetrievalCapability()).thenReturn("CHARACTER_HASH");
        when(mapper.missingEmbeddings(repositoryId, "local-hash-64", 64, "CHARACTER_HASH"))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        firstChunk,
                                        "content",
                                        "first",
                                        "content_hash",
                                        "hash-1"),
                                Map.of(
                                        "id",
                                        secondChunk,
                                        "content",
                                        "second",
                                        "content_hash",
                                        "hash-2")));
        when(mapper.missingKnowledgeEmbeddings(repositoryId, "local-hash-64", 64, "CHARACTER_HASH"))
                .thenReturn(List.of(Map.of("id", cardId, "content", "knowledge", "revision", 1)));

        assertTrue(service.prepareRepositoryEmbeddings(repositoryId));

        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper)
                .upsertKnowledgeEmbedding(
                        eq(cardId),
                        eq(repositoryId),
                        eq(1),
                        eq("local-hash-64"),
                        eq(64),
                        eq("CHARACTER_HASH"),
                        anyString(),
                        anyString());
        order.verify(mapper).missingEmbeddings(repositoryId, "local-hash-64", 64, "CHARACTER_HASH");
        verify(llm, never()).vectorize(anyString());
        verify(mapper)
                .upsertEmbedding(
                        eq(firstChunk),
                        eq(repositoryId),
                        eq("local-hash-64"),
                        eq(64),
                        eq("CHARACTER_HASH"),
                        anyString(),
                        eq("hash-1"));
        verify(mapper)
                .upsertEmbedding(
                        eq(secondChunk),
                        eq(repositoryId),
                        eq("local-hash-64"),
                        eq(64),
                        eq("CHARACTER_HASH"),
                        anyString(),
                        eq("hash-2"));
        verify(mapper)
                .upsertKnowledgeEmbedding(
                        eq(cardId),
                        eq(repositoryId),
                        eq(1),
                        eq("local-hash-64"),
                        eq(64),
                        eq("CHARACTER_HASH"),
                        anyString(),
                        anyString());
    }

    @Test
    void heuristicCallScanKeepsOverlappingMatchesWithoutDuplicateEdges() {
        UUID repositoryId = UUID.randomUUID();
        UUID contentVersion = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID fooId = UUID.randomUUID();
        UUID ooId = UUID.randomUUID();
        when(mapper.graphChunks(repositoryId))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        sourceId,
                                        "content_version",
                                        contentVersion,
                                        "symbol_name",
                                        "caller",
                                        "content",
                                        "foo( foo("),
                                Map.of(
                                        "id",
                                        fooId,
                                        "content_version",
                                        contentVersion,
                                        "symbol_name",
                                        "foo",
                                        "content",
                                        ""),
                                Map.of(
                                        "id",
                                        ooId,
                                        "content_version",
                                        contentVersion,
                                        "symbol_name",
                                        "oo",
                                        "content",
                                        "")));

        assertTrue(service.prepareRepositoryEmbeddings(repositoryId));

        verify(mapper)
                .insertHeuristicCallEdge(
                        any(),
                        eq(repositoryId),
                        eq(contentVersion),
                        eq(sourceId),
                        eq(fooId),
                        eq("caller"),
                        eq("foo"));
        verify(mapper)
                .insertHeuristicCallEdge(
                        any(),
                        eq(repositoryId),
                        eq(contentVersion),
                        eq(sourceId),
                        eq(ooId),
                        eq("caller"),
                        eq("oo"));
    }

    @Test
    void buildsHeuristicCallReferencesOnlyDuringIndexPreparation() {
        UUID repositoryId = UUID.randomUUID();

        assertTrue(service.prepareRepositoryEmbeddings(repositoryId));

        verify(mapper).deleteHeuristicCallEdges(repositoryId);
        verify(mapper).graphChunks(repositoryId);
    }

    @Test
    void heuristicGraphReportsItsSourceContentVersionAndLimitationsWithoutRebuilding() {
        UUID repositoryId = UUID.randomUUID();
        UUID contentVersion = UUID.randomUUID();
        when(mapper.currentContentVersion(repositoryId)).thenReturn(contentVersion);

        IntelligenceService.GraphResult result = service.graph(repositoryId, "Example", 2, "BOTH");

        assertEquals("HEURISTIC_CALL_REFERENCE", result.relationSource());
        assertEquals(contentVersion, result.contentVersion());
        assertEquals("SYMBOL_TOKEN_FOLLOWED_BY_PARENTHESIS", result.algorithm());
        assertTrue(
                result.limitations().stream().anyMatch(item -> item.contains("不是 CodeGraph CLI")));
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

        IntelligenceService.SearchHit hit = service.hybridSearch(repositoryId, "Example", 5).get(0);

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

        IntelligenceService.SearchHit hit = service.hybridSearch(repositoryId, "Example", 5).get(0);

        assertEquals("SEMANTIC_EMBEDDING", hit.similarityKind());
        assertTrue(hit.channels().contains("CODE_SEMANTIC"));
    }

    @Test
    void exposesContentVersionModelRecallTimingAndEnabledChannels() {
        UUID repositoryId = UUID.randomUUID();
        UUID contentVersion = UUID.randomUUID();
        Map<String, Object> evidence = vectorEvidence();
        when(mapper.currentContentVersion(repositoryId)).thenReturn(contentVersion);
        stubVectorModel("text-embedding-test", 3, "SEMANTIC_EMBEDDING", "[0.1,0.2,0.3]");
        when(mapper.searchCodeVector(
                        eq(repositoryId), anyString(), eq("text-embedding-test"), eq(3), anyInt()))
                .thenReturn(List.of(evidence));

        IntelligenceService.SearchResponse response =
                service.hybridSearchDetailed(repositoryId, "Example", 5);

        assertEquals(contentVersion, response.retrieval().contentVersion());
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
        when(mapper.searchCodeKeyword(eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(vectorEvidence()));
        when(llm.vectorize(anyString())).thenThrow(new IllegalStateException("embedding timeout"));
        when(llm.activeVectorModelName()).thenReturn("unreachable-model");
        when(llm.activeRetrievalCapability()).thenReturn("SEMANTIC_EMBEDDING");

        IntelligenceService.SearchResponse response =
                service.hybridSearchDetailed(repositoryId, "Example", 5);

        assertTrue(response.retrieval().degraded());
        assertTrue(response.retrieval().enabledChannels().contains("CODE_KEYWORD"));
        assertTrue(
                response.retrieval().enabledChannels().stream()
                        .noneMatch(channel -> channel.contains("SEMANTIC")));
        assertTrue(
                response.retrieval().unavailableChannels().stream()
                        .anyMatch(
                                channel ->
                                        channel.channel().equals("CODE_VECTOR")
                                                && channel.reason()
                                                        .equals("VECTOR_RETRIEVAL_FAILED")));
        assertTrue(
                response.retrieval()
                        .degradationReasons()
                        .contains("CODE_VECTOR:VECTOR_RETRIEVAL_FAILED"));
    }

    private void stubVectorModel(String model, int dimension, String capability, String vector) {
        when(llm.activeVectorModelName()).thenReturn(model);
        when(llm.activeVectorModelDimension()).thenReturn(dimension);
        when(llm.activeRetrievalCapability()).thenReturn(capability);
        when(llm.vectorize(anyString()))
                .thenReturn(
                        new LlmSettingsService.VectorEmbedding(
                                model, dimension, vector, capability));
    }

    private static Map<String, Object> vectorEvidence() {
        return Map.ofEntries(
                Map.entry("id", UUID.randomUUID()),
                Map.entry("content_version", UUID.randomUUID()),
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
        Map<String, Object> evidence =
                Map.ofEntries(
                        Map.entry("id", UUID.randomUUID()),
                        Map.entry("content_version", UUID.randomUUID()),
                        Map.entry("file_path", "src/Example.java"),
                        Map.entry("symbol_name", "Example"),
                        Map.entry("symbol_kind", "CLASS"),
                        Map.entry("start_line", 1),
                        Map.entry("end_line", 3),
                        Map.entry("content", "class Example {}"),
                        Map.entry("content_hash", "hash"),
                        Map.entry("lexical_score", 1.0d));
        when(mapper.searchCodeKeyword(eq(repositoryId), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(evidence));
        when(graphRetrievalMapper.relatedCodeChunks(eq(repositoryId), anyList(), anyInt()))
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
