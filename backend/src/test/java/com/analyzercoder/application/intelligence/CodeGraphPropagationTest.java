package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeGraphPropagationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesExportedEdgesAndBuildsCompletePathsWithoutStarShapingOrRisk() {
        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        CodeGraphService.Artifact artifact =
                new CodeGraphService.Artifact(
                        artifactId,
                        repositoryId,
                        snapshotId,
                        "0.41.0",
                        "PUBLISHED",
                        "artifact/.codegraph",
                        3,
                        3);
        String impact =
                """
                {
                  "symbol": "charge",
                  "depth": 3,
                  "nodeCount": 3,
                  "edgeCount": 2,
                  "affected": [
                    {"name":"PaymentService","filePath":"src/PaymentService.java","startLine":20},
                    {"name":"CheckoutController","filePath":"src/CheckoutController.java","startLine":8}
                  ]
                }
                """;
        String exported =
                """
                {
                  "nodes": [
                    {"id":"n-focus","label":"charge","kind":"method","source_file":"src/Gateway.java","start_line":41,"end_line":48},
                    {"id":"n-service","label":"PaymentService","kind":"class","source_file":"src/PaymentService.java","start_line":20,"end_line":70},
                    {"id":"n-controller","label":"CheckoutController","kind":"class","source_file":"src/CheckoutController.java","start_line":8,"end_line":55},
                    {"id":"n-file","label":"src/Gateway.java","kind":"File","source_file":"src/Gateway.java","start_line":1,"end_line":80}
                  ],
                  "edges": [
                    {"source":"n-service","target":"n-focus","relation":"calls","line":33},
                    {"source":"n-controller","target":"n-service","relation":"references","line":22},
                    {"source":"n-file","target":"n-focus","relation":"contains","line":41}
                  ]
                }
                """;

        CodeGraphPropagation result =
                CodeGraphPropagation.fromCli(json, impact, exported, "charge", 3, artifact);

        assertThat(result.relationSource()).isEqualTo("CODEGRAPH_CLI");
        assertThat(result.graphArtifactId()).isEqualTo(artifactId);
        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.nodes())
                .extracting(CodeGraphPropagation.Node::id, CodeGraphPropagation.Node::depth)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("n-focus", 0),
                        org.assertj.core.groups.Tuple.tuple("n-service", 1),
                        org.assertj.core.groups.Tuple.tuple("n-controller", 2));
        assertThat(result.edges())
                .extracting(
                        CodeGraphPropagation.Edge::source,
                        CodeGraphPropagation.Edge::target,
                        CodeGraphPropagation.Edge::relation)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("n-service", "n-focus", "calls"),
                        org.assertj.core.groups.Tuple.tuple(
                                "n-controller", "n-service", "references"));
        assertThat(result.edges())
                .noneMatch(
                        edge ->
                                edge.source().equals("n-focus")
                                        && edge.target().equals("n-controller"));
        assertThat(result.paths())
                .filteredOn(path -> path.targetNodeId().equals("n-controller"))
                .singleElement()
                .satisfies(
                        path -> {
                            assertThat(path.nodeIds())
                                    .containsExactly("n-focus", "n-service", "n-controller");
                            assertThat(path.edgeIds()).hasSize(2);
                            assertThat(path.depth()).isEqualTo(2);
                        });
        assertThat(result.affectedNodeCount()).isEqualTo(2);
        assertThat(result.maxDepthReached()).isEqualTo(2);
        assertThat(result.coverage().complete()).isTrue();
        assertThat(result.limitations()).containsExactly("CODEGRAPH_STATIC_ANALYSIS_ONLY");
    }

    @Test
    void reportsUnmappedAffectedRowsInsteadOfInventingAnEdge() {
        CodeGraphService.Artifact artifact = artifact();
        CodeGraphPropagation result =
                CodeGraphPropagation.fromCli(
                        json,
                        """
                        {"nodeCount":2,"edgeCount":1,"affected":[
                          {"name":"MissingCaller","filePath":"src/Missing.java","startLine":7}
                        ]}
                        """,
                        """
                        {"nodes":[
                          {"id":"focus","label":"charge","kind":"method","source_file":"src/Gateway.java","start_line":4}
                        ],"links":[]}
                        """,
                        "charge",
                        3,
                        artifact);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.edges()).isEmpty();
        assertThat(result.paths()).isEmpty();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.coverage().unmappedAffectedRecordCount()).isEqualTo(1);
        assertThat(result.limitations())
                .contains(
                        "CODEGRAPH_AFFECTED_NODE_UNMAPPED:1",
                        "CODEGRAPH_NODE_COUNT_MISMATCH:reported=2,represented=1",
                        "CODEGRAPH_EDGE_COUNT_MISMATCH:reported=1,represented=0");
    }

    @Test
    void rejectsFlatImpactWhenTheCliCannotProvideExportedEdges() {
        assertThatThrownBy(
                        () ->
                                CodeGraphPropagation.fromCli(
                                        json,
                                        "{\"nodeCount\":2,\"affected\":[]}",
                                        "{\"nodes\":[]}",
                                        "charge",
                                        2,
                                        artifact()))
                .isInstanceOf(CodeGraphException.class)
                .extracting(error -> ((CodeGraphException) error).code())
                .isEqualTo("CODEGRAPH_EXPORT_SCHEMA_UNSUPPORTED");
    }

    @Test
    void rejectsUnsafeOrUnlocatedFocusNodes() {
        assertThatThrownBy(
                        () ->
                                CodeGraphPropagation.fromCli(
                                        json,
                                        "{\"nodeCount\":1,\"affected\":[]}",
                                        """
                                        {"nodes":[
                                          {"id":"focus","label":"charge","source_file":"../secret.java","start_line":4}
                                        ],"edges":[]}
                                        """,
                                        "charge",
                                        2,
                                        artifact()))
                .isInstanceOf(CodeGraphException.class)
                .extracting(error -> ((CodeGraphException) error).code())
                .isEqualTo("CODEGRAPH_SYMBOL_NOT_FOUND");
    }

    private static CodeGraphService.Artifact artifact() {
        return new CodeGraphService.Artifact(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "0.41.0",
                "PUBLISHED",
                "artifact/.codegraph",
                1,
                0);
    }
}
