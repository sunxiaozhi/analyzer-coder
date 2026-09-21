package com.analyzercoder.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.infrastructure.persistence.mapper.IntelligenceMapper;
import com.analyzercoder.infrastructure.persistence.type.PostgresUuidTypeHandler;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
                UUID chunk1 = chunk(db, repo, main, s1, "branchproof main content"),
                        chunk2 = chunk(db, repo, release, s2, "branchproof release content");
                UUID mainCard = card(db, repo, main, "branchproof main"),
                        releaseCard = card(db, repo, release, "branchproof release");
                db.update(
                        "INSERT INTO branch_context_knowledge VALUES(?,?,1),(?,?,1)",
                        ctx1,
                        mainCard,
                        ctx2,
                        releaseCard);
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
                            .containsExactly(mainCard);
                    assertThat(knowledge(mapper, repo, s2, release, ctx2))
                            .extracting(row -> row.get("id"))
                            .containsExactly(releaseCard);
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
                            mainCard);
                    session.clearCache();
                    assertThat(knowledge(mapper, repo, s1, main, ctx1))
                            .extracting(row -> row.get("content"))
                            .containsExactly("original branchproof");
                    db.update(
                            "UPDATE knowledge_cards SET publication_status='DRAFT' WHERE id=?",
                            mainCard);
                    session.clearCache();
                    assertThat(knowledge(mapper, repo, s1, main, ctx1)).isEmpty();
                    db.update(
                            "UPDATE knowledge_cards SET publication_status='PUBLISHED' WHERE id=?",
                            mainCard);
                    db.update(
                            "INSERT INTO knowledge_code_refs(card_id,revision,position,repo_id,content_version,chunk_id,file_path,start_line,end_line,content_hash) VALUES(?,1,0,?,?,?,'same.java',1,1,'test')",
                            mainCard,
                            repo,
                            s1,
                            chunk1);
                    db.update(
                            "INSERT INTO knowledge_branch_validations(card_id,revision,branch_id,content_version,state) VALUES(?,1,?,?,'CURRENT')",
                            mainCard,
                            main,
                            s1);
                    session.clearCache();
                    assertThat(knowledge(mapper, repo, s1, main, ctx1))
                            .extracting(row -> row.get("id"))
                            .contains(mainCard);
                    assertThat(knowledge(mapper, repo, s2, release, ctx2))
                            .extracting(row -> row.get("id"))
                            .containsExactly(releaseCard);

                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM knowledge_cards WHERE id=? AND branch_id=?",
                                            Integer.class,
                                            mainCard,
                                            main))
                            .isEqualTo(1);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM knowledge_cards WHERE id=? AND branch_id=?",
                                            Integer.class,
                                            mainCard,
                                            release))
                            .isZero();
                    assertThat(db.update("DELETE FROM code_chunks WHERE id=?", chunk1))
                            .isEqualTo(1);
                    db.update("UPDATE repositories SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", repo);
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
            IntelligenceMapper mapper, UUID repo, UUID contentVersion, UUID branch, UUID context) {
        return mapper.searchBranchKnowledgeKeyword(
                repo, contentVersion, branch, context, "branchproof", List.of("branchproof"), 1, 20);
    }

    private static void branch(
            JdbcTemplate db,
            UUID repo,
            UUID branch,
            UUID contentVersion,
            UUID context,
            UUID account,
            String name) {
        db.update(
                "INSERT INTO repository_branches(id,repo_id,name,content_version,commit_sha,content_path,published_at,preparation_status) VALUES(?,?,?,?,'test','test-only',CURRENT_TIMESTAMP,'READY')",
                branch,
                repo,
                name,
                contentVersion);
        db.update(
                "INSERT INTO branch_read_contexts(id,account_id,repo_id,branch_id,content_version,expires_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour')",
                context,
                account,
                repo,
                branch,
                contentVersion);
    }

    private static UUID chunk(
            JdbcTemplate db, UUID repo, UUID branch, UUID contentVersion, String content) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO code_chunks(id,repo_id,branch_id,content_version,commit_sha,file_path,language,asset_type,chunk_type,start_line,end_line,content,content_hash,created_at) VALUES(?,?,?,?,'test','same.java','java','CODE','FILE',1,1,?,'test',CURRENT_TIMESTAMP)",
                id,
                repo,
                branch,
                contentVersion,
                content);
        return id;
    }

    private static UUID card(JdbcTemplate db, UUID repo, UUID branch, String title) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO knowledge_cards(id,repo_id,branch_id,title,content,publication_status,review_status) VALUES(?,?,?,?,'original branchproof','PUBLISHED','APPROVED')",
                id,
                repo,
                branch,
                title);
        return id;
    }
}
