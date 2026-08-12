package com.analyzercoder.application.intelligence;

import com.analyzercoder.infrastructure.persistence.mapper.KnowledgeAttachmentMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 编排知识附件相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class KnowledgeAttachmentService {
    private static final long IMAGE_LIMIT = 10L * 1024 * 1024;
    private static final long FILE_LIMIT = 50L * 1024 * 1024;
    private static final long REVISION_LIMIT = 200L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final Set<String> FILE_EXTENSIONS =
            Set.of("pdf", "txt", "md", "csv", "json", "docx", "xlsx", "pptx");
    private final KnowledgeAttachmentMapper mapper;
    private final Path managedRoot;

    public KnowledgeAttachmentService(
            KnowledgeAttachmentMapper mapper,
            @Value("${app.repository.managed-data-root:${java.io.tmpdir}/analyzer-coder}")
                    String managedRoot) {
        this.mapper = mapper;
        this.managedRoot = Path.of(managedRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public Attachment upload(UUID repositoryId, UUID actorId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("附件不能为空");
        }
        String originalName = safeName(file.getOriginalFilename());
        String extension = extension(originalName);
        boolean image = IMAGE_EXTENSIONS.contains(extension);
        if (!image && !FILE_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持此附件类型");
        }
        long limit = image ? IMAGE_LIMIT : FILE_LIMIT;
        if (file.getSize() <= 0 || file.getSize() > limit) {
            throw new IllegalArgumentException(image ? "图片不能超过 10 MiB" : "附件不能超过 50 MiB");
        }

        Path temporary =
                managedRoot
                        .resolve("staging")
                        .resolve("knowledge")
                        .resolve(UUID.randomUUID().toString())
                        .normalize();
        if (!temporary.startsWith(managedRoot)) {
            throw new IllegalStateException("附件临时路径越界");
        }
        try {
            Files.createDirectories(temporary.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = file.getInputStream();
                    var output = Files.newOutputStream(temporary)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    size += read;
                    if (size > limit) {
                        throw new IllegalArgumentException(
                                image ? "图片不能超过 10 MiB" : "附件不能超过 50 MiB");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            verifySignature(temporary, extension);
            Path target =
                    managedRoot
                            .resolve("repositories")
                            .resolve(repositoryId.toString())
                            .resolve("knowledge")
                            .resolve("objects")
                            .resolve(sha.substring(0, 2))
                            .resolve(sha)
                            .normalize();
            if (!target.startsWith(managedRoot)) {
                throw new IllegalStateException("附件存储路径越界");
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Files.deleteIfExists(temporary);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            UUID id = UUID.randomUUID();
            String mediaType = normalizedMediaType(file.getContentType(), extension);
            mapper.insert(
                    id,
                    repositoryId,
                    originalName,
                    mediaType,
                    size,
                    sha,
                    target.toString(),
                    actorId,
                    Instant.now());
            return new Attachment(id, originalName, mediaType, size, sha, "READY", Instant.now());
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("附件保存失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("SHA-256 不可用", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        }
    }

    @Transactional
    public void attach(UUID repositoryId, UUID cardId, int revision, List<UUID> attachmentIds) {
        List<UUID> ids =
                attachmentIds == null
                        ? previousIds(repositoryId, cardId, revision - 1)
                        : attachmentIds.stream().distinct().toList();
        if (ids.size() > 20) {
            throw new IllegalArgumentException("每个修订最多 20 个附件");
        }
        long bytes = 0;
        int position = 0;
        for (UUID id : ids) {
            Map<String, Object> row = mapper.find(repositoryId, id);
            if (row == null) {
                throw new IllegalArgumentException("附件不存在或不属于当前仓库");
            }
            bytes += number(row, "size_bytes");
            if (bytes > REVISION_LIMIT) {
                throw new IllegalArgumentException("单个修订附件总量不能超过 200 MiB");
            }
            if (mapper.insertRef(cardId, revision, id, position++) != 1) {
                throw new IllegalArgumentException("附件关联失败");
            }
        }
    }

    public List<Attachment> list(UUID repositoryId, UUID cardId, int revision) {
        return mapper.listForCard(repositoryId, cardId, revision).stream()
                .map(KnowledgeAttachmentService::view)
                .toList();
    }

    public Download download(UUID repositoryId, UUID attachmentId) {
        Map<String, Object> row = mapper.find(repositoryId, attachmentId);
        if (row == null) {
            throw new IllegalArgumentException("附件不存在");
        }
        Path path = Path.of(string(row, "storage_path")).toAbsolutePath().normalize();
        if (!path.startsWith(managedRoot) || !Files.isRegularFile(path)) {
            throw new IllegalStateException("附件文件不可用");
        }
        return new Download(
                path,
                string(row, "original_name"),
                string(row, "media_type"),
                number(row, "size_bytes"));
    }

    private List<UUID> previousIds(UUID repositoryId, UUID cardId, int revision) {
        if (revision < 1) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> row : mapper.listForCard(repositoryId, cardId, revision)) {
            ids.add(UUID.fromString(string(row, "id")));
        }
        return ids;
    }

    private static void verifySignature(Path path, String extension) throws IOException {
        byte[] head = new byte[12];
        int count;
        try (InputStream input = Files.newInputStream(path)) {
            count = input.read(head);
        }
        if ("png".equals(extension)
                && !(count >= 8
                        && head[0] == (byte) 0x89
                        && head[1] == 0x50
                        && head[2] == 0x4E
                        && head[3] == 0x47)) {
            throw new IllegalArgumentException("图片内容与 PNG 扩展名不符");
        }
        if (Set.of("jpg", "jpeg").contains(extension)
                && !(count >= 3
                        && head[0] == (byte) 0xFF
                        && head[1] == (byte) 0xD8
                        && head[2] == (byte) 0xFF)) {
            throw new IllegalArgumentException("图片内容与 JPEG 扩展名不符");
        }
        if ("gif".equals(extension) && !(count >= 6 && new String(head, 0, 3).equals("GIF"))) {
            throw new IllegalArgumentException("图片内容与 GIF 扩展名不符");
        }
        if ("pdf".equals(extension) && !(count >= 5 && new String(head, 0, 5).equals("%PDF-"))) {
            throw new IllegalArgumentException("附件内容与 PDF 扩展名不符");
        }
        if (Set.of("docx", "xlsx", "pptx").contains(extension)
                && !(count >= 4 && head[0] == 0x50 && head[1] == 0x4B)) {
            throw new IllegalArgumentException("Office 附件格式不正确");
        }
    }

    private static Attachment view(Map<String, Object> row) {
        return new Attachment(
                UUID.fromString(string(row, "id")),
                string(row, "original_name"),
                string(row, "media_type"),
                number(row, "size_bytes"),
                string(row, "sha256"),
                string(row, "scan_status"),
                instant(row, "created_at"));
    }

    private static String safeName(String value) {
        String name = value == null ? "attachment" : Path.of(value).getFileName().toString().trim();
        if (name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("附件名称无效");
        }
        return name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedMediaType(String supplied, String ext) {
        if (supplied != null
                && !supplied.isBlank()
                && !supplied.equals("application/octet-stream")) {
            return supplied;
        }
        return IMAGE_EXTENSIONS.contains(ext)
                ? "image/" + (ext.equals("jpg") ? "jpeg" : ext)
                : "application/octet-stream";
    }

    private static Object value(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? row.get(key.toUpperCase(Locale.ROOT)) : v;
    }

    private static String string(Map<String, Object> row, String key) {
        Object v = value(row, key);
        return v == null ? null : v.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) value(row, key)).longValue();
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object v = value(row, key);
        return v instanceof Instant i ? i : ((java.sql.Timestamp) v).toInstant();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public record Attachment(
            UUID id,
            String originalName,
            String mediaType,
            long sizeBytes,
            String sha256,
            String scanStatus,
            Instant createdAt) {}

    public record Download(Path path, String originalName, String mediaType, long sizeBytes) {}
}
