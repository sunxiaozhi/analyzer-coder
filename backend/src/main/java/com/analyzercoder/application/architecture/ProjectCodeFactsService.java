package com.analyzercoder.application.architecture;

import com.analyzercoder.application.intelligence.CodeGraphService;
import com.analyzercoder.application.repository.RepositoryCodeBrowserService;
import com.analyzercoder.domain.indexing.RepositoryAssetClassifier;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 从代码、依赖清单和静态图谱中生成项目总览事实；Markdown 不参与推断。 */
@Service
public class ProjectCodeFactsService {
    private static final int MAX_EVIDENCE_PATHS = 4;
    private static final int MAX_MANIFEST_BYTES = 512_000;
    private static final Set<String> SOURCE_LANGUAGES =
            Set.of(
                    "java", "kotlin", "typescript", "tsx", "javascript", "jsx", "vue",
                    "python", "go", "rust", "c", "cpp", "csharp", "php", "ruby", "sql",
                    "shell", "batch");

    private final RepositoryCodeBrowserService browser;
    private final ProjectArchitectureMapService architecture;
    private final CodeGraphService codeGraph;
    private final ObjectMapper json;

    public ProjectCodeFactsService(
            RepositoryCodeBrowserService browser,
            ProjectArchitectureMapService architecture,
            CodeGraphService codeGraph,
            ObjectMapper json) {
        this.browser = browser;
        this.architecture = architecture;
        this.codeGraph = codeGraph;
        this.json = json;
    }

    public CodeFacts analyze(CodeRepositoryId repositoryId) {
        RepositoryCodeBrowserService.SnapshotFiles snapshot = browser.list(repositoryId);
        ProjectArchitectureMapService.ArchitectureMap map = architecture.map(repositoryId);
        CodeGraphService.Artifact artifact = codeGraph.latest(repositoryId.value());
        return analyze(
                snapshot,
                path -> browser.read(repositoryId, path).content(),
                map,
                artifact,
                json,
                Instant.now());
    }

    static CodeFacts analyze(
            RepositoryCodeBrowserService.SnapshotFiles snapshot,
            Function<String, String> contentReader,
            ProjectArchitectureMapService.ArchitectureMap architecture,
            CodeGraphService.Artifact artifact,
            ObjectMapper json,
            Instant generatedAt) {
        List<RepositoryCodeBrowserService.FileEntry> codeFiles =
                snapshot.files().stream()
                        .filter(ProjectCodeFactsService::isCodeFile)
                        .toList();
        List<FileCategory> categories = categories(codeFiles);
        List<TechnologyFact> technologies = technologies(snapshot.files(), contentReader, json);
        GraphFacts graph = graphFacts(architecture, artifact);
        List<ProjectSuggestion> suggestions =
                suggestions(codeFiles, categories, technologies, architecture, artifact, graph);
        int confidence = confidence(architecture, artifact, technologies);
        return new CodeFacts(
                snapshot.snapshotId(),
                snapshot.commit(),
                generatedAt,
                projectType(technologies, codeFiles),
                confidence,
                codeFiles.size(),
                technologies,
                categories,
                graph,
                suggestions,
                List.of(
                        "技术栈仅来自源码扩展名、构建文件、依赖清单和运行配置",
                        "代码职责分类来自文件路径和命名约定，样例路径用于复核",
                        "CodeGraph 符号规模与快照静态模块关系分开统计，避免混为同一种图谱",
                        "README、设计文档和其他 Markdown 不参与本页结论"));
    }

    private static boolean isCodeFile(RepositoryCodeBrowserService.FileEntry file) {
        return RepositoryAssetClassifier.classify(file.path(), file.language())
                        == RepositoryAssetType.CODE
                && SOURCE_LANGUAGES.contains(file.language().toLowerCase(Locale.ROOT));
    }

    private static List<FileCategory> categories(
            List<RepositoryCodeBrowserService.FileEntry> codeFiles) {
        Map<String, CategoryAccumulator> values = new LinkedHashMap<>();
        for (RepositoryCodeBrowserService.FileEntry file : codeFiles) {
            CategoryRule rule = category(file.path());
            values.computeIfAbsent(
                            rule.key(),
                            ignored -> new CategoryAccumulator(rule.key(), rule.label(), rule.detail()))
                    .add(file.path());
        }
        return values.values().stream()
                .sorted(
                        Comparator.comparingLong(CategoryAccumulator::count)
                                .reversed()
                                .thenComparing(CategoryAccumulator::label))
                .map(CategoryAccumulator::view)
                .toList();
    }

    private static CategoryRule category(String path) {
        String value = normalized(path);
        String name = fileName(value);
        if (isTest(value, name))
            return new CategoryRule("TEST", "测试代码", "单元、集成和端到端测试");
        if (matches(value, "/controller/", "/controllers/", "/api/", "/interfaces/rest/")
                || name.endsWith("controller.java")
                || name.endsWith("resource.java")
                || name.endsWith("route.ts")
                || name.endsWith("routes.ts"))
            return new CategoryRule("API", "接口与控制器", "HTTP 接口、路由和请求入口");
        if (matches(value, "/service/", "/services/", "/application/", "/usecase/", "/use-case/")
                || name.endsWith("service.java")
                || name.endsWith("usecase.java"))
            return new CategoryRule("SERVICE", "应用与服务", "用例编排和业务服务");
        if (matches(value, "/repository/", "/repositories/", "/mapper/", "/dao/", "/persistence/")
                || name.endsWith("repository.java")
                || name.endsWith("mapper.java")
                || name.endsWith("dao.java"))
            return new CategoryRule("DATA", "数据访问", "仓储、映射器和持久化适配器");
        if (matches(value, "/domain/", "/model/", "/models/", "/entity/", "/entities/")
                || name.endsWith("entity.java")
                || name.endsWith("model.java"))
            return new CategoryRule("DOMAIN", "领域与模型", "领域对象、实体和值对象");
        if (matches(value, "/views/", "/pages/", "/screens/")
                || name.endsWith("view.vue")
                || name.endsWith("page.tsx"))
            return new CategoryRule("VIEW", "页面与视图", "面向用户的页面和视图容器");
        if (matches(value, "/components/", "/widgets/", "/ui/"))
            return new CategoryRule("COMPONENT", "界面组件", "可复用的 UI 组件");
        if (matches(value, "/store/", "/stores/", "/state/", "/composables/", "/hooks/"))
            return new CategoryRule("STATE", "状态与前端逻辑", "状态管理、组合函数和 Hooks");
        if (matches(value, "/config/", "/configuration/")
                || name.endsWith("config.java")
                || name.endsWith("configuration.java"))
            return new CategoryRule("CONFIG", "代码配置", "框架装配和代码级配置");
        if (matches(value, "/migration/", "/migrations/", "/db/", "/sql/")
                || value.endsWith(".sql"))
            return new CategoryRule("DATABASE_SCRIPT", "数据库脚本", "迁移、建表和数据脚本");
        if (matches(value, "/infrastructure/", "/adapter/", "/adapters/", "/integration/"))
            return new CategoryRule("INFRASTRUCTURE", "基础设施与集成", "外部系统适配和基础设施实现");
        return new CategoryRule("OTHER", "其他源码", "未命中稳定职责命名规则的代码");
    }

    private static List<TechnologyFact> technologies(
            List<RepositoryCodeBrowserService.FileEntry> files,
            Function<String, String> contentReader,
            ObjectMapper json) {
        Map<String, TechnologyAccumulator> facts = new LinkedHashMap<>();
        Map<String, Long> languages = new LinkedHashMap<>();
        Map<String, List<String>> languageSamples = new LinkedHashMap<>();
        for (RepositoryCodeBrowserService.FileEntry file : files) {
            String language = file.language().toLowerCase(Locale.ROOT);
            if (SOURCE_LANGUAGES.contains(language)) {
                String label = technologyLabel(language);
                languages.merge(label, 1L, Long::sum);
                addSample(languageSamples, label, file.path());
            }
        }
        languages.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .forEach(
                        entry ->
                                addTechnology(
                                        facts,
                                        entry.getKey(),
                                        "LANGUAGE",
                                        "HIGH",
                                        entry.getValue() + " 个源码文件",
                                        languageSamples.getOrDefault(entry.getKey(), List.of())));

        for (RepositoryCodeBrowserService.FileEntry file : files) {
            String path = normalized(file.path());
            String name = fileName(path);
            if (!isManifest(name, path) || file.sizeBytes() > MAX_MANIFEST_BYTES) continue;
            String content;
            try {
                content = contentReader.apply(file.path());
            } catch (RuntimeException ignored) {
                continue;
            }
            inspectManifest(facts, file.path(), name, content, json);
        }
        return facts.values().stream()
                .sorted(
                        Comparator.comparingInt(TechnologyAccumulator::rank)
                                .thenComparing(TechnologyAccumulator::name))
                .map(TechnologyAccumulator::view)
                .toList();
    }

    private static boolean isManifest(String name, String path) {
        return Set.of(
                        "package.json", "pom.xml", "build.gradle", "build.gradle.kts", "go.mod",
                        "cargo.toml", "requirements.txt", "pyproject.toml", "dockerfile",
                        "compose.yml", "compose.yaml", "docker-compose.yml", "docker-compose.yaml")
                .contains(name)
                || name.endsWith(".csproj")
                || path.endsWith("/vite.config.ts")
                || path.endsWith("/vite.config.js");
    }

    private static void inspectManifest(
            Map<String, TechnologyAccumulator> facts,
            String path,
            String name,
            String content,
            ObjectMapper json) {
        String lower = content.toLowerCase(Locale.ROOT);
        if ("package.json".equals(name)) inspectPackageJson(facts, path, content, lower, json);
        if ("pom.xml".equals(name) || name.startsWith("build.gradle")) {
            addTechnology(facts, "Maven", "BUILD", "HIGH", "Maven 构建清单", List.of(path), "pom.xml".equals(name));
            addTechnology(facts, "Gradle", "BUILD", "HIGH", "Gradle 构建清单", List.of(path), name.startsWith("build.gradle"));
            detect(facts, lower, "spring-boot", "Spring Boot", "FRAMEWORK", path);
            detect(facts, lower, "spring-security", "Spring Security", "FRAMEWORK", path);
            detect(facts, lower, "spring-cloud", "Spring Cloud", "FRAMEWORK", path);
            detect(facts, lower, "mybatis", "MyBatis", "DATA", path);
            detect(facts, lower, "postgresql", "PostgreSQL", "DATA", path);
            detect(facts, lower, "mysql", "MySQL", "DATA", path);
            detect(facts, lower, "redis", "Redis", "DATA", path);
        }
        if ("requirements.txt".equals(name) || "pyproject.toml".equals(name)) {
            detect(facts, lower, "django", "Django", "FRAMEWORK", path);
            detect(facts, lower, "fastapi", "FastAPI", "FRAMEWORK", path);
            detect(facts, lower, "flask", "Flask", "FRAMEWORK", path);
            detect(facts, lower, "sqlalchemy", "SQLAlchemy", "DATA", path);
        }
        addTechnology(facts, "Go Modules", "BUILD", "HIGH", "Go 模块清单", List.of(path), "go.mod".equals(name));
        addTechnology(facts, "Cargo", "BUILD", "HIGH", "Rust 包清单", List.of(path), "cargo.toml".equals(name));
        addTechnology(facts, ".NET", "FRAMEWORK", "HIGH", ".NET 项目清单", List.of(path), name.endsWith(".csproj"));
        addTechnology(facts, "Vite", "BUILD", "HIGH", "Vite 配置文件", List.of(path), name.startsWith("vite.config"));
        addTechnology(facts, "Docker", "INFRASTRUCTURE", "HIGH", "容器构建或编排文件", List.of(path), name.contains("docker") || name.startsWith("compose."));
    }

    private static void inspectPackageJson(
            Map<String, TechnologyAccumulator> facts,
            String path,
            String content,
            String lower,
            ObjectMapper json) {
        addTechnology(facts, "Node.js", "RUNTIME", "HIGH", "package.json 项目清单", List.of(path));
        Set<String> dependencies = new LinkedHashSet<>();
        try {
            JsonNode root = json.readTree(content);
            root.path("dependencies").fieldNames().forEachRemaining(dependencies::add);
            root.path("devDependencies").fieldNames().forEachRemaining(dependencies::add);
        } catch (Exception ignored) {
            // 清单不合法时仍可使用精确字符串检测，但会保持为清单级证据。
        }
        detectDependency(facts, dependencies, lower, "vue", "Vue", "FRAMEWORK", path);
        detectDependency(facts, dependencies, lower, "react", "React", "FRAMEWORK", path);
        detectDependency(facts, dependencies, lower, "@angular/core", "Angular", "FRAMEWORK", path);
        detectDependency(facts, dependencies, lower, "typescript", "TypeScript", "LANGUAGE", path);
        detectDependency(facts, dependencies, lower, "vite", "Vite", "BUILD", path);
        detectDependency(facts, dependencies, lower, "pinia", "Pinia", "STATE", path);
        detectDependency(facts, dependencies, lower, "vuex", "Vuex", "STATE", path);
        detectDependency(facts, dependencies, lower, "element-plus", "Element Plus", "UI", path);
        detectDependency(facts, dependencies, lower, "element-ui", "Element UI", "UI", path);
        detectDependency(facts, dependencies, lower, "vitest", "Vitest", "TEST", path);
        detectDependency(facts, dependencies, lower, "jest", "Jest", "TEST", path);
    }

    private static void detectDependency(
            Map<String, TechnologyAccumulator> facts,
            Set<String> dependencies,
            String lower,
            String dependency,
            String label,
            String category,
            String path) {
        if (dependencies.contains(dependency)
                || lower.contains("\"" + dependency.toLowerCase(Locale.ROOT) + "\"")) {
            addTechnology(facts, label, category, "HIGH", "依赖清单声明 " + dependency, List.of(path));
        }
    }

    private static void detect(
            Map<String, TechnologyAccumulator> facts,
            String content,
            String token,
            String label,
            String category,
            String path) {
        if (content.contains(token))
            addTechnology(facts, label, category, "HIGH", "构建清单声明 " + token, List.of(path));
    }

    private static void addTechnology(
            Map<String, TechnologyAccumulator> facts,
            String name,
            String category,
            String confidence,
            String detail,
            List<String> evidence) {
        addTechnology(facts, name, category, confidence, detail, evidence, true);
    }

    private static void addTechnology(
            Map<String, TechnologyAccumulator> facts,
            String name,
            String category,
            String confidence,
            String detail,
            List<String> evidence,
            boolean condition) {
        if (!condition) return;
        facts.computeIfAbsent(
                        name,
                        ignored ->
                                new TechnologyAccumulator(name, category, confidence, detail))
                .addEvidence(evidence);
    }

    private static GraphFacts graphFacts(
            ProjectArchitectureMapService.ArchitectureMap architecture,
            CodeGraphService.Artifact artifact) {
        List<ProjectArchitectureMapService.ArchitectureNode> modules =
                architecture == null
                        ? List.of()
                        : architecture.nodes().stream()
                                .filter(node -> "MODULE".equals(node.kind()))
                                .toList();
        List<ProjectArchitectureMapService.ArchitectureEdge> dependencies =
                architecture == null
                        ? List.of()
                        : architecture.edges().stream()
                                .filter(edge -> "DEPENDS_ON".equals(edge.relation()))
                                .toList();
        long runtimeEdges =
                architecture == null
                        ? 0
                        : architecture.edges().stream()
                                .filter(edge -> "CONNECTS_TO".equals(edge.relation()))
                                .count();
        List<ModuleHotspot> hotspots =
                modules.stream()
                        .map(
                                module -> {
                                    int outgoing =
                                            dependencies.stream()
                                                    .filter(edge -> edge.source().equals(module.id()))
                                                    .mapToInt(ProjectArchitectureMapService.ArchitectureEdge::weight)
                                                    .sum();
                                    int incoming =
                                            dependencies.stream()
                                                    .filter(edge -> edge.target().equals(module.id()))
                                                    .mapToInt(ProjectArchitectureMapService.ArchitectureEdge::weight)
                                                    .sum();
                                    return new ModuleHotspot(
                                            module.id(),
                                            module.codeFileCount(),
                                            incoming,
                                            outgoing,
                                            incoming + outgoing);
                                })
                        .sorted(
                                Comparator.comparingInt(ModuleHotspot::relationWeight)
                                        .reversed()
                                        .thenComparing(
                                                Comparator.comparingLong(ModuleHotspot::codeFiles)
                                                        .reversed()))
                        .limit(8)
                        .toList();
        return new GraphFacts(
                artifact != null,
                artifact == null ? null : artifact.cliVersion(),
                artifact == null ? 0 : artifact.nodeCount(),
                artifact == null ? 0 : artifact.edgeCount(),
                modules.size(),
                dependencies.size(),
                runtimeEdges,
                hotspots,
                architecture == null ? 0 : architecture.coverage().analyzedFiles(),
                architecture == null ? 0 : architecture.coverage().totalCodeFiles(),
                architecture != null && architecture.coverage().partial());
    }

    private static List<ProjectSuggestion> suggestions(
            List<RepositoryCodeBrowserService.FileEntry> codeFiles,
            List<FileCategory> categories,
            List<TechnologyFact> technologies,
            ProjectArchitectureMapService.ArchitectureMap architecture,
            CodeGraphService.Artifact artifact,
            GraphFacts graph) {
        List<ProjectSuggestion> result = new ArrayList<>();
        FileCategory tests =
                categories.stream().filter(value -> "TEST".equals(value.key())).findFirst().orElse(null);
        long testCount = tests == null ? 0 : tests.count();
        if (testCount == 0 && !codeFiles.isEmpty()) {
            result.add(
                    new ProjectSuggestion(
                            "HIGH",
                            "QUALITY",
                            "补齐最小测试保护网",
                            "未识别到测试文件。先覆盖启动入口、核心服务和数据访问边界，再进行结构性重构。",
                            List.of(codeFiles.get(0).path())));
        } else if (codeFiles.size() >= 20 && testCount * 10 < codeFiles.size()) {
            result.add(
                    new ProjectSuggestion(
                            "MEDIUM",
                            "QUALITY",
                            "提高关键路径测试覆盖",
                            "测试文件约占代码文件的 "
                                    + Math.round(testCount * 100.0 / codeFiles.size())
                                    + "%；优先覆盖图谱热点模块，而不是只追求总覆盖率。",
                            tests.samples()));
        }
        if (artifact == null) {
            result.add(
                    new ProjectSuggestion(
                            "HIGH",
                            "GRAPH",
                            "构建当前快照的 CodeGraph",
                            "当前没有已发布的符号图谱，无法可靠查看符号调用与变更影响。",
                            List.of("snapshot:" + (architecture == null ? "unknown" : architecture.snapshotId()))));
        }
        if (graph.partial()) {
            result.add(
                    new ProjectSuggestion(
                            "MEDIUM",
                            "COVERAGE",
                            "扩大静态依赖扫描覆盖",
                            "仅分析 " + graph.analyzedCodeFiles() + "/" + graph.totalCodeFiles() + " 个代码文件；总览中的模块关系并不完整。",
                            architecture.coverage().notes()));
        }
        if (architecture != null) {
            architecture.risks().stream()
                    .limit(4)
                    .map(
                            risk ->
                                    new ProjectSuggestion(
                                            risk.severity(),
                                            "ARCHITECTURE",
                                            risk.title(),
                                            risk.detail(),
                                            risk.modules()))
                    .forEach(result::add);
        }
        graph.hotspots().stream().findFirst().ifPresent(
                hotspot -> {
                    if (hotspot.relationWeight() >= 8) {
                        result.add(
                                new ProjectSuggestion(
                                        "MEDIUM",
                                        "MAINTAINABILITY",
                                        "优先梳理图谱热点模块 " + hotspot.module(),
                                        "该模块累计 "
                                                + hotspot.relationWeight()
                                                + " 次静态依赖关系、"
                                                + hotspot.codeFiles()
                                                + " 个代码文件；改动前先确认上下游边界。",
                                        List.of(hotspot.module())));
                    }
                });
        boolean hasBuild =
                technologies.stream()
                        .anyMatch(value -> Set.of("BUILD", "RUNTIME").contains(value.category()));
        if (!codeFiles.isEmpty() && !hasBuild) {
            result.add(
                    new ProjectSuggestion(
                            "LOW",
                            "ONBOARDING",
                            "补充机器可读的构建入口",
                            "未识别到常见构建或包管理清单。建议提供可自动验证的构建脚本或容器入口。",
                            codeFiles.stream().limit(2).map(RepositoryCodeBrowserService.FileEntry::path).toList()));
        }
        return result.stream().limit(8).toList();
    }

    private static int confidence(
            ProjectArchitectureMapService.ArchitectureMap architecture,
            CodeGraphService.Artifact artifact,
            List<TechnologyFact> technologies) {
        int value = 25;
        if (!technologies.isEmpty()) value += 25;
        if (artifact != null) value += 25;
        if (architecture != null && architecture.coverage().totalCodeFiles() > 0) {
            double ratio =
                    (double) architecture.coverage().analyzedFiles()
                            / architecture.coverage().totalCodeFiles();
            value += (int) Math.round(25 * Math.min(1, ratio));
        }
        return Math.min(100, value);
    }

    private static String projectType(
            List<TechnologyFact> technologies,
            List<RepositoryCodeBrowserService.FileEntry> codeFiles) {
        Set<String> names = new LinkedHashSet<>();
        technologies.forEach(value -> names.add(value.name()));
        boolean backend =
                names.stream()
                        .anyMatch(
                                name ->
                                        Set.of(
                                                        "Spring Boot", "Django", "FastAPI", "Flask",
                                                        ".NET", "Go")
                                                .contains(name));
        boolean frontend = names.stream().anyMatch(name -> Set.of("Vue", "React", "Angular").contains(name));
        if (backend && frontend) return "前后端分离 Web 应用";
        if (backend) return "后端服务";
        if (frontend) return "前端应用";
        if (names.contains("Docker") && codeFiles.size() < 20) return "基础设施或部署项目";
        return "多用途代码项目";
    }

    private static boolean isTest(String path, String name) {
        return matches(path, "/test/", "/tests/", "/__tests__/", "/spec/")
                || path.startsWith("test/")
                || path.startsWith("tests/")
                || name.contains(".spec.")
                || name.contains(".test.")
                || name.endsWith("test.java")
                || name.endsWith("tests.java");
    }

    private static boolean matches(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static String normalized(String path) {
        return ("/" + path.replace('\\', '/')).toLowerCase(Locale.ROOT);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String technologyLabel(String language) {
        return switch (language) {
            case "java" -> "Java";
            case "kotlin" -> "Kotlin";
            case "typescript", "tsx" -> "TypeScript";
            case "javascript", "jsx" -> "JavaScript";
            case "vue" -> "Vue SFC";
            case "python" -> "Python";
            case "go" -> "Go";
            case "rust" -> "Rust";
            case "csharp" -> "C#";
            case "cpp" -> "C++";
            case "sql" -> "SQL";
            default -> language;
        };
    }

    private static void addSample(Map<String, List<String>> samples, String key, String path) {
        List<String> values = samples.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (values.size() < MAX_EVIDENCE_PATHS) values.add(path);
    }

    private record CategoryRule(String key, String label, String detail) {}

    private static final class CategoryAccumulator {
        private final String key;
        private final String label;
        private final String detail;
        private final List<String> samples = new ArrayList<>();
        private long count;

        private CategoryAccumulator(String key, String label, String detail) {
            this.key = key;
            this.label = label;
            this.detail = detail;
        }

        private void add(String path) {
            count++;
            if (samples.size() < MAX_EVIDENCE_PATHS) samples.add(path);
        }

        private long count() {
            return count;
        }

        private String label() {
            return label;
        }

        private FileCategory view() {
            return new FileCategory(key, label, detail, count, List.copyOf(samples));
        }
    }

    private static final class TechnologyAccumulator {
        private final String name;
        private final String category;
        private final String confidence;
        private final String detail;
        private final Set<String> evidence = new LinkedHashSet<>();

        private TechnologyAccumulator(
                String name, String category, String confidence, String detail) {
            this.name = name;
            this.category = category;
            this.confidence = confidence;
            this.detail = detail;
        }

        private void addEvidence(List<String> values) {
            values.stream().limit(MAX_EVIDENCE_PATHS).forEach(evidence::add);
        }

        private int rank() {
            return switch (category) {
                case "FRAMEWORK" -> 0;
                case "LANGUAGE" -> 1;
                case "DATA" -> 2;
                case "UI", "STATE" -> 3;
                case "BUILD", "RUNTIME" -> 4;
                default -> 5;
            };
        }

        private String name() {
            return name;
        }

        private TechnologyFact view() {
            return new TechnologyFact(
                    name, category, confidence, detail, evidence.stream().limit(MAX_EVIDENCE_PATHS).toList());
        }
    }

    public record CodeFacts(
            String snapshotId,
            String commitSha,
            Instant generatedAt,
            String projectType,
            int confidence,
            long codeFileCount,
            List<TechnologyFact> technologies,
            List<FileCategory> fileCategories,
            GraphFacts graph,
            List<ProjectSuggestion> suggestions,
            List<String> evidenceNotes) {}

    public record TechnologyFact(
            String name,
            String category,
            String confidence,
            String detail,
            List<String> evidencePaths) {}

    public record FileCategory(
            String key, String label, String detail, long count, List<String> samples) {}

    public record GraphFacts(
            boolean codeGraphReady,
            String codeGraphVersion,
            int symbolNodes,
            int symbolEdges,
            long modules,
            long dependencyEdges,
            long runtimeEdges,
            List<ModuleHotspot> hotspots,
            int analyzedCodeFiles,
            int totalCodeFiles,
            boolean partial) {}

    public record ModuleHotspot(
            String module, long codeFiles, int incomingWeight, int outgoingWeight, int relationWeight) {}

    public record ProjectSuggestion(
            String severity, String category, String title, String detail, List<String> evidence) {}
}
