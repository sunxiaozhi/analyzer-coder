package com.analyzercoder.application.chunk;

import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.util.List;
import org.springframework.stereotype.Service;

/** 编排代码片段相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class CodeChunkQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final CodeRepositoryStore repositoryStore;
    private final CodeChunkStore codeChunkStore;

    public CodeChunkQueryService(
            CodeRepositoryStore repositoryStore, CodeChunkStore codeChunkStore) {
        this.repositoryStore = repositoryStore;
        this.codeChunkStore = codeChunkStore;
    }

    public CodeChunkQueryResult list(
            CodeRepositoryId repositoryId, String query, Integer limit, Integer offset) {
        repositoryStore
                .findById(repositoryId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Repository not found: " + repositoryId.value()));

        int resolvedLimit = resolveLimit(limit);
        int resolvedOffset = resolveOffset(offset);
        String normalizedQuery = query == null ? "" : query.trim();

        if (normalizedQuery.isBlank()) {
            List<CodeChunk> chunks =
                    codeChunkStore.findByRepositoryId(repositoryId, resolvedLimit, resolvedOffset);
            return new CodeChunkQueryResult(
                    codeChunkStore.countByRepositoryId(repositoryId),
                    resolvedLimit,
                    resolvedOffset,
                    chunks);
        }

        List<CodeChunk> chunks =
                codeChunkStore.searchByRepositoryId(
                        repositoryId, normalizedQuery, resolvedLimit, resolvedOffset);
        return new CodeChunkQueryResult(
                codeChunkStore.countSearchByRepositoryId(repositoryId, normalizedQuery),
                resolvedLimit,
                resolvedOffset,
                chunks);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("每页数量必须在 1 到 " + MAX_LIMIT + " 之间");
        }
        return limit;
    }

    private int resolveOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("分页偏移量不能为负数");
        }
        return offset;
    }
}
