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

@Component
public class FileSystemRepositoryScanner implements RepositoryScannerPort {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
        ".git",
        ".codegraph",
        ".idea",
        ".vscode",
        "node_modules",
        "dist",
        "build",
        "target"
    );

    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
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
        Map.entry("properties", "properties")
    );

    private final long maxFileBytes;

    public FileSystemRepositoryScanner(@Value("${app.indexing.max-file-bytes:524288}") long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public List<ScannedRepositoryFile> scan(CodeRepository repository) {
        try (Stream<Path> paths = Files.walk(repository.path())) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> !isInExcludedDirectory(repository.path(), path))
                .filter(this::isSupportedFile)
                .filter(this::isWithinSizeLimit)
                .map(path -> readFile(repository.path(), path))
                .flatMap(List::stream)
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan repository: " + repository.path(), exception);
        }
    }

    private boolean isInExcludedDirectory(Path root, Path path) {
        Path relativePath = root.relativize(path);
        for (Path part : relativePath) {
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
            int lineCount = content.split("\\R", -1).length;
            return List.of(new ScannedRepositoryFile(relativePath, LANGUAGE_BY_EXTENSION.get(extension(path)), content, lineCount));
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    private String extension(Path path) {
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
