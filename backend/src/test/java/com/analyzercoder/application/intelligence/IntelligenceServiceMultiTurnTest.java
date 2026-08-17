package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.infrastructure.persistence.mapper.GraphRetrievalMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntelligenceServiceMultiTurnTest {
    private IntelligenceMapper mapper;
    private IntelligenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IntelligenceMapper.class);
        service = new IntelligenceService(
                mapper,
                mock(GraphRetrievalMapper.class),
                mock(KnowledgeAttachmentService.class),
                mock(MarkdownRenderingService.class),
                mock(LlmSettingsService.class),
                new RetrievalQueryAnalyzer(),
                new RetrievalRanker(),
                new AnswerCitationValidator(),
                new ObjectMapper());
    }

    @Test
    void rejectsFollowUpForThreadOutsideRepositoryAndAccount() {
        UUID repositoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        when(mapper.findThread(threadId, repositoryId, accountId)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.ask(repositoryId, accountId, "继续追问", UUID.randomUUID(), threadId));

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