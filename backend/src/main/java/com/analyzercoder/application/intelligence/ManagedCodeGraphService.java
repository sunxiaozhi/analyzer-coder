package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 在受管副本上构建并查询 CodeGraph 产物。
 *
 * <p>分析过程复制已发布快照，且主动跳过符号链接和已有 {@code .codegraph} 目录，确保 CLI 不修改仓库快照， 同时避免通过符号链接越过受管目录边界。
 */
@Primary
@Service
public class ManagedCodeGraphService extends CodeGraphService {
    private static final int MAX_IMPACT_DEPTH = 5;
    private static final int MAX_PROCESS_ERROR_LENGTH = 1000;

    private final CodeGraphArtifactMapper mapper;
    private final ObjectMapper json;
    private final String executable;
    private final Path root;
    private final long timeoutMinutes;
    private final CodeGraphArtifactPublisher publisher;

    public ManagedCodeGraphService(
            CodeGraphArtifactMapper mapper,
            ObjectMapper json,
            @Value("${app.codegraph.executable:codegraph}") String executable,
            @Value("${app.codegraph.timeout-minutes:10}") long timeoutMinutes,
            @Value("${app.codegraph.artifact-root:${java.io.tmpdir}/analyzer-coder/codegraph}")
                    String root,
            CodeGraphArtifactPublisher publisher) {
        super(mapper, json, executable, timeoutMinutes);
        this.mapper = mapper;
        this.json = json;
        this.executable = executable;
        this.timeoutMinutes = timeoutMinutes;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.publisher = publisher;
    }

    @Override
    public Artifact build(UUID repositoryId) {
        return build(repositoryId, BuildControl.none());
    }

    @Override
    public Artifact build(UUID repositoryId, BuildControl control) {
        Version version = version(repositoryId);
        UUID artifactId = UUID.randomUUID();
        Path project =
                root.resolve(repositoryId.toString())
                        .resolve("codegraph")
                        .resolve(version.snapshotId().toString())
                        .resolve(artifactId.toString())
                        .resolve("project");
        try {
            control.checkpoint("copy_snapshot");
            copySnapshot(version.snapshotPath(), project, control);
            String output =
                    run(
                            List.of("init", project.toString()),
                            timeoutMinutes * 60,
                            control,
                            "building_codegraph");
            int nodes = metric(output, "nodes");
            int edges = metric(output, "edges");
            String cliVersion =
                    run(List.of("--version"), 30, control, "inspect_codegraph").trim();
            Path marker = project.resolve(".codegraph");
            if (!Files.isDirectory(marker)) {
                throw new IllegalStateException("CodeGraph 未生成预期产物目录");
            }

            control.checkpoint("publish_codegraph");
            Version current = version(repositoryId);
            if (!version.snapshotId().equals(current.snapshotId())) {
                throw new IllegalStateException("CodeGraph 构建期间仓库快照已切换，拒绝发布旧版本产物");
            }
            CodeGraphArtifactRow row =
                    new CodeGraphArtifactRow(
                            artifactId,
                            repositoryId,
                            version.snapshotId(),
                            cliVersion,
                            "PUBLISHED",
                            marker.toString(),
                            nodes,
                            edges);
            publisher.publish(row);
            return new Artifact(
                    artifactId,
                    repositoryId,
                    version.snapshotId(),
                    cliVersion,
                    "PUBLISHED",
                    marker.toString(),
                    nodes,
                    edges);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 CodeGraph 分析副本", exception);
        }
    }

    @Override
    public CodeGraphPropagation impact(UUID repositoryId, String symbol, int depth) {
        Version version = version(repositoryId);
        Artifact artifact = published(repositoryId, version.snapshotId());
        Path project = Path.of(artifact.artifactPath()).getParent();
        int boundedDepth = Math.max(1, Math.min(depth, MAX_IMPACT_DEPTH));
        String impactOutput;
        String exportOutput;
        try {
            impactOutput =
                    run(
                            List.of(
                                    "impact",
                                    "-p",
                                    project.toString(),
                                    "-d",
                                    String.valueOf(boundedDepth),
                                    "-j",
                                    symbol),
                            120);
        } catch (IllegalStateException exception) {
            throw new CodeGraphException(
                    "CODEGRAPH_IMPACT_QUERY_FAILED", "CodeGraph impact 查询失败", exception);
        }
        try {
            exportOutput =
                    run(List.of("export", project.toString(), "--no-centrality"), 120);
        } catch (IllegalStateException exception) {
            throw new CodeGraphException(
                    "CODEGRAPH_EXPORT_NOT_AVAILABLE",
                    "当前 CodeGraph CLI 无法提供真实边导出，已拒绝拼接关系",
                    exception);
        }
        return CodeGraphPropagation.fromCli(
                json, impactOutput, exportOutput, symbol, boundedDepth, artifact);
    }

    @Override
    public Artifact latest(UUID repositoryId) {
        Version version = version(repositoryId);
        return artifact(mapper.findLatest(repositoryId, version.snapshotId()));
    }

    private Artifact published(UUID repositoryId, UUID snapshotId) {
        Artifact result = artifact(mapper.findPublished(repositoryId, snapshotId));
        if (result == null) {
            throw new CodeGraphException(
                    "CODEGRAPH_ARTIFACT_NOT_AVAILABLE", "当前 Snapshot 尚未发布 CodeGraph 产物");
        }
        if (!snapshotId.equals(result.snapshotId())) {
            throw new CodeGraphException(
                    "CODEGRAPH_VERSION_MISMATCH", "CodeGraph 产物与当前 Snapshot 不一致");
        }
        Path marker = Path.of(result.artifactPath()).toAbsolutePath().normalize();
        if (!marker.startsWith(root) || !Files.isDirectory(marker)) {
            throw new CodeGraphException(
                    "CODEGRAPH_ARTIFACT_MISSING", "CodeGraph 产物目录不存在或超出受管目录");
        }
        return result;
    }

    private Version version(UUID repositoryId) {
        var row = mapper.findRepositoryVersion(repositoryId);
        if (row == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        return new Version(row.snapshotId(), Path.of(row.snapshotPath()));
    }

    private static void copySnapshot(Path source, Path target, BuildControl control)
            throws IOException {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(candidate -> !candidate.equals(source)).toList()) {
                control.checkpoint("copy_snapshot");
                Path relativePath = source.relativize(path);
                if (relativePath.getNameCount() > 0
                        && ".codegraph".equals(relativePath.getName(0).toString())) {
                    continue;
                }

                Path outputPath = target.resolve(relativePath).normalize();
                if (!outputPath.startsWith(target)) {
                    throw new IOException("快照路径越界");
                }
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                Files.createDirectories(outputPath.getParent());
                Files.copy(path, outputPath, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private String run(List<String> arguments, long timeoutSeconds) {
        return run(arguments, timeoutSeconds, BuildControl.none(), "query_codegraph");
    }

    private String run(
            List<String> arguments,
            long timeoutSeconds,
            BuildControl control,
            String step) {
        try {
            List<String> command = command(arguments);
            java.lang.ProcessBuilder builder =
                    new java.lang.ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("NO_COLOR", "1");
            Process process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = outputReader(process, buffer);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            try {
                while (!process.waitFor(1, TimeUnit.SECONDS)) {
                    control.checkpoint(step);
                    if (System.nanoTime() >= deadline) {
                        process.destroyForcibly();
                        throw new IllegalStateException("CodeGraph 执行超时");
                    }
                }
            } catch (RuntimeException exception) {
                process.destroyForcibly();
                throw exception;
            }
            reader.join(5000);

            String output = buffer.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("CodeGraph 执行失败: " + truncate(output));
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("未找到 CodeGraph CLI", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CodeGraph 执行被中断", exception);
        }
    }

    private List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>();
        String normalizedExecutable = executable.toLowerCase(Locale.ROOT);
        if (isWindows()
                || normalizedExecutable.endsWith(".cmd")
                || normalizedExecutable.endsWith(".bat")) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/s");
            command.add("/c");
            command.add(executable);
        } else {
            command.add(executable);
        }
        command.addAll(arguments);
        return command;
    }

    private static Thread outputReader(Process process, ByteArrayOutputStream buffer) {
        Thread reader =
                new Thread(
                        () -> {
                            try {
                                process.getInputStream().transferTo(buffer);
                            } catch (IOException ignored) {
                                // 主流程会根据进程退出码报告错误；读取线程不单独改变执行结果。
                            }
                        },
                        "managed-codegraph-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static String truncate(String output) {
        return output.substring(0, Math.min(output.length(), MAX_PROCESS_ERROR_LENGTH));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static int metric(String output, String label) {
        Matcher matcher =
                Pattern.compile("([0-9,]+)\\s+" + label, Pattern.CASE_INSENSITIVE).matcher(output);
        return matcher.find() ? Integer.parseInt(matcher.group(1).replace(",", "")) : 0;
    }

    private record Version(UUID snapshotId, Path snapshotPath) {}
}
