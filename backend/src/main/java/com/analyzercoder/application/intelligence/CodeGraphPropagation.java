package com.analyzercoder.application.intelligence;

import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 由同一 CodeGraph 产物的 impact 与 export 输出重建真实传播边和完整最短路径。 */
public record CodeGraphPropagation(
        List<Node> nodes,
        List<Edge> edges,
        List<PropagationPath> paths,
        String relationSource,
        UUID graphArtifactId,
        UUID snapshotId,
        String cliVersion,
        int affectedNodeCount,
        int maxDepthReached,
        Coverage coverage,
        List<String> limitations) {
    public CodeGraphPropagation {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        paths = List.copyOf(paths);
        limitations = List.copyOf(limitations);
    }

    public static CodeGraphPropagation fromCli(
            ObjectMapper json,
            String impactOutput,
            String exportOutput,
            String requestedSymbol,
            int requestedDepth,
            CodeGraphService.Artifact artifact) {
        try {
            JsonNode impact = json.readTree(impactOutput);
            JsonNode exported = json.readTree(exportOutput);
            return assemble(impact, exported, requestedSymbol, requestedDepth, artifact);
        } catch (IOException exception) {
            throw new CodeGraphException(
                    "CODEGRAPH_CLI_OUTPUT_INVALID", "CodeGraph 返回了无法解析的 JSON", exception);
        }
    }

    private static CodeGraphPropagation assemble(
            JsonNode impact,
            JsonNode exported,
            String requestedSymbol,
            int requestedDepth,
            CodeGraphService.Artifact artifact) {
        if (!impact.isObject() || !impact.path("affected").isArray()) {
            throw new CodeGraphException(
                    "CODEGRAPH_IMPACT_SCHEMA_UNSUPPORTED",
                    "当前 CodeGraph impact 输出不包含结构化 affected 列表");
        }
        JsonNode exportedNodes = exported.path("nodes");
        JsonNode exportedEdges =
                exported.path("edges").isArray() ? exported.path("edges") : exported.path("links");
        if (!exportedNodes.isArray() || !exportedEdges.isArray()) {
            throw new CodeGraphException(
                    "CODEGRAPH_EXPORT_SCHEMA_UNSUPPORTED",
                    "当前 CodeGraph export 输出不包含 nodes 与 edges");
        }

        Map<String, RawNode> nodeById = parseNodes(exportedNodes);
        List<RawEdge> allEdges = parseEdges(exportedEdges, nodeById.keySet());
        List<String> focusIds =
                nodeById.values().stream()
                        .filter(node -> node.matchesSymbol(requestedSymbol))
                        .map(RawNode::id)
                        .sorted()
                        .toList();
        if (focusIds.isEmpty()) {
            throw new CodeGraphException(
                    "CODEGRAPH_SYMBOL_NOT_FOUND",
                    "CodeGraph export 中找不到可定位的精确符号：" + requestedSymbol);
        }

        List<AffectedSelector> affected = new ArrayList<>();
        impact.path("affected").forEach(item -> affected.add(AffectedSelector.from(item)));
        Set<String> allowedIds = new LinkedHashSet<>(focusIds);
        List<Set<String>> matchesByAffected = new ArrayList<>();
        for (AffectedSelector selector : affected) {
            Set<String> matches = new LinkedHashSet<>();
            for (RawNode node : nodeById.values()) {
                if (selector.matches(node)) {
                    matches.add(node.id());
                    allowedIds.add(node.id());
                }
            }
            matchesByAffected.add(matches);
        }

        int boundedDepth = Math.max(1, Math.min(requestedDepth, 5));
        Traversal traversal = traverse(focusIds, allowedIds, allEdges, boundedDepth);
        List<Node> nodes =
                traversal.depthByNode().entrySet().stream()
                        .sorted(
                                Map.Entry.<String, Integer>comparingByValue()
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .map(
                                entry ->
                                        nodeById
                                                .get(entry.getKey())
                                                .toNode(entry.getValue(), focusIds.contains(entry.getKey())))
                        .toList();
        Set<String> representedIds = traversal.depthByNode().keySet();
        List<Edge> edges =
                allEdges.stream()
                        .filter(
                                edge ->
                                        representedIds.contains(edge.source())
                                                && representedIds.contains(edge.target()))
                        .map(RawEdge::toEdge)
                        .sorted(
                                Comparator.comparing(Edge::source)
                                        .thenComparing(Edge::target)
                                        .thenComparing(Edge::relation)
                                        .thenComparing(Edge::id))
                        .toList();
        List<PropagationPath> paths = buildPaths(traversal, focusIds);

        int representedAffected =
                (int)
                        matchesByAffected.stream()
                                .filter(
                                        matches ->
                                                matches.stream().anyMatch(representedIds::contains))
                                .count();
        int unmappedAffected = Math.max(0, affected.size() - representedAffected);
        int reportedNodes = impact.path("nodeCount").asInt(0);
        int reportedEdges = impact.path("edgeCount").asInt(0);
        boolean nodeCountConsistent = reportedNodes <= 0 || reportedNodes == nodes.size();
        boolean edgeCountConsistent = reportedEdges <= 0 || reportedEdges == edges.size();
        boolean complete = unmappedAffected == 0 && nodeCountConsistent && edgeCountConsistent;
        Coverage coverage =
                new Coverage(
                        reportedNodes,
                        reportedEdges,
                        nodes.size(),
                        edges.size(),
                        affected.size(),
                        representedAffected,
                        unmappedAffected,
                        complete);

        List<String> limitations = new ArrayList<>();
        limitations.add("CODEGRAPH_STATIC_ANALYSIS_ONLY");
        if (focusIds.size() > 1) {
            limitations.add("CODEGRAPH_DUPLICATE_SYMBOL_DEFINITIONS:" + focusIds.size());
        }
        if (unmappedAffected > 0) {
            limitations.add("CODEGRAPH_AFFECTED_NODE_UNMAPPED:" + unmappedAffected);
        }
        if (reportedNodes > 0 && reportedNodes != nodes.size()) {
            limitations.add(
                    "CODEGRAPH_NODE_COUNT_MISMATCH:reported="
                            + reportedNodes
                            + ",represented="
                            + nodes.size());
        }
        if (reportedEdges > 0 && reportedEdges != edges.size()) {
            limitations.add(
                    "CODEGRAPH_EDGE_COUNT_MISMATCH:reported="
                            + reportedEdges
                            + ",represented="
                            + edges.size());
        }
        if (impact.path("godotDynamic").asBoolean(false)) {
            limitations.add("CODEGRAPH_DYNAMIC_RESOURCE_REFERENCES_PRESENT");
        }

        return new CodeGraphPropagation(
                nodes,
                edges,
                paths,
                "CODEGRAPH_CLI",
                artifact.id(),
                artifact.snapshotId(),
                artifact.cliVersion(),
                Math.max(0, nodes.size() - focusIds.size()),
                traversal.depthByNode().values().stream().mapToInt(Integer::intValue).max().orElse(0),
                coverage,
                limitations);
    }

    private static Map<String, RawNode> parseNodes(JsonNode input) {
        Map<String, RawNode> nodes = new LinkedHashMap<>();
        for (JsonNode item : input) {
            String id = text(item, "id");
            String symbol = firstText(item, "label", "qualified_name", "name");
            String filePath = safePath(firstText(item, "source_file", "file_path", "filePath"));
            if (id.isBlank() || symbol.isBlank() || filePath == null) {
                continue;
            }
            Integer startLine = positiveInteger(item, "start_line", "startLine", "line");
            Integer endLine = positiveInteger(item, "end_line", "endLine");
            nodes.put(
                    id,
                    new RawNode(
                            id,
                            symbol,
                            firstText(item, "qualified_name"),
                            firstText(item, "kind", "file_type"),
                            filePath,
                            startLine,
                            endLine));
        }
        return nodes;
    }

    private static List<RawEdge> parseEdges(JsonNode input, Set<String> knownNodeIds) {
        List<RawEdge> edges = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : input) {
            String source = text(item, "source");
            String target = text(item, "target");
            String relation = firstText(item, "relation", "kind");
            Integer line = positiveInteger(item, "line");
            if (!knownNodeIds.contains(source)
                    || !knownNodeIds.contains(target)
                    || relation.isBlank()
                    || "contains".equalsIgnoreCase(relation)) {
                continue;
            }
            String identity = source + "\u0000" + target + "\u0000" + relation + "\u0000" + line;
            if (unique.add(identity)) {
                String id =
                        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
                edges.add(new RawEdge(id, source, target, relation, line));
            }
        }
        return edges;
    }

    private static Traversal traverse(
            List<String> focusIds, Set<String> allowedIds, List<RawEdge> edges, int maximumDepth) {
        Map<String, List<RawEdge>> incomingByTarget = new HashMap<>();
        for (RawEdge edge : edges) {
            if (allowedIds.contains(edge.source()) && allowedIds.contains(edge.target())) {
                incomingByTarget.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
            }
        }
        incomingByTarget
                .values()
                .forEach(
                        incoming ->
                                incoming.sort(
                                        Comparator.comparing(RawEdge::source)
                                                .thenComparing(RawEdge::relation)
                                                .thenComparing(RawEdge::id)));

        Map<String, Integer> depthByNode = new LinkedHashMap<>();
        Map<String, RawEdge> towardFocusByNode = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String focusId : focusIds) {
            depthByNode.put(focusId, 0);
            queue.add(focusId);
        }
        while (!queue.isEmpty()) {
            String target = queue.removeFirst();
            int nextDepth = depthByNode.get(target) + 1;
            if (nextDepth > maximumDepth) {
                continue;
            }
            for (RawEdge edge : incomingByTarget.getOrDefault(target, List.of())) {
                if (!depthByNode.containsKey(edge.source())) {
                    depthByNode.put(edge.source(), nextDepth);
                    towardFocusByNode.put(edge.source(), edge);
                    queue.addLast(edge.source());
                }
            }
        }
        return new Traversal(depthByNode, towardFocusByNode);
    }

    private static List<PropagationPath> buildPaths(Traversal traversal, List<String> focusIds) {
        Set<String> focuses = Set.copyOf(focusIds);
        List<PropagationPath> paths = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : traversal.depthByNode().entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            List<String> reversedNodes = new ArrayList<>();
            List<String> reversedEdges = new ArrayList<>();
            String current = entry.getKey();
            reversedNodes.add(current);
            while (!focuses.contains(current)) {
                RawEdge edge = traversal.towardFocusByNode().get(current);
                if (edge == null) {
                    break;
                }
                reversedEdges.add(edge.id());
                current = edge.target();
                reversedNodes.add(current);
            }
            if (!focuses.contains(current)) {
                continue;
            }
            java.util.Collections.reverse(reversedNodes);
            java.util.Collections.reverse(reversedEdges);
            paths.add(
                    new PropagationPath(
                            entry.getKey(),
                            List.copyOf(reversedNodes),
                            List.copyOf(reversedEdges),
                            entry.getValue()));
        }
        paths.sort(
                Comparator.comparingInt(PropagationPath::depth)
                        .thenComparing(PropagationPath::targetNodeId));
        return paths;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText("").trim() : "";
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Integer positiveInteger(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToInt() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private static String safePath(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        try {
            return RepositoryGlobMatcher.normalizeRepositoryPath(requested);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record Node(
            String id,
            String symbol,
            String kind,
            String filePath,
            Integer startLine,
            Integer endLine,
            int depth,
            boolean focus) {}

    public record Edge(
            String id, String source, String target, String relation, Integer sourceLine) {}

    /** nodeIds 按“变更焦点到受影响节点”排序；edgeIds 保留 CLI 的依赖方向。 */
    public record PropagationPath(
            String targetNodeId, List<String> nodeIds, List<String> edgeIds, int depth) {
        public PropagationPath {
            nodeIds = List.copyOf(nodeIds);
            edgeIds = List.copyOf(edgeIds);
        }
    }

    public record Coverage(
            int cliReportedNodeCount,
            int cliReportedEdgeCount,
            int representedNodeCount,
            int representedEdgeCount,
            int affectedRecordCount,
            int representedAffectedRecordCount,
            int unmappedAffectedRecordCount,
            boolean complete) {}

    private record RawNode(
            String id,
            String symbol,
            String qualifiedName,
            String kind,
            String filePath,
            Integer startLine,
            Integer endLine) {
        boolean matchesSymbol(String requested) {
            return symbol.equals(requested)
                    || (!qualifiedName.isBlank() && qualifiedName.equals(requested));
        }

        Node toNode(int depth, boolean focus) {
            return new Node(id, symbol, kind, filePath, startLine, endLine, depth, focus);
        }
    }

    private record RawEdge(
            String id, String source, String target, String relation, Integer sourceLine) {
        Edge toEdge() {
            return new Edge(id, source, target, relation, sourceLine);
        }
    }

    private record AffectedSelector(String name, String filePath, Integer startLine) {
        static AffectedSelector from(JsonNode node) {
            if (node.isTextual()) {
                return new AffectedSelector(node.asText().trim(), null, null);
            }
            return new AffectedSelector(
                    firstText(node, "name", "symbol", "label", "qualifiedName"),
                    safePath(firstText(node, "filePath", "file_path", "source_file", "file")),
                    positiveInteger(node, "startLine", "start_line", "line"));
        }

        boolean matches(RawNode node) {
            if (name.isBlank() && filePath == null) {
                return false;
            }
            if (!name.isBlank()
                    && !node.symbol().equals(name)
                    && !node.qualifiedName().equals(name)) {
                return false;
            }
            if (filePath != null && !node.filePath().equals(filePath)) {
                return false;
            }
            return startLine == null || startLine.equals(node.startLine());
        }
    }

    private record Traversal(
            Map<String, Integer> depthByNode, Map<String, RawEdge> towardFocusByNode) {}
}
