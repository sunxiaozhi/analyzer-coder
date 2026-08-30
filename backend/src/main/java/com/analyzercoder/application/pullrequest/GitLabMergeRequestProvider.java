package com.analyzercoder.application.pullrequest;

import com.analyzercoder.application.change.RepositoryChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** GitLab Merge Request REST 适配器；逐页读取 Diff，并用 Notes API 幂等更新评论。 */
@Component
public class GitLabMergeRequestProvider extends PullRequestHttpSupport
        implements PullRequestProvider {
    private static final int MAX_PAGES = 50;

    public GitLabMergeRequestProvider(ObjectMapper json) {
        super(json);
    }

    GitLabMergeRequestProvider(ObjectMapper json, HttpClient http) {
        super(json, http);
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.GITLAB;
    }

    @Override
    public PullRequestSnapshot fetch(Reference reference, AccessToken accessToken) {
        String root = mergeRequestRoot(reference);
        JsonNode metadata =
                json(
                        get(
                                endpoint(reference.apiBaseUrl(), root),
                                headers(accessToken),
                                MAX_JSON_BYTES));
        StringBuilder patch = new StringBuilder();
        ArrayList<RepositoryChange.Limitation> limitations = new ArrayList<>();
        boolean partial = false;
        int page = 1;
        while (page <= MAX_PAGES) {
            Response response =
                    get(
                            endpoint(
                                    reference.apiBaseUrl(),
                                    root + "/diffs?per_page=100&page=" + page),
                            headers(accessToken),
                            MAX_JSON_BYTES);
            JsonNode diffs = json(response);
            if (!diffs.isArray()) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_RESPONSE_INVALID", "GitLab Diff 响应不是数组");
            }
            for (JsonNode diff : diffs) {
                String oldPath = text(diff, "old_path", null);
                String newPath = text(diff, "new_path", null);
                if (oldPath == null && newPath == null) {
                    throw new PullRequestIntegrationException(
                            "PROVIDER_RESPONSE_INVALID", "GitLab Diff 缺少文件路径");
                }
                appendDiff(patch, diff, oldPath, newPath);
                if (diff.path("too_large").asBoolean(false)
                        || diff.path("collapsed").asBoolean(false)) {
                    partial = true;
                    limitations.add(
                            new RepositoryChange.Limitation(
                                    "PROVIDER_DIFF_OMITTED",
                                    "GitLab 未返回完整 Diff: " + first(newPath, oldPath)));
                }
                if (patch.length() > MAX_PATCH_BYTES) {
                    throw new PullRequestIntegrationException(
                            "PROVIDER_PATCH_TOO_LARGE", "PR/MR Patch 超过 5 MiB，未生成不完整审查");
                }
            }
            String next = response.nextPage();
            if (next == null || next.isBlank()) {
                break;
            }
            try {
                page = Integer.parseInt(next);
            } catch (NumberFormatException exception) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_RESPONSE_INVALID", "GitLab 分页信息无效", exception);
            }
        }
        if (page > MAX_PAGES) {
            partial = true;
            limitations.add(
                    new RepositoryChange.Limitation(
                            "PROVIDER_PAGE_LIMIT_EXCEEDED", "GitLab Diff 超过 50 页读取上限"));
        }
        JsonNode refs = metadata.path("diff_refs");
        return new PullRequestSnapshot(
                kind(),
                reference.externalId(),
                reference.number(),
                text(metadata, "title", "未命名 Merge Request"),
                text(metadata, "web_url", null),
                text(metadata.path("author"), "username", text(metadata.path("author"), "name", null)),
                metadata.path("draft").asBoolean(metadata.path("work_in_progress").asBoolean(false)),
                text(refs, "base_sha", null),
                text(refs, "head_sha", null),
                patch.toString(),
                partial,
                List.copyOf(limitations),
                Instant.now());
    }

    @Override
    public CommentResult upsertReviewComment(
            Reference reference, String marker, String body, AccessToken accessToken) {
        String notes = mergeRequestRoot(reference) + "/notes";
        int page = 1;
        while (page <= MAX_PAGES) {
            Response response =
                    get(
                            endpoint(
                                    reference.apiBaseUrl(),
                                    notes + "?per_page=100&page=" + page),
                            headers(accessToken),
                            MAX_JSON_BYTES);
            JsonNode rows = json(response);
            if (!rows.isArray()) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_RESPONSE_INVALID", "GitLab 评论响应不是数组");
            }
            for (JsonNode row : rows) {
                if (row.path("body").asText("").contains(marker)) {
                    String id = row.path("id").asText();
                    jsonRequest(
                            "PUT",
                            endpoint(reference.apiBaseUrl(), notes + "/" + encode(id)),
                            headers(accessToken),
                            Map.of("body", body));
                    return new CommentResult(CommentAction.UPDATED, id, null);
                }
            }
            String next = response.nextPage();
            if (next == null || next.isBlank()) {
                break;
            }
            try {
                page = Integer.parseInt(next);
            } catch (NumberFormatException exception) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_RESPONSE_INVALID", "GitLab 分页信息无效", exception);
            }
        }
        if (page > MAX_PAGES) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_COMMENT_SCAN_LIMIT",
                    "GitLab 评论超过 50 页，无法证明隐藏 Marker 不存在，已停止发布");
        }
        JsonNode created =
                json(
                        jsonRequest(
                                "POST",
                                endpoint(reference.apiBaseUrl(), notes),
                                headers(accessToken),
                                Map.of("body", body)));
        return new CommentResult(CommentAction.CREATED, created.path("id").asText(), null);
    }

    private static String mergeRequestRoot(Reference reference) {
        return "/projects/"
                + encode(reference.projectPath())
                + "/merge_requests/"
                + reference.number();
    }

    private static void appendDiff(
            StringBuilder patch, JsonNode diff, String oldPath, String newPath) {
        String oldValue = first(oldPath, newPath);
        String newValue = first(newPath, oldPath);
        patch.append("diff --git a/").append(oldValue).append(" b/").append(newValue).append('\n');
        if (diff.path("new_file").asBoolean(false)) {
            patch.append("new file mode 100644\n");
        } else if (diff.path("deleted_file").asBoolean(false)) {
            patch.append("deleted file mode 100644\n");
        } else if (diff.path("renamed_file").asBoolean(false)) {
            patch.append("rename from ").append(oldValue).append('\n');
            patch.append("rename to ").append(newValue).append('\n');
        }
        String body = diff.path("diff").asText("");
        if (body.isBlank() && diff.path("too_large").asBoolean(false)) {
            patch.append("Binary files a/").append(oldValue).append(" and b/").append(newValue).append(" differ\n");
        } else {
            patch.append(body);
            if (!body.endsWith("\n")) {
                patch.append('\n');
            }
        }
    }

    private static Map<String, String> headers(AccessToken token) {
        return Map.of("Accept", "application/json", "PRIVATE-TOKEN", token.value());
    }

    private static String first(String primary, String fallback) {
        return primary == null ? fallback : primary;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }
}
