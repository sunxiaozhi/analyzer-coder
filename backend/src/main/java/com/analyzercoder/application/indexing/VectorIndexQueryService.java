package com.analyzercoder.application.indexing;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.infrastructure.persistence.mapper.VectorIndexQueryMapper;
import com.github.pagehelper.PageHelper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 编排向量索引查询相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class VectorIndexQueryService {
    private final VectorIndexQueryMapper mapper;

    public VectorIndexQueryService(VectorIndexQueryMapper mapper) {
        this.mapper = mapper;
    }

    public Summary summary(UUID repositoryId) {
        Map<String, Object> row = mapper.summary(repositoryId);
        if (row == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        return new Summary(
                uuid(row, "repository_id"),
                uuid(row, "snapshot_id"),
                string(row, "commit_sha"),
                number(row, "total_chunks").longValue(),
                number(row, "vectorized_chunks").longValue(),
                number(row, "missing_chunks").longValue(),
                number(row, "knowledge_cards").longValue(),
                number(row, "vectorized_knowledge_cards").longValue(),
                string(row, "vector_model"),
                integer(row, "dimension"),
                instant(row, "updated_at"));
    }

    public PageResult<ChunkItem> chunks(
            UUID repositoryId,
            String query,
            String status,
            String chunkType,
            int pageNum,
            int pageSize) {
        PageResult.validate(pageNum, pageSize);
        PageHelper.startPage(pageNum, pageSize);
        return PageResult.fromPage(
                        mapper.chunks(
                                repositoryId,
                                normalized(query),
                                status(status),
                                normalized(chunkType)))
                .map(this::chunkItem);
    }

    public PageResult<KnowledgeItem> knowledge(
            UUID repositoryId, String query, String status, int pageNum, int pageSize) {
        PageResult.validate(pageNum, pageSize);
        PageHelper.startPage(pageNum, pageSize);
        return PageResult.fromPage(
                        mapper.knowledge(repositoryId, normalized(query), status(status)))
                .map(this::knowledgeItem);
    }

    private ChunkItem chunkItem(Map<String, Object> row) {
        return new ChunkItem(
                uuid(row, "id"),
                uuid(row, "snapshot_id"),
                string(row, "commit_sha"),
                string(row, "file_path"),
                string(row, "symbol_name"),
                string(row, "symbol_kind"),
                string(row, "language"),
                string(row, "chunk_type"),
                integer(row, "start_line"),
                integer(row, "end_line"),
                string(row, "content_excerpt"),
                string(row, "content_hash"),
                string(row, "vector_model"),
                integer(row, "dimension"),
                instant(row, "vectorized_at"),
                string(row, "vector_status"));
    }

    private KnowledgeItem knowledgeItem(Map<String, Object> row) {
        return new KnowledgeItem(
                uuid(row, "id"),
                string(row, "title"),
                string(row, "card_type"),
                integer(row, "revision"),
                string(row, "content_excerpt"),
                string(row, "content_hash"),
                string(row, "vector_model"),
                integer(row, "dimension"),
                instant(row, "vectorized_at"),
                string(row, "vector_status"));
    }

    private static String status(String value) {
        String normalized = normalized(value);
        if (normalized == null) {
            return null;
        }
        String resolved = normalized.toUpperCase(Locale.ROOT);
        if (!List.of("EMBEDDED", "MISSING").contains(resolved)) {
            throw new IllegalArgumentException("向量状态仅支持 EMBEDDED 或 MISSING");
        }
        return resolved;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Object value(Map<String, Object> row, String key) {
        Object found = row.get(key);
        return found == null ? row.get(key.toUpperCase(Locale.ROOT)) : found;
    }

    private static String string(Map<String, Object> row, String key) {
        Object found = value(row, key);
        return found == null ? null : found.toString();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object found = value(row, key);
        if (found == null) {
            return null;
        }
        return found instanceof UUID id ? id : UUID.fromString(found.toString());
    }

    private static Number number(Map<String, Object> row, String key) {
        Object found = value(row, key);
        return found instanceof Number number ? number : 0;
    }

    private static Integer integer(Map<String, Object> row, String key) {
        Object found = value(row, key);
        return found instanceof Number number ? number.intValue() : null;
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object found = value(row, key);
        if (found == null) {
            return null;
        }
        if (found instanceof Instant instant) {
            return instant;
        }
        if (found instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (found instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return Instant.parse(found.toString());
    }

    public record Summary(
            UUID repositoryId,
            UUID snapshotId,
            String commitSha,
            long totalChunks,
            long vectorizedChunks,
            long missingChunks,
            long knowledgeCards,
            long vectorizedKnowledgeCards,
            String vectorModel,
            Integer dimension,
            Instant updatedAt) {}

    public record ChunkItem(
            UUID id,
            UUID snapshotId,
            String commitSha,
            String filePath,
            String symbolName,
            String symbolKind,
            String language,
            String chunkType,
            Integer startLine,
            Integer endLine,
            String contentExcerpt,
            String contentHash,
            String vectorModel,
            Integer dimension,
            Instant vectorizedAt,
            String vectorStatus) {}

    public record KnowledgeItem(
            UUID id,
            String title,
            String cardType,
            Integer revision,
            String contentExcerpt,
            String contentHash,
            String vectorModel,
            Integer dimension,
            Instant vectorizedAt,
            String vectorStatus) {}
}
