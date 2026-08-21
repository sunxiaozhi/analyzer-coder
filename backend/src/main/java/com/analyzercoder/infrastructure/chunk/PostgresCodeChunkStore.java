package com.analyzercoder.infrastructure.chunk;

import com.analyzercoder.domain.chunk.ChunkType;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkId;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.infrastructure.persistence.mapper.CodeChunkMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeChunkRow;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 提供代码片段的存储实现，并负责领域对象与持久化数据之间的转换。 */
@Primary
@Repository
public class PostgresCodeChunkStore implements CodeChunkStore {
    private final CodeChunkMapper mapper;

    public PostgresCodeChunkStore(CodeChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void replaceRepositoryChunks(
            CodeRepositoryId repositoryId, Collection<CodeChunk> chunks) {
        mapper.deleteByRepositoryId(repositoryId.value());
        List<CodeChunkRow> rows = chunks.stream().map(PostgresCodeChunkStore::row).toList();
        for (int start = 0; start < rows.size(); start += 250) {
            mapper.insertBatch(rows.subList(start, Math.min(start + 250, rows.size())));
        }
    }

    @Override
    @Transactional
    public void replaceRepositoryPaths(
            CodeRepositoryId repositoryId,
            Collection<String> paths,
            Collection<CodeChunk> chunks,
            RepositorySnapshotId snapshotId,
            String commitSha) {
        if (!paths.isEmpty()) {
            mapper.deleteByPaths(repositoryId.value(), paths);
        }
        mapper.rebaseUnchanged(repositoryId.value(), paths, snapshotId.value(), commitSha);
        List<CodeChunkRow> rows = chunks.stream().map(PostgresCodeChunkStore::row).toList();
        for (int start = 0; start < rows.size(); start += 250) {
            mapper.insertBatch(rows.subList(start, Math.min(start + 250, rows.size())));
        }
    }

    @Override
    public String latestIndexedCommit(CodeRepositoryId repositoryId) {
        return mapper.latestIndexedCommit(repositoryId.value());
    }

    @Override
    public List<CodeChunk> findByRepositoryId(CodeRepositoryId id) {
        return mapper.find(id.value(), null, null, null).stream()
                .map(PostgresCodeChunkStore::domain)
                .toList();
    }

    @Override
    public List<CodeChunk> findByRepositoryId(CodeRepositoryId id, int limit, int offset) {
        return mapper.find(id.value(), null, limit, offset).stream()
                .map(PostgresCodeChunkStore::domain)
                .toList();
    }

    @Override
    public List<CodeChunk> searchByRepositoryId(
            CodeRepositoryId id, String query, int limit, int offset) {
        return mapper.find(id.value(), query, limit, offset).stream()
                .map(PostgresCodeChunkStore::domain)
                .toList();
    }

    @Override
    public long countByRepositoryId(CodeRepositoryId id) {
        return mapper.count(id.value(), null);
    }

    @Override
    public long countSearchByRepositoryId(CodeRepositoryId id, String query) {
        return mapper.count(id.value(), query);
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId id) {
        mapper.deleteByRepositoryId(id.value());
    }

    private static CodeChunkRow row(CodeChunk chunk) {
        return new CodeChunkRow(
                chunk.id().value(),
                chunk.repositoryId().value(),
                chunk.snapshotId().value(),
                chunk.commitSha(),
                chunk.filePath(),
                chunk.symbolId(),
                chunk.symbolName(),
                chunk.symbolKind(),
                chunk.language(),
                chunk.assetType().name(),
                chunk.chunkType().name(),
                chunk.startLine(),
                chunk.endLine(),
                chunk.content(),
                chunk.contentHash(),
                chunk.createdAt());
    }

    private static CodeChunk domain(CodeChunkRow row) {
        if (row.snapshotId() == null) {
            throw new IllegalStateException("代码片段缺少内容版本标识");
        }
        return new CodeChunk(
                CodeChunkId.of(row.id()),
                CodeRepositoryId.of(row.repositoryId()),
                RepositorySnapshotId.of(row.snapshotId()),
                row.commitSha(),
                row.filePath(),
                row.symbolId(),
                row.symbolName(),
                row.symbolKind(),
                row.language(),
                RepositoryAssetType.valueOf(row.assetType()),
                ChunkType.valueOf(row.chunkType()),
                row.startLine(),
                row.endLine(),
                row.content(),
                row.contentHash(),
                row.createdAt());
    }
}
