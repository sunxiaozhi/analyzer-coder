package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        return build(repoId, BuildControl.none());
    }

    @Transactional
    public Artifact build(UUID repoId, BuildControl control) {
        RepoVersion repo = version(repoId);
        Path marker = repo.path().resolve(".codegraph");
        String command = Files.isDirectory(marker) ? "index" : "init";
        control.checkpoint("building_codegraph");
        run(List.of(command, repo.path().toString()), timeout);
        control.checkpoint("inspect_codegraph");
        String cli = run(List.of("--version"), Duration.ofSeconds(15)).trim();
        String status = run(List.of("status", repo.path().toString()), Duration.ofMinutes(1));
        int nodes = numberBefore(status, "nodes"), edges = numberBefore(status, "edges");
        UUID id = UUID.randomUUID();
        control.checkpoint("publish_codegraph");
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

    public CodeGraphPropagation impact(UUID repoId, String symbol, int depth) {
        RepoVersion repo = version(repoId);
        Artifact artifact = artifact(mapper.findPublished(repoId, repo.snapshotId()));
        if (artifact == null || !Files.isDirectory(repo.path().resolve(".codegraph"))) {
            throw new CodeGraphException(
                    "CODEGRAPH_ARTIFACT_NOT_AVAILABLE", "当前 Snapshot 尚未发布 CodeGraph 产物");
        }
        int boundedDepth = Math.max(1, Math.min(depth, 5));
        String impactOutput;
        String exportOutput;
        try {
            impactOutput =
                    run(
                            List.of(
                                    "impact",
                                    "-p",
                                    repo.path().toString(),
                                    "-d",
                                    String.valueOf(boundedDepth),
                                    "-j",
                                    symbol),
                            Duration.ofMinutes(2));
        } catch (IllegalStateException exception) {
            throw new CodeGraphException(
                    "CODEGRAPH_IMPACT_QUERY_FAILED", "CodeGraph impact 查询失败", exception);
        }
        try {
            exportOutput =
                    run(
                            List.of("export", repo.path().toString(), "--no-centrality"),
                            Duration.ofMinutes(2));
        } catch (IllegalStateException exception) {
            throw new CodeGraphException(
                    "CODEGRAPH_EXPORT_NOT_AVAILABLE",
                    "当前 CodeGraph CLI 无法提供真实边导出，已拒绝拼接关系",
                    exception);
        }
        return CodeGraphPropagation.fromCli(
                json, impactOutput, exportOutput, symbol, boundedDepth, artifact);
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

    public interface BuildControl {
        void checkpoint(String step);

        static BuildControl none() {
            return step -> {};
        }
    }

    public static class BuildCanceledException extends RuntimeException {
        public BuildCanceledException() {
            super("CodeGraph 构建已取消");
        }
    }

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
