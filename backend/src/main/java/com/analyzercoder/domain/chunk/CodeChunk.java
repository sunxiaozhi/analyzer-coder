package com.analyzercoder.domain.chunk;

import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** 描述代码片段的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record CodeChunk(
        CodeChunkId id,
        CodeRepositoryId repositoryId,
        RepositorySnapshotId snapshotId,
        String commitSha,
        String filePath,
        String symbolId,
        String symbolName,
        String symbolKind,
        String language,
        RepositoryAssetType assetType,
        ChunkType chunkType,
        Integer startLine,
        Integer endLine,
        String content,
        String contentHash,
        Instant createdAt) {
    public static CodeChunk fileChunk(
            CodeRepositoryId repositoryId,
            RepositorySnapshotId snapshotId,
            String commitSha,
            String filePath,
            String language,
            RepositoryAssetType assetType,
            int startLine,
            int endLine,
            String content) {
        return create(
                repositoryId,
                snapshotId,
                commitSha,
                filePath,
                null,
                null,
                null,
                language,
                assetType,
                assetType == RepositoryAssetType.CONFIG
                        ? ChunkType.CONFIG
                        : ChunkType.FILE,
                startLine,
                endLine,
                content);
    }

    public static CodeChunk symbolChunk(
            CodeRepositoryId repositoryId,
            RepositorySnapshotId snapshotId,
            String commitSha,
            String filePath,
            String language,
            RepositoryAssetType assetType,
            String symbolName,
            String symbolKind,
            int startLine,
            int endLine,
            String content) {
        String symbolId = filePath + "#" + symbolName + ":" + startLine;
        return create(
                repositoryId,
                snapshotId,
                commitSha,
                filePath,
                symbolId,
                symbolName,
                symbolKind,
                language,
                assetType,
                assetType == RepositoryAssetType.CODE
                        ? ChunkType.SYMBOL
                        : assetType == RepositoryAssetType.CONFIG
                                ? ChunkType.CONFIG
                                : ChunkType.DOC_SECTION,
                startLine,
                endLine,
                content);
    }

    private static CodeChunk create(
            CodeRepositoryId repositoryId,
            RepositorySnapshotId snapshotId,
            String commitSha,
            String filePath,
            String symbolId,
            String symbolName,
            String symbolKind,
            String language,
            RepositoryAssetType assetType,
            ChunkType chunkType,
            int startLine,
            int endLine,
            String content) {
        return new CodeChunk(
                CodeChunkId.newId(),
                repositoryId,
                snapshotId,
                commitSha == null || commitSha.isBlank() ? "unknown" : commitSha,
                filePath,
                symbolId,
                symbolName,
                symbolKind,
                language,
                assetType,
                chunkType,
                startLine,
                endLine,
                content,
                sha256(content),
                Instant.now());
    }

    public CodeChunk {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(commitSha, "commitSha must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(chunkType, "chunkType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(assetType, "assetType must not be null");
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 摘要功能不可用", exception);
        }
    }
}
