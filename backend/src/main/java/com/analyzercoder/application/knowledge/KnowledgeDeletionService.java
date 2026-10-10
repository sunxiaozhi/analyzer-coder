package com.analyzercoder.application.knowledge;

import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes one draft and its dependent data using the schema's transactional cascades. */
@Service
public class KnowledgeDeletionService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeDeletionService.class);
    private final JdbcTemplate db;
    private final AccessControlService access;

    public KnowledgeDeletionService(JdbcTemplate db, AccessControlService access) {
        this.db = db;
        this.access = access;
    }

    @Transactional
    public void delete(
            AuthenticatedAccount actor,
            BranchReadContext context,
            UUID cardId,
            int expectedRevision) {
        access.require(
                actor, CodeRepositoryId.of(context.repositoryId()), RepositoryPermission.MANAGE);
        if (expectedRevision < 1)
            throw new ApiSecurityException(400, "KNOWLEDGE_REVISION_REQUIRED", "删除时必须提供有效的知识修订号");
        var rows =
                db.queryForList(
                        """
                SELECT publication_status,revision FROM knowledge_cards
                WHERE id=? AND repo_id=? AND knowledge_applies_to_branch(id,?) FOR UPDATE
                """,
                        cardId,
                        context.repositoryId(),
                        context.branchId());
        if (rows.isEmpty())
            throw new ApiSecurityException(404, "KNOWLEDGE_NOT_FOUND", "知识不存在或不适用于当前分支");
        var card = rows.get(0);
        if (((Number) card.get("revision")).intValue() != expectedRevision)
            throw new ApiSecurityException(409, "KNOWLEDGE_REVISION_CONFLICT", "知识已被修改，请刷新后重新确认删除");
        if (!"DRAFT".equals(card.get("publication_status")))
            throw new ApiSecurityException(
                    409, "KNOWLEDGE_DELETE_REQUIRES_DRAFT", "仅草稿知识可以删除，请先撤回为草稿");
        // Existing foreign keys cascade embeddings, revision data, Markdown links, code references,
        // branch scopes/validations and attachment references. Markdown sources remain available;
        // historical QA evidence keeps its snapshot and its live knowledge pointer becomes NULL.
        int deleted =
                db.update(
                        """
                DELETE FROM knowledge_cards
                WHERE id=? AND repo_id=? AND revision=? AND publication_status='DRAFT'
                """,
                        cardId,
                        context.repositoryId(),
                        expectedRevision);
        if (deleted != 1)
            throw new ApiSecurityException(409, "KNOWLEDGE_REVISION_CONFLICT", "知识状态已变化，请刷新后重试");
        LOG.info(
                "知识草稿删除: repoId={}, branchId={}, cardId={}, revision={}, actorId={}",
                context.repositoryId(),
                context.branchId(),
                cardId,
                expectedRevision,
                actor.id());
    }
}
