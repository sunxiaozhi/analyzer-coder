package com.analyzercoder.infrastructure.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RepositoryPathPolicy {

    private final List<Path> allowedRoots;

    public RepositoryPathPolicy(@Value("${app.repository.allowed-roots:.}") String configuredRoots) {
        this.allowedRoots = Arrays.stream(configuredRoots.split("[,;]"))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(RepositoryPathPolicy::realPath)
            .toList();
        if (allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("At least one repository allowed root must be configured");
        }
    }

    public Path validate(String rawPath) {
        Path candidate = realPath(Path.of(rawPath));
        if (!Files.isDirectory(candidate) || !Files.isReadable(candidate)) {
            throw new IllegalArgumentException("Repository path must be a readable directory");
        }
        if (allowedRoots.stream().noneMatch(candidate::startsWith)) {
            throw new IllegalArgumentException("Repository path is outside configured allowed roots");
        }
        return candidate;
    }

    private static Path realPath(String value) {
        return realPath(Path.of(value));
    }

    private static Path realPath(Path path) {
        try {
            return path.toAbsolutePath().normalize().toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Configured path does not exist or cannot be resolved", exception);
        }
    }
}
