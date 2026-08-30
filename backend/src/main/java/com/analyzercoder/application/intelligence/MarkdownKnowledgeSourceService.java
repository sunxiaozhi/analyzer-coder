package com.analyzercoder.application.intelligence;

import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.indexing.ScannedRepositoryFile;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.infrastructure.persistence.mapper.MarkdownKnowledgeSourceMapper;
import com.analyzercoder.security.ApiSecurityException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Discovers repository Markdown and turns an exact source version into a reviewable knowledge card. */
@Service
public class MarkdownKnowledgeSourceService {
    private static final int MAX_CODE_REFERENCES = 30;
    private static final int MAX_BATCH_GENERATION = 100;
    private static final int MAX_CARD_CONTENT_LENGTH = 600_000;
    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("(?m)^\\s*#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final MarkdownKnowledgeSourceMapper mapper;
    private final CodeRepositoryStore repositories;
    private final IntelligenceService intelligence;

    public MarkdownKnowledgeSourceService(
            MarkdownKnowledgeSourceMapper mapper,
            CodeRepositoryStore repositories,
            IntelligenceService intelligence) {
        this.mapper = mapper;
        this.repositories = repositories;
        this.intelligence = intelligence;
    }

    /**
     * Synchronizes the complete Markdown manifest from a repository scan. Both full and incremental
     * indexing pass the complete scan so unchanged sources also advance to the new snapshot token.
     */
    @Transactional
    public void synchronize(
            CodeRepository repository,
            List<ScannedRepositoryFile> allFiles,
            boolean incremental,
            Set<String> changedPaths) {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(allFiles, "allFiles must not be null");
        UUID repositoryId = repository.id().value();
        UUID snapshotId = currentSnapshot(repository);

        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (ScannedRepositoryFile file : allFiles) {
            if (!isMarkdown(file)) {
                continue;
            }
            SourceDocument source = source(file);
            documents.put(source.path(), source);
        }

        List<SourceDocument> orderedDocuments =
                documents.values().stream()
                        .sorted(Comparator.comparing(SourceDocument::path))
                        .toList();
        for (SourceDocument source : orderedDocuments) {
            mapper.upsertSource(
                    UUID.randomUUID(),
                    repositoryId,
                    snapshotId,
                    source.path(),
                    source.contentHash(),
                    source.title(),
                    source.assetType(),
                    source.content(),
                    source.lineCount(),
                    source.byteSize());
        }

        // allFiles is the complete current scan even for incremental jobs. Replacing the manifest
        // prevents removed or renamed Markdown from remaining visible as a current source.
        mapper.deleteMissingSources(repositoryId, new ArrayList<>(documents.keySet()));
        mapper.reconcileLinkedCards(
                repositoryId,
                repository.currentSnapshotId().value(),
                repository.currentCommit(),
                repository.currentCommit() != null && !repository.currentCommit().isBlank());
    }

    @Transactional(readOnly = true)
    public MarkdownSourceList list(UUID repositoryId) {
        CodeRepository repository = repository(repositoryId);
        UUID snapshotId = currentSnapshot(repository);
        List<MarkdownSource> items =
                mapper.listSources(repositoryId, snapshotId).stream()
                        .map(MarkdownKnowledgeSourceService::sourceView)
                        .toList();
        long current = items.stream().filter(item -> "CURRENT".equals(item.status())).count();
        long stale = items.stream().filter(item -> "STALE".equals(item.status())).count();
        long pending = items.size() - current - stale;
        return new MarkdownSourceList(
                snapshotId,
                new Counts(items.size(), pending, current, stale),
                items);
    }

    @Transactional
    public IntelligenceService.KnowledgeCard generate(
            UUID repositoryId, UUID actorId, GenerateInput input) {
        if (input == null
                || input.expectedSnapshotId() == null
                || input.sourcePath() == null
                || input.expectedContentHash() == null) {
            throw new IllegalArgumentException("Markdown 来源参数不能为空");
        }
        String sourcePath = normalizePath(input.sourcePath());
        String expectedHash = normalizeHash(input.expectedContentHash());
        CodeRepository repository = repository(repositoryId);
        verifyExpectedSnapshot(repository, input.expectedSnapshotId());
        Map<String, Object> source = lockCurrentSource(repositoryId, sourcePath);
        verifySourceVersion(source, input.expectedSnapshotId(), expectedHash);
        Map<String, Object> view =
                mapper.findSource(repositoryId, input.expectedSnapshotId(), sourcePath);
        if (view == null) {
            throw sourceChanged();
        }
        return generateSource(repositoryId, actorId, view, false);
    }

    @Transactional
    public BatchGenerationResult generatePending(
            UUID repositoryId, UUID actorId, UUID expectedSnapshotId) {
        if (expectedSnapshotId == null) {
            throw new IllegalArgumentException("预期快照不能为空");
        }
        CodeRepository repository = repository(repositoryId);
        verifyExpectedSnapshot(repository, expectedSnapshotId);
        List<Map<String, Object>> candidates = mapper.listSources(repositoryId, expectedSnapshotId);
        int pendingTotal =
                (int)
                        candidates.stream()
                                .filter(
                                        candidate ->
                                                "PENDING".equals(
                                                        string(candidate, "source_status")))
                                .count();
        int generated = 0;
        for (Map<String, Object> candidate : candidates) {
            if (generated >= MAX_BATCH_GENERATION) {
                break;
            }
            if (!"PENDING".equals(string(candidate, "source_status"))) {
                continue;
            }
            String sourcePath = string(candidate, "source_path");
            Map<String, Object> locked = lockCurrentSource(repositoryId, sourcePath);
            verifySourceVersion(
                    locked,
                    expectedSnapshotId,
                    string(candidate, "source_content_hash"));
            Map<String, Object> current =
                    mapper.findSource(repositoryId, expectedSnapshotId, sourcePath);
            if (current == null || !"PENDING".equals(string(current, "source_status"))) {
                continue;
            }
            IntelligenceService.KnowledgeCard card =
                    generateSource(repositoryId, actorId, current, true);
            if (card != null) {
                generated++;
            }
        }
        return new BatchGenerationResult(generated, Math.max(0, pendingTotal - generated));
    }

    private IntelligenceService.KnowledgeCard generateSource(
            UUID repositoryId,
            UUID actorId,
            Map<String, Object> source,
            boolean pendingOnly) {
        String status = string(source, "source_status");
        UUID linkedCardId = uuid(source, "card_id");
        if (pendingOnly && !"PENDING".equals(status)) {
            return null;
        }
        if ("CURRENT".equals(status)) {
            return card(repositoryId, linkedCardId);
        }

        String content = string(source, "content");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Markdown 来源内容为空");
        }
        if (content.length() > MAX_CARD_CONTENT_LENGTH) {
            throw new ApiSecurityException(
                    409,
                    "MARKDOWN_SOURCE_TOO_LARGE",
                    "Markdown 内容超过知识卡片 600000 字符限制，暂不能直接生成");
        }

        UUID snapshotId = uuid(source, "source_snapshot_id");
        String sourcePath = string(source, "source_path");
        List<IntelligenceService.CodeReferenceInput> references =
                mapper.findChunkIds(
                                repositoryId,
                                snapshotId,
                                sourcePath,
                                MAX_CODE_REFERENCES)
                        .stream()
                        .map(IntelligenceService.CodeReferenceInput::new)
                        .toList();

        IntelligenceService.KnowledgeCard previous =
                "STALE".equals(status) ? card(repositoryId, linkedCardId) : null;
        IntelligenceService.CardInput cardInput =
                new IntelligenceService.CardInput(
                        string(source, "title"),
                        cardType(string(source, "asset_type"), sourcePath),
                        content,
                        tags(previous, string(source, "asset_type")),
                        previous == null ? "REFERENCE" : previous.knowledgeKind().name(),
                        previous == null ? "INFO" : previous.severity().name(),
                        previous == null ? "REFERENCE" : previous.enforcement().name(),
                        previous == null ? null : previous.ownerAccountId(),
                        previous == null ? KnowledgeScope.empty() : previous.scope(),
                        previous == null ? KnowledgeObligations.empty() : previous.obligations(),
                        previous == null
                                ? List.of()
                                : previous.attachments().stream()
                                        .map(KnowledgeAttachmentService.Attachment::id)
                                        .toList(),
                        references);

        IntelligenceService.KnowledgeCard generated =
                previous == null
                        ? intelligence.createCard(repositoryId, actorId, cardInput)
                        : intelligence.updateCard(
                                repositoryId, previous.id(), actorId, cardInput);
        mapper.insertProvenance(
                generated.id(),
                generated.revision(),
                uuid(source, "source_id"),
                repositoryId,
                snapshotId,
                sourcePath,
                string(source, "source_content_hash"));
        return generated;
    }

    private Map<String, Object> lockCurrentSource(UUID repositoryId, String sourcePath) {
        Map<String, Object> source = mapper.lockSource(repositoryId, sourcePath);
        if (source == null) {
            throw sourceChanged();
        }
        return source;
    }

    private static void verifySourceVersion(
            Map<String, Object> source, UUID expectedSnapshotId, String expectedHash) {
        if (!expectedSnapshotId.equals(uuid(source, "source_snapshot_id"))
                || !expectedSnapshotId.equals(uuid(source, "repository_snapshot_id"))
                || !normalizeHash(expectedHash)
                        .equals(normalizeHash(string(source, "source_content_hash")))) {
            throw sourceChanged();
        }
    }

    private static void verifyExpectedSnapshot(
            CodeRepository repository, UUID expectedSnapshotId) {
        if (!currentSnapshot(repository).equals(expectedSnapshotId)) {
            throw sourceChanged();
        }
    }

    private IntelligenceService.KnowledgeCard card(UUID repositoryId, UUID cardId) {
        if (cardId == null) {
            throw new IllegalStateException("Markdown 来源缺少关联知识卡片");
        }
        return intelligence.cards(repositoryId, true).stream()
                .filter(item -> item.id().equals(cardId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Markdown 关联知识卡片不存在"));
    }

    private CodeRepository repository(UUID repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("仓库标识不能为空");
        }
        return repositories
                .findById(CodeRepositoryId.of(repositoryId))
                .orElseThrow(() -> new IllegalArgumentException("仓库不存在"));
    }

    private static UUID currentSnapshot(CodeRepository repository) {
        if (repository.currentSnapshotId() == null) {
            throw new ApiSecurityException(
                    409, "MARKDOWN_SOURCE_NOT_READY", "仓库尚未发布可读取的内容快照");
        }
        return repository.currentSnapshotId().value();
    }

    private static boolean isMarkdown(ScannedRepositoryFile file) {
        return file != null
                && "markdown".equalsIgnoreCase(file.language())
                && (file.assetType() == RepositoryAssetType.DOCUMENT
                        || file.assetType() == RepositoryAssetType.RULE
                        || file.assetType() == RepositoryAssetType.TASK);
    }

    private static SourceDocument source(ScannedRepositoryFile file) {
        String path = normalizePath(file.relativePath());
        byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
        return new SourceDocument(
                path,
                title(file.content(), path),
                file.assetType().name(),
                file.content(),
                sha256(bytes),
                Math.max(1, file.lineCount()),
                bytes.length);
    }

    private static String title(String content, String path) {
        Matcher heading = MARKDOWN_HEADING.matcher(content == null ? "" : content);
        String value;
        if (heading.find()) {
            value = heading.group(1).trim();
        } else {
            int slash = path.lastIndexOf('/');
            value = slash < 0 ? path : path.substring(slash + 1);
            int extension = value.lastIndexOf('.');
            if (extension > 0) {
                value = value.substring(0, extension);
            }
        }
        if (value.isBlank()) {
            value = "未命名 Markdown";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private static String cardType(String assetType, String path) {
        if ("RULE".equals(assetType)) {
            return "项目规则";
        }
        if ("TASK".equals(assetType)) {
            return "任务说明";
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String fileName = slash < 0 ? normalized : normalized.substring(slash + 1);
        if ("readme.md".equals(fileName) || "readme.mdx".equals(fileName)) {
            return "项目说明";
        }
        if (normalized.contains("/adr/")
                || normalized.startsWith("adr/")
                || fileName.startsWith("adr-")
                || normalized.contains("architecture")
                || normalized.contains("design")) {
            return "架构设计";
        }
        return "项目文档";
    }

    private static List<String> tags(
            IntelligenceService.KnowledgeCard previous, String assetType) {
        Set<String> values = new LinkedHashSet<>();
        if (previous != null) {
            values.addAll(previous.tags());
        }
        values.add("markdown");
        values.add("repository-source");
        if (assetType != null && !assetType.isBlank()) {
            values.add(assetType.toLowerCase(Locale.ROOT));
        }
        return values.stream().limit(20).toList();
    }

    private static MarkdownSource sourceView(Map<String, Object> row) {
        return new MarkdownSource(
                uuid(row, "source_id"),
                string(row, "source_path"),
                uuid(row, "source_snapshot_id"),
                string(row, "source_content_hash"),
                string(row, "title"),
                string(row, "asset_type"),
                integer(row, "line_count"),
                longValue(row, "byte_size"),
                string(row, "source_status"),
                uuid(row, "card_id"),
                integerNullable(row, "card_revision"),
                uuid(row, "generated_snapshot_id"),
                string(row, "generated_content_hash"));
    }

    private static String normalizePath(String value) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isBlank() || path.startsWith("/") || path.length() > 2_000) {
            throw new IllegalArgumentException("Markdown 来源路径无效");
        }
        for (String part : path.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Markdown 来源路径无效");
            }
        }
        return path;
    }

    private static String normalizeHash(String value) {
        String hash = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Markdown 内容摘要无效");
        }
        return hash;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 摘要功能不可用", exception);
        }
    }

    private static ApiSecurityException sourceChanged() {
        return new ApiSecurityException(
                409, "MARKDOWN_SOURCE_CHANGED", "Markdown 来源已变化，请刷新后重试");
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object result = row.get(key);
        if (result == null) {
            result = row.get(key.toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static String string(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result == null ? null : String.valueOf(result).trim();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return null;
        }
        return result instanceof UUID id ? id : UUID.fromString(result.toString());
    }

    private static int integer(Map<String, Object> row, String key) {
        Integer result = integerNullable(row, key);
        return result == null ? 0 : result;
    }

    private static Integer integerNullable(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return null;
        }
        return result instanceof Number number
                ? number.intValue()
                : Integer.valueOf(result.toString());
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return 0;
        }
        return result instanceof Number number
                ? number.longValue()
                : Long.parseLong(result.toString());
    }

    private record SourceDocument(
            String path,
            String title,
            String assetType,
            String content,
            String contentHash,
            int lineCount,
            long byteSize) {}

    public record GenerateInput(
            String sourcePath, UUID expectedSnapshotId, String expectedContentHash) {}

    public record MarkdownSourceList(UUID snapshotId, Counts counts, List<MarkdownSource> items) {}

    public record Counts(long total, long pending, long current, long stale) {}

    public record MarkdownSource(
            UUID sourceId,
            String sourcePath,
            UUID sourceSnapshotId,
            String sourceContentHash,
            String title,
            String assetType,
            int lineCount,
            long byteSize,
            String status,
            UUID cardId,
            Integer cardRevision,
            UUID generatedSnapshotId,
            String generatedContentHash) {}

    public record BatchGenerationResult(int generated, int remaining) {}
}
