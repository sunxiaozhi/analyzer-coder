package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.chunk.CodeChunkQueryResult;
import com.analyzercoder.application.chunk.CodeChunkQueryService;
import com.analyzercoder.domain.chunk.ChunkType;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/chunks")
public class ChunkController {

    private final CodeChunkQueryService codeChunkQueryService;

    public ChunkController(CodeChunkQueryService codeChunkQueryService) {
        this.codeChunkQueryService = codeChunkQueryService;
    }

    @GetMapping
    public CodeChunkListResponse list(
        @PathVariable UUID repositoryId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Integer offset
    ) {
        CodeChunkQueryResult result = codeChunkQueryService.list(CodeRepositoryId.of(repositoryId), q, limit, offset);
        return CodeChunkListResponse.from(result);
    }

    public record CodeChunkListResponse(
        long total,
        int limit,
        int offset,
        List<CodeChunkResponse> chunks
    ) {

        public static CodeChunkListResponse from(CodeChunkQueryResult result) {
            return new CodeChunkListResponse(
                result.total(),
                result.limit(),
                result.offset(),
                result.chunks().stream()
                    .map(CodeChunkResponse::from)
                    .toList()
            );
        }
    }

    public record CodeChunkResponse(
        UUID id,
        UUID repositoryId,
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

        public static CodeChunkResponse from(CodeChunk chunk) {
            return new CodeChunkResponse(
                chunk.id().value(),
                chunk.repositoryId().value(),
                chunk.commitSha(),
                chunk.filePath(),
                chunk.symbolId(),
                chunk.symbolName(),
                chunk.symbolKind(),
                chunk.language(),
                chunk.chunkType(),
                chunk.startLine(),
                chunk.endLine(),
                chunk.content(),
                chunk.contentHash(),
                chunk.createdAt()
            );
        }
    }
}
