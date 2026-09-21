package com.analyzercoder.application.mcp;

import com.analyzercoder.application.branch.RepositoryBranchService;
import com.analyzercoder.application.intelligence.CodeGraphException;
import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.intelligence.ManagedCodeGraphService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Account-scoped discovery and read-only CodeGraph queries on pinned branch contentVersions. */
@Service
public class McpCodeGraphTools {
    private final AccessControlService access;
    private final CodeRepositoryStore repositories;
    private final RepositoryBranchService branches;
    private final CodeGraphService graphs;
    private final ManagedCodeGraphService managed;
    private final ObjectMapper json;

    public McpCodeGraphTools(
            AccessControlService access,
            CodeRepositoryStore repositories,
            RepositoryBranchService branches,
            CodeGraphService graphs,
            ManagedCodeGraphService managed,
            ObjectMapper json) {
        this.access = access;
        this.repositories = repositories;
        this.branches = branches;
        this.graphs = graphs;
        this.managed = managed;
        this.json = json;
    }

    public JsonNode call(String name, JsonNode input, AuthenticatedAccount actor) {
        if ("list_codegraph_scopes".equals(name)) return listScopes(input, actor);
        UUID repositoryId = UUID.fromString(input.path("repositoryId").asText());
        access.require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.READ);
        UUID branchId = optionalUuid(input, "branchId");
        UUID contextId = optionalUuid(input, "contextId");
        if (branchId == null && contextId == null)
            throw new IllegalArgumentException("CodeGraph 查询需要 branchId 或 contextId");
        var context = branches.resolve(actor, repositoryId, branchId, contextId);
        var artifact = graphs.latestContentVersion(repositoryId, context.contentVersion());
        if (artifact == null || !"PUBLISHED".equals(artifact.status()))
            throw new CodeGraphException(
                    "CODEGRAPH_ARTIFACT_NOT_AVAILABLE", "所选分支内容版本尚未发布 CodeGraph 产物");
        List<String> args = new ArrayList<>();
        String operation;
        switch (name) {
            case "codegraph_explore" -> {
                operation = "explore";
                args.add("--max-files");
                args.add(String.valueOf(number(input, "maxFiles", 8, 1, 20)));
                args.add(safe(input, "query", true));
            }
            case "codegraph_node" -> {
                operation = "node";
                if (input.hasNonNull("file")) {
                    args.add("-f");
                    args.add(safeFile(input.get("file").asText()));
                }
                args.add("--offset");
                args.add(String.valueOf(number(input, "offset", 0, 0, 10000)));
                args.add("--limit");
                args.add(String.valueOf(number(input, "limit", 50, 1, 100)));
                if (input.hasNonNull("name")) args.add(safe(input, "name", true));
                else if (!input.hasNonNull("file"))
                    throw new IllegalArgumentException("name 或 file 至少提供一个");
            }
            case "codegraph_search" -> {
                operation = "query";
                args.add("-l");
                args.add(String.valueOf(number(input, "limit", 20, 1, 50)));
                args.add("-j");
                args.add(safe(input, "query", true));
            }
            case "codegraph_callers", "codegraph_callees" -> {
                operation = name.substring("codegraph_".length());
                args.add("-l");
                args.add(String.valueOf(number(input, "limit", 20, 1, 50)));
                args.add("-j");
                args.add(safe(input, "symbol", true));
            }
            case "codegraph_impact" -> {
                operation = "impact";
                args.add("-d");
                args.add(String.valueOf(number(input, "depth", 2, 1, 5)));
                args.add("-j");
                args.add(safe(input, "symbol", true));
            }
            case "codegraph_files" -> {
                operation = "files";
                if (input.hasNonNull("filter")) {
                    args.add("--filter");
                    args.add(safeFile(input.get("filter").asText()));
                }
                if (input.hasNonNull("pattern")) {
                    args.add("--pattern");
                    args.add(safeFile(input.get("pattern").asText()));
                }
                args.add("-j");
            }
            case "codegraph_status" -> operation = "status";
            case "codegraph_affected" -> {
                operation = "affected";
                args.add("-d");
                args.add(String.valueOf(number(input, "depth", 2, 1, 5)));
                args.add("-j");
                JsonNode files = input.path("files");
                if (!files.isArray() || files.isEmpty())
                    throw new IllegalArgumentException("files 不能为空");
                for (JsonNode file : files) args.add(safeFile(file.asText()));
            }
            default -> throw new IllegalArgumentException("未知 MCP 工具");
        }
        String output = managed.readContentVersion(repositoryId, context.contentVersion(), operation, args);
        JsonNode result;
        try {
            result = json.readTree(output);
        } catch (Exception ignored) {
            result = json.valueToTree(output);
        }
        return json.valueToTree(
                Map.of(
                        "context",
                        context,
                        "artifactId",
                        artifact.id(),
                        "cliVersion",
                        artifact.cliVersion(),
                        "result",
                        result));
    }

    private JsonNode listScopes(JsonNode input, AuthenticatedAccount actor) {
        int page = number(input, "page", 1, 1, 100000);
        int pageSize = number(input, "pageSize", 20, 1, 50);
        Set<UUID> visible = Set.copyOf(access.visibleRepositoryIds(actor));
        var projects =
                repositories.findAll().stream()
                        .filter(r -> visible.contains(r.id().value()))
                        .sorted(Comparator.comparing(r -> r.name().toLowerCase()))
                        .toList();
        int start = (int) Math.min(projects.size(), (long) (page - 1) * pageSize);
        var result = new ArrayList<Map<String, Object>>();
        for (var project : projects.subList(start, Math.min(projects.size(), start + pageSize))) {
            var branchList = new ArrayList<Map<String, Object>>();
            for (var branch : branches.list(actor, project.id().value())) {
                var item = new java.util.LinkedHashMap<String, Object>();
                item.put("branchId", branch.id());
                item.put("name", branch.name());
                item.put("status", branch.status());
                item.put("trackingStatus", branch.trackingStatus());
                item.put("contentVersion", branch.contentVersion());
                item.put("commitSha", branch.commitSha());
                var artifact =
                        branch.contentVersion() == null
                                ? null
                                : graphs.latestContentVersion(project.id().value(), branch.contentVersion());
                item.put(
                        "codegraphReady",
                        branch.archivedAt() == null
                                && artifact != null
                                && "PUBLISHED".equals(artifact.status()));
                branchList.add(item);
            }
            result.add(
                    Map.of(
                            "repositoryId",
                            project.id().value(),
                            "name",
                            project.name(),
                            "sourceType",
                            project.sourceType().name(),
                            "branches",
                            branchList));
        }
        return json.valueToTree(
                Map.of(
                        "projects",
                        result,
                        "page",
                        page,
                        "pageSize",
                        pageSize,
                        "totalProjects",
                        projects.size(),
                        "hasMore",
                        start + pageSize < projects.size()));
    }

    private static UUID optionalUuid(JsonNode input, String field) {
        return input.hasNonNull(field) ? UUID.fromString(input.get(field).asText()) : null;
    }

    private static int number(JsonNode input, String field, int fallback, int min, int max) {
        int value = input.path(field).asInt(fallback);
        if (value < min || value > max) throw new IllegalArgumentException(field + " 超出允许范围");
        return value;
    }

    private static String safeFile(String value) {
        String path = safe(value, true).replace('\\', '/');
        if (path.startsWith("/")
                || path.startsWith("-")
                || path.contains("../") || path.endsWith("/..")
                || path.equals("..")
                || path.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("文件路径必须位于所选分支内");
        return path;
    }

    private static String safe(JsonNode input, String field, boolean required) {
        return safe(input.path(field).asText(""), required);
    }

    private static String safe(String value, boolean required) {
        String trimmed = value.trim();
        if ((required && trimmed.isEmpty())
                || trimmed.startsWith("-")
                || trimmed.length() > 500
                || trimmed.matches(".*[\\r\\n&|<>^\"%!`].*"))
            throw new IllegalArgumentException("CodeGraph 查询参数无效");
        return trimmed;
    }
}
