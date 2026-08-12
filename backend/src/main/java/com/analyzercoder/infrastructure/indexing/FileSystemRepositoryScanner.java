package com.analyzercoder.infrastructure.indexing;

import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.indexing.ScannedRepositoryFile;
import com.analyzercoder.domain.repository.CodeRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 遍历受控仓库快照，按忽略规则、文件类型和大小限制生成待索引文件。 */
@Component
public class FileSystemRepositoryScanner implements RepositoryScannerPort {

    private static final Set<String> EXCLUDED_DIRECTORIES =
            Set.of(
                    ".git",
                    ".codegraph",
                    ".idea",
                    ".vscode",
                    "node_modules",
                    "dist",
                    "build",
                    "target");
    private static final Map<String, String> LANGUAGE_BY_EXTENSION =
            Map.ofEntries(
                    Map.entry("java", "java"),
                    Map.entry("kt", "kotlin"),
                    Map.entry("ts", "typescript"),
                    Map.entry("tsx", "typescript"),
                    Map.entry("js", "javascript"),
                    Map.entry("jsx", "javascript"),
                    Map.entry("py", "python"),
                    Map.entry("go", "go"),
                    Map.entry("md", "markdown"),
                    Map.entry("yml", "yaml"),
                    Map.entry("yaml", "yaml"),
                    Map.entry("json", "json"),
                    Map.entry("xml", "xml"),
                    Map.entry("sql", "sql"),
                    Map.entry("properties", "properties"));

    private final long maxFileBytes;

    public FileSystemRepositoryScanner(
            @Value("${app.indexing.max-file-bytes:524288}") long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public List<ScannedRepositoryFile> scan(CodeRepository repository) {
        Path root = repository.currentSnapshotPath();
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalStateException("仓库尚未发布可读取的代码版本");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !isInExcludedDirectory(root, path))
                    .filter(this::isSupportedFile)
                    .filter(this::isWithinSizeLimit)
                    .map(path -> readFile(root, path))
                    .flatMap(List::stream)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描当前代码版本失败", exception);
        }
    }

    private boolean isInExcludedDirectory(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSupportedFile(Path path) {
        return LANGUAGE_BY_EXTENSION.containsKey(extension(path));
    }

    private boolean isWithinSizeLimit(Path path) {
        try {
            return Files.size(path) <= maxFileBytes;
        } catch (IOException exception) {
            return false;
        }
    }

    private List<ScannedRepositoryFile> readFile(Path root, Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return List.of();
            }
            String relativePath = root.relativize(path).toString().replace('\\', '/');
            return List.of(
                    new ScannedRepositoryFile(
                            relativePath,
                            LANGUAGE_BY_EXTENSION.get(extension(path)),
                            content,
                            content.split("\\R", -1).length));
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    private String extension(Path path) {
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        return index < 0 || index == fileName.length() - 1
                ? ""
                : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
