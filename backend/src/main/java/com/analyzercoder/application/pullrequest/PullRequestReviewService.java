package com.analyzercoder.application.pullrequest;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.repository.RepositoryCredentialService;
import com.analyzercoder.application.review.TaskReviewRequest;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.security.AuthenticatedAccount;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 绑定提供方事实、当前快照、任务审查和幂等评论的 PR/MR 集成用例。 */
@Service
public class PullRequestReviewService {
    private final CodeRepositoryStore repositories;
    private final RepositoryMapper repositoryMapper;
    private final RepositoryCredentialService credentials;
    private final PullRequestTargetResolver targets;
    private final UnifiedDiffRepositoryChangeParser patches;
    private final TaskReviewService reviews;
    private final PullRequestReviewCommentRenderer comments;
    private final Map<PullRequestProvider.ProviderKind, PullRequestProvider> providers;

    public PullRequestReviewService(
            CodeRepositoryStore repositories,
            RepositoryMapper repositoryMapper,
            RepositoryCredentialService credentials,
            PullRequestTargetResolver targets,
            UnifiedDiffRepositoryChangeParser patches,
            TaskReviewService reviews,
            PullRequestReviewCommentRenderer comments,
            List<PullRequestProvider> providers) {
        this.repositories = repositories;
        this.repositoryMapper = repositoryMapper;
        this.credentials = credentials;
        this.targets = targets;
        this.patches = patches;
        this.reviews = reviews;
        this.comments = comments;
        EnumMap<PullRequestProvider.ProviderKind, PullRequestProvider> indexed =
                new EnumMap<>(PullRequestProvider.ProviderKind.class);
        for (PullRequestProvider provider : providers) {
            if (indexed.put(provider.kind(), provider) != null) {
                throw new IllegalStateException("PR/MR 提供方重复注册: " + provider.kind());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public ReviewResult review(
            AuthenticatedAccount actor, CodeRepositoryId repositoryId, ReviewRequest request) {
        if (actor == null || request == null) {
            throw new IllegalArgumentException("PR/MR 审查请求不完整");
        }
        return review(
                repositoryId,
                actor.id(),
                request,
                remoteUrl -> credentials.resolveBound(actor, repositoryId.value(), remoteUrl));
    }

    /** Webhook 已完成提供方签名验证后，以仓库 OWNER 作为可追溯创建者执行同一用例。 */
    public ReviewResult reviewWebhook(
            CodeRepositoryId repositoryId, UUID createdBy, ReviewRequest request) {
        if (createdBy == null || request == null) {
            throw new IllegalArgumentException("Webhook 审查请求不完整");
        }
        return review(
                repositoryId,
                createdBy,
                request,
                remoteUrl ->
                        credentials.resolveBoundInternal(repositoryId.value(), remoteUrl));
    }

    private ReviewResult review(
            CodeRepositoryId repositoryId,
            UUID createdBy,
            ReviewRequest request,
            CredentialResolver credentialResolver) {
        CodeRepository repository =
                repositories
                        .findById(repositoryId)
                        .orElseThrow(
                                () ->
                                        new PullRequestIntegrationException(
                                                "REPOSITORY_NOT_FOUND", "代码仓库不存在"));
        if (repository.currentSnapshotId() == null
                || repository.currentSnapshotPath() == null
                || repository.currentCommit() == null) {
            throw new PullRequestIntegrationException(
                    "CURRENT_SNAPSHOT_REQUIRED", "仓库尚未发布可用于 PR/MR 审查的代码快照");
        }
        String remoteUrl = repositoryMapper.findRemoteUrl(repositoryId.value());
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new PullRequestIntegrationException(
                    "REMOTE_REPOSITORY_REQUIRED", "当前仓库没有可用于 PR/MR 集成的远程地址");
        }
        PullRequestProvider.Reference reference =
                targets.resolve(
                        request.provider(), remoteUrl, request.apiBaseUrl(), request.number());
        RepositoryCredentialService.Resolved credential =
                credentialResolver.resolve(remoteUrl);
        if (credential == null) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_CREDENTIAL_REQUIRED", "当前仓库没有绑定可用于 PR/MR API 的访问令牌");
        }
        PullRequestProvider provider = providers.get(request.provider());
        if (provider == null) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_NOT_AVAILABLE", "所选 PR/MR 提供方未启用");
        }
        PullRequestProvider.AccessToken token =
                new PullRequestProvider.AccessToken(credential.value().secret());
        PullRequestProvider.PullRequestSnapshot source = provider.fetch(reference, token);
        if (!repository.currentCommit().equalsIgnoreCase(source.headSha())) {
            throw new PullRequestIntegrationException(
                    "PR_HEAD_NOT_CURRENT_SNAPSHOT",
                    "PR/MR Head 与当前发布快照不一致，请先同步仓库并完成准备流程");
        }
        RepositoryChange change = patches.parse(source);
        String task =
                request.task() == null
                        ? source.provider() + " " + source.externalId() + " · " + source.title()
                        : request.task();
        TaskReviewRequest taskRequest =
                new TaskReviewRequest(
                        request.clientRequestId(),
                        task,
                        GitChangeRequest.Source.COMMIT_RANGE,
                        change.baseCommit(),
                        change.headCommit(),
                        request.modelConfigId());
        TaskReviewResult review =
                reviews.createExternal(repositoryId, createdBy, taskRequest, change);
        String marker = marker(repositoryId, source);
        PullRequestProvider.CommentResult comment =
                provider.upsertReviewComment(
                        reference, marker, comments.render(marker, source, review), token);
        return new ReviewResult(
                source.provider(),
                source.externalId(),
                source.number(),
                source.title(),
                source.webUrl(),
                source.author(),
                source.draft(),
                source.fetchedAt(),
                review,
                comment);
    }

    @FunctionalInterface
    private interface CredentialResolver {
        RepositoryCredentialService.Resolved resolve(String remoteUrl);
    }

    static String marker(
            CodeRepositoryId repositoryId, PullRequestProvider.PullRequestSnapshot source) {
        return "<!-- analyzer-coder:task-review:"
                + repositoryId.value()
                + ":"
                + source.provider().name().toLowerCase()
                + ":"
                + source.number()
                + " -->";
    }

    public record ReviewRequest(
            UUID clientRequestId,
            PullRequestProvider.ProviderKind provider,
            long number,
            String task,
            UUID modelConfigId,
            String apiBaseUrl) {
        public ReviewRequest {
            if (clientRequestId == null || provider == null || number < 1) {
                throw new IllegalArgumentException("clientRequestId、provider 和 PR/MR 编号不能为空");
            }
            task = normalize(task, 2_000, "任务描述");
            apiBaseUrl = normalize(apiBaseUrl, 500, "API 地址");
        }

        private static String normalize(String value, int maximum, String label) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim();
            if (normalized.length() > maximum) {
                throw new IllegalArgumentException(label + "不能超过 " + maximum + " 个字符");
            }
            return normalized;
        }
    }

    public record ReviewResult(
            PullRequestProvider.ProviderKind provider,
            String externalId,
            long number,
            String title,
            String webUrl,
            String author,
            boolean draft,
            java.time.Instant fetchedAt,
            TaskReviewResult review,
            PullRequestProvider.CommentResult comment) {}
}
