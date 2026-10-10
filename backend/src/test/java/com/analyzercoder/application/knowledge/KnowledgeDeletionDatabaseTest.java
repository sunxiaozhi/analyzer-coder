package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.analyzercoder.application.branch.BranchReadContext;
import com.analyzercoder.infrastructure.persistence.mapper.MarkdownKnowledgeSourceMapper;
import com.analyzercoder.infrastructure.persistence.type.PostgresUuidTypeHandler;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named = "APP_BRANCH_JOB_TEST_URL", matches = ".+")
class KnowledgeDeletionDatabaseTest {
    JdbcTemplate admin, db;
    String schema;
    KnowledgeDeletionService service;
    MarkdownKnowledgeSourceMapper sources;
    final UUID repo = UUID.randomUUID(),
            branch = UUID.randomUUID(),
            version = UUID.randomUUID(),
            card = UUID.randomUUID(),
            other = UUID.randomUUID(),
            sourceId = UUID.randomUUID(),
            account = UUID.randomUUID();
    final String hash = "a".repeat(64);
    BranchReadContext context;
    AuthenticatedAccount actor;

    @BeforeEach
    void initializeRealSchema() throws Exception {
        String url = System.getenv("APP_BRANCH_JOB_TEST_URL"),
                user = System.getenv("APP_BRANCH_JOB_TEST_USER"),
                password = System.getenv("APP_BRANCH_JOB_TEST_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "knowledge_delete_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + "?currentSchema=" + schema + ",public", user, password);
        db = new JdbcTemplate(dataSource);
        try (var input =
                new ClassPathResource("db/migration/V1__init_schema.sql").getInputStream()) {
            db.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        db.update(
                "INSERT INTO accounts(id,username,display_name,password_hash,account_role,created_at,updated_at) VALUES(?,'delete-test','Delete test','test','SUPER_ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                account);
        db.update(
                "INSERT INTO repositories(id,name,normalized_name,path,owner_account_id,created_at,updated_at) VALUES(?,'Delete test','delete-test','test://delete',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                repo,
                account);
        db.update(
                "INSERT INTO repository_branches(id,repo_id,name,content_version,commit_sha,content_path,preparation_status) VALUES(?,?,'main',?,'test','test-only','READY')",
                branch,
                repo,
                version);
        db.update(
                "INSERT INTO knowledge_cards(id,repo_id,branch_id,title,content,publication_status) VALUES(?,?,?,'Draft','Draft content','DRAFT'),(?,?,?,'Other','Other content','DRAFT')",
                card,
                repo,
                branch,
                other,
                repo,
                branch);
        db.update(
                "INSERT INTO repository_markdown_sources(id,repo_id,branch_id,content_version,file_path,content_hash,title,asset_type,content,line_count,byte_size) VALUES(?,?,?,?,'rules.md',?,'Rules','DOCUMENT','Original Markdown',1,17)",
                sourceId,
                repo,
                branch,
                version,
                hash);
        link(card, 1);
        db.update("UPDATE knowledge_cards SET revision=2 WHERE id=?", card);
        link(card, 2);
        db.update(
                "INSERT INTO knowledge_card_embeddings(card_id,repo_id,revision,model,dimension,embedding,content_hash,retrieval_capability) VALUES(?,?,2,'bge-m3',2,'[1,0]',?,'SEMANTIC_EMBEDDING'),(?,?,1,'bge-m3',2,'[0,1]',?,'SEMANTIC_EMBEDDING')",
                card,
                repo,
                hash,
                other,
                repo,
                hash);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new ClassPathResource("mappers/MarkdownKnowledgeSourceMapper.xml"));
        factory.setTypeHandlers(new PostgresUuidTypeHandler());
        sources =
                new SqlSessionTemplate(factory.getObject())
                        .getMapper(MarkdownKnowledgeSourceMapper.class);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(dataSource));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy =
                new ProxyFactory(
                        new KnowledgeDeletionService(db, mock(AccessControlService.class)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        service = (KnowledgeDeletionService) proxy.getProxy();
        context =
                new BranchReadContext(
                        UUID.randomUUID(),
                        repo,
                        branch,
                        "main",
                        version,
                        "test",
                        Path.of("."),
                        Instant.now());
        actor =
                new AuthenticatedAccount(
                        account, "owner", "Owner", AccountRole.SUPER_ADMIN, false, null);
    }

    void link(UUID id, int revision) {
        db.update(
                "INSERT INTO knowledge_card_markdown_source_links(card_id,revision,source_id,repo_id,source_branch_id,source_content_version,source_path,source_content_hash) VALUES(?,?,?,?,?,?,'rules.md',?)",
                id,
                revision,
                sourceId,
                repo,
                branch,
                version,
                hash);
    }

    int count(String table, String column, UUID id) {
        return db.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void deletionCascadesVectorsAllRevisionLinksAndScopesWhileSourceRemainsRegenerable() {
        assertThat(sources.findBranchSource(repo, branch, version, "rules.md").get("source_status"))
                .isEqualTo("CURRENT");
        service.delete(actor, context, card, 2);
        assertThat(count("knowledge_cards", "id", card)).isZero();
        for (String table :
                new String[] {
                    "knowledge_card_embeddings",
                    "knowledge_card_revisions",
                    "knowledge_card_markdown_source_links",
                    "knowledge_branch_scopes",
                    "knowledge_branch_scope_history"
                }) assertThat(count(table, "card_id", card)).as(table).isZero();
        assertThat(count("knowledge_cards", "id", other)).isEqualTo(1);
        assertThat(count("knowledge_card_embeddings", "card_id", other)).isEqualTo(1);
        var source = sources.findBranchSource(repo, branch, version, "rules.md");
        assertThat(source.get("source_status")).isEqualTo("PENDING");
        assertThat(source.get("card_id")).isNull();
        assertThat(source.get("content")).isEqualTo("Original Markdown");
        // A later generation may create a fresh card and associate the preserved source again.
        link(other, 1);
        assertThat(sources.findBranchSource(repo, branch, version, "rules.md").get("card_id"))
                .isEqualTo(other);
    }

    @Test
    void anotherKnowledgeAssociationIsPreservedWhenDeletingOneLinkedCard() {
        link(other, 1);
        service.delete(actor, context, card, 2);
        var source = sources.findBranchSource(repo, branch, version, "rules.md");
        assertThat(source.get("source_status")).isEqualTo("CURRENT");
        assertThat(source.get("card_id")).isEqualTo(other);
    }

    @Test
    void failedCommitRollsBackCardVectorAndMarkdownCleanupTogether() {
        db.execute(
                "CREATE TABLE deletion_blocker(card_id uuid REFERENCES knowledge_cards(id) DEFERRABLE INITIALLY DEFERRED)");
        db.update("INSERT INTO deletion_blocker VALUES(?)", card);
        assertThrows(RuntimeException.class, () -> service.delete(actor, context, card, 2));
        assertThat(count("knowledge_cards", "id", card)).isEqualTo(1);
        assertThat(count("knowledge_card_embeddings", "card_id", card)).isEqualTo(1);
        assertThat(count("knowledge_card_revisions", "card_id", card)).isEqualTo(2);
        assertThat(count("knowledge_card_markdown_source_links", "card_id", card)).isEqualTo(2);
        assertThat(sources.findBranchSource(repo, branch, version, "rules.md").get("source_status"))
                .isEqualTo("CURRENT");
    }

    @Test
    void wrongRepositoryOrBranchAndChangedStateLeaveAllDataUntouched() {
        var wrongContext =
                new BranchReadContext(
                        context.contextId(),
                        UUID.randomUUID(),
                        branch,
                        "main",
                        version,
                        "test",
                        Path.of("."),
                        Instant.now());
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, wrongContext, card, 2))
                                .status())
                .isEqualTo(404);
        var wrongBranch =
                new BranchReadContext(
                        context.contextId(),
                        repo,
                        UUID.randomUUID(),
                        "other",
                        version,
                        "test",
                        Path.of("."),
                        Instant.now());
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, wrongBranch, card, 2))
                                .status())
                .isEqualTo(404);
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 1))
                                .code())
                .isEqualTo("KNOWLEDGE_REVISION_CONFLICT");
        db.update("UPDATE knowledge_cards SET publication_status='PUBLISHED' WHERE id=?", card);
        assertThat(
                        assertThrows(
                                        ApiSecurityException.class,
                                        () -> service.delete(actor, context, card, 2))
                                .code())
                .isEqualTo("KNOWLEDGE_DELETE_REQUIRES_DRAFT");
        assertThat(count("knowledge_card_embeddings", "card_id", card)).isEqualTo(1);
        assertThat(count("knowledge_card_markdown_source_links", "card_id", card)).isEqualTo(2);
    }
}
