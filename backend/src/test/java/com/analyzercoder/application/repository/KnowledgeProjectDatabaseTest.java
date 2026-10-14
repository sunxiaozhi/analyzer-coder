package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.CodebaseKnowledgeApplication;
import com.analyzercoder.application.branch.BranchPreparationJobs;
import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.KnowledgePublicationService;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.security.AccountRole;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Real Flyway/MyBatis workflow; enable only against a dedicated test database. */
@EnabledIfEnvironmentVariable(named = "APP_RUN_POSTGRES_IT", matches = "true")
@SpringBootTest(
        classes = CodebaseKnowledgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.workers.enabled=false"})
@Transactional
class KnowledgeProjectDatabaseTest {
    @Autowired KnowledgeProjectService projects;
    @Autowired RepositoryBranchService branches;
    @Autowired BranchPreparationJobs jobs;
    @Autowired IntelligenceService intelligence;
    @Autowired KnowledgePublicationService publication;
    @Autowired JdbcTemplate db;

    @Test
    void knowledgeOnlyCreationPublicationRetrievalAndMultiTurnAskNeedNoCode() {
        var owner = account();
        var project = projects.create(owner, "退款知识库", "退款处理资料");
        UUID repo = project.id().value();
        assertThat(project.sourceType()).isEqualTo(RepositorySourceType.KNOWLEDGE);
        assertThat(project.currentContentVersion()).isNull();
        assertThat(Files.exists(project.path())).isFalse();
        assertThat(branches.list(owner, repo))
                .singleElement()
                .satisfies(
                        branch -> {
                            assertThat(branch.status()).isEqualTo("READY");
                            assertThat(branch.contentVersion()).isNull();
                            assertThat(branch.commitSha()).isNull();
                        });
        UUID workspace = branches.list(owner, repo).get(0).id();
        var context = branches.resolve(owner, repo, workspace, null);
        assertThat(context.contentVersion()).isNull();
        assertThat(context.contentPath()).isNull();
        assertThat(branches.resolve(owner, repo, null, context.contextId()).branchId())
                .isEqualTo(workspace);

        var card =
                intelligence.createCard(
                        repo, workspace, owner.id(), input("退款期限", "退款期限为七天，订单支付后七天内允许申请退款。"));
        assertThat(intelligence.unifiedSearchDetailed(repo, "退款期限", 10, context).evidence())
                .isEmpty();
        publication.publish(owner, context, card.id(), card.revision());
        context = branches.resolve(owner, repo, workspace, null);
        var result = intelligence.unifiedSearchDetailed(repo, "退款期限", 10, context);
        assertThat(result.evidence())
                .isNotEmpty()
                .allMatch(hit -> "KNOWLEDGE".equals(hit.sourceType()));
        assertThat(result.retrieval().enabledChannels())
                .allMatch(channel -> channel.startsWith("KNOWLEDGE_"));
        assertThat(result.retrieval().unavailableChannels())
                .noneMatch(channel -> channel.channel().startsWith("CODE"));

        var answer =
                intelligence.ask(
                        repo, owner.id(), "退款期限是什么", UUID.randomUUID(), null, null, context);
        assertThat(answer.citations())
                .isNotEmpty()
                .allMatch(citation -> "KNOWLEDGE".equals(citation.sourceType()));
        assertThat(answer.contentVersion()).isNull();
        var followup =
                intelligence.ask(
                        repo,
                        owner.id(),
                        "退款期限为几天",
                        UUID.randomUUID(),
                        answer.threadId(),
                        null,
                        context);
        assertThat(followup.turnNo()).isEqualTo(2);
        assertThat(intelligence.history(repo, owner.id(), 10, 0, context)).hasSize(1);

        intelligence.setCardPublication(repo, card.id(), owner.id(), "DRAFT");
        assertThat(intelligence.unifiedSearchDetailed(repo, "退款期限", 10, context).evidence())
                .isEmpty();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM code_chunks WHERE repo_id=?",
                                Integer.class,
                                repo))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_branch_validations WHERE branch_id=?",
                                Integer.class,
                                workspace))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM repository_project_drafts WHERE owner_account_id=?",
                                Integer.class,
                                owner.id()))
                .isZero();
    }

    @Test
    void protectsContextIsolationAndRejectsCodeTasksWithoutEnqueueing() {
        var owner = account();
        UUID repo = projects.create(owner, "知识项目", "").id().value();
        UUID workspace = branches.list(owner, repo).get(0).id();
        var context = branches.resolve(owner, repo, workspace, null);
        var stranger = account();
        assertThatThrownBy(() -> branches.resolve(stranger, repo, null, context.contextId()))
                .isInstanceOf(ApiSecurityException.class);
        UUID otherRepo = projects.create(owner, "另一个项目", "").id().value();
        assertThatThrownBy(() -> branches.resolve(owner, otherRepo, null, context.contextId()))
                .isInstanceOf(ApiSecurityException.class);
        assertThatThrownBy(() -> branches.track(owner, repo, "main")).hasMessageContaining("纯知识库");
        assertThatThrownBy(() -> jobs.submit(owner, repo, workspace))
                .isInstanceOf(ApiSecurityException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM branch_preparation_jobs WHERE repo_id=?",
                                Integer.class,
                                repo))
                .isZero();
        assertThatThrownBy(() -> projects.create(owner, "知识项目", "重名"))
                .isInstanceOf(ApiSecurityException.class);
        db.update(
                "UPDATE branch_read_contexts SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",
                context.contextId());
        assertThatThrownBy(() -> branches.resolve(owner, repo, null, context.contextId()))
                .isInstanceOf(ApiSecurityException.class);
    }

    @Test
    void knowledgeKindIsFilteredBeforeTheCandidateLimit() {
        var owner = account();
        UUID repo = projects.create(owner, "类型筛选测试", "").id().value();
        UUID branch = branches.list(owner, repo).get(0).id();
        var context = branches.resolve(owner, repo, branch, null);
        var target =
                intelligence.createCard(
                        repo, branch, owner.id(), input("退款操作", "退款操作处理流程", "RUNBOOK"));
        publication.publish(owner, context, target.id(), target.revision());
        for (int i = 0; i < 60; i++) {
            var other =
                    intelligence.createCard(
                            repo, branch, owner.id(), input("退款操作参考" + i, "退款操作处理流程"));
            publication.publish(owner, context, other.id(), other.revision());
        }
        db.update(
                "UPDATE knowledge_card_revisions SET changed_at=CURRENT_TIMESTAMP+INTERVAL '1 day' WHERE repo_id=? AND card_id<>?",
                repo,
                target.id());
        // Equal vector distances make both channels fill their candidate budgets with the newer
        // reference cards, so the runbook is outside the unfiltered result deterministically.
        db.update(
                "UPDATE knowledge_card_embeddings SET embedding=(SELECT embedding FROM knowledge_card_embeddings WHERE card_id=? LIMIT 1) WHERE card_id IN(SELECT id FROM knowledge_cards WHERE repo_id=?)",
                target.id(),
                repo);
        context = branches.resolve(owner, repo, branch, null);
        assertThat(intelligence.unifiedSearchDetailed(repo, "退款操作", 50, context).evidence())
                .noneMatch(hit -> target.id().equals(hit.knowledgeCardId()));
        assertThat(
                        intelligence
                                .unifiedSearchDetailed(repo, "退款操作", 50, context, "RUNBOOK")
                                .evidence())
                .singleElement()
                .satisfies(hit -> assertThat(hit.knowledgeCardId()).isEqualTo(target.id()));
    }

    private IntelligenceService.CardInput input(String title, String content) {
        return input(title, content, "REFERENCE");
    }

    private IntelligenceService.CardInput input(String title, String content, String kind) {
        return new IntelligenceService.CardInput(
                title,
                "REFERENCE",
                content,
                List.of("退款"),
                kind,
                "INFO",
                "REFERENCE",
                null,
                KnowledgeScope.empty(),
                KnowledgeObligations.empty(),
                List.of(),
                List.of());
    }

    private AuthenticatedAccount account() {
        UUID id = UUID.randomUUID();
        String username = "knowledge-test-" + id;
        db.update(
                """
                INSERT INTO accounts(id,username,display_name,password_hash,account_role,must_change_password,created_at,updated_at)
                VALUES(?,?,'Test','not-a-real-password','NORMAL',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                id,
                username);
        return new AuthenticatedAccount(id, username, "Test", AccountRole.NORMAL, false, null);
    }
}
