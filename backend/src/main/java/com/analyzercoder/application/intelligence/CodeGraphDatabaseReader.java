package com.analyzercoder.application.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Reads the immutable node and edge facts published by CodeGraph 1.6 and later. */
final class CodeGraphDatabaseReader {
    private static final int MAX_NODES = 500_000;
    private static final int MAX_EDGES = 2_000_000;

    private CodeGraphDatabaseReader() {}

    static ObjectNode read(ObjectMapper json, Path marker) {
        Path database = marker.resolve("codegraph.db").toAbsolutePath().normalize();
        if (!database.startsWith(marker.toAbsolutePath().normalize())
                || !Files.isRegularFile(database)) {
            throw new CodeGraphException(
                    "CODEGRAPH_DATABASE_NOT_AVAILABLE", "CodeGraph 产物中不存在可读取的图数据库");
        }

        ObjectNode graph = json.createObjectNode();
        ArrayNode nodes = graph.putArray("nodes");
        ArrayNode edges = graph.putArray("edges");
        String url = "jdbc:sqlite:" + database.toUri() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            readNodes(connection, nodes);
            readEdges(connection, edges);
            return graph;
        } catch (SQLException exception) {
            throw new CodeGraphException(
                    "CODEGRAPH_DATABASE_UNREADABLE", "无法读取已发布的 CodeGraph 图数据库", exception);
        }
    }

    private static void readNodes(Connection connection, ArrayNode output) throws SQLException {
        String sql =
                """
                SELECT id, name, qualified_name, kind, file_path, start_line, end_line
                FROM nodes
                ORDER BY id
                """;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                if (output.size() >= MAX_NODES) {
                    throw new CodeGraphException(
                            "CODEGRAPH_DATABASE_LIMIT_EXCEEDED", "CodeGraph 节点数量超过平台读取上限");
                }
                ObjectNode node = output.addObject();
                node.put("id", rows.getString("id"));
                node.put("label", rows.getString("name"));
                node.put("qualified_name", rows.getString("qualified_name"));
                node.put("kind", rows.getString("kind"));
                node.put("source_file", rows.getString("file_path"));
                node.put("start_line", rows.getInt("start_line"));
                node.put("end_line", rows.getInt("end_line"));
            }
        }
    }

    private static void readEdges(Connection connection, ArrayNode output) throws SQLException {
        String sql =
                """
                SELECT source, target, kind, line
                FROM edges
                ORDER BY source, target, kind, id
                """;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                if (output.size() >= MAX_EDGES) {
                    throw new CodeGraphException(
                            "CODEGRAPH_DATABASE_LIMIT_EXCEEDED", "CodeGraph 关系数量超过平台读取上限");
                }
                ObjectNode edge = output.addObject();
                edge.put("source", rows.getString("source"));
                edge.put("target", rows.getString("target"));
                edge.put("relation", rows.getString("kind"));
                int line = rows.getInt("line");
                if (!rows.wasNull()) {
                    edge.put("line", line);
                }
            }
        }
    }
}
