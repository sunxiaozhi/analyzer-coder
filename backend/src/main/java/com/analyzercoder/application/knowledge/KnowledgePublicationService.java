package com.analyzercoder.application.knowledge;

import com.analyzercoder.application.branch.BranchKnowledgeService;
import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一次确认同时记录评审、当前分支适用性和发布状态。 */
@Service
public class KnowledgePublicationService {
    private final JdbcTemplate db;
    private final AccessControlService access;
    private final BranchKnowledgeService branches;
    private final IntelligenceService intelligence;

    public KnowledgePublicationService(
            JdbcTemplate db,
            AccessControlService access,
            BranchKnowledgeService branches,
            IntelligenceService intelligence) {
        this.db = db;
        this.access = access;
        this.branches = branches;
        this.intelligence = intelligence;
    }

    @Transactional
    public IntelligenceService.KnowledgeCard publish(
            AuthenticatedAccount actor,
            BranchReadContext context,
            UUID cardId,
            int expectedRevision) {
        access.require(
                actor, CodeRepositoryId.of(context.repositoryId()), RepositoryPermission.MANAGE);
        var revisions =
                db.queryForList(
                        """
                SELECT revision FROM knowledge_cards
                WHERE id=? AND repo_id=? AND branch_id=? FOR UPDATE
                """,
                        Integer.class,
                        cardId,
                        context.repositoryId(),
                        context.branchId());
        if (revisions.size() != 1 || revisions.get(0) != expectedRevision) {
            throw new ApiSecurityException(409, "KNOWLEDGE_REVISION_CONFLICT", "知识已修改，请刷新后重新发布");
        }
        Integer missing =
                db.queryForObject(
                        """
                SELECT COUNT(*) FROM knowledge_code_refs r
                WHERE r.card_id=? AND r.revision=? AND NOT EXISTS (
                    SELECT 1 FROM code_chunks c WHERE c.repo_id=r.repo_id AND c.content_version=?
                    AND c.file_path=r.file_path AND c.start_line=r.start_line AND c.content_hash=r.content_hash)
                """,
                        Integer.class,
                        cardId,
                        expectedRevision,
                        context.contentVersion());
        if (missing != null && missing > 0) {
            throw new IllegalStateException("关联代码已变化，请编辑卡片并重新选择代码后发布");
        }
        db.update(
                """
                UPDATE knowledge_cards SET source_version_status='CURRENT', verified_commit=?,
                    last_verified_content_version=?, verification_note='发布时已人工确认适用于当前分支',
                    source_version_checked_at=CURRENT_TIMESTAMP,
                    review_status='APPROVED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP
                WHERE id=? AND repo_id=?
                """,
                context.commitSha(),
                context.contentVersion(),
                actor.id(),
                cardId,
                context.repositoryId());
        branches.verify(actor, context, cardId, expectedRevision, "CURRENT", "发布时已人工确认适用于当前分支");
        return intelligence.setCardPublication(
                context.repositoryId(), cardId, actor.id(), "PUBLISHED");
    }
}
