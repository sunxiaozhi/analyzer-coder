package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "APP_BRANCH_JOB_TEST_URL", matches = ".+")
class BranchPreparationJobsTest {
    @Test
    void queueDeduplicatesResumesPinnedCommitsAndAllowsFailedRetry() throws Exception {
        String url = System.getenv("APP_BRANCH_JOB_TEST_URL");
        String user = System.getenv("APP_BRANCH_JOB_TEST_USER");
        String password = System.getenv("APP_BRANCH_JOB_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "branch_job_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source =
                    new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
            var db = new JdbcTemplate(source);
            db.execute(
                    "CREATE TABLE accounts(id UUID PRIMARY KEY,username TEXT,display_name TEXT,account_role TEXT,enabled BOOLEAN,must_change_password BOOLEAN)");
            db.execute("CREATE TABLE repositories(id UUID PRIMARY KEY,deleted_at TIMESTAMPTZ)");
            db.execute(
                    "CREATE TABLE repository_branches(id UUID PRIMARY KEY,repo_id UUID,tracking_status TEXT DEFAULT 'ACTIVE',UNIQUE(repo_id,id))");
            db.execute(
                    "CREATE TABLE branch_snapshots(id UUID PRIMARY KEY,branch_id UUID,repo_id UUID,content_indexed_at TIMESTAMPTZ,UNIQUE(branch_id,id))");
            db.execute("CREATE TABLE code_chunks(repo_id UUID,snapshot_id UUID)");
            try (var input =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/migration/V5__branch_preparation_jobs.sql")) {
                db.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            UUID repo = UUID.randomUUID(), branch = UUID.randomUUID(), account = UUID.randomUUID();
            db.update(
                    "INSERT INTO accounts VALUES(?,'owner','Owner','SUPER_ADMIN',TRUE,FALSE)",
                    account);
            db.update("INSERT INTO repositories VALUES(?,NULL)", repo);
            db.update("INSERT INTO repository_branches(id,repo_id) VALUES(?,?)", branch, repo);
            var actor =
                    new AuthenticatedAccount(
                            account, "owner", "Owner", AccountRole.SUPER_ADMIN, false, null);
            var executor = mock(RepositoryBranchService.class);
            var intelligence = mock(IntelligenceService.class);
            var service =
                    new BranchPreparationJobs(
                            db,
                            source,
                            executor,
                            mock(AccessControlService.class),
                            new DataSourceTransactionManager(source),
                            intelligence);
            var first = service.submit(actor, repo, branch);
            assertThat(service.submit(actor, repo, branch).id()).isEqualTo(first.id());
            verifyNoInteractions(executor);
            // A stopped process leaves RUNNING and a fixed commit in storage.
            String commit = "a".repeat(40);
            db.update(
                    "UPDATE branch_preparation_jobs SET status='RUNNING',target_commit=?,updated_at=CURRENT_TIMESTAMP-INTERVAL '2 hours' WHERE id=?",
                    commit,
                    first.id());
            doAnswer(
                            call -> {
                                assertThat(call.<String>getArgument(3)).isEqualTo(commit);
                                call.<BiConsumer<String, String>>getArgument(4)
                                        .accept("INDEXING", commit);
                                call.<Runnable>getArgument(5).run();
                                return null;
                            })
                    .when(executor)
                    .executePreparation(any(), eq(repo), eq(branch), any(), any(), any());
            service.processNext();
            assertThat(service.list(actor, repo).get(0).status()).isEqualTo("SUCCEEDED");

            reset(executor);
            var second = service.submit(actor, repo, branch);
            doThrow(new IllegalStateException("private filesystem details"))
                    .when(executor)
                    .executePreparation(any(), any(), any(), any(), any(), any());
            service.processNext();
            assertThat(service.list(actor, repo).get(0).status()).isEqualTo("FAILED");
            assertThat(service.list(actor, repo).get(0).error())
                    .doesNotContain("private filesystem");
            assertThat(service.submit(actor, repo, branch).id()).isNotEqualTo(second.id());

            db.execute(
                    "CREATE TABLE knowledge_cards(id UUID PRIMARY KEY,repo_id UUID,revision INTEGER,title TEXT,content TEXT,publication_status TEXT)");
            db.execute(
                    "CREATE TABLE knowledge_branch_scopes(card_id UUID,mode TEXT,branch_ids UUID[])");
            db.execute(
                    "CREATE TABLE knowledge_branch_validations(card_id UUID,revision INTEGER,branch_id UUID,snapshot_id UUID,state TEXT,note TEXT,checked_by UUID,checked_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(card_id,revision,branch_id,snapshot_id))");
            UUID card = UUID.randomUUID(), snapshot = UUID.randomUUID();
            db.update(
                    "INSERT INTO knowledge_cards VALUES(?,?,1,'Knowledge','Content','PUBLISHED')",
                    card,
                    repo);
            db.update(
                    "INSERT INTO knowledge_branch_scopes VALUES(?,'SELECTED_BRANCHES',ARRAY[?]::uuid[])",
                    card,
                    branch);
            var knowledge = new BranchKnowledgeService(db, mock(AccessControlService.class));
            var context =
                    new BranchReadContext(
                            UUID.randomUUID(),
                            repo,
                            branch,
                            "main",
                            snapshot,
                            commit,
                            Path.of("."),
                            Instant.now().plusSeconds(60));
            assertThat(knowledge.validations(actor, context).get(0).state())
                    .isEqualTo("UNVERIFIED");
            knowledge.verify(actor, context, card, 1, "CURRENT", "Checked source");
            assertThat(knowledge.validations(actor, context).get(0).state()).isEqualTo("CURRENT");
            var next =
                    new BranchReadContext(
                            UUID.randomUUID(),
                            repo,
                            branch,
                            "main",
                            UUID.randomUUID(),
                            commit,
                            Path.of("."),
                            Instant.now().plusSeconds(60));
            assertThat(knowledge.validations(actor, next).get(0).state()).isEqualTo("UNVERIFIED");
            db.update("UPDATE knowledge_cards SET revision=2 WHERE id=?", card);
            assertThat(knowledge.validations(actor, context).get(0).state())
                    .isEqualTo("UNVERIFIED");
            db.update("UPDATE knowledge_cards SET publication_status='ARCHIVED' WHERE id=?", card);
            assertThat(knowledge.validations(actor, context)).isEmpty();

            db.update("UPDATE branch_preparation_jobs SET status='FAILED' WHERE status='QUEUED'");
            db.update(
                    "INSERT INTO branch_snapshots VALUES(?,?,?,CURRENT_TIMESTAMP)",
                    snapshot,
                    branch,
                    repo);
            db.update(
                    "INSERT INTO code_chunks VALUES(?,?),(?,?)",
                    repo,
                    snapshot,
                    repo,
                    next.snapshotId());
            db.update(
                    "INSERT INTO branch_snapshots VALUES(?,?,?,CURRENT_TIMESTAMP)",
                    next.snapshotId(),
                    branch,
                    repo);
            var vectorJob = service.submitVectors(actor, context);
            assertThat(service.submitVectors(actor, context).id()).isEqualTo(vectorJob.id());
            assertThatThrownBy(() -> service.submitVectors(actor, next))
                    .hasMessageContaining("其他快照");
            doAnswer(
                            call -> {
                                call.<Runnable>getArgument(2).run();
                                return null;
                            })
                    .when(intelligence)
                    .prepareBranchEmbeddings(eq(repo), eq(snapshot), any());
            service.processNext();
            verify(intelligence).prepareBranchEmbeddings(eq(repo), eq(snapshot), any());
            assertThat(
                            service.list(actor, repo).stream()
                                    .filter(job -> job.kind().equals("VECTORS"))
                                    .findFirst()
                                    .orElseThrow()
                                    .status())
                    .isEqualTo("SUCCEEDED");
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
