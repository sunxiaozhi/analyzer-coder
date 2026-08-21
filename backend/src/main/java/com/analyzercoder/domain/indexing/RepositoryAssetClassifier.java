package com.analyzercoder.domain.indexing;

import java.util.Locale;
import java.util.Set;

/** 使用稳定的路径规则为仓库文件分类，供扫描、画像和 Agent 上下文共用。 */
public final class RepositoryAssetClassifier {
    private static final Set<String> RULE_FILES =
            Set.of(
                    "agents.md",
                    "claude.md",
                    "codex.md",
                    ".cursorrules",
                    "copilot-instructions.md",
                    "contributing.md");
    private static final Set<String> TASK_FILES =
            Set.of("tasks.md", "task.md", "todo.md", "roadmap.md", "checklist.md", "gate.md");
    private static final Set<String> CONFIG_EXTENSIONS =
            Set.of("yml", "yaml", "json", "xml", "properties", "toml", "ini", "conf", "env");

    private RepositoryAssetClassifier() {}

    public static RepositoryAssetType classify(String path, String language) {
        String normalized = normalize(path);
        String fileName = fileName(normalized);
        if (isRule(normalized, fileName)) return RepositoryAssetType.RULE;
        if (isTask(normalized, fileName)) return RepositoryAssetType.TASK;
        if (isConfig(fileName, extension(fileName), language)) return RepositoryAssetType.CONFIG;
        if ("markdown".equalsIgnoreCase(language)
                || Set.of("md", "mdx", "rst", "txt", "adoc").contains(extension(fileName))) {
            return RepositoryAssetType.DOCUMENT;
        }
        return RepositoryAssetType.CODE;
    }

    public static boolean isKeyAsset(String path, RepositoryAssetType type) {
        String normalized = normalize(path);
        String fileName = fileName(normalized);
        return type == RepositoryAssetType.RULE
                || type == RepositoryAssetType.TASK
                || fileName.equals("readme.md")
                || fileName.equals("design.md")
                || fileName.equals("architecture.md")
                || fileName.startsWith("adr-")
                || normalized.contains("/adr/")
                || normalized.contains("/architecture/");
    }

    private static boolean isRule(String path, String fileName) {
        return RULE_FILES.contains(fileName)
                || path.contains("/.github/instructions/")
                || path.startsWith(".github/instructions/")
                || path.contains("/rules/")
                || path.startsWith("rules/");
    }

    private static boolean isTask(String path, String fileName) {
        return TASK_FILES.contains(fileName)
                || fileName.endsWith(".task.md")
                || fileName.endsWith(".gate.md")
                || path.contains("/tasks/")
                || path.startsWith("tasks/")
                || path.contains("/gates/")
                || path.startsWith("gates/");
    }

    private static boolean isConfig(String fileName, String extension, String language) {
        return CONFIG_EXTENSIONS.contains(extension)
                || Set.of("dockerfile", "makefile", "gradlew", "mvnw").contains(fileName)
                || "yaml".equalsIgnoreCase(language)
                || "json".equalsIgnoreCase(language)
                || "properties".equalsIgnoreCase(language);
    }

    private static String normalize(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
    }
}
