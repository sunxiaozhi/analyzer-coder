package com.analyzercoder.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.analyzercoder.infrastructure.persistence.type.PostgresUuidTypeHandler;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Savepoint;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Exercises the real migration and mapper SQL, then rolls everything back. No Spring workers. */
@EnabledIfEnvironmentVariable(named = "APP_BRANCH_JDBC_URL", matches = ".+")
class BranchIsolationDatabaseTest {
    @Test
    void migrationAndQueriesKeepBranchesAndKnowledgeRevisionsIsolated() throws Exception {
        try (Connection connection =
                DriverManager.getConnection(
                        System.getenv("APP_BRANCH_JDBC_URL"),
                        System.getenv("APP_BRANCH_JDBC_USER"),
                        System.getenv("APP_BRANCH_JDBC_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                try (var input =
                        getClass()
                                .getClassLoader()
                                .getResourceAsStream("db/migration/V3__branch_contexts.sql")) {
                    connection
                            .createStatement()
                            .execute(
                                    new String(
                                            Objects.requireNonNull(input).readAllBytes(),
                                            StandardCharsets.UTF_8));
                }
                JdbcTemplate db =
                        new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                UUID repo =
                        db.queryForObject(
                                "SELECT id FROM repositories WHERE deleted_at IS NULL ORDER BY id LIMIT 1",
                                UUID.class);
                UUID account =
                        db.queryForObject(
                                "SELECT id FROM accounts ORDER BY id LIMIT 1", UUID.class);
                UUID main = UUID.randomUUID(),
                        release = UUID.randomUUID(),
                        s1 = UUID.randomUUID(),
                        s2 = UUID.randomUUID(),
                        ctx1 = UUID.randomUUID(),
                        ctx2 = UUID.randomUUID();
                branch(db, repo, main, s1, ctx1, account, "test-main-" + main);
                branch(db, repo, release, s2, ctx2, account, "test-release-" + release);
                UUID chunk1 = chunk(db, repo, s1, "branchproof main content"),
                        chunk2 = chunk(db, repo, s2, "branchproof release content");
                UUID shared = card(db, repo, "branchproof shared"),
                        exclusive = card(db, repo, "branchproof release-only");
                db.update(
                        "UPDATE knowledge_branch_scopes SET mode='ALL_BRANCHES',branch_ids='{}' WHERE card_id=?",
                        shared);
                db.update(
                        "UPDATE knowledge_branch_scopes SET branch_ids=ARRAY[?]::uuid[] WHERE card_id=?",
                        release,
                        exclusive);
                db.update(
                        "INSERT INTO branch_context_knowledge VALUES(?,?,1),(?,?,1),(?,?,1)",
                        ctx1,
                        shared,
                        ctx2,
                        shared,
                        ctx2,
                        exclusive);
                Configuration config = new Configuration();
                config.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
                try (var xml =
                        getClass()
                                .getClassLoader()
                                .getResourceAsStream("mappers/IntelligenceMapper.xml")) {
                    new XMLMapperBuilder(
                                    xml,
                                    config,
                                    "mappers/IntelligenceMapper.xml",
                                    config.getSqlFragments())
                            .parse();
                }
                try (SqlSession session =
                        new SqlSessionFactoryBuilder()
                                .build(config)
                                .openSession(db.getDataSource().getConnection())) {
                    IntelligenceMapper mapper = session.getMapper(IntelligenceMapper.class);
                    assertThat(
                                    mapper.searchBranchCodeKeyword(
                                            repo, s1, "branchproof", List.of("branchproof"), 1, 20))
                            .extracting(row -> row.get("id"))
                            .containsExactly(chunk1);
                    assertThat(
                                    mapper.searchBranchCodeKeyword(
                                            repo, s2, "branchproof", List.of("branchproof"), 1, 20))
                            .extracting(row -> row.get("id"))
                            .containsExactly(chunk2);
                    assertThat(knowledge(mapper, repo, s1, main, ctx1))
                            .extracting(row -> row.get("id"))
                            .containsExactly(shared);
                    assertThat(knowledge(mapper, repo, s2, release, ctx2))
                            .extracting(row -> row.get("id"))
                            .containsExactlyInAnyOrder(shared, exclusive);
                    assertThat(
                                    mapper.searchBranchCodeVector(
                                            repo, s1, "[0.1,0.2]", "branch-test", 2, 10))
                            .isEmpty();
                    assertThat(
                                    mapper.searchBranchKnowledgeVector(
                                            repo,
                                            s1,
                                            main,
                                            ctx1,
                                            "[0.1,0.2]",
                                            "branch-test",
                                            2,
                                            10))
                            .isEmpty();

                    db.update(
                            "UPDATE knowledge_cards SET content='changed branchproof',revision=2 WHERE id=?",
                            shared);
                    session.clearCache();
                    assertThat(knowledge(mapper, repo, s1, main, ctx1))
                            .extracting(row -> row.get("content"))
                            .containsExactly("original branchproof");
                    db.update(
                            "UPDATE knowledge_cards SET publication_status='DRAFT' WHERE id=?",
                            shared);
                    session.clearCache();
                    assertThat(knowledge(mapper, repo, s1, main, ctx1)).isEmpty();
                    db.update(
                            "UPDATE knowledge_cards SET publication_status='PUBLISHED' WHERE id=?",
                            shared);
                    db.update(
                            "INSERT INTO knowledge_code_refs(card_id,revision,position,repo_id,snapshot_id,chunk_id,file_path,start_line,end_line,content_hash) VALUES(?,1,0,?,?,?,'same.java',1,1,'test')",
                            shared,
                            repo,
                            s1,
                            chunk1);
                    db.update(
                            "INSERT INTO knowledge_branch_validations(card_id,revision,branch_id,snapshot_id,state) VALUES(?,1,?,?,'CURRENT')",
                            shared,
                            release,
                            s2);
                    session.clearCache();
                    assertThat(knowledge(mapper, repo, s1, main, ctx1)).isEmpty();
                    assertThat(knowledge(mapper, repo, s2, release, ctx2))
                            .extracting(row -> row.get("id"))
                            .contains(shared);

                    db.update(
                            "INSERT INTO knowledge_code_refs(card_id,revision,position,repo_id,snapshot_id,chunk_id,file_path,start_line,end_line,content_hash) VALUES(?,2,0,?,?,?,'same.java',1,1,'test')",
                            shared,
                            repo,
                            s1,
                            chunk1);
                    var scopes =
                            new com.analyzercoder.application.branch.BranchKnowledgeService(
                                    db,
                                    org.mockito.Mockito.mock(
                                            com.analyzercoder.security.AccessControlService.class));
                    var actor =
                            new com.analyzercoder.security.AuthenticatedAccount(
                                    account,
                                    "test",
                                    "test",
                                    com.analyzercoder.security.AccountRole.SUPER_ADMIN,
                                    false,
                                    null);
                    scopes.apply(actor, repo, shared, 2, "ALL_BRANCHES", List.of());
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM knowledge_code_refs WHERE card_id=? AND revision=3",
                                            Integer.class,
                                            shared))
                            .isEqualTo(1);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM knowledge_branch_scope_history WHERE card_id=? AND revision=3 AND mode='ALL_BRANCHES'",
                                            Integer.class,
                                            shared))
                            .isEqualTo(1);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM knowledge_branch_validations WHERE card_id=? AND revision=3",
                                            Integer.class,
                                            shared))
                            .isZero();

                    Savepoint point = connection.setSavepoint();
                    assertThatThrownBy(
                                    () -> db.update("DELETE FROM code_chunks WHERE id=?", chunk1))
                            .hasMessageContaining("Immutable branch snapshot");
                    connection.rollback(point);
                    db.update(
                            "UPDATE repositories SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",
                            repo);
                    assertThat(db.update("DELETE FROM code_chunks WHERE id=?", chunk1))
                            .isEqualTo(1);
                    db.update(
                            "UPDATE repositories SET repository_status='DELETED' WHERE id=?", repo);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM repository_branches WHERE repo_id=?",
                                            Integer.class,
                                            repo))
                            .isZero();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<Map<String, Object>> knowledge(
            IntelligenceMapper mapper, UUID repo, UUID snapshot, UUID branch, UUID context) {
        return mapper.searchBranchKnowledgeKeyword(
                repo, snapshot, branch, context, "branchproof", List.of("branchproof"), 1, 20);
    }

    private static void branch(
            JdbcTemplate db,
            UUID repo,
            UUID branch,
            UUID snapshot,
            UUID context,
            UUID account,
            String name) {
        db.update(
                "INSERT INTO repository_branches(id,repo_id,name) VALUES(?,?,?)",
                branch,
                repo,
                name);
        db.update(
                "INSERT INTO branch_snapshots(id,repo_id,branch_id,commit_sha,content_path) VALUES(?,?,?,'test','test-only')",
                snapshot,
                repo,
                branch);
        db.update(
                "UPDATE repository_branches SET published_snapshot_id=? WHERE id=?",
                snapshot,
                branch);
        db.update(
                "INSERT INTO branch_read_contexts(id,account_id,repo_id,branch_id,snapshot_id,expires_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour')",
                context,
                account,
                repo,
                branch,
                snapshot);
    }

    private static UUID chunk(JdbcTemplate db, UUID repo, UUID snapshot, String content) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO code_chunks(id,repo_id,snapshot_id,commit_sha,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,created_at) VALUES(?,?,?,'test','same.java','java','CODE','FILE',1,1,?,'test',CURRENT_TIMESTAMP)",
                id,
                repo,
                snapshot,
                content);
        return id;
    }

    private static UUID card(JdbcTemplate db, UUID repo, String title) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO knowledge_cards(id,repo_id,title,content,publication_status,review_status) VALUES(?, ?,?,'original branchproof','PUBLISHED','APPROVED')",
                id,
                repo,
                title);
        return id;
    }
}
