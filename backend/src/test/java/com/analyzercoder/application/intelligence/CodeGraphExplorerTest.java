package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeGraphExplorerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aggregatesOnlyRealCrossModuleEdgesAndRetainsSnapshot() throws Exception {
        var graph =
                json.readTree(
                        """
                {"nodes":[
                  {"id":"a","label":"entry","kind":"function","source_file":"frontend/src/a.ts"},
                  {"id":"b","label":"helper","kind":"function","source_file":"frontend/src/b.ts"},
                  {"id":"c","label":"save","kind":"method","source_file":"backend/src/c.java"}],
                 "edges":[{"source":"a","target":"b","relation":"calls"},
                          {"source":"a","target":"c","relation":"calls"},
                          {"source":"b","target":"c","relation":"calls"},
                          {"source":"a","target":"missing","relation":"calls"}]}
                """);
        var snapshot = UUID.randomUUID();
        var result = CodeGraphExplorer.project(graph, UUID.randomUUID(), snapshot, "", "");
        assertThat(result.snapshotId()).isEqualTo(snapshot);
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.edges())
                .containsExactly(new CodeGraphExplorer.Edge("frontend", "backend", "calls", 2));
        var symbols = CodeGraphExplorer.project(graph, UUID.randomUUID(), snapshot, "frontend", "");
        assertThat(symbols.nodes()).hasSize(2);
        assertThat(symbols.edges())
                .containsExactly(new CodeGraphExplorer.Edge("a", "b", "calls", 1));
        assertThat(
                        CodeGraphExplorer.project(
                                        graph, UUID.randomUUID(), snapshot, "frontend", "SAVE")
                                .nodes())
                .isEmpty();
    }

    @Test
    void boundsSymbolsAndNeverReturnsDanglingEdges() {
        var graph = json.createObjectNode();
        var nodes = graph.putArray("nodes");
        var edges = graph.putArray("edges");
        for (int i = 0; i < 300; i++) {
            nodes.addObject()
                    .put("id", "n" + i)
                    .put("label", "function" + i)
                    .put("source_file", "src/file.ts");
            if (i > 0)
                edges.addObject()
                        .put("source", "n0")
                        .put("target", "n" + i)
                        .put("relation", "calls");
        }
        var result =
                CodeGraphExplorer.project(graph, UUID.randomUUID(), UUID.randomUUID(), "src", "");
        assertThat(result.nodes()).hasSize(240);
        assertThat(result.totalNodes()).isEqualTo(300);
        assertThat(result.partial()).isTrue();
        var ids = result.nodes().stream().map(CodeGraphExplorer.Node::id).toList();
        assertThat(result.edges())
                .allMatch(edge -> ids.contains(edge.source()) && ids.contains(edge.target()));
    }
}
