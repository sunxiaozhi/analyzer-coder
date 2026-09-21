package com.analyzercoder.application.branch;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BranchKnowledgeService {
    private final JdbcTemplate db;
    private final AccessControlService access;

    public BranchKnowledgeService(JdbcTemplate db, AccessControlService access) {
        this.db = db;
        this.access = access;
    }

    public record ValidationCard(
            UUID cardId, int revision, String title, String content, String state, String note) {}

    public List<ValidationCard> validations(AuthenticatedAccount actor, BranchReadContext context) {
        access.require(
                actor, CodeRepositoryId.of(context.repositoryId()), RepositoryPermission.READ);
        return db.query(
                """
                SELECT k.id,k.revision,k.title,k.content,COALESCE(v.state,'UNVERIFIED') state,
                       COALESCE(v.note,'') note
                FROM knowledge_cards k
                LEFT JOIN knowledge_branch_validations v ON v.card_id=k.id AND v.revision=k.revision
                    AND v.branch_id=? AND v.content_version=?
                WHERE k.repo_id=? AND k.branch_id=? AND k.publication_status<>'ARCHIVED'
                ORDER BY k.title,k.id
                """,
                (row, number) ->
                        new ValidationCard(
                                row.getObject("id", UUID.class),
                                row.getInt("revision"),
                                row.getString("title"),
                                row.getString("content"),
                                row.getString("state"),
                                row.getString("note")),
                context.branchId(),
                context.contentVersion(),
                context.repositoryId(),
                context.branchId());
    }

    @Transactional
    public void verify(
            AuthenticatedAccount actor,
            BranchReadContext context,
            UUID cardId,
            int revision,
            String state,
            String note) {
        access.require(
                actor, CodeRepositoryId.of(context.repositoryId()), RepositoryPermission.MANAGE);
        if (state == null
                || !Set.of("CURRENT", "UNVERIFIED", "REVIEW_REQUIRED", "INVALID").contains(state)
                || note == null
                || note.isBlank()
                || note.length() > 2000) throw new IllegalArgumentException("请填写有效的分支验证状态和说明");
        Integer count =
                db.queryForObject(
                        """
            SELECT COUNT(*) FROM knowledge_cards k
            WHERE k.repo_id=? AND k.id=? AND k.revision=? AND k.branch_id=?
            """,
                        Integer.class,
                        context.repositoryId(),
                        cardId,
                        revision,
                        context.branchId());
        if (count == null || count != 1)
            throw new ApiSecurityException(409, "KNOWLEDGE_SCOPE_MISMATCH", "知识修订已变化或不适用于该分支");
        db.update(
                """
            INSERT INTO knowledge_branch_validations(card_id,revision,branch_id,content_version,state,note,checked_by) VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(card_id,revision,branch_id,content_version) DO UPDATE SET state=EXCLUDED.state,note=EXCLUDED.note,checked_by=EXCLUDED.checked_by,checked_at=CURRENT_TIMESTAMP
            """,
                cardId,
                revision,
                context.branchId(),
                context.contentVersion(),
                state,
                note,
                actor.id());
    }

    public Set<UUID> applicable(UUID repoId, UUID branchId) {
        return new HashSet<>(
                db.queryForList(
                        "SELECT id FROM knowledge_cards WHERE repo_id=? AND branch_id=?",
                        UUID.class,
                        repoId,
                        branchId));
    }
}
