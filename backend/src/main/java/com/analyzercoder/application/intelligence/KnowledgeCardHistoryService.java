package com.analyzercoder.application.intelligence;

import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeHistoryMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeRevisionRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排知识卡片历史相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class KnowledgeCardHistoryService {
    private final KnowledgeHistoryMapper mapper;
    private final KnowledgeAttachmentService attachments;
    private final MarkdownRenderingService markdown;
    private final ObjectMapper json;

    public KnowledgeCardHistoryService(
            KnowledgeHistoryMapper mapper,
            KnowledgeAttachmentService attachments,
            MarkdownRenderingService markdown,
            ObjectMapper json) {
        this.mapper = mapper;
        this.attachments = attachments;
        this.markdown = markdown;
        this.json = json;
    }

    private Revision revision(KnowledgeRevisionRow row) {
        return new Revision(
                row.cardId(),
                row.revision(),
                row.repositoryId(),
                row.title(),
                row.cardType(),
                row.content(),
                List.of(row.tags()),
                KnowledgeKind.valueOf(row.knowledgeKind()),
                KnowledgeSeverity.valueOf(row.severity()),
                KnowledgeEnforcement.valueOf(row.enforcement()),
                row.ownerAccountId(),
                readPayload(row.scopePayload(), KnowledgeScope.class, KnowledgeScope.empty()),
                readPayload(
                        row.obligationsPayload(),
                        KnowledgeObligations.class,
                        KnowledgeObligations.empty()),
                row.lastVerifiedSnapshotId(),
                row.verificationNote(),
                row.publicationStatus(),
                row.changedBy(),
                row.changedAt());
    }

    public List<Revision> history(UUID repoId, UUID cardId) {
        return mapper.findHistory(repoId, cardId).stream()
                .map(this::revision)
                .toList();
    }

    @Transactional
    public IntelligenceService.KnowledgeCard restore(
            UUID repoId, UUID cardId, int revision, UUID actor) {
        KnowledgeRevisionRow source = mapper.findRevision(repoId, cardId, revision);
        if (source == null) {
            throw new IllegalArgumentException("知识卡片历史修订不存在");
        }
        if (mapper.restore(repoId, cardId, source, actor) == 0) {
            throw new IllegalArgumentException("知识卡片不存在");
        }
        KnowledgeCardRow card = mapper.findCard(repoId, cardId);
        attachments.attach(
                repoId,
                cardId,
                card.revision(),
                attachments.list(repoId, cardId, revision).stream()
                        .map(KnowledgeAttachmentService.Attachment::id)
                        .toList());
        return new IntelligenceService.KnowledgeCard(
                card.id(),
                card.repositoryId(),
                card.title(),
                card.cardType(),
                card.content(),
                markdown.render(repoId, card.content()),
                List.of(card.tags()),
                KnowledgeKind.valueOf(card.knowledgeKind()),
                KnowledgeSeverity.valueOf(card.severity()),
                KnowledgeEnforcement.valueOf(card.enforcement()),
                card.ownerAccountId(),
                readPayload(card.scopePayload(), KnowledgeScope.class, KnowledgeScope.empty()),
                readPayload(
                        card.obligationsPayload(),
                        KnowledgeObligations.class,
                        KnowledgeObligations.empty()),
                card.lastVerifiedSnapshotId(),
                card.verificationNote(),
                card.publicationStatus(),
                card.revision(),
                card.createdAt(),
                card.updatedAt(),
                card.verifiedCommit(),
                card.sourceVersionStatus(),
                card.sourceVersionCheckedAt(),
                card.reviewStatus(),
                card.reviewedBy(),
                card.reviewedAt(),
                attachments.list(repoId, cardId, card.revision()),
                List.of());
    }

    private <T> T readPayload(String payload, Class<T> type, T fallback) {
        if (payload == null || payload.isBlank()) {
            return fallback;
        }
        try {
            return json.readValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工程知识历史结构化数据无法读取", exception);
        }
    }

    public record Revision(
            UUID cardId,
            int revision,
            UUID repositoryId,
            String title,
            String cardType,
            String content,
            List<String> tags,
            KnowledgeKind knowledgeKind,
            KnowledgeSeverity severity,
            KnowledgeEnforcement enforcement,
            UUID ownerAccountId,
            KnowledgeScope scope,
            KnowledgeObligations obligations,
            UUID lastVerifiedSnapshotId,
            String verificationNote,
            String publicationStatus,
            UUID changedBy,
            Instant changedAt) {}
}
