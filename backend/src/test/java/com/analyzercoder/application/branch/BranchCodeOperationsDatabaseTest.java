package com.analyzercoder.application.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.indexing.ScannedRepositoryFile;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.infrastructure.repository.GitBranchSnapshotFactory;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real V1-V8 migration and branch workflow, isolated from live project data and workers. */
@EnabledIfEnvironmentVariable(named = "APP_BRANCH_OPERATIONS_TEST_URL", matches = ".+")
class BranchCodeOperationsDatabaseTest {
    @TempDir Path directory;

    @Test
    void migrationAndIndependentTasksPreserveOtherBranchesAndHistory() throws Exception {
        String url = System.getenv("APP_BRANCH_OPERATIONS_TEST_URL");
        String user = System.getenv("APP_BRANCH_OPERATIONS_TEST_USER");
        String password = System.getenv("APP_BRANCH_OPERATIONS_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "branch_operations_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source =
                    new DriverManagerDataSource(
                            url
                                    + (url.contains("?") ? "&" : "?")
                                    + "currentSchema="
                                    + schema
                                    + ",public",
                            user,
                            password);
            var db = new JdbcTemplate(source);
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("7")
                    .load()
                    .migrate();
            var repository = CodeRepository.create("Project", directory);
            UUID repo = repository.id().value(), account = UUID.randomUUID();
            db.update(
                    """
                    INSERT INTO accounts(id,username,display_name,password_hash,account_role,must_change_password,created_at,updated_at)
                    VALUES(?,'branch-test','Test','not-a-real-password','SUPER_ADMIN',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """,
                    account);
            db.update(
                    """
                    INSERT INTO repositories(id,name,normalized_name,path,default_branch,owner_account_id,created_at,updated_at)
                    VALUES(?,'Project','project',?,'main',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """,
                    repo,
                    directory.toString(),
                    account);
            UUID main =
                    db.queryForObject(
                            "SELECT id FROM repository_branches WHERE repo_id=? AND name='main'",
                            UUID.class,
                            repo);
            UUID legacy = UUID.randomUUID();
            db.update(
                    "INSERT INTO branch_snapshots(id,repo_id,branch_id,commit_sha,content_path) VALUES(?,?,?,?,?)",
                    legacy,
                    repo,
                    main,
                    "a".repeat(40),
                    directory.resolve("legacy").toString());
            chunk(db, repo, legacy, "legacy proof");
            db.update(
                    "UPDATE repository_branches SET published_snapshot_id=?,preparation_status='READY' WHERE id=?",
                    legacy,
                    main);
            UUID legacyJob = UUID.randomUUID();
            db.update(
                    "INSERT INTO branch_preparation_jobs(id,repo_id,branch_id,account_id,status,kind) VALUES(?,?,?,?,'SUCCEEDED','SNAPSHOT')",
                    legacyJob,
                    repo,
                    main,
                    account);
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
            assertThat(
                            db.queryForObject(
                                    "SELECT content_indexed_at IS NOT NULL FROM branch_snapshots WHERE id=?",
                                    Boolean.class,
                                    legacy))
                    .isTrue();
            assertThat(
                            db.queryForObject(
                                    "SELECT kind FROM branch_preparation_jobs WHERE id=?",
                                    String.class,
                                    legacyJob))
                    .isEqualTo("SNAPSHOT");
            UUID feature = UUID.randomUUID();
            db.update(
                    "INSERT INTO repository_branches(id,repo_id,name) VALUES(?,?,'feature')",
                    feature,
                    repo);
            // Editing the preference must not overwrite either branch's published code.
            db.update("UPDATE repositories SET default_branch='feature' WHERE id=?", repo);
            assertThat(published(db, main)).isEqualTo(legacy);
            assertThat(published(db, feature)).isNull();

            var actor =
                    new AuthenticatedAccount(
                            account, "branch-test", "Test", AccountRole.SUPER_ADMIN, false, null);
            var access = mock(AccessControlService.class);
            var branches = mock(RepositoryBranchService.class);
            when(branches.list(actor, repo))
                    .thenAnswer(
                            call ->
                                    db.query(
                                            """
                    SELECT b.*,s.commit_sha FROM repository_branches b LEFT JOIN branch_snapshots s ON s.id=b.published_snapshot_id WHERE b.repo_id=?
                    """,
                                            (r, n) ->
                                                    new RepositoryBranchService.Branch(
                                                            r.getObject("id", UUID.class),
                                                            r.getString("name"),
                                                            r.getObject(
                                                                    "published_snapshot_id",
                                                                    UUID.class),
                                                            r.getString("commit_sha"),
                                                            r.getString("preparation_status"),
                                                            r.getString("preparation_error"),
                                                            r.getLong("generation"),
                                                            r.getString("tracking_status"),
                                                            null),
                                            repo));
            when(branches.repositoryFor(any()))
                    .thenAnswer(call -> snapshotRepository(repository, call.getArgument(0)));
            var repositories = mock(CodeRepositoryStore.class);
            when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
            var factory = mock(GitBranchSnapshotFactory.class);
            when(factory.resolve(directory, "feature")).thenReturn("b".repeat(40));
            when(factory.resolve(directory, "main")).thenReturn("a".repeat(40));
            when(factory.create(eq(repository.id()), eq(directory), anyString()))
                    .thenAnswer(
                            call ->
                                    new ManagedRepositorySnapshot(
                                            RepositorySnapshotId.newId(),
                                            repository.id(),
                                            directory.resolve(UUID.randomUUID().toString()),
                                            call.getArgument(2),
                                            "digest",
                                            Instant.now()));
            var scanner = mock(RepositoryScannerPort.class);
            when(scanner.scan(any()))
                    .thenAnswer(
                            call ->
                                    List.of(
                                            new ScannedRepositoryFile(
                                                    "src/proof.ts",
                                                    "typescript",
                                                    RepositoryAssetType.CODE,
                                                    "const proof = '"
                                                            + call.<CodeRepository>getArgument(0)
                                                                    .currentCommit()
                                                            + "';",
                                                    1)));
            var markdown = mock(MarkdownKnowledgeSourceService.class);
            var manager = new DataSourceTransactionManager(source);
            var content =
                    new BranchContentIndexService(
                            db, scanner, new CodeSymbolExtractor(), markdown, manager);
            var graph = mock(CodeGraphService.class);
            doAnswer(
                            call -> {
                                call.<CodeGraphService.BuildControl>getArgument(3)
                                        .checkpoint("INDEXING");
                                db.update(
                                        """
                        INSERT INTO codegraph_artifacts(id,repo_id,snapshot_id,cli_version,status,artifact_path,node_count,edge_count)
                        VALUES(?,?,?,'test','PUBLISHED','test-artifact',1,0)
                        """,
                                        UUID.randomUUID(),
                                        call.getArgument(0, UUID.class),
                                        call.getArgument(1, UUID.class));
                                return null;
                            })
                    .when(graph)
                    .buildSnapshot(any(), any(), any(), any());
            var remote = mock(BranchRemoteService.class);
            var operations =
                    new BranchCodeOperationsService(
                            db,
                            branches,
                            repositories,
                            access,
                            remote,
                            factory,
                            content,
                            graph,
                            manager);
            var jobs =
                    new BranchPreparationJobs(
                            db, source, branches, access, manager, mock(IntelligenceService.class));
            ReflectionTestUtils.setField(jobs, "codeOperations", operations);
            var sync = jobs.submitOperation(actor, repo, feature, "SYNC", null);
            assertThat(jobs.submitOperation(actor, repo, feature, "SYNC", null).id())
                    .isEqualTo(sync.id());
            assertThat(jobs.processNext()).isTrue();
            UUID first = published(db, feature);
            assertThat(first).isNotNull().isNotEqualTo(legacy);
            assertThat(chunkCount(db, repo, first)).isZero();
            assertThat(published(db, main)).isEqualTo(legacy);
            verifyNoInteractions(scanner, graph, remote);
            assertThatThrownBy(() -> operations.snapshotContext(actor, repo, main, first))
                    .isInstanceOf(ApiSecurityException.class);

            jobs.submitOperation(actor, repo, feature, "CONTENT", first);
            jobs.processNext();
            assertThat(chunkCount(db, repo, first)).isPositive();
            long count = chunkCount(db, repo, first);
            jobs.submitOperation(actor, repo, feature, "CONTENT", first);
            jobs.processNext();
            assertThat(chunkCount(db, repo, first)).isEqualTo(count);
            verify(factory, times(1)).resolve(directory, "feature");
            verifyNoInteractions(graph);
            var before =
                    operations.statuses(actor, repo).stream()
                            .filter(s -> s.branchId().equals(feature))
                            .findFirst()
                            .orElseThrow();
            assertThat(before.contentReady()).isTrue();
            assertThat(before.graphReady()).isFalse();
            assertThat(before.vectorsReady()).isFalse();
            jobs.submitOperation(actor, repo, feature, "GRAPH", first);
            jobs.processNext();
            assertThat(
                            operations.statuses(actor, repo).stream()
                                    .filter(s -> s.branchId().equals(feature))
                                    .findFirst()
                                    .orElseThrow()
                                    .graphReady())
                    .isTrue();

            // An unchanged commit reuses the snapshot instead of exporting another full tree.
            jobs.submitOperation(actor, repo, feature, "SYNC", null);
            jobs.processNext();
            assertThat(published(db, feature)).isEqualTo(first);
            verify(factory, times(1)).create(eq(repository.id()), eq(directory), anyString());

            when(factory.resolve(directory, "feature")).thenReturn("c".repeat(40));
            jobs.submitOperation(actor, repo, feature, "PREPARE", null);
            jobs.processNext();
            UUID second = published(db, feature);
            assertThat(second).isNotEqualTo(first);
            assertThat(chunkCount(db, repo, second)).isPositive();
            assertThat(chunkCount(db, repo, first)).isEqualTo(count);
            assertThat(chunkCount(db, repo, legacy)).isEqualTo(1);
            assertThat(published(db, main)).isEqualTo(legacy);
            assertThat(
                            operations.statuses(actor, repo).stream()
                                    .filter(s -> s.branchId().equals(feature))
                                    .findFirst()
                                    .orElseThrow()
                                    .graphReady())
                    .isTrue();

            // A pinned historical index does not replace current Markdown sources or code pointers.
            clearInvocations(markdown);
            operations.indexContent(
                    actor, operations.snapshotContext(actor, repo, feature, first), () -> {});
            verifyNoInteractions(markdown);
            assertThat(published(db, feature)).isEqualTo(second);
            assertThat(
                            db.queryForObject(
                                    "SELECT current_snapshot_id FROM repositories WHERE id=?",
                                    UUID.class,
                                    repo))
                    .isNull();
            assertThatThrownBy(
                            () -> db.update("DELETE FROM code_chunks WHERE snapshot_id=?", first))
                    .hasMessageContaining("Immutable");

            // Project-level maintenance permission gates all branch writes before side effects.
            clearInvocations(factory);
            doThrow(new ApiSecurityException(403, "FORBIDDEN", "Read only"))
                    .when(access)
                    .require(actor, repository.id(), RepositoryPermission.MAINTAIN);
            assertThatThrownBy(() -> jobs.submitOperation(actor, repo, feature, "SYNC", null))
                    .isInstanceOf(ApiSecurityException.class);
            assertThatThrownBy(
                            () ->
                                    operations.indexContent(
                                            actor,
                                            operations.snapshotContext(actor, repo, feature, first),
                                            () -> {}))
                    .isInstanceOf(ApiSecurityException.class);
            verifyNoInteractions(factory);
        } finally {
            if (!schema.matches("branch_operations_test_[a-f0-9]{32}"))
                throw new IllegalStateException("Unexpected test schema");
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static CodeRepository snapshotRepository(CodeRepository source, BranchReadContext c) {
        return source.withManagedSnapshot(
                new com.analyzercoder.domain.repository.GitRepositorySnapshot(
                        c.branchName(), c.commitSha(), c.commitSha(), false, Instant.now()),
                new ManagedRepositorySnapshot(
                        RepositorySnapshotId.of(c.snapshotId()),
                        source.id(),
                        c.contentPath(),
                        c.commitSha(),
                        c.commitSha(),
                        Instant.now()));
    }

    private static UUID published(JdbcTemplate db, UUID branch) {
        return db.queryForObject(
                "SELECT published_snapshot_id FROM repository_branches WHERE id=?",
                UUID.class,
                branch);
    }

    private static long chunkCount(JdbcTemplate db, UUID repo, UUID snapshot) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM code_chunks WHERE repo_id=? AND snapshot_id=?",
                Long.class,
                repo,
                snapshot);
    }

    private static void chunk(JdbcTemplate db, UUID repo, UUID snapshot, String text) {
        db.update(
                """
                INSERT INTO code_chunks(id,repo_id,snapshot_id,commit_sha,file_path,language,chunk_type,start_line,end_line,content,content_hash,created_at)
                VALUES(?,?,?,'legacy','src/legacy.ts','typescript','FILE',1,1,?,'legacy-hash',CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                repo,
                snapshot,
                text);
    }
}
