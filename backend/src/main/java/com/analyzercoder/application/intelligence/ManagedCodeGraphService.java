package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * <p>仓库级构建仍复制已发布版本以保持兼容；分支构建直接复用其受管内容目录并原地维护 {@code .codegraph}，
 * 避免大型分支在每次构建前再次复制全部源码。分支任务由上层分支锁串行化。
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
            @Value("${app.codegraph.timeout-minutes:30}") long timeoutMinutes,
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
        return buildVersion(repositoryId, version, control, false);
    }

    @Override
    public Artifact buildContentVersion(
            UUID repositoryId, UUID contentVersion, Path contentVersionPath, BuildControl control) {
        return buildVersion(
                repositoryId, new Version(contentVersion, contentVersionPath), control, true);
    }

    private Artifact buildVersion(
            UUID repositoryId, Version version, BuildControl control, boolean immutableBranch) {
        UUID artifactId = UUID.randomUUID();
        Path project =
                immutableBranch
                        ? version.contentVersionPath()
                        : artifactProject(repositoryId, version, artifactId);
        try {
            if (immutableBranch) {
                prepareBranchWorkspace(project);
            } else {
                control.checkpoint("copy_contentVersion");
                copyContentVersion(version.contentVersionPath(), project, control);
            }
            Path marker = project.resolve(".codegraph");
            String operation = Files.isDirectory(marker) ? "index" : "init";
            String output =
                    run(
                            List.of(operation, project.toString()),
                            timeoutMinutes * 60,
                            control,
                            "building_codegraph");
            if (!Files.isDirectory(marker)) {
                throw new IllegalStateException("CodeGraph 未生成预期产物目录");
            }
            CodeGraphDatabaseReader.Metrics metrics =
                    Files.isRegularFile(marker.resolve("codegraph.db"))
                            ? CodeGraphDatabaseReader.metrics(marker)
                            : new CodeGraphDatabaseReader.Metrics(
                                    metric(output, "nodes"), metric(output, "edges"));
            int nodes = metrics.nodes();
            int edges = metrics.edges();
            if (nodes == 0) {
                throw new IllegalStateException("CodeGraph 未生成可用节点，无法发布可用图谱");
            }
            String cliVersion = run(List.of("--version"), 30, control, "inspect_codegraph").trim();

            control.checkpoint("publish_codegraph");
            Version current = immutableBranch ? version : version(repositoryId);
            if (!version.contentVersion().equals(current.contentVersion())) {
                throw new IllegalStateException("CodeGraph 构建期间仓库内容版本已切换，拒绝发布旧版本产物");
            }
            CodeGraphArtifactRow row =
                    new CodeGraphArtifactRow(
                            artifactId,
                            repositoryId,
                            version.contentVersion(),
                            cliVersion,
                            "PUBLISHED",
                            marker.toString(),
                            nodes,
                            edges);
            publisher.publish(row);
            return new Artifact(
                    artifactId,
                    repositoryId,
                    version.contentVersion(),
                    cliVersion,
                    "PUBLISHED",
                    marker.toString(),
                    nodes,
                    edges);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "无法准备 CodeGraph 工作目录：" + exception.getMessage(), exception);
        }
    }

    private Path artifactProject(UUID repositoryId, Version version, UUID artifactId) {
        return root.resolve(repositoryId.toString())
                .resolve("codegraph")
                .resolve(version.contentVersion().toString())
                .resolve(artifactId.toString())
                .resolve("project");
    }

    private static void prepareBranchWorkspace(Path project) throws IOException {
        if (!Files.isDirectory(project)) {
            throw new IOException("分支内容目录不存在");
        }
        if (!Files.isWritable(project) && !project.toFile().setWritable(true, false)) {
            throw new IOException("分支内容目录不可写");
        }
    }

    @Override
    public CodeGraphPropagation impact(UUID repositoryId, String symbol, int depth) {
        Version version = version(repositoryId);
        return impactContentVersion(repositoryId, version.contentVersion(), symbol, depth);
    }

    @Override
    public CodeGraphPropagation impactContentVersion(
            UUID repositoryId, UUID contentVersion, String symbol, int depth) {
        Artifact artifact = published(repositoryId, contentVersion);
        Path project = Path.of(artifact.artifactPath()).getParent();
        int boundedDepth = Math.max(1, Math.min(depth, MAX_IMPACT_DEPTH));
        String impactOutput;
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
        return CodeGraphPropagation.fromDatabase(
                json,
                impactOutput,
                CodeGraphDatabaseReader.read(json, Path.of(artifact.artifactPath())),
                symbol,
                boundedDepth,
                artifact);
    }

    @Override
    public Artifact latest(UUID repositoryId) {
        Version version = version(repositoryId);
        return artifact(mapper.findLatest(repositoryId, version.contentVersion()));
    }

    private Artifact published(UUID repositoryId, UUID contentVersion) {
        Artifact result = artifact(mapper.findPublished(repositoryId, contentVersion));
        if (result == null) {
            throw new CodeGraphException(
                    "CODEGRAPH_ARTIFACT_NOT_AVAILABLE", "当前 ContentVersion 尚未发布 CodeGraph 产物");
        }
        if (!contentVersion.equals(result.contentVersion())) {
            throw new CodeGraphException(
                    "CODEGRAPH_VERSION_MISMATCH", "CodeGraph 产物与当前 ContentVersion 不一致");
        }
        Path marker = Path.of(result.artifactPath()).toAbsolutePath().normalize();
        if (!marker.startsWith(root) || !Files.isDirectory(marker)) {
            throw new CodeGraphException("CODEGRAPH_ARTIFACT_MISSING", "CodeGraph 产物目录不存在或超出受管目录");
        }
        return result;
    }

    /** Fixed read commands over a published managed contentVersion. */
    public String readContentVersion(
            UUID repositoryId, UUID contentVersion, String operation, List<String> arguments) {
        if (!List.of(
                        "explore",
                        "node",
                        "query",
                        "callers",
                        "callees",
                        "impact",
                        "files",
                        "status",
                        "affected")
                .contains(operation)) {
            throw new IllegalArgumentException("不支持的 CodeGraph 只读操作");
        }
        Artifact artifact = published(repositoryId, contentVersion);
        Path project = Path.of(artifact.artifactPath()).getParent();
        List<String> command = new ArrayList<>();
        command.add(operation);
        if ("status".equals(operation)) {
            command.add("-j");
            command.add(project.toString());
        } else {
            command.add("-p");
            command.add(project.toString());
            command.addAll(arguments);
        }
        try {
            String output = run(command, 30);
            if (output.length() > 200_000)
                throw new CodeGraphException(
                        "CODEGRAPH_RESULT_TOO_LARGE", "CodeGraph 查询结果过大，请缩小范围");
            return output.replace(project.toString(), ".");
        } catch (CodeGraphException failure) {
            throw failure;
        } catch (IllegalStateException failure) {
            throw new CodeGraphException(
                    "CODEGRAPH_QUERY_FAILED", "CodeGraph 查询失败，请检查查询条件", failure);
        }
    }

    @Override
    public CodeGraphExplorer.View explore(UUID repositoryId, String module, String query) {
        return explore(repositoryId, module, query, CodeGraphExplorer.Options.defaults());
    }

    @Override
    public CodeGraphExplorer.View explore(UUID repositoryId, String module, String query, CodeGraphExplorer.Options options) {
        Version current = version(repositoryId);
        Artifact artifact = published(repositoryId, current.contentVersion());
        var graph = CodeGraphDatabaseReader.read(json, Path.of(artifact.artifactPath()));
        if (!current.contentVersion().equals(version(repositoryId).contentVersion())) {
            throw new CodeGraphException("CODEGRAPH_VERSION_MISMATCH", "查询期间内容版本已更新，请刷新图谱");
        }
        return CodeGraphExplorer.project(
                graph, repositoryId, current.contentVersion(), module, query, options);
    }

    @Override
    public CodeGraphExplorer.View exploreContentVersion(
            UUID repositoryId, UUID contentVersion, String module, String query) {
        return exploreContentVersion(repositoryId, contentVersion, module, query, CodeGraphExplorer.Options.defaults());
    }

    @Override
    public CodeGraphExplorer.View exploreContentVersion(UUID repositoryId, UUID contentVersion,
            String module, String query, CodeGraphExplorer.Options options) {
        Artifact artifact = published(repositoryId, contentVersion);
        return CodeGraphExplorer.project(
                CodeGraphDatabaseReader.read(json, Path.of(artifact.artifactPath())),
                repositoryId,
                contentVersion,
                module,
                query, options);
    }

    private Version version(UUID repositoryId) {
        var row = mapper.findRepositoryVersion(repositoryId);
        if (row == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        return new Version(row.contentVersion(), Path.of(row.contentVersionPath()));
    }

    private static void copyContentVersion(Path source, Path target, BuildControl control)
            throws IOException {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(candidate -> !candidate.equals(source)).toList()) {
                control.checkpoint("copy_contentVersion");
                Path relativePath = source.relativize(path);
                if (relativePath.getNameCount() > 0
                        && ".codegraph".equals(relativePath.getName(0).toString())) {
                    continue;
                }

                Path outputPath = target.resolve(relativePath).normalize();
                if (!outputPath.startsWith(target)) {
                    throw new IOException("内容版本路径越界");
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
            List<String> arguments, long timeoutSeconds, BuildControl control, String step) {
        try {
            List<String> command = command(arguments);
            java.lang.ProcessBuilder builder =
                    new java.lang.ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("NO_COLOR", "1");
            Process process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = outputReader(process, buffer);
            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
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

            String output = CodeGraphProcessOutput.decode(buffer.toByteArray());
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        CodeGraphProcessOutput.failureMessage(truncate(output)));
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

    static int metric(String output, String label) {
        Matcher matcher =
                Pattern.compile("([0-9,]+)\\s+" + label, Pattern.CASE_INSENSITIVE).matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("无法读取 CodeGraph 的 " + label + " 统计，请检查 CLI 输出格式");
        }
        return Integer.parseInt(matcher.group(1).replace(",", ""));
    }

    private record Version(UUID contentVersion, Path contentVersionPath) {}
}
