package com.analyzercoder.application.branch;

import com.analyzercoder.application.architecture.ProjectCodeFactsService;
import com.analyzercoder.application.indexing.VectorIndexQueryService;
import com.analyzercoder.application.overview.ProjectHealthOverviewService;
import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.analyzercoder.application.repository.RepositoryPreparationService;
import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.ProjectKnowledgeHealthRow;
import com.analyzercoder.security.AuthenticatedAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** All overview statistics are pinned to the explicit branch contentVersion. */
@Service
public class BranchOverviewService {
    private final RepositoryBranchService branches;
    private final RepositoryCodeBrowserService browser;
    private final ProjectCodeFactsService facts;
    private final CodeGraphArtifactMapper graph;
    private final JdbcTemplate db;

    public BranchOverviewService(
            RepositoryBranchService branches,
            RepositoryCodeBrowserService browser,
            ProjectCodeFactsService facts,
            CodeGraphArtifactMapper graph,
            JdbcTemplate db) {
        this.branches = branches;
        this.browser = browser;
        this.facts = facts;
        this.graph = graph;
        this.db = db;
    }

    public record Overview(
            RepositoryPreparationService.PreparationView preparation,
            ProjectCodeFactsService.CodeFacts codeFacts,
            ProjectHealthOverviewService.ProjectHealthOverview health) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview view(AuthenticatedAccount actor, BranchReadContext context) {
        // Revalidate the account-bound coordinates, never reconstruct them from a default branch.
        context =
                branches.resolve(
                        actor, context.repositoryId(), context.branchId(), context.contextId());
        var repository = branches.repositoryFor(context);
        var contentVersion = browser.list(repository);
        var artifact = graph.findPublished(context.repositoryId(), context.contentVersion());
        var knowledge = knowledge(context);
        var summary = summary(context, knowledge.trusted());
        var profile = RepositoryPreparationService.profile(contentVersion.files(), summary, artifact);
        boolean content =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT content_indexed_at IS NOT NULL FROM repository_branches WHERE repo_id=? AND id=? AND content_version=?",
                                Boolean.class,
                                context.repositoryId(),
                                context.branchId(),
                                context.contentVersion()));
        var stages =
                List.of(
                        stage("contentVersion", "分支代码同步", true),
                        stage("content", "内容索引", content),
                        stage(
                                "vectors",
                                "向量索引（可选）",
                                summary.totalChunks() > 0 && summary.missingChunks() == 0),
                        stage("graph", "代码图谱", artifact != null),
                        new RepositoryPreparationService.PreparationStage(
                                "knowledge_drift",
                                "分支知识验证",
                                knowledge.suspect() + knowledge.stale() == 0 ? "READY" : "DEGRADED",
                                "验证结果固定到此分支、内容版本与知识修订"));
        var issues = new ArrayList<ProjectHealthOverviewService.HealthIssue>();
        if (!content)
            issues.add(issue("CONTENT_INDEX_NOT_READY", "BLOCKING", "当前分支内容索引未就绪", "PREPARATION"));
        if (artifact == null)
            issues.add(issue("CODEGRAPH_NOT_READY", "WARNING", "当前分支代码图谱未构建", "PREPARATION"));
        if (summary.missingChunks() > 0)
            issues.add(issue("VECTOR_INDEX_INCOMPLETE", "WARNING", "当前模型的向量索引不完整", "PREPARATION"));
        if (knowledge.trusted() == 0)
            issues.add(issue("NO_TRUSTED_KNOWLEDGE", "WARNING", "当前分支没有已审核且已验证的知识", "KNOWLEDGE"));
        if (knowledge.suspect() + knowledge.stale() > 0)
            issues.add(
                    issue("KNOWLEDGE_REVIEW_REQUIRED", "WARNING", "当前分支存在待复核或失效知识", "KNOWLEDGE"));
        if (knowledge.requiredWithoutOwner() > 0)
            issues.add(
                    issue("REQUIRED_KNOWLEDGE_WITHOUT_OWNER", "WARNING", "必需知识缺少维护人", "KNOWLEDGE"));
        if (knowledge.unreviewed() > 0)
            issues.add(issue("UNREVIEWED_KNOWLEDGE", "WARNING", "当前分支存在未审核知识", "KNOWLEDGE"));
        String state = !content ? "NOT_READY" : issues.isEmpty() ? "READY" : "DEGRADED";
        var preparation =
                new RepositoryPreparationService.PreparationView(
                        context.repositoryId(),
                        state,
                        content ? 100 : 25,
                        "在项目管理中按分支同步、构建索引或一键准备",
                        stages,
                        profile,
                        null,
                        null,
                        null,
                        context.contentVersion(),
                        context.commitSha(),
                        context.branchName(),
                        false,
                        Instant.now());
        var health =
                new ProjectHealthOverviewService.ProjectHealthOverview(
                        context.repositoryId(),
                        context.contentVersion(),
                        context.commitSha(),
                        !content ? "BLOCKED" : issues.isEmpty() ? "READY" : "DEGRADED",
                        content,
                        knowledge,
                        issues,
                        Instant.now());
        return new Overview(preparation, facts.analyze(repository), health);
    }

    private VectorIndexQueryService.Summary summary(BranchReadContext c, long knowledge) {
        return db.queryForObject(
                """
                WITH active AS (
                    SELECT COALESCE(vm.model,'local-hash-64') model,COALESCE(vm.dimension,64) dimension,
                        CASE WHEN vm.provider_type IS NULL OR vm.provider_type='LOCAL_HASH'
                            THEN 'CHARACTER_HASH' ELSE 'SEMANTIC_EMBEDDING' END capability
                    FROM (SELECT 1) seed LEFT JOIN vector_model_activation va ON va.singleton_id=1
                    LEFT JOIN vector_model_configs vm ON vm.id=va.active_config_id
                )
                SELECT a.model,a.dimension,a.capability,COUNT(c.id) total,COUNT(e.chunk_id) vectorized
                FROM active a LEFT JOIN code_chunks c ON c.repo_id=? AND c.content_version=?
                LEFT JOIN chunk_embeddings e ON e.chunk_id=c.id AND e.content_hash=c.content_hash
                    AND e.model=a.model AND e.dimension=a.dimension AND e.retrieval_capability=a.capability
                GROUP BY a.model,a.dimension,a.capability
                """,
                (r, n) ->
                        new VectorIndexQueryService.Summary(
                                c.repositoryId(),
                                c.contentVersion(),
                                c.commitSha(),
                                r.getLong("total"),
                                r.getLong("vectorized"),
                                r.getLong("total") - r.getLong("vectorized"),
                                knowledge,
                                0,
                                r.getString("model"),
                                r.getInt("dimension"),
                                r.getString("capability"),
                                "CHARACTER_HASH".equals(r.getString("capability"))
                                        ? "字符相似度"
                                        : "语义检索",
                                Instant.now()),
                c.repositoryId(),
                c.contentVersion());
    }

    private ProjectKnowledgeHealthRow knowledge(BranchReadContext c) {
        return db.queryForObject(
                """
                SELECT COUNT(*) total,
                    COUNT(*) FILTER(WHERE v.state='CURRENT') current_count,
                    COUNT(*) FILTER(WHERE v.state='REVIEW_REQUIRED') suspect_count,
                    COUNT(*) FILTER(WHERE v.state='INVALID') stale_count,
                    COUNT(*) FILTER(WHERE v.state IS NULL OR v.state='UNVERIFIED') unverified_count,
                    COUNT(*) FILTER(WHERE v.state='CURRENT' AND k.publication_status='PUBLISHED' AND k.review_status='APPROVED') trusted_count,
                    COUNT(*) FILTER(WHERE k.enforcement='REQUIRED' AND k.owner_account_id IS NULL) required_without_owner,
                    COUNT(*) FILTER(WHERE k.review_status='UNREVIEWED') unreviewed_count
                FROM knowledge_cards k
                LEFT JOIN knowledge_branch_validations v ON v.card_id=k.id AND v.revision=k.revision AND v.branch_id=? AND v.content_version=?
                WHERE k.repo_id=? AND k.publication_status<>'ARCHIVED'
                    AND k.branch_id=?
                """,
                (r, n) ->
                        new ProjectKnowledgeHealthRow(
                                r.getLong("total"),
                                r.getLong("current_count"),
                                r.getLong("suspect_count"),
                                r.getLong("stale_count"),
                                r.getLong("unverified_count"),
                                r.getLong("trusted_count"),
                                r.getLong("required_without_owner"),
                                r.getLong("unreviewed_count")),
                c.branchId(),
                c.contentVersion(),
                c.repositoryId(),
                c.branchId());
    }

    private static RepositoryPreparationService.PreparationStage stage(
            String key, String label, boolean ready) {
        return new RepositoryPreparationService.PreparationStage(
                key, label, ready ? "READY" : "PENDING", ready ? "当前内容版本已就绪" : "在项目管理中构建此分支的索引");
    }

    private static ProjectHealthOverviewService.HealthIssue issue(
            String code, String severity, String title, String target) {
        return new ProjectHealthOverviewService.HealthIssue(
                code, severity, title, "仅针对当前分支内容版本", target);
    }
}
