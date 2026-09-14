package com.analyzercoder.application.branch;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.Arrays;
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

    public record Scope(UUID cardId, int revision, String mode, List<UUID> branchIds) {}

    public record ValidationCard(
            UUID cardId, int revision, String title, String content, String state, String note) {}

    public List<ValidationCard> validations(AuthenticatedAccount actor, BranchReadContext context) {
        access.require(
                actor, CodeRepositoryId.of(context.repositoryId()), RepositoryPermission.READ);
        return db.query(
                """
                SELECT k.id,k.revision,k.title,k.content,COALESCE(v.state,'UNVERIFIED') state,
                       COALESCE(v.note,'') note
                FROM knowledge_cards k JOIN knowledge_branch_scopes s ON s.card_id=k.id
                LEFT JOIN knowledge_branch_validations v ON v.card_id=k.id AND v.revision=k.revision
                    AND v.branch_id=? AND v.snapshot_id=?
                WHERE k.repo_id=? AND k.publication_status<>'ARCHIVED'
                    AND (s.mode='ALL_BRANCHES' OR ?=ANY(s.branch_ids))
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
                context.snapshotId(),
                context.repositoryId(),
                context.branchId());
    }

    public List<Scope> scopes(AuthenticatedAccount actor, UUID repoId) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.READ);
        return db.query(
                "SELECT k.id,k.revision,s.mode,s.branch_ids FROM knowledge_cards k JOIN knowledge_branch_scopes s ON s.card_id=k.id WHERE k.repo_id=?",
                (r, n) ->
                        new Scope(
                                r.getObject("id", UUID.class),
                                r.getInt("revision"),
                                r.getString("mode"),
                                Arrays.asList((UUID[]) r.getArray("branch_ids").getArray())),
                repoId);
    }

    @Transactional
    public void apply(
            AuthenticatedAccount actor,
            UUID repoId,
            UUID cardId,
            int expectedRevision,
            String mode,
            List<UUID> branchIds) {
        access.require(actor, CodeRepositoryId.of(repoId), RepositoryPermission.MANAGE);
        List<UUID> ids = branchIds == null ? List.of() : branchIds.stream().distinct().toList();
        if (mode == null
                || !Set.of("ALL_BRANCHES", "SELECTED_BRANCHES").contains(mode)
                || ids.size() > 30
                || ("SELECTED_BRANCHES".equals(mode) && ids.isEmpty())
                || ("ALL_BRANCHES".equals(mode) && !ids.isEmpty()))
            throw new IllegalArgumentException("知识适用分支范围无效");
        for (UUID id : ids)
            if (id == null
                    || db.queryForObject(
                                    "SELECT COUNT(*) FROM repository_branches WHERE id=? AND repo_id=?",
                                    Integer.class,
                                    id,
                                    repoId)
                            != 1) throw new IllegalArgumentException("适用分支不属于当前仓库");
        var versions =
                db.queryForList(
                        "SELECT revision FROM knowledge_cards WHERE id=? AND repo_id=? FOR UPDATE",
                        Integer.class,
                        cardId,
                        repoId);
        if (versions.isEmpty() || versions.get(0) != expectedRevision)
            throw new ApiSecurityException(409, "KNOWLEDGE_REVISION_CONFLICT", "知识已更新，请刷新后重试");
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "INSERT INTO knowledge_branch_scopes(card_id,repo_id,mode,branch_ids) VALUES(?,?,?,?) ON CONFLICT(card_id) DO UPDATE SET mode=EXCLUDED.mode,branch_ids=EXCLUDED.branch_ids");
                    statement.setObject(1, cardId);
                    statement.setObject(2, repoId);
                    statement.setString(3, mode);
                    statement.setArray(4, connection.createArrayOf("uuid", ids.toArray()));
                    return statement;
                });
        db.update(
                "UPDATE knowledge_cards SET revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                actor.id(),
                cardId);
        // A scope edit creates a revision, but must not erase its evidence or attachments.
        db.update(
                """
            INSERT INTO knowledge_code_refs(card_id,revision,position,repo_id,snapshot_id,chunk_id,file_path,symbol_name,start_line,end_line,content_hash)
            SELECT card_id,revision+1,position,repo_id,snapshot_id,chunk_id,file_path,symbol_name,start_line,end_line,content_hash
            FROM knowledge_code_refs WHERE card_id=? AND revision=?
            """,
                cardId,
                expectedRevision);
        db.update(
                """
            INSERT INTO knowledge_card_attachment_refs(card_id,revision,attachment_id,position)
            SELECT card_id,revision+1,attachment_id,position FROM knowledge_card_attachment_refs WHERE card_id=? AND revision=?
            """,
                cardId,
                expectedRevision);
    }

    @Transactional
    public void bindCreated(UUID repoId, UUID cardId, UUID branchId) {
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "UPDATE knowledge_branch_scopes SET branch_ids=? WHERE card_id=? AND repo_id=?");
                    statement.setArray(1, connection.createArrayOf("uuid", new UUID[] {branchId}));
                    statement.setObject(2, cardId);
                    statement.setObject(3, repoId);
                    return statement;
                });
        db.update(
                "UPDATE knowledge_branch_scope_history SET branch_ids=ARRAY[?]::uuid[] WHERE card_id=? AND revision=1",
                branchId,
                cardId);
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
            SELECT COUNT(*) FROM knowledge_cards k JOIN knowledge_branch_scopes s ON s.card_id=k.id
            WHERE k.repo_id=? AND k.id=? AND k.revision=? AND (s.mode='ALL_BRANCHES' OR ?=ANY(s.branch_ids))
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
            INSERT INTO knowledge_branch_validations(card_id,revision,branch_id,snapshot_id,state,note,checked_by) VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(card_id,revision,branch_id,snapshot_id) DO UPDATE SET state=EXCLUDED.state,note=EXCLUDED.note,checked_by=EXCLUDED.checked_by,checked_at=CURRENT_TIMESTAMP
            """,
                cardId,
                revision,
                context.branchId(),
                context.snapshotId(),
                state,
                note,
                actor.id());
    }

    public Set<UUID> applicable(UUID repoId, UUID branchId) {
        return new HashSet<>(
                db.queryForList(
                        "SELECT card_id FROM knowledge_branch_scopes WHERE repo_id=? AND (mode='ALL_BRANCHES' OR ?=ANY(branch_ids))",
                        UUID.class,
                        repoId,
                        branchId));
    }
}
