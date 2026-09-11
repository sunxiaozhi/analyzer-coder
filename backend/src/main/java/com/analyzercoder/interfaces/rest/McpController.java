package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.mcp.McpToolCatalog;
import com.analyzercoder.application.mcp.McpToolService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.SecurityContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 无状态 Streamable HTTP JSON 响应模式；每次请求通过账户令牌认证。 */
@RestController
public class McpController {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(McpController.class);
    private static final String VERSION = "2025-11-25";
    private static final List<String> VERSIONS = List.of("2025-03-26", "2025-06-18", VERSION);
    private final McpToolCatalog catalog;
    private final McpToolService tools;
    private final ObjectMapper json;

    public McpController(McpToolCatalog catalog, McpToolService tools, ObjectMapper json) {
        this.catalog = catalog;
        this.tools = tools;
        this.json = json;
    }

    @GetMapping("/api/mcp")
    public ResponseEntity<Void> noEventStream() {
        return ResponseEntity.status(405).header("Allow", "POST").build();
    }

    @PostMapping(value = "/api/mcp", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> message(@RequestBody JsonNode body, HttpServletRequest request) {
        JsonNode id = body.get("id");
        String version = request.getHeader("MCP-Protocol-Version");
        if (version != null && !VERSIONS.contains(version))
            return ResponseEntity.badRequest()
                    .body(error(id, -32600, "Unsupported MCP protocol version"));
        if (!body.isObject()
                || !body.path("jsonrpc").asText().equals("2.0")
                || !body.path("method").isTextual()
                || (id != null && !id.isTextual() && !id.isIntegralNumber()))
            return ResponseEntity.badRequest().body(error(null, -32600, "Invalid Request"));
        String method = body.path("method").asText();
        if (id == null) {
            if (method.startsWith("notifications/")) return ResponseEntity.accepted().build();
            return ResponseEntity.badRequest().body(error(null, -32600, "Requests require an id"));
        }
        Object result;
        switch (method) {
            case "initialize" -> {
                String requested = body.path("params").path("protocolVersion").asText();
                result =
                        Map.of(
                                "protocolVersion",
                                VERSIONS.contains(requested) ? requested : VERSION,
                                "capabilities",
                                Map.of("tools", Map.of("listChanged", false)),
                                "serverInfo",
                                Map.of("name", "analyzer-coder", "version", "1.0.0"),
                                "instructions",
                                "Use search_project to retrieve current code and published project knowledge with versioned evidence. Every call uses the token account's current repository permissions.");
            }
            case "ping" -> result = Map.of();
            case "tools/list" -> result = Map.of("tools", catalog.tools());
            case "tools/call" -> {
                String name = body.path("params").path("name").asText();
                JsonNode arguments = body.path("params").path("arguments");
                if (!catalog.contains(name))
                    return ResponseEntity.ok(error(id, -32602, "Unknown tool"));
                try {
                    catalog.validate(name, arguments);
                    JsonNode data =
                            tools.call(
                                    name,
                                    arguments,
                                    SecurityContext.account(request),
                                    request.getRemoteAddr());
                    result =
                            Map.of(
                                    "content",
                                    List.of(Map.of("type", "text", "text", data.toString())),
                                    "structuredContent",
                                    data,
                                    "isError",
                                    false);
                } catch (ApiSecurityException | IllegalArgumentException failure) {
                    result =
                            Map.of(
                                    "content",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    failure.getMessage() == null
                                                            ? "工具调用失败"
                                                            : failure.getMessage())),
                                    "isError",
                                    true);
                } catch (RuntimeException failure) {
                    // Do not expose database, filesystem, or secret-bearing exception messages to
                    // clients.
                    LOG.error("MCP tool execution failed: {}", name, failure);
                    result =
                            Map.of(
                                    "content",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    "MCP_INTERNAL_ERROR: 工具执行失败，请查看服务端日志")),
                                    "isError",
                                    true);
                }
            }
            default -> {
                return ResponseEntity.ok(error(id, -32601, "Method not found"));
            }
        }
        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", json.valueToTree(result));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedJson() {
        return ResponseEntity.badRequest().body(error(null, -32700, "Parse error"));
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode result = json.createObjectNode().put("jsonrpc", "2.0");
        result.set("id", id);
        result.putObject("error").put("code", code).put("message", message);
        return result;
    }
}
