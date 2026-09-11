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

    public McpToolService(
            AccessControlService access,
            IntelligenceService intelligence,
            ObjectMapper json) {
        this.access = access;
        this.intelligence = intelligence;
        this.json = json;
    }

    public JsonNode call(String name, JsonNode input, AuthenticatedAccount actor, String ip) {
        if (!"search_project".equals(name)) {
            throw new IllegalArgumentException("未知 MCP 工具");
        }
        CodeRepositoryId repository =
                CodeRepositoryId.of(UUID.fromString(input.path("repositoryId").asText()));
        access.require(actor, repository, RepositoryPermission.READ);
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("检索词不能为空");
        }
        int limit = Math.max(1, Math.min(input.path("limit").asInt(20), 50));
        return json.valueToTree(
                intelligence.unifiedSearchDetailed(repository.value(), query, limit));
    }
}
