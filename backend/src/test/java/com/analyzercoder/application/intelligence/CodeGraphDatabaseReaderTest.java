package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGraphDatabaseReaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void readsPublishedNodesAndEdgesWithoutUsingRemovedExportCommand() throws Exception {
        Path marker = temporaryDirectory.resolve(".codegraph");
        Files.createDirectories(marker);
        Path database = marker.resolve("codegraph.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.createStatement()) {
                statement.execute(
                        """
                        CREATE TABLE nodes (
                          id TEXT PRIMARY KEY, name TEXT NOT NULL, qualified_name TEXT NOT NULL,
                          kind TEXT NOT NULL, file_path TEXT NOT NULL,
                          start_line INTEGER NOT NULL, end_line INTEGER NOT NULL
                        )
                        """);
                statement.execute(
                        """
                        CREATE TABLE edges (
                          id INTEGER PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL,
                          kind TEXT NOT NULL, line INTEGER
                        )
                        """);
                statement.execute(
                        """
                        INSERT INTO nodes VALUES
                          ('focus','charge','Gateway::charge','method','src/Gateway.java',4,9),
                          ('caller','checkout','Checkout::checkout','method','src/Checkout.java',12,20)
                        """);
                statement.execute(
                        "INSERT INTO edges VALUES (1,'caller','focus','calls',16)");
            }
        }

        var graph = CodeGraphDatabaseReader.read(new ObjectMapper(), marker);

        assertThat(graph.path("nodes")).hasSize(2);
        assertThat(graph.path("edges")).hasSize(1);
        assertThat(graph.path("edges").get(0).path("relation").asText()).isEqualTo("calls");
        assertThat(graph.path("edges").get(0).path("line").asInt()).isEqualTo(16);

        CodeGraphPropagation propagation =
                CodeGraphPropagation.fromDatabase(
                        new ObjectMapper(),
                        """
                        {"nodeCount":2,"edgeCount":1,"affected":[
                          {"name":"charge","filePath":"src/Gateway.java","startLine":4},
                          {"name":"checkout","filePath":"src/Checkout.java","startLine":12}
                        ]}
                        """,
                        graph,
                        "Gateway::charge",
                        3,
                        new CodeGraphService.Artifact(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "1.6.0",
                                "PUBLISHED",
                                marker.toString(),
                                2,
                                1));

        assertThat(propagation.relationSource()).isEqualTo("CODEGRAPH_SQLITE");
        assertThat(propagation.coverage().complete()).isTrue();
        assertThat(propagation.paths()).singleElement();
    }
}
