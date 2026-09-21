package com.analyzercoder.application.intelligence;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Bounded, contentVersion-bound projection of the published graph for interactive exploration. */
public final class CodeGraphExplorer {
    private CodeGraphExplorer() {}

    public record Node(
            String id,
            String label,
            String kind,
            String filePath,
            int startLine,
            int endLine,
            String module,
            int count) {}

    public record Edge(String source, String target, String kind, int count) {}

    public record View(
            UUID repositoryId,
            UUID contentVersion,
            String level,
            List<Node> nodes,
            List<Edge> edges,
            int totalNodes,
            int totalEdges,
            boolean partial) {}

    static View project(
            JsonNode graph, UUID repositoryId, UUID contentVersion, String module, String query) {
        Map<String, Node> symbols = new LinkedHashMap<>();
        Map<String, Integer> groups = new TreeMap<>();
        for (JsonNode row : graph.path("nodes")) {
            String path = row.path("source_file").asText("").replace('\\', '/');
            if (path.isBlank()) continue;
            String group = ProjectArchitectureMapService.moduleForPath(path);
            Node node =
                    new Node(
                            row.path("id").asText(),
                            row.path("label").asText(),
                            row.path("kind").asText(),
                            path,
                            row.path("start_line").asInt(),
                            row.path("end_line").asInt(),
                            group,
                            1);
            symbols.put(node.id(), node);
            groups.merge(group, 1, Integer::sum);
        }
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean overview = (module == null || module.isBlank()) && term.isEmpty();
        List<Node> candidates =
                overview
                        ? groups.entrySet().stream()
                                .map(
                                        e ->
                                                new Node(
                                                        e.getKey(),
                                                        e.getKey(),
                                                        "MODULE",
                                                        "",
                                                        0,
                                                        0,
                                                        e.getKey(),
                                                        e.getValue()))
                                .toList()
                        : symbols.values().stream()
                                .filter(
                                        n ->
                                                module == null
                                                        || module.isBlank()
                                                        || module.equals(n.module()))
                                .filter(
                                        n ->
                                                term.isEmpty()
                                                        || (n.label() + " " + n.filePath())
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(term))
                                .toList();
        List<Node> nodes = candidates.stream().limit(overview ? 120 : 240).toList();
        Set<String> visible = new HashSet<>();
        nodes.forEach(n -> visible.add(n.id()));
        Map<List<String>, Integer> links = new LinkedHashMap<>();
        for (JsonNode row : graph.path("edges")) {
            Node source = symbols.get(row.path("source").asText());
            Node target = symbols.get(row.path("target").asText());
            if (source == null || target == null) continue;
            String a = overview ? source.module() : source.id();
            String b = overview ? target.module() : target.id();
            if (overview && a.equals(b)) continue;
            if (!visible.contains(a) || !visible.contains(b)) continue;
            links.merge(List.of(a, b, row.path("relation").asText()), 1, Integer::sum);
        }
        List<Edge> edges =
                links.entrySet().stream()
                        .limit(1200)
                        .map(
                                e ->
                                        new Edge(
                                                e.getKey().get(0),
                                                e.getKey().get(1),
                                                e.getKey().get(2),
                                                e.getValue()))
                        .toList();
        return new View(
                repositoryId,
                contentVersion,
                overview ? "MODULE" : "SYMBOL",
                nodes,
                edges,
                candidates.size(),
                links.size(),
                candidates.size() > nodes.size() || links.size() > edges.size());
    }
}
