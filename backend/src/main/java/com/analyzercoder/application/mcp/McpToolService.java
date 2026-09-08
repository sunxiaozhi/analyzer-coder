package com.analyzercoder.application.mcp;

import com.analyzercoder.application.memory.TaskContextService;
import com.analyzercoder.application.outcome.TaskReviewOutcomeService;
import com.analyzercoder.application.review.TaskReviewRequest;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 远程工具复用现有业务服务和实时仓库权限，不保存任何全局用户身份。 */
@Service
public class McpToolService {
    private final AccessControlService access;
    private final TaskReviewService reviews;
    private final TaskContextService contexts;
    private final TaskReviewOutcomeService outcomes;
    private final ObjectMapper json;

    public McpToolService(
            AccessControlService access,
            TaskReviewService reviews,
            TaskContextService contexts,
            TaskReviewOutcomeService outcomes,
            ObjectMapper json) {
        this.access = access;
        this.reviews = reviews;
        this.contexts = contexts;
        this.outcomes = outcomes;
        this.json = json;
    }

    public JsonNode call(String name, JsonNode input, AuthenticatedAccount actor, String ip) {
        CodeRepositoryId repository =
                CodeRepositoryId.of(UUID.fromString(input.path("repositoryId").asText()));
        access.require(actor, repository, RepositoryPermission.READ);
        if (name.equals("get_task_context")) {
            JsonNode context = context(repository, input, false);
            return input.path("includeContent").asBoolean() ? context : compactContext(context);
        }
        if (name.equals("review_change")) {
            ObjectNode body =
                    pick(
                            input,
                            "clientRequestId",
                            "task",
                            "changeSource",
                            "baseRef",
                            "headRef",
                            "modelConfigId");
            if (!body.hasNonNull("clientRequestId"))
                body.put("clientRequestId", UUID.randomUUID().toString());
            JsonNode result =
                    json.valueToTree(
                            reviews.create(
                                    repository,
                                    actor.id(),
                                    json.convertValue(body, TaskReviewRequest.class)));
            return input.path("includeEvidence").asBoolean() ? result : compactReview(result);
        }
        UUID reviewId = UUID.fromString(input.path("reviewId").asText());
        if (name.equals("report_task_outcome")) {
            ObjectNode body =
                    pick(
                            input,
                            "clientRequestId",
                            "finalCommit",
                            "summary",
                            "tests",
                            "approvals",
                            "feedback");
            if (!body.hasNonNull("clientRequestId"))
                body.put("clientRequestId", UUID.randomUUID().toString());
            return json.valueToTree(
                    outcomes.report(
                            repository,
                            reviewId,
                            actor,
                            json.convertValue(body, TaskReviewOutcomeService.OutcomeRequest.class),
                            ip));
        }
        // The persisted review lookup is scoped by repositoryId as well as reviewId.
        JsonNode review = json.valueToTree(reviews.get(repository, reviewId));
        ObjectNode result = json.createObjectNode().put("reviewId", reviewId.toString());
        boolean full = input.path("includeEvidence").asBoolean();
        switch (name) {
            case "get_rules_for_symbol" -> {
                String symbol = input.path("symbol").asText();
                result.put("symbol", symbol);
                ArrayNode rules = result.putArray("rules");
                for (JsonNode match : review.path("applicableKnowledge")) {
                    for (JsonNode reason : match.path("reasons")) {
                        if (symbol.equals(reason.path("target").asText())
                                || symbol.equals(reason.path("rule").asText())
                                || symbol.equals(
                                        reason.path("evidence").path("symbolName").asText())) {
                            rules.add(full ? match : compactKnowledge(match));
                            break;
                        }
                    }
                }
            }
            case "get_required_tests" -> {
                ArrayNode tests = result.putArray("tests");
                for (JsonNode item : review.path("requiredTests")) {
                    ObjectNode compact = pick(item, "key", "title", "status", "knowledgeIds");
                    compact.set("sourceIds", sourceIds(item));
                    tests.add(full ? item : compact);
                }
            }
            case "get_stale_knowledge" -> {
                ArrayNode stale = result.putArray("staleKnowledge");
                for (JsonNode item : review.path("staleKnowledge"))
                    stale.add(full ? item : compactKnowledge(item));
            }
            case "get_evidence" -> {
                ArrayNode sources = json.createArrayNode();
                for (String field :
                        List.of(
                                "applicableKnowledge",
                                "requiredTests",
                                "requiredApprovals",
                                "staleKnowledge",
                                "unknowns"))
                    for (JsonNode item : review.path(field))
                        for (JsonNode source : item.path("sources")) sources.add(source);
                for (JsonNode item : review.path("referenceCandidates"))
                    if (item.hasNonNull("provenance")) sources.add(item.get("provenance"));
                if (input.hasNonNull("task"))
                    for (JsonNode entry : context(repository, input, true).path("entries"))
                        for (JsonNode source : entry.path("sources")) sources.add(source);
                for (JsonNode source : sources)
                    if (source.path("id").asText().equals(input.path("evidenceId").asText())) {
                        result.set("evidence", source);
                        return result;
                    }
                throw new IllegalArgumentException("EVIDENCE_NOT_FOUND: 当前仓库审查中不存在该证据");
            }
            default -> throw new IllegalArgumentException("未知 MCP 工具");
        }
        return result;
    }

    private JsonNode context(CodeRepositoryId repository, JsonNode input, boolean evidence) {
        String review = input.path(evidence ? "reviewId" : "taskReviewId").asText(null);
        return json.valueToTree(
                contexts.generate(
                        repository,
                        input.path("task").asText(),
                        review == null ? null : UUID.fromString(review),
                        evidence ? 40 : input.path("maxItems").asInt(12),
                        evidence ? 60000 : input.path("maxChars").asInt(12000),
                        evidence ? 15000 : input.path("maxTokens").asInt(3000)));
    }

    private ObjectNode compactKnowledge(JsonNode item) {
        ObjectNode result =
                pick(
                        item,
                        "knowledgeId",
                        "title",
                        "kind",
                        "severity",
                        "enforcement",
                        "revision",
                        "sourceVersionStatus");
        result.set("sourceIds", sourceIds(item));
        return result;
    }

    private ArrayNode sourceIds(JsonNode item) {
        ArrayNode ids = json.createArrayNode();
        for (JsonNode source : item.path("sources")) ids.add(source.path("id"));
        return ids;
    }

    private ObjectNode compactContext(JsonNode context) {
        ObjectNode result =
                pick(
                        context,
                        "repositoryId",
                        "repositoryName",
                        "snapshotId",
                        "commitSha",
                        "task",
                        "taskReviewId",
                        "requiredTests",
                        "requiredApprovals",
                        "budget");
        ArrayNode entries = result.putArray("entries");
        for (JsonNode entry : context.path("entries")) {
            ObjectNode item =
                    pick(
                            entry,
                            "id",
                            "type",
                            "title",
                            "severity",
                            "enforcement",
                            "knowledgeId",
                            "knowledgeRevision",
                            "filePath",
                            "symbolName",
                            "startLine",
                            "endLine",
                            "requiredTests",
                            "requiredApproverAccountIds",
                            "unknownCode");
            item.set("sourceIds", sourceIds(entry));
            entries.add(item);
        }
        ArrayNode unknowns = result.putArray("unknowns");
        for (JsonNode item : context.path("unknowns")) unknowns.add(pick(item, "code", "detail"));
        return result;
    }

    private ObjectNode compactReview(JsonNode review) {
        ObjectNode result =
                pick(
                        review,
                        "reviewId",
                        "status",
                        "repositoryId",
                        "snapshotId",
                        "task",
                        "changeSource");
        result.set("baseCommit", review.path("change").path("baseCommit"));
        result.set("headCommit", review.path("change").path("headCommit"));
        ArrayNode files = result.putArray("changedFiles");
        for (JsonNode change : review.path("change").path("changes"))
            files.add(
                    change.hasNonNull("newPath") ? change.get("newPath") : change.path("oldPath"));
        ArrayNode symbols = result.putArray("changedSymbols");
        for (JsonNode symbol : review.path("changedSymbols")) {
            ObjectNode item = pick(symbol, "name", "kind", "filePath");
            item.set("startLine", symbol.path("declarationStartLine"));
            item.set("endLine", symbol.path("declarationEndLine"));
            symbols.add(item);
        }
        for (String field : List.of("applicableKnowledge", "staleKnowledge")) {
            ArrayNode items = result.putArray(field);
            for (JsonNode item : review.path(field)) items.add(compactKnowledge(item));
        }
        for (String field : List.of("requiredTests", "requiredApprovals", "unknowns")) {
            ArrayNode items = result.putArray(field);
            for (JsonNode item : review.path(field))
                items.add(item.path(field.equals("unknowns") ? "unknownReason" : "key"));
        }
        return result;
    }

    private ObjectNode pick(JsonNode source, String... fields) {
        ObjectNode result = json.createObjectNode();
        for (String field : fields) if (source.has(field)) result.set(field, source.get(field));
        return result;
    }
}
