package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排代码图谱构建、查询与产物读取，并统一处理仓库访问权限和任务状态。 */
@Service
public class CodeGraphService {
    private final CodeGraphArtifactMapper mapper;
    private final ObjectMapper json;
    private final String executable;
    private final Duration timeout;

    public CodeGraphService(
            CodeGraphArtifactMapper mapper,
            ObjectMapper json,
            @Value("${app.codegraph.executable:codegraph}") String executable,
            @Value("${app.codegraph.timeout-minutes:10}") long minutes) {
        this.mapper = mapper;
        this.json = json;
        this.executable = executable;
        this.timeout = Duration.ofMinutes(Math.max(1, minutes));
    }

    @Transactional
    public Artifact build(UUID repoId) {
        RepoVersion repo = version(repoId);
        Path marker = repo.path().resolve(".codegraph");
        String command = Files.isDirectory(marker) ? "index" : "init";
        run(List.of(command, repo.path().toString()), timeout);
        String cli = run(List.of("--version"), Duration.ofSeconds(15)).trim();
        String status = run(List.of("status", repo.path().toString()), Duration.ofMinutes(1));
        int nodes = numberBefore(status, "nodes"), edges = numberBefore(status, "edges");
        UUID id = UUID.randomUUID();
        mapper.retirePublished(repoId);
        mapper.insertPublished(
                new CodeGraphArtifactRow(
                        id,
                        repoId,
                        repo.snapshotId(),
                        cli,
                        "PUBLISHED",
                        marker.toString(),
                        nodes,
                        edges));
        return new Artifact(
                id, repoId, repo.snapshotId(), cli, "PUBLISHED", marker.toString(), nodes, edges);
    }

    public IntelligenceService.GraphResult impact(UUID repoId, String symbol, int depth) {
        RepoVersion repo = version(repoId);
        if (!Files.isDirectory(repo.path().resolve(".codegraph"))) {
            throw new IllegalStateException("当前快照尚未构建 CodeGraph，请先点击构建图谱");
        }
        String output =
                run(
                        List.of(
                                "impact",
                                "-p",
                                repo.path().toString(),
                                "-d",
                                String.valueOf(Math.max(1, Math.min(depth, 5))),
                                "-j",
                                symbol),
                        Duration.ofMinutes(2));
        try {
            JsonNode root = json.readTree(output);
            Map<String, IntelligenceService.GraphNode> nodes = new LinkedHashMap<>();
            nodes.put(symbol, new IntelligenceService.GraphNode(symbol, 0, true));
            List<IntelligenceService.GraphEdge> edges = new ArrayList<>();
            for (JsonNode item : root.path("affected")) {
                String name = item.path("name").asText();
                String file = item.path("filePath").asText();
                int line = item.path("startLine").asInt();
                String label = name + " @ " + file + ":" + line;
                if (!label.startsWith(symbol + " @")) {
                    nodes.putIfAbsent(label, new IntelligenceService.GraphNode(label, 1, false));
                    edges.add(new IntelligenceService.GraphEdge(symbol, label, "AFFECTS"));
                }
            }
            int count = root.path("nodeCount").asInt(nodes.size());
            String risk = count > 100 ? "HIGH" : count > 30 ? "MEDIUM" : "LOW";
            return new IntelligenceService.GraphResult(
                    new ArrayList<>(nodes.values()),
                    edges,
                    risk,
                    List.of(
                            "CodeGraph CLI 确定性静态分析",
                            "动态反射和运行时分派可能无法确认",
                            "快照 " + repo.snapshotId()));
        } catch (IOException e) {
            throw new IllegalStateException("CodeGraph 返回了无法解析的结果", e);
        }
    }

    public Artifact latest(UUID repoId) {
        return artifact(mapper.findLatest(repoId, null));
    }

    private RepoVersion version(UUID id) {
        var row = mapper.findRepositoryVersion(id);
        if (row == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        return new RepoVersion(row.snapshotId(), Path.of(row.snapshotPath()));
    }

    protected static Artifact artifact(CodeGraphArtifactRow row) {
        return row == null
                ? null
                : new Artifact(
                        row.id(),
                        row.repositoryId(),
                        row.snapshotId(),
                        row.cliVersion(),
                        row.status(),
                        row.artifactPath(),
                        row.nodeCount(),
                        row.edgeCount());
    }

    private String run(List<String> args, Duration wait) {
        try {
            List<String> cmd = new ArrayList<>();
            if (isWindows()
                    || executable.toLowerCase().endsWith(".cmd")
                    || executable.toLowerCase().endsWith(".bat")) {
                cmd.add("cmd.exe");
                cmd.add("/d");
                cmd.add("/s");
                cmd.add("/c");
            }
            cmd.add(executable);
            cmd.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
            builder.environment().put("NO_COLOR", "1");
            Process process = builder.start();
            if (!process.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("CodeGraph 执行超时");
            }
            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "CodeGraph 执行失败: " + output.substring(0, Math.min(1000, output.length())));
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("未找到 CodeGraph CLI，请安装并配置 app.codegraph.executable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CodeGraph 执行被中断", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static int numberBefore(String text, String token) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "([0-9,]+)\\s+" + token, java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(text);
        return m.find() ? Integer.parseInt(m.group(1).replace(",", "")) : 0;
    }

    private record RepoVersion(UUID snapshotId, Path path) {}

    public record Artifact(
            UUID id,
            UUID repositoryId,
            UUID snapshotId,
            String cliVersion,
            String status,
            String artifactPath,
            int nodeCount,
            int edgeCount) {}
}
