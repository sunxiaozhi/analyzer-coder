package com.analyzercoder.application.pullrequest;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 将已验签的提供方事件映射到唯一已登记仓库，并生成按 Head 幂等的审查请求。 */
@Service
public class ProviderWebhookService {
    private static final Set<String> GITHUB_ACTIONS =
            Set.of("opened", "reopened", "synchronize", "ready_for_review");
    private static final Set<String> GITLAB_ACTIONS = Set.of("open", "reopen", "update");

    private final RepositoryMapper repositories;
    private final PullRequestReviewService reviews;
    private final ObjectMapper json;

    public ProviderWebhookService(
            RepositoryMapper repositories, PullRequestReviewService reviews, ObjectMapper json) {
        this.repositories = repositories;
        this.reviews = reviews;
        this.json = json;
    }

    public WebhookResult github(String event, String deliveryId, byte[] payload) {
        if (!"pull_request".equalsIgnoreCase(normalized(event))) {
            return WebhookResult.ignored(PullRequestProvider.ProviderKind.GITHUB, "EVENT_IGNORED");
        }
        JsonNode root = parse(payload);
        String action = normalized(text(root, "action"));
        if (!GITHUB_ACTIONS.contains(action)) {
            return WebhookResult.ignored(PullRequestProvider.ProviderKind.GITHUB, "ACTION_IGNORED");
        }
        JsonNode pullRequest = root.path("pull_request");
        return dispatch(
                PullRequestProvider.ProviderKind.GITHUB,
                text(root.path("repository"), "clone_url"),
                root.path("number").asLong(0),
                text(pullRequest.path("head"), "sha"),
                deliveryId);
    }

    public WebhookResult gitlab(String event, String deliveryId, byte[] payload) {
        if (!"Merge Request Hook".equalsIgnoreCase(normalized(event))) {
            return WebhookResult.ignored(PullRequestProvider.ProviderKind.GITLAB, "EVENT_IGNORED");
        }
        JsonNode root = parse(payload);
        JsonNode attributes = root.path("object_attributes");
        String action = normalized(text(attributes, "action"));
        if (!GITLAB_ACTIONS.contains(action)) {
            return WebhookResult.ignored(PullRequestProvider.ProviderKind.GITLAB, "ACTION_IGNORED");
        }
        String head = text(attributes.path("last_commit"), "id");
        if (head == null) {
            head = text(root.path("object_attributes"), "last_commit_id");
        }
        return dispatch(
                PullRequestProvider.ProviderKind.GITLAB,
                text(root.path("project"), "git_http_url"),
                attributes.path("iid").asLong(0),
                head,
                deliveryId);
    }

    private WebhookResult dispatch(
            PullRequestProvider.ProviderKind provider,
            String remoteUrl,
            long number,
            String headSha,
            String deliveryId) {
        if (remoteUrl == null || number < 1) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_PAYLOAD_INVALID", "Webhook 缺少仓库地址或 PR/MR 编号");
        }
        List<UUID> matches = repositories.findIdsByRemoteUrls(remoteCandidates(remoteUrl));
        if (matches == null || matches.isEmpty()) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_REPOSITORY_NOT_FOUND", "Webhook 对应的远程仓库尚未接入平台");
        }
        if (matches.size() != 1) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_REPOSITORY_AMBIGUOUS", "多个仓库使用同一远程地址，无法安全选择审查目标");
        }
        RepositoryRow repository = repositories.findById(matches.get(0));
        if (repository == null || repository.ownerAccountId() == null) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_REPOSITORY_NOT_FOUND", "Webhook 对应仓库或 OWNER 不存在");
        }
        UUID requestId =
                deterministicRequestId(
                        provider, repository.id(), number, first(headSha, deliveryId));
        PullRequestReviewService.ReviewResult review =
                reviews.reviewWebhook(
                        CodeRepositoryId.of(repository.id()),
                        repository.ownerAccountId(),
                        new PullRequestReviewService.ReviewRequest(
                                requestId,
                                provider,
                                number,
                                provider + " webhook #" + number,
                                null,
                                null));
        return new WebhookResult(
                true, null, provider, repository.id(), number, requestId, review);
    }

    private JsonNode parse(byte[] payload) {
        try {
            return json.readTree(payload);
        } catch (IOException exception) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_PAYLOAD_INVALID", "Webhook 请求体不是有效 JSON", exception);
        }
    }

    private static List<String> remoteCandidates(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String base =
                    ("https://" + uri.getRawAuthority() + uri.getRawPath())
                            .replaceAll("/+$", "")
                            .toLowerCase(Locale.ROOT);
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            candidates.add(base);
            candidates.add(base.endsWith(".git") ? base.substring(0, base.length() - 4) : base + ".git");
            return List.copyOf(candidates);
        } catch (RuntimeException exception) {
            throw new PullRequestIntegrationException(
                    "WEBHOOK_PAYLOAD_INVALID", "Webhook 仓库地址无效");
        }
    }

    private static UUID deterministicRequestId(
            PullRequestProvider.ProviderKind provider,
            UUID repositoryId,
            long number,
            String version) {
        String key = provider + ":" + repositoryId + ":" + number + ":" + normalized(version);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String first(String primary, String fallback) {
        String value = normalized(primary);
        return value.isBlank() ? normalized(fallback) : value;
    }

    public record WebhookResult(
            boolean accepted,
            String reason,
            PullRequestProvider.ProviderKind provider,
            UUID repositoryId,
            long number,
            UUID clientRequestId,
            PullRequestReviewService.ReviewResult review) {
        static WebhookResult ignored(
                PullRequestProvider.ProviderKind provider, String reason) {
            return new WebhookResult(false, reason, provider, null, 0, null, null);
        }
    }
}
