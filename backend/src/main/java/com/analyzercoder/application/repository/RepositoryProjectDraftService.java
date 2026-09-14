package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable metadata-first lifecycle for projects whose source may take time to acquire. */
@Service
public class RepositoryProjectDraftService {
    private final JdbcTemplate db;
    private final AccessControlService access;

    public RepositoryProjectDraftService(JdbcTemplate db, AccessControlService access) {
        this.db = db;
        this.access = access;
    }

    @Transactional
    public Draft create(AuthenticatedAccount actor, String name, String description) {
        String cleaned = clean(name, 100, "项目名称");
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO repository_project_drafts(id,owner_account_id,name,description) VALUES(?,?,?,?)",
                id,
                actor.id(),
                cleaned,
                cleanOptional(description, 500, "项目说明"));
        return get(actor, id);
    }

    @Transactional
    public Draft configure(
            AuthenticatedAccount actor,
            UUID id,
            long expectedVersion,
            RepositorySourceType sourceType,
            String sourceLocation,
            UUID credentialId) {
        if (sourceType == null) throw new IllegalArgumentException("请选择代码来源类型");
        String location = clean(sourceLocation, 2_000, "代码来源");
        if (db.update(
                        """
                        UPDATE repository_project_drafts SET source_type=?,source_location=?,credential_id=?,
                            lifecycle_status='SOURCE_CONFIGURED',error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND owner_account_id=? AND version=? AND lifecycle_status IN ('DRAFT','SOURCE_CONFIGURED','FAILED')
                        """,
                        sourceType.name(),
                        location,
                        credentialId,
                        id,
                        actor.id(),
                        expectedVersion)
                != 1)
            throw new ApiSecurityException(409, "PROJECT_DRAFT_CONFLICT", "项目草稿已变化，请刷新后重试");
        return get(actor, id);
    }

    @Transactional
    public void importing(AuthenticatedAccount actor, UUID id) {
        if (id == null) return;
        if (db.update(
                        """
                        UPDATE repository_project_drafts SET lifecycle_status='IMPORTING',error=NULL,
                            version=version+1,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND owner_account_id=? AND lifecycle_status IN ('SOURCE_CONFIGURED','FAILED')
                        """,
                        id,
                        actor.id())
                != 1)
            throw new ApiSecurityException(409, "PROJECT_DRAFT_CONFLICT", "项目草稿尚未配置来源或已被处理");
    }

    @Transactional
    public Draft complete(AuthenticatedAccount actor, UUID id, UUID repositoryId) {
        if (repositoryId == null) throw new IllegalArgumentException("缺少已导入的仓库");
        access.require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.MANAGE);
        applyMetadata(id, repositoryId);
        if (db.update(
                        """
                        UPDATE repository_project_drafts SET lifecycle_status='READY',result_repository_id=?,
                            error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND owner_account_id=? AND lifecycle_status IN ('SOURCE_CONFIGURED','IMPORTING')
                        """,
                        repositoryId,
                        id,
                        actor.id())
                != 1) throw new ApiSecurityException(409, "PROJECT_DRAFT_CONFLICT", "项目草稿状态已变化");
        return get(actor, id);
    }

    @Transactional
    public void complete(UUID id, UUID repositoryId) {
        if (id != null) {
            applyMetadata(id, repositoryId);
            db.update(
                    "UPDATE repository_project_drafts SET lifecycle_status='READY',result_repository_id=?,error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    repositoryId,
                    id);
        }
    }

    private void applyMetadata(UUID draftId, UUID repositoryId) {
        if (db.update(
                        """
                        UPDATE repositories r SET description=d.description,
                            repository_version=r.repository_version+1,updated_at=CURRENT_TIMESTAMP
                        FROM repository_project_drafts d
                        WHERE d.id=? AND r.id=? AND r.owner_account_id=d.owner_account_id
                        """,
                        draftId,
                        repositoryId)
                != 1) {
            throw new ApiSecurityException(
                    409, "PROJECT_DRAFT_REPOSITORY_MISMATCH", "项目草稿与导入仓库归属不一致");
        }
    }

    @Transactional
    public void fail(UUID id, String error) {
        if (id != null)
            db.update(
                    "UPDATE repository_project_drafts SET lifecycle_status='FAILED',error=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lifecycle_status<>'READY'",
                    error == null ? "来源准备失败" : error.substring(0, Math.min(500, error.length())),
                    id);
    }

    public List<Draft> list(AuthenticatedAccount actor) {
        String sql =
                "SELECT * FROM repository_project_drafts"
                        + (actor.isSuperAdmin() ? "" : " WHERE owner_account_id=?")
                        + " ORDER BY updated_at DESC";
        return actor.isSuperAdmin()
                ? db.query(sql, this::map)
                : db.query(sql, this::map, actor.id());
    }

    public Draft get(AuthenticatedAccount actor, UUID id) {
        return db
                .query(
                        "SELECT * FROM repository_project_drafts WHERE id=? AND (owner_account_id=? OR ?)",
                        this::map,
                        id,
                        actor.id(),
                        actor.isSuperAdmin())
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ApiSecurityException(404, "PROJECT_DRAFT_NOT_FOUND", "项目草稿不存在"));
    }

    private Draft map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new Draft(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                row.getString("source_type"),
                row.getString("source_location"),
                row.getObject("credential_id", UUID.class),
                row.getString("lifecycle_status"),
                row.getObject("result_repository_id", UUID.class),
                row.getString("error"),
                row.getLong("version"),
                row.getTimestamp("updated_at").toInstant());
    }

    private static String clean(String value, int max, String label) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > max)
            throw new IllegalArgumentException(label + "长度必须为 1-" + max + " 个字符");
        return cleaned;
    }

    private static String cleanOptional(String value, int max, String label) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > max)
            throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
        return cleaned;
    }

    public record Draft(
            UUID id,
            String name,
            String description,
            String sourceType,
            String sourceLocation,
            UUID credentialId,
            String lifecycleStatus,
            UUID resultRepositoryId,
            String error,
            long version,
            Instant updatedAt) {}
}
