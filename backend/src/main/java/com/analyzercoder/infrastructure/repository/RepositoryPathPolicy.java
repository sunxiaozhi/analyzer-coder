package com.analyzercoder.infrastructure.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RepositoryPathPolicy {
    private final List<Path> allowedRoots;
    private final Path managedRoot;

    @Autowired
    public RepositoryPathPolicy(
        @Value("${app.repository.allowed-roots:.}") String configuredRoots,
        @Value("${app.repository.managed-data-root:${java.io.tmpdir}/analyzer-coder}") String configuredManagedRoot
    ) {
        this.allowedRoots = Arrays.stream(configuredRoots.split("[,;]"))
            .map(String::trim).filter(value -> !value.isEmpty()).map(RepositoryPathPolicy::realPath).toList();
        if (allowedRoots.isEmpty()) throw new IllegalArgumentException("必须至少配置一个允许接入的仓库根目录");
        try {
            Path root = Path.of(configuredManagedRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            this.managedRoot = root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法创建或解析平台受管数据目录", exception);
        }
    }

    public RepositoryPathPolicy(String configuredRoots) {
        this(configuredRoots, Path.of(System.getProperty("java.io.tmpdir"), "analyzer-coder").toString());
    }
    public Path validate(String rawPath) {
        return validateUnder(rawPath, allowedRoots, "Repository path is outside configured allowed roots");
    }

    public Path validateManaged(String rawPath) {
        return validateUnder(rawPath, List.of(managedRoot), "Managed import path escaped managed data root");
    }

    private static Path validateUnder(String rawPath, List<Path> roots, String outsideMessage) {
        Path candidate = realPath(Path.of(rawPath));
        if (!Files.isDirectory(candidate) || !Files.isReadable(candidate))
            throw new IllegalArgumentException("仓库路径必须是可读取的目录");
        if (roots.stream().noneMatch(candidate::startsWith)) throw new IllegalArgumentException(outsideMessage);
        return candidate;
    }

    private static Path realPath(String value) { return realPath(Path.of(value)); }
    private static Path realPath(Path path) {
        try { return path.toAbsolutePath().normalize().toRealPath(); }
        catch (IOException exception) { throw new IllegalArgumentException("配置的路径不存在或无法解析", exception); }
    }
}
