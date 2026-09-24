package com.analyzercoder.application.intelligence;

import com.analyzercoder.application.architecture.ProjectArchitectureMapService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Content-version-bound symbol graph with bounded, directional neighborhood expansion. */
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
            int count,
            String qualifiedName,
            int incomingCount,
            int outgoingCount,
            int hiddenNeighborCount,
            int neighborCount) {}

    public record Edge(
            String source,
            String target,
            String kind,
            int count,
            List<Integer> sourceLines,
            boolean sourceLinesTruncated) {}

    public record View(
            UUID repositoryId,
            UUID contentVersion,
            String level,
            List<Node> nodes,
            List<Edge> edges,
            int totalNodes,
            int totalEdges,
            boolean partial,
            int repositoryNodes,
            int repositoryEdges,
            int unmappedNodes,
            List<String> relationKinds) {}

    public record Options(String focusId, String direction, int depth, int limit) {
        public Options {
            focusId = focusId == null ? "" : focusId;
            direction = direction == null ? "both" : direction;
            if (!Set.of("both", "in", "out").contains(direction))
                throw new IllegalArgumentException("关系方向必须为 both、in 或 out");
            if (depth < 1 || depth > 3 || limit < 1 || limit > 1200 || focusId.length() > 500)
                throw new IllegalArgumentException("展开深度为 1–3，节点数量为 1–1200");
        }

        public static Options defaults() {
            return new Options("", "both", 1, 240);
        }
    }

    private static final class Relation {
        final String source, target, kind;
        int count;
        final Set<Integer> lines = new TreeSet<>();
        boolean truncated;

        Relation(String source, String target, String kind) {
            this.source = source;
            this.target = target;
            this.kind = kind;
        }

        Edge edge() {
            return new Edge(source, target, kind, count, List.copyOf(lines), truncated);
        }
    }

    static View project(
            JsonNode graph, UUID repositoryId, UUID contentVersion, String module, String query) {
        return project(graph, repositoryId, contentVersion, module, query, Options.defaults());
    }

    static View project(
            JsonNode graph,
            UUID repositoryId,
            UUID contentVersion,
            String module,
            String query,
            Options options) {
        Map<String, Node> symbols = new LinkedHashMap<>();
        for (JsonNode row : graph.path("nodes")) {
            String path = row.path("source_file").asText("").replace('\\', '/');
            String id = row.path("id").asText("");
            if (path.isBlank() || id.isBlank()) continue;
            symbols.put(
                    id,
                    new Node(
                            id,
                            row.path("label").asText(),
                            row.path("kind").asText(),
                            path,
                            row.path("start_line").asInt(),
                            row.path("end_line").asInt(),
                            ProjectArchitectureMapService.moduleForPath(path),
                            1,
                            row.path("qualified_name").asText(row.path("label").asText()),
                            0,
                            0,
                            0,
                            0));
        }
        Map<List<String>, Relation> relations = new LinkedHashMap<>();
        for (JsonNode row : graph.path("edges")) {
            String source = row.path("source").asText(), target = row.path("target").asText();
            if (!symbols.containsKey(source) || !symbols.containsKey(target)) continue;
            String kind = row.path("relation").asText();
            Relation relation =
                    relations.computeIfAbsent(
                            List.of(source, target, kind),
                            key -> new Relation(source, target, kind));
            relation.count++;
            int line = row.path("line").asInt();
            if (line > 0 && !relation.lines.contains(line)) {
                if (relation.lines.size() < 40) relation.lines.add(line);
                else relation.truncated = true;
            }
        }
        Map<String, Set<String>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        Map<String, Integer> inCounts = new HashMap<>(), outCounts = new HashMap<>();
        Set<String> kinds = new TreeSet<>();
        relations
                .values()
                .forEach(
                        r -> {
                            outgoing.computeIfAbsent(r.source, key -> new LinkedHashSet<>())
                                    .add(r.target);
                            incoming.computeIfAbsent(r.target, key -> new LinkedHashSet<>())
                                    .add(r.source);
                            outCounts.merge(r.source, 1, Integer::sum);
                            inCounts.merge(r.target, 1, Integer::sum);
                            kinds.add(r.kind);
                        });
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Node> matches =
                symbols.values().stream()
                        .filter(
                                n ->
                                        module == null
                                                || module.isBlank()
                                                || module.equals(n.module()))
                        .filter(
                                n ->
                                        term.isEmpty()
                                                || (n.label()
                                                                + " "
                                                                + n.qualifiedName()
                                                                + " "
                                                                + n.filePath())
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(term))
                        .sorted(
                                Comparator.comparing(Node::filePath)
                                        .thenComparingInt(Node::startLine)
                                        .thenComparing(Node::id))
                        .toList();
        // Round-robin modules so the initial bounded scene does not contain only the first
        // directory.
        Map<String, List<Node>> groups = new TreeMap<>();
        matches.forEach(n -> groups.computeIfAbsent(n.module(), key -> new ArrayList<>()).add(n));
        LinkedHashSet<String> scope = new LinkedHashSet<>();
        if (!options.focusId().isBlank()) {
            if (symbols.containsKey(options.focusId())) scope.add(options.focusId());
        } else {
            int largest = groups.values().stream().mapToInt(List::size).max().orElse(0);
            for (int i = 0; i < largest; i++)
                for (List<Node> group : groups.values())
                    if (i < group.size()) scope.add(group.get(i).id());
        }
        if (!options.focusId().isBlank()
                || !term.isBlank()
                || (module != null && !module.isBlank())) {
            Set<String> frontier = new LinkedHashSet<>(scope);
            for (int hop = 0; hop < options.depth() && !frontier.isEmpty(); hop++) {
                Set<String> next = new LinkedHashSet<>();
                for (String id : frontier) {
                    if (!options.direction().equals("in"))
                        next.addAll(outgoing.getOrDefault(id, Set.of()));
                    if (!options.direction().equals("out"))
                        next.addAll(incoming.getOrDefault(id, Set.of()));
                }
                next.removeAll(scope);
                scope.addAll(next);
                frontier = next;
            }
        }
        Set<String> visible = new LinkedHashSet<>(scope.stream().limit(options.limit()).toList());
        List<Node> nodes =
                visible.stream()
                        .map(symbols::get)
                        .map(
                                n -> {
                                    Set<String> neighbors =
                                            new LinkedHashSet<>(
                                                    incoming.getOrDefault(n.id(), Set.of()));
                                    neighbors.addAll(outgoing.getOrDefault(n.id(), Set.of()));
                                    neighbors.remove(n.id());
                                    int neighborCount = neighbors.size();
                                    neighbors.removeAll(visible);
                                    return new Node(
                                            n.id(),
                                            n.label(),
                                            n.kind(),
                                            n.filePath(),
                                            n.startLine(),
                                            n.endLine(),
                                            n.module(),
                                            1,
                                            n.qualifiedName(),
                                            inCounts.getOrDefault(n.id(), 0),
                                            outCounts.getOrDefault(n.id(), 0),
                                            neighbors.size(),
                                            neighborCount);
                                })
                        .toList();
        List<Relation> scopedEdges =
                relations.values().stream()
                        .filter(r -> scope.contains(r.source) && scope.contains(r.target))
                        .toList();
        // Selected relations come first when a dense graph reaches the edge budget.
        List<Edge> edges =
                scopedEdges.stream()
                        .filter(r -> visible.contains(r.source) && visible.contains(r.target))
                        .sorted(
                                Comparator.comparingInt(
                                        r ->
                                                r.source.equals(options.focusId())
                                                                || r.target.equals(
                                                                        options.focusId())
                                                        ? 0
                                                        : 1))
                        .limit(6000)
                        .map(Relation::edge)
                        .toList();
        return new View(
                repositoryId,
                contentVersion,
                "SYMBOL",
                nodes,
                edges,
                scope.size(),
                scopedEdges.size(),
                scope.size() > nodes.size() || scopedEdges.size() > edges.size(),
                symbols.size(),
                relations.size(),
                graph.path("nodes").size() - symbols.size(),
                List.copyOf(kinds));
    }
}
