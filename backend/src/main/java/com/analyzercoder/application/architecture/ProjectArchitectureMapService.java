package com.analyzercoder.application.architecture;

import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.analyzercoder.domain.indexing.RepositoryAssetClassifier;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 从当前发布快照中提取模块级依赖，为项目总览提供无需关键词的确定性架构地图。 */
@Service
public class ProjectArchitectureMapService {
    private static final int MAX_ANALYZED_FILES = 1000;
    private static final long MAX_ANALYZED_FILE_BYTES = 262_144;
    private static final int MAX_RUNTIME_ANALYZED_FILES = 1200;
    private static final int MAX_EDGE_SAMPLES = 3;
    private static final Pattern JAVA_PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern JAVA_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;");
    private static final Pattern SCRIPT_IMPORT =
            Pattern.compile(
                    "(?:from\\s*|import\\s*\\(|require\\s*\\()"
                            + "['\"]([^'\"]+)['\"]");
    private static final Pattern PYTHON_IMPORT =
            Pattern.compile("(?m)^\\s*(?:from|import)\\s+([\\w.]+)");
    private static final Set<String> LAYERS =
            Set.of(
                    "domain",
                    "application",
                    "infrastructure",
                    "interfaces",
                    "api",
                    "service",
                    "services",
                    "controller",
                    "controllers",
                    "repository",
                    "repositories",
                    "features",
                    "components",
                    "views",
                    "stores",
                    "config");
    private static final Set<String> GROUPING_ROOTS =
            Set.of("apps", "packages", "services", "modules", "plugins");
    private static final List<String> SOURCE_EXTENSIONS =
            List.of(".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".vue", ".py");

    private final RepositoryCodeBrowserService browser;
    private final Map<String, ArchitectureMap> snapshotCache = new ConcurrentHashMap<>();

    public ProjectArchitectureMapService(RepositoryCodeBrowserService browser) {
        this.browser = browser;
    }

    public ArchitectureMap map(CodeRepositoryId repositoryId) {
        RepositoryCodeBrowserService.SnapshotFiles snapshot = browser.list(repositoryId);
        String repositoryPrefix = repositoryId.value() + ":";
        String cacheKey = repositoryPrefix + snapshot.snapshotId();
        snapshotCache
                .keySet()
                .removeIf(key -> key.startsWith(repositoryPrefix) && !key.equals(cacheKey));
        return snapshotCache.computeIfAbsent(
                cacheKey,
                        ignored ->
                                analyze(
                                        repositoryId,
                                        snapshot,
                                        path -> readSnapshotContent(repositoryId, snapshot, path),
                                        Instant.now()));
    }

    private String readSnapshotContent(
            CodeRepositoryId repositoryId,
            RepositoryCodeBrowserService.SnapshotFiles snapshot,
            String path) {
        RepositoryCodeBrowserService.FileContent file = browser.read(repositoryId, path);
        if (!snapshot.snapshotId().equals(file.snapshotId())) {
            throw new ArchitectureSnapshotChangedException(
                    "架构分析期间仓库快照已切换，请基于新快照重试");
        }
        return file.content();
    }

    static ArchitectureMap analyze(
            CodeRepositoryId repositoryId,
            RepositoryCodeBrowserService.SnapshotFiles snapshot,
            Function<String, String> contentReader,
            Instant generatedAt) {
        List<RepositoryCodeBrowserService.FileEntry> codeFiles =
                snapshot.files().stream()
                        .filter(
                                file ->
                                        RepositoryAssetClassifier.classify(
                                                        file.path(), file.language())
                                                == RepositoryAssetType.CODE)
                        .toList();
        List<RepositoryCodeBrowserService.FileEntry> candidates =
                codeFiles.stream()
                        .filter(file -> file.sizeBytes() <= MAX_ANALYZED_FILE_BYTES)
                        .limit(MAX_ANALYZED_FILES)
                        .toList();

        List<RepositoryCodeBrowserService.FileEntry> runtimeCandidates =
                snapshot.files().stream()
                        .filter(
                                file -> {
                                    RepositoryAssetType type =
                                            RepositoryAssetClassifier.classify(
                                                    file.path(), file.language());
                                    return type == RepositoryAssetType.CODE
                                            || type == RepositoryAssetType.CONFIG;
                                })
                        .filter(file -> file.sizeBytes() <= MAX_ANALYZED_FILE_BYTES)
                        .limit(MAX_RUNTIME_ANALYZED_FILES)
                        .toList();
        Map<String, String> contents = new LinkedHashMap<>();
        int unreadable = 0;
        for (RepositoryCodeBrowserService.FileEntry file : candidates) {
            try {
                contents.put(file.path(), contentReader.apply(file.path()));
            } catch (ArchitectureSnapshotChangedException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                unreadable++;
            }
        }
        Map<String, String> runtimeContents = new LinkedHashMap<>(contents);
        int runtimeUnreadable = 0;
        for (RepositoryCodeBrowserService.FileEntry file : runtimeCandidates) {
            if (runtimeContents.containsKey(file.path())) continue;
            try {
                runtimeContents.put(file.path(), contentReader.apply(file.path()));
            } catch (ArchitectureSnapshotChangedException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                runtimeUnreadable++;
            }
        }


        Map<String, ModuleAccumulator> modules = new LinkedHashMap<>();
        for (RepositoryCodeBrowserService.FileEntry file : codeFiles) {
            String module = moduleOf(file.path());
            modules.computeIfAbsent(module, ModuleAccumulator::new).add(file);
        }

        Map<String, String> javaTypes = javaTypes(contents);
        Map<String, String> pathAliases = pathAliases(contents.keySet());
        Map<EdgeKey, EdgeAccumulator> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : contents.entrySet()) {
            String sourceModule = moduleOf(source.getKey());
            for (String targetPath :
                    referencedPaths(source.getKey(), source.getValue(), javaTypes, pathAliases)) {
                String targetModule = moduleOf(targetPath);
                if (sourceModule.equals(targetModule) || !modules.containsKey(targetModule)) {
                    continue;
                }
                EdgeKey key = new EdgeKey(sourceModule, targetModule);
                dependencies
                        .computeIfAbsent(key, ignored -> new EdgeAccumulator())
                        .add(
                                source.getKey() + " → " + targetPath,
                                evidenceSample(
                                        source.getKey(),
                                        targetPath,
                                        snapshot.snapshotId(),
                                        source.getValue()));
            }
        }

        RuntimeGraph runtimeGraph =
                runtimeGraph(runtimeContents, modules.keySet(), snapshot.snapshotId());

        List<ArchitectureNode> nodes = new ArrayList<>();
        nodes.add(
                new ArchitectureNode(
                        "$project",
                        "当前项目",
                        "",
                        "PROJECT",
                        snapshot.files().size(),
                        codeFiles.size(),
                        primaryLanguage(codeFiles),
                        null));
        modules.values().stream()
                .sorted(Comparator.comparing(ModuleAccumulator::id))
                .map(ModuleAccumulator::view)
                .forEach(nodes::add);
        nodes.addAll(runtimeGraph.nodes());

        List<ArchitectureEdge> edges = new ArrayList<>();
        for (String module : modules.keySet().stream().sorted().toList()) {
            edges.add(
                    new ArchitectureEdge(
                            "$project", module, "CONTAINS", 1, List.of(), List.of()));
        }
        dependencies.entrySet().stream()
                .sorted(
                        Map.Entry.comparingByKey(
                                Comparator.comparing(EdgeKey::source)
                                        .thenComparing(EdgeKey::target)))
                .map(
                        entry ->
                                new ArchitectureEdge(
                                        entry.getKey().source(),
                                        entry.getKey().target(),
                                        "DEPENDS_ON",
                                        entry.getValue().weight,
                                        List.copyOf(entry.getValue().samples),
                                        List.copyOf(entry.getValue().evidenceSamples)))
                .forEach(edges::add);

        edges.addAll(runtimeGraph.edges());
        List<ArchitectureRisk> risks = new ArrayList<>(risks(dependencies.keySet()));
        risks.addAll(runtimeGraph.risks());
        int skippedLarge =
                (int)
                        codeFiles.stream()
                                .filter(file -> file.sizeBytes() > MAX_ANALYZED_FILE_BYTES)
                                .count();
        int skippedByLimit = Math.max(0, codeFiles.size() - skippedLarge - candidates.size());
        List<String> notes = new ArrayList<>();
        notes.add("依赖关系来自当前快照的静态 import/require 分析");
        notes.add(
                "运行依赖扫描覆盖 "
                        + runtimeContents.size()
                        + "/"
                        + runtimeCandidates.size()
                        + " 个代码或配置文件");
        notes.add("反射、动态主机和运行时装配关系可能无法识别");
        if (skippedLarge + skippedByLimit + unreadable > 0) {
            notes.add(
                    "有 "
                            + (skippedLarge + skippedByLimit + unreadable)
                            + " 个文件因大小、数量或编码限制未参与依赖分析");
        }
        if (runtimeUnreadable > 0) {
            notes.add(runtimeUnreadable + " 个运行依赖候选文件无法读取");
        }
        return new ArchitectureMap(
                repositoryId.value().toString(),
                snapshot.snapshotId(),
                snapshot.commit(),
                generatedAt,
                nodes,
                edges,
                risks,
                new AnalysisCoverage(
                        contents.size(),
                        codeFiles.size(),
                        skippedLarge,
                        skippedByLimit,
                        unreadable,
                        contents.size() < codeFiles.size(),
                        notes));
    }
    private static RuntimeGraph runtimeGraph(
            Map<String, String> runtimeContents, Set<String> moduleIds, String snapshotId) {
        Map<String, RuntimeDependencyDetector.DetectedResource> resources =
                new LinkedHashMap<>();
        Map<EdgeKey, EdgeAccumulator> links = new LinkedHashMap<>();
        Map<String, ArchitectureRisk> risks = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : runtimeContents.entrySet()) {
            String sourceModule = runtimeSource(source.getKey(), moduleIds);
            for (RuntimeDependencyDetector.DetectedResource resource :
                    RuntimeDependencyDetector.detect(source.getValue())) {
                resources.putIfAbsent(resource.id(), resource);
                EdgeKey key = new EdgeKey(sourceModule, resource.id());
                links.computeIfAbsent(key, ignored -> new EdgeAccumulator())
                        .add(
                                source.getKey(),
                                evidenceSample(
                                        source.getKey(),
                                        null,
                                        snapshotId,
                                        source.getValue()));

                if (resource.insecure()) {
                    String riskId = "transport:" + sourceModule + ":" + resource.id();
                    risks.putIfAbsent(
                            riskId,
                            new ArchitectureRisk(
                                    riskId,
                                    "HIGH",
                                    "INSECURE_TRANSPORT",
                                    "外部服务使用明文 HTTP",
                                    sourceModule
                                            + " 连接 "
                                            + resource.locator()
                                            + "，建议确认是否应使用 HTTPS。",
                                    List.of(sourceModule, resource.id())));
                }
                if ("domain".equals(layerOf(sourceModule))) {
                    String riskId = "runtime-boundary:" + sourceModule + ":" + resource.id();
                    risks.putIfAbsent(
                            riskId,
                            new ArchitectureRisk(
                                    riskId,
                                    "HIGH",
                                    "BOUNDARY",
                                    "领域层直接耦合运行基础设施",
                                    sourceModule
                                            + " 直接引用 "
                                            + resource.label()
                                            + "，建议通过领域端口隔离。",
                                    List.of(sourceModule, resource.id())));
                }
            }
        }

        List<ArchitectureNode> nodes =
                resources.values().stream()
                        .sorted(
                                Comparator.comparing(
                                                RuntimeDependencyDetector.DetectedResource::type)
                                        .thenComparing(
                                                RuntimeDependencyDetector.DetectedResource
                                                        ::locator))
                        .map(
                                resource ->
                                        new ArchitectureNode(
                                                resource.id(),
                                                resourceLabel(resource),
                                                resource.locator(),
                                                "RESOURCE",
                                                0,
                                                0,
                                                "",
                                                resource.type()))
                        .toList();
        List<ArchitectureEdge> edges =
                links.entrySet().stream()
                        .sorted(
                                Map.Entry.comparingByKey(
                                        Comparator.comparing(EdgeKey::source)
                                                .thenComparing(EdgeKey::target)))
                        .map(
                                entry ->
                                        new ArchitectureEdge(
                                                entry.getKey().source(),
                                                entry.getKey().target(),
                                                "CONNECTS_TO",
                                                entry.getValue().weight,
                                                List.copyOf(entry.getValue().samples),
                                                List.copyOf(entry.getValue().evidenceSamples)))
                        .toList();
        return new RuntimeGraph(nodes, edges, List.copyOf(risks.values()));
    }

    private static String runtimeSource(String path, Set<String> moduleIds) {
        String module = moduleOf(path);
        return moduleIds.contains(module) ? module : "$project";
    }

    private static String resourceLabel(
            RuntimeDependencyDetector.DetectedResource resource) {
        return "configured".equals(resource.locator())
                ? resource.label()
                : resource.label() + " · " + resource.locator();
    }

    private static ArchitectureEvidenceSample evidenceSample(
            String filePath, String relatedFilePath, String snapshotId, String content) {
        return new ArchitectureEvidenceSample(
                filePath, relatedFilePath, snapshotId, sha256(content));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }


    private static Map<String, String> javaTypes(Map<String, String> contents) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            if (!entry.getKey().endsWith(".java")) continue;
            Matcher matcher = JAVA_PACKAGE.matcher(entry.getValue());
            if (!matcher.find()) continue;
            String fileName = Path.of(entry.getKey()).getFileName().toString();
            result.put(matcher.group(1) + "." + stripExtension(fileName), entry.getKey());
        }
        return result;
    }

    private static Map<String, String> pathAliases(Set<String> paths) {
        Map<String, String> result = new HashMap<>();
        for (String path : paths) {
            String portable = portable(path);
            result.put(portable, portable);
            String withoutExtension = stripSourceExtension(portable);
            result.put(withoutExtension, portable);
            if (withoutExtension.endsWith("/index")) {
                result.put(
                        withoutExtension.substring(0, withoutExtension.length() - "/index".length()),
                        portable);
            }
            if (portable.endsWith(".py")) {
                result.put(stripExtension(portable).replace('/', '.'), portable);
            }
        }
        return result;
    }

    private static Set<String> referencedPaths(
            String sourcePath,
            String content,
            Map<String, String> javaTypes,
            Map<String, String> pathAliases) {
        Set<String> result = new LinkedHashSet<>();
        if (sourcePath.endsWith(".java")) {
            Matcher imports = JAVA_IMPORT.matcher(content);
            while (imports.find()) {
                String imported = imports.group(1);
                String resolved = resolveJava(imported, javaTypes);
                if (resolved != null) result.add(resolved);
            }
            return result;
        }
        if (isScript(sourcePath)) {
            Matcher imports = SCRIPT_IMPORT.matcher(content);
            while (imports.find()) {
                String resolved = resolveScript(sourcePath, imports.group(1), pathAliases);
                if (resolved != null) result.add(resolved);
            }
            return result;
        }
        if (sourcePath.endsWith(".py")) {
            Matcher imports = PYTHON_IMPORT.matcher(content);
            while (imports.find()) {
                String resolved = pathAliases.get(imports.group(1));
                if (resolved != null) result.add(resolved);
            }
        }
        return result;
    }

    private static String resolveJava(String imported, Map<String, String> javaTypes) {
        String candidate = imported.endsWith(".*")
                ? imported.substring(0, imported.length() - 2)
                : imported;
        while (candidate.contains(".")) {
            String match = javaTypes.get(candidate);
            if (match != null) return match;
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
        return null;
    }

    private static String resolveScript(
            String sourcePath, String specifier, Map<String, String> pathAliases) {
        if (!specifier.startsWith(".") && !specifier.startsWith("@/")) return null;
        String candidate;
        if (specifier.startsWith("@/")) {
            int src = sourcePath.indexOf("/src/");
            if (src < 0) return null;
            candidate = sourcePath.substring(0, src + 5) + specifier.substring(2);
        } else {
            Path parent = Path.of(sourcePath).getParent();
            if (parent == null) return null;
            candidate = portable(parent.resolve(specifier).normalize().toString());
        }
        return pathAliases.get(portable(candidate));
    }

    private static List<ArchitectureRisk> risks(Set<EdgeKey> dependencies) {
        List<ArchitectureRisk> result = new ArrayList<>();
        Set<String> cycleKeys = new HashSet<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (EdgeKey edge : dependencies) {
            adjacency.computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>())
                    .add(edge.target());
            String sourceLayer = layerOf(edge.source());
            String targetLayer = layerOf(edge.target());
            if ("domain".equals(sourceLayer)
                    && Set.of("application", "infrastructure", "interfaces")
                            .contains(targetLayer)) {
                result.add(
                        new ArchitectureRisk(
                                "boundary:" + edge.source() + ":" + edge.target(),
                                "HIGH",
                                "BOUNDARY",
                                "领域层跨越架构边界",
                                edge.source() + " 依赖 " + edge.target() + "，建议通过领域端口反转依赖。",
                                List.of(edge.source(), edge.target())));
            } else if ("application".equals(sourceLayer)
                    && Set.of("infrastructure", "interfaces").contains(targetLayer)) {
                result.add(
                        new ArchitectureRisk(
                                "boundary:" + edge.source() + ":" + edge.target(),
                                "MEDIUM",
                                "BOUNDARY",
                                "应用层直接依赖外层实现",
                                edge.source() + " 依赖 " + edge.target() + "，可检查是否需要抽取端口。",
                                List.of(edge.source(), edge.target())));
            }
        }
        for (EdgeKey edge : dependencies) {
            if (!hasPath(adjacency, edge.target(), edge.source())) continue;
            List<String> pair = List.of(edge.source(), edge.target()).stream().sorted().toList();
            String key = String.join("|", pair);
            if (cycleKeys.add(key)) {
                result.add(
                        new ArchitectureRisk(
                                "cycle:" + key,
                                "HIGH",
                                "CYCLE",
                                "发现模块循环依赖",
                                edge.source() + " 与 " + edge.target() + " 位于同一依赖环路。",
                                pair));
            }
        }
        return result.stream()
                .sorted(
                        Comparator.comparingInt(
                                        (ArchitectureRisk risk) ->
                                                "HIGH".equals(risk.severity()) ? 0 : 1)
                                .thenComparing(ArchitectureRisk::id))
                .toList();
    }

    private static boolean hasPath(
            Map<String, Set<String>> adjacency, String start, String target) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (current.equals(target)) return true;
            queue.addAll(adjacency.getOrDefault(current, Set.of()));
        }
        return false;
    }

    static boolean isLayerModule(String module) {
        int slash = module.lastIndexOf('/');
        return slash > 0 && LAYERS.contains(module.substring(slash + 1).toLowerCase(Locale.ROOT));
    }

    private static String moduleOf(String path) {
        String[] segments = portable(path).split("/");
        if (segments.length == 1) return "(root)";
        String root = segments[0];
        if (GROUPING_ROOTS.contains(root) && segments.length > 2) {
            return root + "/" + segments[1];
        }
        int srcIndex = indexOf(segments, "src");
        if (srcIndex >= 0) {
            for (int index = srcIndex + 1; index < segments.length - 1; index++) {
                String segment = segments[index].toLowerCase(Locale.ROOT);
                if (LAYERS.contains(segment)) return root + "/" + segment;
            }
        }
        return root;
    }

    /** 使用与架构地图完全相同的规则计算仓库路径所属模块。 */
    public static String moduleForPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("模块路径不能为空");
        }
        return moduleOf(path);
    }

    private static int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index++) {
            if (target.equalsIgnoreCase(values[index])) return index;
        }
        return -1;
    }

    private static String layerOf(String module) {
        int slash = module.lastIndexOf('/');
        return (slash < 0 ? module : module.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static boolean isScript(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".vue");
    }

    private static String stripSourceExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : SOURCE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return path.substring(0, path.length() - extension.length());
            }
        }
        return path;
    }

    private static String stripExtension(String value) {
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        int dot = value.lastIndexOf('.');
        return dot > slash ? value.substring(0, dot) : value;
    }

    private static String portable(String value) {
        return value.replace('\\', '/');
    }

    private static String primaryLanguage(
            List<RepositoryCodeBrowserService.FileEntry> files) {
        Map<String, Long> counts = new HashMap<>();
        for (RepositoryCodeBrowserService.FileEntry file : files) {
            counts.merge(file.language(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Long>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("text");
    }

    private static final class ModuleAccumulator {
        private final String id;
        private final List<RepositoryCodeBrowserService.FileEntry> files = new ArrayList<>();

        private ModuleAccumulator(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        private void add(RepositoryCodeBrowserService.FileEntry file) {
            files.add(file);
        }

        private ArchitectureNode view() {
            return new ArchitectureNode(
                    id, id.substring(id.lastIndexOf('/') + 1), id, "MODULE",
                    files.size(), files.size(), primaryLanguage(files), null);
        }
    }

    private record RuntimeGraph(
            List<ArchitectureNode> nodes,
            List<ArchitectureEdge> edges,
            List<ArchitectureRisk> risks) {}

    private record EdgeKey(String source, String target) {}

    private static final class EdgeAccumulator {
        private int weight;
        private final List<String> samples = new ArrayList<>();
        private final List<ArchitectureEvidenceSample> evidenceSamples = new ArrayList<>();

        private void add(String sample, ArchitectureEvidenceSample evidenceSample) {
            weight++;
            if (samples.size() < MAX_EDGE_SAMPLES && !samples.contains(sample)) {
                samples.add(sample);
                evidenceSamples.add(evidenceSample);
            }
        }
    }

    public record ArchitectureMap(
            String repositoryId,
            String snapshotId,
            String commitSha,
            Instant generatedAt,
            List<ArchitectureNode> nodes,
            List<ArchitectureEdge> edges,
            List<ArchitectureRisk> risks,
            AnalysisCoverage coverage) {}

    public record ArchitectureNode(
            String id,
            String label,
            String path,
            String kind,
            long fileCount,
            long codeFileCount,
            String primaryLanguage,
            String resourceType) {}

    public record ArchitectureEdge(
            String source,
            String target,
            String relation,
            int weight,
            List<String> samples,
            List<ArchitectureEvidenceSample> evidenceSamples) {
        public ArchitectureEdge {
            samples = samples == null ? List.of() : List.copyOf(samples);
            evidenceSamples =
                    evidenceSamples == null ? List.of() : List.copyOf(evidenceSamples);
        }
    }

    public record ArchitectureEvidenceSample(
            String filePath,
            String relatedFilePath,
            String snapshotId,
            String contentHash) {}

    public record ArchitectureRisk(
            String id,
            String severity,
            String type,
            String title,
            String detail,
            List<String> modules) {}

    public record AnalysisCoverage(
            int analyzedFiles,
            int totalCodeFiles,
            int skippedLargeFiles,
            int skippedByLimit,
            int unreadableFiles,
            boolean partial,
            List<String> notes) {}

    public static final class ArchitectureSnapshotChangedException
            extends IllegalStateException {
        public ArchitectureSnapshotChangedException(String message) {
            super(message);
        }
    }
}
