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

    public record BranchScope(String mode, List<UUID> branchIds) {
        public BranchScope {
            branchIds = branchIds == null ? List.of() : List.copyOf(branchIds);
        }
    }

    public BranchScope scope(UUID cardId, int revision) {
        return db.queryForObject(
                "SELECT mode,branch_ids FROM knowledge_branch_scope_history WHERE card_id=? AND revision=?",
                (r, n) ->
                        new BranchScope(
                                r.getString("mode"),
                                java.util.Arrays.asList(
                                        (UUID[]) r.getArray("branch_ids").getArray())),
                cardId,
                revision);
    }

    public void checkRevision(UUID repoId, UUID cardId, Integer expectedRevision) {
        if (expectedRevision == null) return;
        var revisions =
                db.queryForList(
                        "SELECT revision FROM knowledge_cards WHERE id=? AND repo_id=? FOR UPDATE",
                        Integer.class,
                        cardId,
                        repoId);
        if (revisions.size() != 1 || !expectedRevision.equals(revisions.get(0)))
            throw new ApiSecurityException(409, "KNOWLEDGE_REVISION_CONFLICT", "知识已被修改，请刷新后重试");
    }

    @Transactional
    public void saveScope(UUID repoId, UUID cardId, int revision, BranchScope scope) {
        if (scope == null) return;
        var ids = scope.branchIds().stream().distinct().toList();
        if (!("ALL_BRANCHES".equals(scope.mode()) && ids.isEmpty())
                && !("SELECTED_BRANCHES".equals(scope.mode())
                        && !ids.isEmpty()
                        && ids.size() <= 30))
            throw new IllegalArgumentException("请选择项目共享或 1 至 30 个指定分支");
        for (UUID id : ids) {
            Integer count =
                    db.queryForObject(
                            "SELECT COUNT(*) FROM repository_branches WHERE repo_id=? AND id=?",
                            Integer.class,
                            repoId,
                            id);
            if (count == null || count != 1) throw new IllegalArgumentException("共享分支不属于当前项目");
        }
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "UPDATE knowledge_branch_scopes SET mode=?,branch_ids=? WHERE card_id=? AND EXISTS(SELECT 1 FROM knowledge_cards WHERE id=? AND repo_id=? AND revision=?)");
                    statement.setString(1, scope.mode());
                    statement.setArray(2, connection.createArrayOf("uuid", ids.toArray()));
                    statement.setObject(3, cardId);
                    statement.setObject(4, cardId);
                    statement.setObject(5, repoId);
                    statement.setInt(6, revision);
                    return statement;
                });
        db.update(
                "UPDATE knowledge_branch_scope_history h SET mode=s.mode,branch_ids=s.branch_ids FROM knowledge_branch_scopes s WHERE h.card_id=s.card_id AND h.card_id=? AND h.revision=?",
                cardId,
                revision);
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
                WHERE k.repo_id=? AND knowledge_applies_to_branch(k.id,?) AND k.publication_status<>'ARCHIVED'
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
            WHERE k.repo_id=? AND k.id=? AND k.revision=? AND knowledge_applies_to_branch(k.id,?)
            """,
                        Integer.class,
                        context.repositoryId(),
                        cardId,
                        revision,
                        context.branchId());
        if (count == null || count != 1)
            throw new ApiSecurityException(409, "KNOWLEDGE_SCOPE_MISMATCH", "知识修订已变化或不适用于该分支");
        String sourceState =
                switch (state) {
                    case "CURRENT" -> "CURRENT";
                    case "REVIEW_REQUIRED" -> "SUSPECT";
                    case "INVALID" -> "STALE";
                    default -> "UNVERIFIED";
                };
        db.update(
                """
                UPDATE knowledge_cards SET source_version_status=?, verification_note=?,
                    verified_commit=CASE WHEN ?='CURRENT' THEN ? ELSE verified_commit END,
                    last_verified_content_version=CASE WHEN ?='CURRENT' THEN ? ELSE last_verified_content_version END,
                    source_version_checked_at=CURRENT_TIMESTAMP
                WHERE id=? AND repo_id=? AND revision=? AND branch_id=?
                    AND EXISTS(SELECT 1 FROM knowledge_branch_scopes s WHERE s.card_id=knowledge_cards.id
                        AND s.mode='SELECTED_BRANCHES' AND cardinality(s.branch_ids)=1)
                """,
                sourceState,
                note,
                state,
                context.commitSha(),
                state,
                context.contentVersion(),
                cardId,
                context.repositoryId(),
                revision,
                context.branchId());
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
                        "SELECT id FROM knowledge_cards WHERE repo_id=? AND knowledge_applies_to_branch(id,?)",
                        UUID.class,
                        repoId,
                        branchId));
    }
}
