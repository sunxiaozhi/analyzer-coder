package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RepositoryCodeBrowserService {
    private final RegisterRepositoryUseCase repositories;
    private final long maxPreviewBytes;

    public RepositoryCodeBrowserService(
        RegisterRepositoryUseCase repositories,
        @Value("${app.repository.browser-max-file-bytes:2097152}") long maxPreviewBytes
    ) {
        this.repositories = repositories;
        this.maxPreviewBytes = maxPreviewBytes;
    }

    public SnapshotFiles list(CodeRepositoryId repositoryId) {
        CodeRepository repository = published(repositoryId);
        Path root = repository.currentSnapshotPath().toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            List<FileEntry> files = paths
                .filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path))
                .map(path -> entry(root, path))
                .sorted(Comparator.comparing(FileEntry::path, String.CASE_INSENSITIVE_ORDER))
                .toList();
            return new SnapshotFiles(
                repository.currentSnapshotId().value().toString(),
                repository.defaultBranch(),
                repository.currentCommit(),
                files
            );
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取当前代码快照", exception);
        }
    }

    public FileContent read(CodeRepositoryId repositoryId, String requestedPath) {
        CodeRepository repository = published(repositoryId);
        Path root = repository.currentSnapshotPath().toAbsolutePath().normalize();
        Path file = resolve(root, requestedPath);
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new IllegalArgumentException("文件不存在于当前代码快照");
            }
            long size = Files.size(file);
            if (size > maxPreviewBytes) {
                throw new IllegalArgumentException("文件超过在线预览大小限制");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (containsNullByte(bytes)) {
                throw new IllegalArgumentException("二进制文件不支持在线预览");
            }
            String content = decodeText(file, bytes);
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            String path = portable(root.relativize(file));
            return new FileContent(
                repository.currentSnapshotId().value().toString(),
                path,
                file.getFileName().toString(),
                language(path),
                size,
                lineCount(content),
                content
            );
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取快照文件", exception);
        }
    }

    private CodeRepository published(CodeRepositoryId repositoryId) {
        CodeRepository repository = repositories.get(repositoryId);
        if (repository.currentSnapshotId() == null || repository.currentSnapshotPath() == null) {
            throw new IllegalStateException("仓库尚未发布可浏览的代码快照");
        }
        return repository;
    }

    private static FileEntry entry(Path root, Path file) {
        try {
            String path = portable(root.relativize(file));
            return new FileEntry(path, file.getFileName().toString(), language(path), Files.size(file));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取快照文件元数据", exception);
        }
    }

    private static Path resolve(Path root, String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        String portable = requestedPath.replace('\\', '/');
        if (portable.startsWith("/") || portable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("文件路径无效");
        }
        Path relative;
        try {
            relative = Path.of(portable).normalize();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("文件路径无效", exception);
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("文件路径不能超出代码快照");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("文件路径不能超出代码快照");
        }
        return resolved;
    }

    private static String decodeText(Path path, byte[] bytes) {
        try {
            return decode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8Failure) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".bat") && !name.endsWith(".cmd")) {
                throw new IllegalArgumentException("文件不是可预览的 UTF-8 文本", utf8Failure);
            }
            try {
                return decode(bytes, Charset.forName("GB18030"));
            } catch (CharacterCodingException legacyFailure) {
                throw new IllegalArgumentException("批处理文件编码无法识别", legacyFailure);
            }
        }
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private static boolean containsNullByte(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private static int lineCount(String content) {
        if (content.isEmpty()) return 0;
        int lines = 1;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') lines++;
        }
        return content.endsWith("\n") ? lines - 1 : lines;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String language(String path) {
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("dockerfile")) return "dockerfile";
        if (name.equals("makefile")) return "makefile";
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "js", "mjs", "cjs" -> "javascript";
            case "jsx" -> "jsx";
            case "vue" -> "vue";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "c", "h" -> "c";
            case "cc", "cpp", "cxx", "hpp" -> "cpp";
            case "cs" -> "csharp";
            case "php" -> "php";
            case "rb" -> "ruby";
            case "sh", "bash", "zsh" -> "shell";
            case "bat", "cmd" -> "batch";
            case "md", "mdx" -> "markdown";
            case "yml", "yaml" -> "yaml";
            case "json" -> "json";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css", "scss", "sass", "less" -> "css";
            case "sql" -> "sql";
            case "properties", "toml", "ini", "conf", "env" -> extension;
            default -> extension.isBlank() ? "text" : extension;
        };
    }

    public record SnapshotFiles(String snapshotId, String branch, String commit, List<FileEntry> files) {}
    public record FileEntry(String path, String name, String language, long sizeBytes) {}
    public record FileContent(
        String snapshotId,
        String path,
        String name,
        String language,
        long sizeBytes,
        int lineCount,
        String content
    ) {}
}
