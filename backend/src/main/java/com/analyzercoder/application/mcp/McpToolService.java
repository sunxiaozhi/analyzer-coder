package com.analyzercoder.application.mcp;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 为外部客户端提供与网页一致的只读代码—知识联合检索。 */
@Service
public class McpToolService {
    private final AccessControlService access;
    private final IntelligenceService intelligence;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    private com.analyzercoder.application.branch.RepositoryBranchService branches;

    public McpToolService(
            AccessControlService access, IntelligenceService intelligence, ObjectMapper json) {
        this.access = access;
        this.intelligence = intelligence;
        this.json = json;
    }

    public JsonNode call(String name, JsonNode input, AuthenticatedAccount actor, String ip) {
        if (!"search_project".equals(name) && !"resolve_project_context".equals(name)) {
            throw new IllegalArgumentException("未知 MCP 工具");
        }
        CodeRepositoryId repository =
                CodeRepositoryId.of(UUID.fromString(input.path("repositoryId").asText()));
        access.require(actor, repository, RepositoryPermission.READ);
        var branchId =
                input.hasNonNull("branchId")
                        ? UUID.fromString(input.path("branchId").asText())
                        : null;
        var contextId =
                input.hasNonNull("contextId")
                        ? UUID.fromString(input.path("contextId").asText())
                        : null;
        var context =
                branchId != null || contextId != null
                        ? branches.resolve(actor, repository.value(), branchId, contextId)
                        : null;
        if ("resolve_project_context".equals(name)) {
            if (context == null) throw new IllegalArgumentException("解析上下文需要 branchId 或 contextId");
            return json.valueToTree(context);
        }
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("检索词不能为空");
        }
        int limit = Math.max(1, Math.min(input.path("limit").asInt(20), 50));
        if (context != null)
            return json.valueToTree(
                    java.util.Map.of(
                            "context",
                            context,
                            "result",
                            intelligence.unifiedSearchDetailed(
                                    repository.value(), query, limit, context)));
        return json.valueToTree(
                intelligence.unifiedSearchDetailed(repository.value(), query, limit));
    }
}
