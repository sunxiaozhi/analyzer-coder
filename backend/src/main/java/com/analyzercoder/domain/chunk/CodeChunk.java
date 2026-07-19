package com.analyzercoder.domain.chunk;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public record CodeChunk(
    CodeChunkId id,
    CodeRepositoryId repositoryId,
    String commitSha,
    String filePath,
    String symbolId,
    String symbolName,
    String symbolKind,
    String language,
    ChunkType chunkType,
    Integer startLine,
    Integer endLine,
    String content,
    String contentHash,
    Instant createdAt
) {

    public static CodeChunk fileChunk(
        CodeRepositoryId repositoryId,
        String commitSha,
        String filePath,
        String language,
        int startLine,
        int endLine,
        String content
    ) {
        return new CodeChunk(
            CodeChunkId.newId(),
            repositoryId,
            commitSha == null || commitSha.isBlank() ? "unknown" : commitSha,
            filePath,
            null,
            null,
            null,
            language,
            ChunkType.FILE,
            startLine,
            endLine,
            content,
            sha256(content),
            Instant.now()
        );
    }

    public CodeChunk {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(commitSha, "commitSha must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(chunkType, "chunkType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }
}
