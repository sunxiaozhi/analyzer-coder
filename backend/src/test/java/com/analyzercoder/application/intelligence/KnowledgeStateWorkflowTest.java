package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.knowledge.EngineeringKnowledgePolicy;
import com.analyzercoder.application.llm.LlmSettingsService;
import com.analyzercoder.infrastructure.persistence.mapper.GraphRetrievalMapper;
import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeStateWorkflowTest {
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID cardId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private IntelligenceMapper mapper;
    private IntelligenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IntelligenceMapper.class);
        KnowledgeAttachmentService attachments = mock(KnowledgeAttachmentService.class);
        MarkdownRenderingService markdown = mock(MarkdownRenderingService.class);
        when(attachments.list(any(), any(), any(Integer.class))).thenReturn(List.of());
        when(markdown.render(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        service =
                new IntelligenceService(
                        mapper,
                        mock(GraphRetrievalMapper.class),
                        attachments,
                        markdown,
                        mock(LlmSettingsService.class),
                        new RetrievalQueryAnalyzer(),
                        new RetrievalRanker(),
                        new AnswerCitationValidator(),
                        new EngineeringKnowledgePolicy(),
                        new ObjectMapper());
    }

    @Test
    void unreviewedCardCannotBePublished() {
        when(mapper.cards(repositoryId, true)).thenReturn(List.of(row("DRAFT", "CURRENT", "UNREVIEWED")));

        assertThatThrownBy(
                        () ->
                                service.setCardPublication(
                                        repositoryId, cardId, actorId, "PUBLISHED"))
                .hasMessageContaining("尚未通过人工评审");
        verify(mapper, never()).setCardPublication(any(), any(), any(), any());
    }

    @Test
    void humanApprovalAndPublicationRemainSeparateRecordedActions() {
        KnowledgeCardRow approved = row("DRAFT", "CURRENT", "APPROVED");
        KnowledgeCardRow published = row("PUBLISHED", "CURRENT", "APPROVED");
        when(mapper.reviewCard(repositoryId, cardId, actorId, "APPROVED")).thenReturn(1);
        when(mapper.setCardPublication(repositoryId, cardId, actorId, "PUBLISHED")).thenReturn(1);
        when(mapper.cards(repositoryId, true))
                .thenReturn(List.of(approved), List.of(approved), List.of(published));

        IntelligenceService.KnowledgeCard reviewed =
                service.reviewCard(repositoryId, cardId, actorId, "APPROVED");
        IntelligenceService.KnowledgeCard result =
                service.setCardPublication(repositoryId, cardId, actorId, "PUBLISHED");

        assertThat(reviewed.publicationStatus()).isEqualTo("DRAFT");
        assertThat(reviewed.reviewStatus()).isEqualTo("APPROVED");
        assertThat(result.publicationStatus()).isEqualTo("PUBLISHED");
        verify(mapper).reviewCard(repositoryId, cardId, actorId, "APPROVED");
        verify(mapper).setCardPublication(repositoryId, cardId, actorId, "PUBLISHED");
    }

    @Test
    void staleSourceCannotBePublishedEvenAfterHumanApproval() {
        when(mapper.cards(repositoryId, true)).thenReturn(List.of(row("DRAFT", "STALE", "APPROVED")));

        assertThatThrownBy(
                        () ->
                                service.setCardPublication(
                                        repositoryId, cardId, actorId, "PUBLISHED"))
                .hasMessageContaining("来源版本已过期");
    }

    @Test
    void requiredKnowledgeCannotPublishWithoutOwnerScopeAndCurrentEvidence() {
        when(mapper.cards(repositoryId, true))
                .thenReturn(
                        List.of(requiredRow(null, emptyScope(), "CURRENT")),
                        List.of(requiredRow(actorId, emptyScope(), "CURRENT")),
                        List.of(requiredRow(actorId, populatedScope(), "UNVERIFIED")));

        assertThatThrownBy(
                        () ->
                                service.setCardPublication(
                                        repositoryId, cardId, actorId, "PUBLISHED"))
                .hasMessageContaining("负责人");
        assertThatThrownBy(
                        () ->
                                service.setCardPublication(
                                        repositoryId, cardId, actorId, "PUBLISHED"))
                .hasMessageContaining("适用范围");
        assertThatThrownBy(
                        () ->
                                service.setCardPublication(
                                        repositoryId, cardId, actorId, "PUBLISHED"))
                .hasMessageContaining("当前代码快照");
        verify(mapper, never()).setCardPublication(any(), any(), any(), any());
    }

    private KnowledgeCardRow row(
            String publicationStatus, String sourceVersionStatus, String reviewStatus) {
        return row(
                publicationStatus,
                sourceVersionStatus,
                reviewStatus,
                "REFERENCE",
                "REFERENCE",
                null,
                emptyScope());
    }

    private KnowledgeCardRow requiredRow(
            UUID ownerAccountId, String scopePayload, String sourceVersionStatus) {
        return row(
                "DRAFT",
                sourceVersionStatus,
                "APPROVED",
                "BUSINESS_RULE",
                "REQUIRED",
                ownerAccountId,
                scopePayload);
    }

    private KnowledgeCardRow row(
            String publicationStatus,
            String sourceVersionStatus,
            String reviewStatus,
            String knowledgeKind,
            String enforcement,
            UUID ownerAccountId,
            String scopePayload) {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new KnowledgeCardRow(
                cardId,
                repositoryId,
                "知识",
                "业务规则",
                "正文",
                new String[0],
                knowledgeKind,
                "INFO",
                enforcement,
                ownerAccountId,
                scopePayload,
                "{\"requiredTests\":[],\"requiredApproverAccountIds\":[],\"instructions\":[]}",
                null,
                null,
                publicationStatus,
                1,
                now,
                now,
                "commit",
                sourceVersionStatus,
                now,
                reviewStatus,
                "UNREVIEWED".equals(reviewStatus) ? null : actorId,
                "UNREVIEWED".equals(reviewStatus) ? null : now);
    }

    private static String emptyScope() {
        return "{\"pathPatterns\":[],\"symbols\":[],\"modules\":[]}";
    }

    private static String populatedScope() {
        return "{\"pathPatterns\":[\"backend/src/**\"],\"symbols\":[],\"modules\":[]}";
    }
}
