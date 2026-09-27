package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Run against a dedicated test database, with the real Flyway schema and no background workers. */
@EnabledIfEnvironmentVariable(named = "APP_RUN_POSTGRES_IT", matches = "true")
@SpringBootTest(
        classes = CodebaseKnowledgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.lazy-initialization=false", "app.workers.enabled=false"})
@Transactional
class RepositoryProjectDraftDeletionIT {
    @Autowired RepositoryProjectDraftService drafts;
    @Autowired JdbcTemplate db;

    @Test
    void deletingFailedDraftPreservesSameNamedProjectAndImportHistory() {
        var owner = account(AccountRole.NORMAL);
        UUID draft = draft(owner, "FAILED");
        UUID repository = UUID.randomUUID();
        db.update(
                """
                INSERT INTO repositories(id,name,normalized_name,path,default_branch,owner_account_id,created_at,updated_at)
                VALUES(?,'Project','project',?,'main',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                repository,
                "/test/" + repository,
                owner.id());
        UUID job = job(owner, draft, "FAILED");

        drafts.delete(owner, draft, 1);

        assertThat(exists(draft)).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT name FROM repositories WHERE id=?",
                                String.class,
                                repository))
                .isEqualTo("Project");
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM repository_import_jobs WHERE id=?",
                                String.class,
                                job))
                .isEqualTo("FAILED");
        assertThat(
                        db.queryForObject(
                                "SELECT project_draft_id FROM repository_import_jobs WHERE id=?",
                                UUID.class,
                                job))
                .isNull();
    }

    @Test
    void onlyOwnerOrSuperAdminCanDeletePendingDrafts() {
        var owner = account(AccountRole.NORMAL);
        var stranger = account(AccountRole.NORMAL);
        var admin = account(AccountRole.SUPER_ADMIN);
        UUID draft = draft(owner, "DRAFT");

        assertThatThrownBy(() -> drafts.delete(stranger, draft, 1))
                .isInstanceOfSatisfying(
                        ApiSecurityException.class,
                        error -> assertThat(error.status()).isEqualTo(404));
        assertThat(exists(draft)).isTrue();
        drafts.delete(admin, draft, 1);
        assertThat(exists(draft)).isFalse();
        UUID configured = draft(owner, "SOURCE_CONFIGURED");
        drafts.delete(owner, configured, 1);
        assertThat(exists(configured)).isFalse();
    }

    @Test
    void rejectsImportingCompletedAndStaleDrafts() {
        var owner = account(AccountRole.NORMAL);
        for (String status : new String[] {"IMPORTING", "READY"}) {
            UUID draft = draft(owner, status);
            assertConflict(owner, draft, 1);
        }
        UUID draft = draft(owner, "FAILED");
        db.update("UPDATE repository_project_drafts SET version=2 WHERE id=?", draft);
        assertConflict(owner, draft, 1);
        drafts.delete(owner, draft, 2);
        assertThat(exists(draft)).isFalse();
    }

    @Test
    void rejectsDraftsWithQueuedOrRunningJobsEvenIfDraftStatusIsOutOfDate() {
        var owner = account(AccountRole.NORMAL);
        for (String status : new String[] {"QUEUED", "RUNNING"}) {
            UUID draft = draft(owner, "FAILED");
            job(owner, draft, status);
            assertConflict(owner, draft, 1);
        }
    }

    private void assertConflict(AuthenticatedAccount owner, UUID draft, long version) {
        assertThatThrownBy(() -> drafts.delete(owner, draft, version))
                .isInstanceOfSatisfying(
                        ApiSecurityException.class,
                        error -> assertThat(error.status()).isEqualTo(409));
        assertThat(exists(draft)).isTrue();
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM repository_project_drafts WHERE id=?)",
                        Boolean.class,
                        id));
    }

    private AuthenticatedAccount account(AccountRole role) {
        UUID id = UUID.randomUUID();
        String username = "draft-test-" + id;
        db.update(
                """
                INSERT INTO accounts(id,username,display_name,password_hash,account_role,must_change_password,created_at,updated_at)
                VALUES(?,?,'Test','not-a-real-password',?,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                id,
                username,
                role.name());
        return new AuthenticatedAccount(id, username, "Test", role, false, null);
    }

    private UUID draft(AuthenticatedAccount owner, String status) {
        UUID id = drafts.create(owner, "Project", "Test draft").id();
        db.update("UPDATE repository_project_drafts SET lifecycle_status=? WHERE id=?", status, id);
        return id;
    }

    private UUID job(AuthenticatedAccount owner, UUID draft, String status) {
        UUID id = UUID.randomUUID();
        db.update(
                """
                INSERT INTO repository_import_jobs(id,account_id,source_type,repository_name,remote_url,project_draft_id,status)
                VALUES(?,?,'GITLAB','Project','https://git.example.com/project.git',?,?)
                """,
                id,
                owner.id(),
                draft,
                status);
        return id;
    }
}
