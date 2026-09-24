package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeGraphExplorerTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode graph() throws Exception {
        return json.readTree(
                """
          {"nodes":[
            {"id":"a","label":"entry","qualified_name":"ui.entry","kind":"function","source_file":"frontend/src/a.ts","start_line":3},
            {"id":"b","label":"helper","kind":"function","source_file":"frontend/src/b.ts"},
            {"id":"c","label":"save","kind":"method","source_file":"backend/src/c.java"},
            {"id":"d","label":"flush","kind":"method","source_file":"backend/src/d.java"}],
           "edges":[{"source":"a","target":"b","relation":"calls","line":8},
                    {"source":"a","target":"c","relation":"calls","line":12},
                    {"source":"a","target":"c","relation":"calls","line":15},
                    {"source":"c","target":"d","relation":"calls"},
                    {"source":"d","target":"c","relation":"calls"},
                    {"source":"a","target":"missing","relation":"calls"}]}
          """);
    }

    @Test
    void opensSymbolsDirectlyAndPreservesNamesLocationsAndCounts() throws Exception {
        var version = UUID.randomUUID();
        var view = CodeGraphExplorer.project(graph(), UUID.randomUUID(), version, "", "");
        assertThat(view.contentVersion()).isEqualTo(version);
        assertThat(view.level()).isEqualTo("SYMBOL");
        assertThat(view.nodes()).hasSize(4);
        assertThat(view.repositoryEdges()).isEqualTo(4);
        assertThat(view.partial()).isFalse();
        var entry = view.nodes().stream().filter(n -> n.id().equals("a")).findFirst().orElseThrow();
        assertThat(entry.qualifiedName()).isEqualTo("ui.entry");
        assertThat(entry.outgoingCount()).isEqualTo(2);
        var relation =
                view.edges().stream()
                        .filter(e -> e.source().equals("a") && e.target().equals("c"))
                        .findFirst()
                        .orElseThrow();
        assertThat(relation.count()).isEqualTo(2);
        assertThat(relation.sourceLines()).containsExactly(12, 15);
    }

    @Test
    void searchKeepsExternalNeighborsAndSupportsQualifiedNames() throws Exception {
        var view =
                CodeGraphExplorer.project(
                        graph(), UUID.randomUUID(), UUID.randomUUID(), "frontend", "UI.ENTRY");
        assertThat(view.nodes())
                .extracting(CodeGraphExplorer.Node::id)
                .containsExactly("a", "b", "c");
        assertThat(view.edges()).hasSize(2);
        assertThat(view.nodes().get(2).hiddenNeighborCount()).isEqualTo(1);
        assertThat(view.nodes().get(2).neighborCount()).isEqualTo(2);
    }

    @Test
    void traversesOnlyRequestedDirectionAndDepthAndTerminatesCycles() throws Exception {
        var in =
                CodeGraphExplorer.project(
                        graph(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "",
                        "",
                        new CodeGraphExplorer.Options("c", "in", 1, 240));
        assertThat(in.nodes())
                .extracting(CodeGraphExplorer.Node::id)
                .containsExactly("c", "a", "d");
        var out =
                CodeGraphExplorer.project(
                        graph(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "",
                        "",
                        new CodeGraphExplorer.Options("a", "out", 2, 240));
        assertThat(out.nodes())
                .extracting(CodeGraphExplorer.Node::id)
                .containsExactly("a", "b", "c", "d");
        var missing =
                CodeGraphExplorer.project(
                        graph(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "",
                        "",
                        new CodeGraphExplorer.Options("missing", "both", 3, 240));
        assertThat(missing.nodes()).isEmpty();
    }

    @Test
    void boundsResultsReportsRemainderAndNeverReturnsDanglingEdges() {
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
        var view =
                CodeGraphExplorer.project(
                        graph,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "",
                        "",
                        new CodeGraphExplorer.Options("n0", "out", 1, 240));
        assertThat(view.nodes()).hasSize(240);
        assertThat(view.totalNodes()).isEqualTo(300);
        assertThat(view.totalEdges()).isEqualTo(299);
        assertThat(view.partial()).isTrue();
        assertThat(view.nodes().get(0).hiddenNeighborCount()).isEqualTo(60);
        var ids = view.nodes().stream().map(CodeGraphExplorer.Node::id).toList();
        assertThat(view.edges())
                .allMatch(e -> ids.contains(e.source()) && ids.contains(e.target()));
        var more =
                CodeGraphExplorer.project(
                        graph,
                        view.repositoryId(),
                        view.contentVersion(),
                        "",
                        "",
                        new CodeGraphExplorer.Options("n0", "out", 1, 480));
        assertThat(more.nodes()).hasSize(300);
        assertThat(more.partial()).isFalse();
    }

    @Test
    void rejectsUnboundedOrInvalidTraversalOptions() {
        assertThatThrownBy(() -> new CodeGraphExplorer.Options("", "unknown", 1, 240))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeGraphExplorer.Options("", "both", 4, 240))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeGraphExplorer.Options("", "both", 1, 1201))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
