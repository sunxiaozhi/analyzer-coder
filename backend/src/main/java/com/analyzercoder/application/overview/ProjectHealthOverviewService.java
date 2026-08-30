package com.analyzercoder.application.overview;

import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.repository.RepositoryPreparationService;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.ProjectHealthMapper;
import com.analyzercoder.infrastructure.persistence.model.ProjectKnowledgeHealthRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 用持久化事实回答项目是否具备可信知识和可执行审查条件。 */
@Service
public class ProjectHealthOverviewService {
    private static final int RECENT_REVIEW_LIMIT = 5;

    private final RegisterRepositoryUseCase repositories;
    private final RepositoryPreparationService preparation;
    private final ProjectHealthMapper health;
    private final TaskReviewService reviews;

    public ProjectHealthOverviewService(
            RegisterRepositoryUseCase repositories,
            RepositoryPreparationService preparation,
            ProjectHealthMapper health,
            TaskReviewService reviews) {
        this.repositories = repositories;
        this.preparation = preparation;
        this.health = health;
        this.reviews = reviews;
    }

    public ProjectHealthOverview view(CodeRepositoryId repositoryId) {
        CodeRepository repository = repositories.get(repositoryId);
        RepositoryPreparationService.PreparationView preparationView =
                preparation.view(repositoryId);
        ProjectKnowledgeHealthRow knowledge =
                health.knowledgeHealth(repositoryId.value());
        if (knowledge == null) {
            knowledge = ProjectKnowledgeHealthRow.empty();
        }
        List<TaskReviewResult.ReviewSummary> recentReviews =
                reviews.list(repositoryId, RECENT_REVIEW_LIMIT, 0);
        List<HealthIssue> issues = issues(repository, preparationView, knowledge);
        boolean readyForReview =
                repository.currentSnapshotId() != null && preparationView.profile().chunkCount() > 0;
        String state = state(preparationView.state(), issues);
        return new ProjectHealthOverview(
                repositoryId.value(),
                repository.currentSnapshotId() == null
                        ? null
                        : repository.currentSnapshotId().value(),
                repository.currentCommit(),
                state,
                readyForReview,
                knowledge,
                recentReviews,
                issues,
                Instant.now());
    }

    private static List<HealthIssue> issues(
            CodeRepository repository,
            RepositoryPreparationService.PreparationView preparation,
            ProjectKnowledgeHealthRow knowledge) {
        List<HealthIssue> issues = new ArrayList<>();
        if (repository.currentSnapshotId() == null) {
            issues.add(
                    issue(
                            "SNAPSHOT_NOT_READY",
                            "BLOCKING",
                            "尚无已发布代码快照",
                            "先准备项目，审查结果才能绑定到不可变 Snapshot。",
                            "PREPARATION"));
        } else if (preparation.profile().chunkCount() == 0) {
            issues.add(
                    issue(
                            "CONTENT_INDEX_NOT_READY",
                            "BLOCKING",
                            "当前快照没有代码片段",
                            "内容索引完成后才能定位变化符号和代码证据。",
                            "PREPARATION"));
        }
        if (preparation.profile().missingChunks() > 0) {
            issues.add(
                    issue(
                            "VECTOR_INDEX_INCOMPLETE",
                            "WARNING",
                            "向量索引不完整",
                            preparation.profile().missingChunks() + " 个片段尚未向量化，检索会降级。",
                            "PREPARATION"));
        }
        if (repository.currentSnapshotId() != null && preparation.profile().graphNodes() == 0) {
            issues.add(
                    issue(
                            "CODEGRAPH_NOT_READY",
                            "WARNING",
                            "CodeGraph 尚不可用",
                            "审查仍可使用代码片段，但关系与传播证据会缺失。",
                            "PREPARATION"));
        }
        preparation.stages().stream()
                .filter(stage -> "knowledge_drift".equals(stage.key()))
                .filter(stage -> "FAILED".equals(stage.state()) || "DEGRADED".equals(stage.state()))
                .findFirst()
                .ifPresent(
                        stage ->
                                issues.add(
                                        issue(
                                                "KNOWLEDGE_DRIFT_" + stage.state(),
                                                "WARNING",
                                                "知识失效检查"
                                                        + ("FAILED".equals(stage.state())
                                                                ? "失败"
                                                                : "发现待复核项"),
                                                stage.detail(),
                                                "PREPARATION")));
        if (knowledge.trusted() == 0) {
            issues.add(
                    issue(
                            "NO_TRUSTED_KNOWLEDGE",
                            "WARNING",
                            "没有可信知识",
                            "当前没有同时满足已发布、已审核和版本 CURRENT 的知识。",
                            "KNOWLEDGE"));
        }
        if (knowledge.requiredWithoutOwner() > 0) {
            issues.add(
                    issue(
                            "REQUIRED_KNOWLEDGE_WITHOUT_OWNER",
                            "WARNING",
                            "必需知识缺少负责人",
                            knowledge.requiredWithoutOwner() + " 条 REQUIRED 知识无法明确审批责任。",
                            "KNOWLEDGE"));
        }
        if (knowledge.unreviewed() > 0) {
            issues.add(
                    issue(
                            "UNREVIEWED_KNOWLEDGE",
                            "WARNING",
                            "存在未审核知识",
                            knowledge.unreviewed() + " 条知识尚未完成审核。",
                            "KNOWLEDGE"));
        }
        if (knowledge.suspect() > 0) {
            issues.add(
                    issue(
                            "SUSPECT_KNOWLEDGE",
                            "WARNING",
                            "知识需要复核",
                            knowledge.suspect() + " 条知识可能受当前代码变化影响。",
                            "KNOWLEDGE"));
        }
        if (knowledge.stale() > 0) {
            issues.add(
                    issue(
                            "STALE_KNOWLEDGE",
                            "WARNING",
                            "知识已经过期",
                            knowledge.stale() + " 条知识不会作为可信审查依据。",
                            "KNOWLEDGE"));
        }
        return List.copyOf(issues);
    }

    private static String state(String preparationState, List<HealthIssue> issues) {
        if ("PROCESSING".equals(preparationState)) {
            return "PREPARING";
        }
        if ("ACTION_REQUIRED".equals(preparationState)
                || issues.stream().anyMatch(issue -> "BLOCKING".equals(issue.severity()))) {
            return "BLOCKED";
        }
        if (!"READY".equals(preparationState) || !issues.isEmpty()) {
            return "DEGRADED";
        }
        return "READY";
    }

    private static HealthIssue issue(
            String code, String severity, String title, String detail, String actionTarget) {
        return new HealthIssue(code, severity, title, detail, actionTarget);
    }

    public record ProjectHealthOverview(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            String state,
            boolean readyForReview,
            ProjectKnowledgeHealthRow knowledge,
            List<TaskReviewResult.ReviewSummary> recentReviews,
            List<HealthIssue> issues,
            Instant generatedAt) {
        public ProjectHealthOverview {
            recentReviews = recentReviews == null ? List.of() : List.copyOf(recentReviews);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record HealthIssue(
            String code, String severity, String title, String detail, String actionTarget) {}
}
