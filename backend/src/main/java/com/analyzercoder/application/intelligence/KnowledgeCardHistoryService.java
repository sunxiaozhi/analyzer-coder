package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeHistoryMapper;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeCardRow;
import com.analyzercoder.infrastructure.persistence.model.KnowledgeRevisionRow;
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

    public KnowledgeCardHistoryService(
            KnowledgeHistoryMapper mapper,
            KnowledgeAttachmentService attachments,
            MarkdownRenderingService markdown) {
        this.mapper = mapper;
        this.attachments = attachments;
        this.markdown = markdown;
    }

    private static Revision revision(KnowledgeRevisionRow row) {
        return new Revision(
                row.cardId(),
                row.revision(),
                row.repositoryId(),
                row.title(),
                row.cardType(),
                row.content(),
                List.of(row.tags()),
                row.publicationStatus(),
                row.changedBy(),
                row.changedAt());
    }

    public List<Revision> history(UUID repoId, UUID cardId) {
        return mapper.findHistory(repoId, cardId).stream()
                .map(KnowledgeCardHistoryService::revision)
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

    public record Revision(
            UUID cardId,
            int revision,
            UUID repositoryId,
            String title,
            String cardType,
            String content,
            List<String> tags,
            String publicationStatus,
            UUID changedBy,
            Instant changedAt) {}
}
