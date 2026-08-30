package com.analyzercoder.application.pullrequest;

import com.analyzercoder.application.change.RepositoryChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** GitHub Pull Request REST 适配器；评论使用 Issue comments API 幂等更新。 */
@Component
public class GitHubPullRequestProvider extends PullRequestHttpSupport
        implements PullRequestProvider {
    private static final int MAX_COMMENT_PAGES = 10;

    public GitHubPullRequestProvider(ObjectMapper json) {
        super(json);
    }

    GitHubPullRequestProvider(ObjectMapper json, HttpClient http) {
        super(json, http);
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.GITHUB;
    }

    @Override
    public PullRequestSnapshot fetch(Reference reference, AccessToken accessToken) {
        String repository = githubRepository(reference.projectPath());
        String endpoint = "/repos/" + repository + "/pulls/" + reference.number();
        JsonNode metadata =
                json(get(endpoint(reference.apiBaseUrl(), endpoint), headers(accessToken, "application/vnd.github+json"), MAX_JSON_BYTES));
        String patch =
                get(endpoint(reference.apiBaseUrl(), endpoint), headers(accessToken, "application/vnd.github.diff"), MAX_PATCH_BYTES)
                        .text();
        int expectedFiles = metadata.path("changed_files").asInt(-1);
        int observedFiles = countDiffFiles(patch);
        boolean partial = expectedFiles >= 0 && expectedFiles != observedFiles;
        List<RepositoryChange.Limitation> limitations =
                partial
                        ? List.of(
                                new RepositoryChange.Limitation(
                                        "PROVIDER_FILE_COUNT_MISMATCH",
                                        "GitHub 报告 "
                                                + expectedFiles
                                                + " 个文件，但 Patch 可确认 "
                                                + observedFiles
                                                + " 个"))
                        : List.of();
        return new PullRequestSnapshot(
                kind(),
                reference.externalId(),
                reference.number(),
                text(metadata, "title", "未命名 Pull Request"),
                text(metadata, "html_url", null),
                nestedText(metadata, "user", "login"),
                metadata.path("draft").asBoolean(false),
                nestedText(metadata, "base", "sha"),
                nestedText(metadata, "head", "sha"),
                patch,
                partial,
                limitations,
                Instant.now());
    }

    @Override
    public CommentResult upsertReviewComment(
            Reference reference, String marker, String body, AccessToken accessToken) {
        String repository = githubRepository(reference.projectPath());
        String comments =
                "/repos/"
                        + repository
                        + "/issues/"
                        + reference.number()
                        + "/comments";
        boolean commentScanComplete = false;
        for (int page = 1; page <= MAX_COMMENT_PAGES; page++) {
            JsonNode rows =
                    json(
                            get(
                                    endpoint(
                                            reference.apiBaseUrl(),
                                            comments + "?per_page=100&page=" + page),
                                    headers(accessToken, "application/vnd.github+json"),
                                    MAX_JSON_BYTES));
            if (!rows.isArray()) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_RESPONSE_INVALID", "GitHub 评论响应不是数组");
            }
            for (JsonNode row : rows) {
                if (row.path("body").asText("").contains(marker)) {
                    String id = row.path("id").asText();
                    JsonNode updated =
                            json(
                                    jsonRequest(
                                            "PATCH",
                                            endpoint(
                                                    reference.apiBaseUrl(),
                                                    "/repos/"
                                                            + repository
                                                            + "/issues/comments/"
                                                            + encode(id)),
                                            headers(accessToken, "application/vnd.github+json"),
                                            Map.of("body", body)));
                    return new CommentResult(
                            CommentAction.UPDATED,
                            id,
                            text(updated, "html_url", text(row, "html_url", null)));
                }
            }
            if (rows.size() < 100) {
                commentScanComplete = true;
                break;
            }
        }
        if (!commentScanComplete) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_COMMENT_SCAN_LIMIT",
                    "GitHub 评论超过 1000 条，无法证明隐藏 Marker 不存在，已停止发布");
        }
        JsonNode created =
                json(
                        jsonRequest(
                                "POST",
                                endpoint(reference.apiBaseUrl(), comments),
                                headers(accessToken, "application/vnd.github+json"),
                                Map.of("body", body)));
        return new CommentResult(
                CommentAction.CREATED,
                created.path("id").asText(),
                text(created, "html_url", null));
    }

    private static Map<String, String> headers(AccessToken token, String accept) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept);
        headers.put("Authorization", "Bearer " + token.value());
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private static String githubRepository(String projectPath) {
        String[] segments = projectPath.split("/");
        if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_PROJECT_INVALID", "GitHub 仓库路径必须为 owner/repository");
        }
        return encode(segments[0]) + "/" + encode(segments[1]);
    }

    private static int countDiffFiles(String patch) {
        int count = 0;
        for (String line : patch.split("\\n")) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }

    private static String nestedText(JsonNode node, String object, String field) {
        return text(node.path(object), field, null);
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }
}
