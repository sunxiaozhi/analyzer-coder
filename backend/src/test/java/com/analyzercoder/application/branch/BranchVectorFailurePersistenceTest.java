package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.llm.LlmConnectionException;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "APP_BRANCH_JOB_TEST_URL", matches = ".+")
class BranchVectorFailurePersistenceTest {
    @Test
    void persistsActionableVectorFailureAndAllowsRetry() throws Exception {
        String url = System.getenv("APP_BRANCH_JOB_TEST_URL");
        String user = System.getenv("APP_BRANCH_JOB_TEST_USER");
        String password = System.getenv("APP_BRANCH_JOB_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "vector_failure_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source =
                    new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
            var db = new JdbcTemplate(source);
            db.execute(
                    "CREATE TABLE accounts(id UUID PRIMARY KEY,username TEXT,display_name TEXT,account_role TEXT,enabled BOOLEAN,must_change_password BOOLEAN)");
            db.execute("CREATE TABLE repositories(id UUID PRIMARY KEY,deleted_at TIMESTAMPTZ)");
            db.execute(
                    "CREATE TABLE repository_branches(id UUID PRIMARY KEY,repo_id UUID,content_version UUID,content_indexed_at TIMESTAMPTZ,tracking_status TEXT DEFAULT 'ACTIVE',UNIQUE(repo_id,id))");
            db.execute(
                    "CREATE TABLE code_chunks(repo_id UUID,branch_id UUID,content_version UUID)");
            db.execute(
                    """
                    CREATE TABLE branch_preparation_jobs (
                        id UUID PRIMARY KEY,
                        repo_id UUID NOT NULL,
                        branch_id UUID NOT NULL,
                        account_id UUID NOT NULL REFERENCES accounts(id),
                        status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
                        stage TEXT NOT NULL DEFAULT 'QUEUED',
                        attempt_token UUID,
                        target_commit TEXT,
                        kind TEXT NOT NULL DEFAULT 'PREPARE'
                            CHECK(kind IN ('SYNC','CONTENT','GRAPH','VECTORS','PREPARE')),
                        target_content_version UUID,
                        error TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id),
                        CHECK(kind IN ('SYNC','PREPARE') OR target_content_version IS NOT NULL)
                    )
                    """);
            db.execute(
                    "CREATE UNIQUE INDEX branch_preparation_one_active ON branch_preparation_jobs(branch_id,kind) WHERE status IN ('QUEUED','RUNNING')");
            db.execute(
                    "CREATE INDEX branch_preparation_queue ON branch_preparation_jobs(status,created_at)");
            UUID repo = UUID.randomUUID(),
                    branch = UUID.randomUUID(),
                    account = UUID.randomUUID(),
                    contentVersion = UUID.randomUUID();
            db.update(
                    "INSERT INTO accounts VALUES(?,'owner','Owner','SUPER_ADMIN',TRUE,FALSE)",
                    account);
            db.update("INSERT INTO repositories VALUES(?,NULL)", repo);
            db.update(
                    "INSERT INTO repository_branches(id,repo_id,content_version,content_indexed_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
                    branch,
                    repo,
                    contentVersion);
            db.update("INSERT INTO code_chunks VALUES(?,?,?)", repo, branch, contentVersion);
            var actor =
                    new AuthenticatedAccount(
                            account, "owner", "Owner", AccountRole.SUPER_ADMIN, false, null);
            var context =
                    new BranchReadContext(
                            UUID.randomUUID(),
                            repo,
                            branch,
                            "main",
                            contentVersion,
                            "a".repeat(40),
                            Path.of("."),
                            Instant.now().plusSeconds(60));
            var intelligence = mock(IntelligenceService.class);
            var service =
                    new BranchPreparationJobs(
                            db,
                            source,
                            mock(RepositoryBranchService.class),
                            mock(AccessControlService.class),
                            new DataSourceTransactionManager(source),
                            intelligence);
            var submitted = service.submitVectors(actor, context);
            doAnswer(
                            call -> {
                                call.<Runnable>getArgument(2).run();
                                throw new LlmConnectionException(
                                        "LLM_TIMEOUT", "第 2 批失败；请求超时=30000ms；已处理 16 个片段");
                            })
                    .when(intelligence)
                    .prepareBranchEmbeddings(eq(repo), eq(contentVersion), any());
            assertThat(service.processNext()).isTrue();
            var failed = service.history(actor, repo, branch, 1, 20).items().get(0);
            assertThat(failed.id()).isEqualTo(submitted.id());
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.stage()).isEqualTo("EMBEDDING");
            assertThat(failed.error())
                    .contains(
                            "LLM_TIMEOUT", "第 2 批", "30000ms", "已处理 16", submitted.id().toString());
            assertThat(service.list(actor, repo).get(0).error()).isEqualTo(failed.error());
            var retry = service.submitVectors(actor, context);
            assertThat(retry.id()).isNotEqualTo(submitted.id());
            doAnswer(
                            call -> {
                                call.<Runnable>getArgument(2).run();
                                return null;
                            })
                    .when(intelligence)
                    .prepareBranchEmbeddings(eq(repo), eq(contentVersion), any());
            assertThat(service.processNext()).isTrue();
            assertThat(service.list(actor, repo).get(0).status()).isEqualTo("SUCCEEDED");
            assertThat(service.list(actor, repo).get(0).error()).isNull();
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
