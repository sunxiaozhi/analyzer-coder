package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.chunk.CodeChunkQueryResult;
import com.analyzercoder.application.chunk.CodeChunkQueryService;
import com.analyzercoder.domain.chunk.ChunkType;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供代码片段相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api/repositories/{repositoryId}/chunks")
public class ChunkController {
    private final CodeChunkQueryService queryService;
    private final AccessControlService accessControl;

    public ChunkController(CodeChunkQueryService queryService, AccessControlService accessControl) {
        this.queryService = queryService;
        this.accessControl = accessControl;
    }

    @GetMapping
    public CodeChunkListResponse list(
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return CodeChunkListResponse.from(queryService.list(id, q, limit, offset));
    }

    public record CodeChunkListResponse(
            long total, int limit, int offset, List<CodeChunkResponse> chunks) {
        public static CodeChunkListResponse from(CodeChunkQueryResult result) {
            return new CodeChunkListResponse(
                    result.total(),
                    result.limit(),
                    result.offset(),
                    result.chunks().stream().map(CodeChunkResponse::from).toList());
        }
    }

    public record CodeChunkResponse(
            UUID id,
            UUID repositoryId,
            UUID snapshotId,
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
        public static CodeChunkResponse from(CodeChunk chunk) {
            return new CodeChunkResponse(
                    chunk.id().value(),
                    chunk.repositoryId().value(),
                    chunk.snapshotId().value(),
                    chunk.commitSha(),
                    chunk.filePath(),
                    chunk.symbolId(),
                    chunk.symbolName(),
                    chunk.symbolKind(),
                    chunk.language(),
                    chunk.assetType(),
                    chunk.chunkType(),
                    chunk.startLine(),
                    chunk.endLine(),
                    chunk.content(),
                    chunk.contentHash(),
                    chunk.createdAt());
        }
    }
}
